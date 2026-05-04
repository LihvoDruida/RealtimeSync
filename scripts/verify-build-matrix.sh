#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -s config/build-compatibility.lock.json ]] || fail "Missing config/build-compatibility.lock.json"
python3 scripts/validate-build-profiles.py
python3 scripts/validate-dependency-artifacts.py
python3 -m py_compile \
  scripts/generate-ci-matrix.py \
  scripts/validate-build-profiles.py \
  scripts/validate-dependency-artifacts.py \
  scripts/validate-jar-metadata.py \
  scripts/check-ci-matrix.py \
  scripts/check-fabric-loom-branches.py
python3 scripts/generate-ci-matrix.py >/tmp/realtime-sync-matrix.json
python3 scripts/check-ci-matrix.py
python3 scripts/check-fabric-loom-branches.py

grep -q "gradle-9.4.0-bin.zip" gradle/wrapper/gradle-wrapper.properties || fail "Gradle wrapper must be 9.4.0 for 26.1.x"
grep -q "id 'net.fabricmc.fabric-loom-remap' version '1.15.5' apply false" build.gradle || fail "Root build must use Fabric Loom Remap 1.15.5 for 1.21.x"
grep -q "id 'net.fabricmc.fabric-loom' version '1.15.5' apply false" build.gradle || fail "Root build must use Fabric Loom 1.15.5 for 26.1.x unobfuscated builds"
grep -q "id 'net.minecraftforge.gradle' version '7.0.25' apply false" build.gradle || fail "Root build must use ForgeGradle 7.0.25"
grep -q "id 'net.neoforged.moddev' version '2.0.141' apply false" build.gradle || fail "Root build must use NeoForge ModDev 2.0.141"

grep -q "generate-ci-matrix.py --github-output" .github/workflows/package.yml || fail "Workflow must generate matrix from compatibility lock"
grep -q "fromJson(needs.prepare-matrix.outputs.build_matrix)" .github/workflows/package.yml || fail "Workflow must consume generated matrix"
grep -q "validate-dependency-artifacts.py" .github/workflows/package.yml || fail "Workflow must validate dependency coordinates"
grep -q "game-version-filter: none" .github/workflows/package.yml || fail "mc-publish must not fetch Mojang version manifest for filtering"
grep -q "fail-mode: warn" .github/workflows/package.yml || fail "CurseForge publish must be warning-mode so one loader does not block artifacts"

if grep -R -E --exclude="*.example" "^(fabric_version|forge_version|neoforge_version)=.*\+$" buildProfiles gradle.properties -n; then
  fail "Dynamic loader dependency versions are forbidden in active profiles"
fi

if grep -R --exclude="verify-build-matrix.sh" "gradle-9.2.1-bin.zip\|gradle-9.3.0-bin.zip\|Gradle Wrapper 9.2.1\|Gradle Wrapper 9.3.0" -n build.gradle settings.gradle README.md VERSIONING.md gradle/wrapper .github buildProfiles scripts config docs; then
  fail "Old Gradle 9.2.1/9.3.0 references remain; the 26.1.x matrix requires Gradle 9.4.0"
fi

if grep -R --exclude="verify-build-matrix.sh" "workingDirectory[[:space:]]" -n forge/build.gradle; then
  fail "ForgeGradle 7 does not support old workingDirectory(...) run DSL"
fi

grep -q "workingDir.convention" forge/build.gradle || fail "Forge module must use ForgeGradle 7 workingDir.convention(...)"
grep -q "implementation minecraft.dependency" forge/build.gradle || fail "Forge module must use ForgeGradle 7 minecraft.dependency(...)"
grep -q "maven fg.forgeMaven" forge/build.gradle || fail "Forge module must add ForgeGradle 7 Forge Maven helper"
grep -q "maven fg.minecraftLibsMaven" forge/build.gradle || fail "Forge module must add ForgeGradle 7 Minecraft libs Maven helper"

if grep -R "import net.minecraft.world.level.GameRules" common fabric forge neoforge -n; then
  fail "Direct GameRules imports are not compatible across all mappings"
fi

if grep -R "\.dimension()\.location()\|\.dimension()" common fabric forge neoforge -n; then
  fail "Direct dimension API calls are not allowed; use RealtimeWorldTime.dimensionId reflection helper"
fi

if grep -R -E "\.(getDayTime|setDayTime)\(" common fabric forge neoforge -n --exclude="RealtimeWorldTime.java" | grep -v "RealtimeWorldTime\.setDayTime" | grep -v "RealtimeWorldTime\.readDayTime"; then
  fail "Direct day-time API calls are not allowed outside RealtimeWorldTime"
fi

if grep -R "\.getGameRules()" common fabric forge neoforge -n; then
  fail "Direct GameRules API calls are not allowed; use RealtimeWorldAccess.gameRules reflection helper"
fi

if grep -R "performPrefixedCommand\|gamerule doDaylightCycle\|gamerule minecraft:advance_time" common fabric forge neoforge -n; then
  fail "Sources must not change daylight gamerules through commands"
fi

grep -R "DO_DAYLIGHT_CYCLE" common/src/main/java >/dev/null || fail "Common runtime must try the legacy daylight gamerule key DO_DAYLIGHT_CYCLE"
grep -R "ADVANCE_TIME" common/src/main/java >/dev/null || fail "Common runtime must try the 1.21.11/26.x daylight gamerule key ADVANCE_TIME"
grep -R "RealtimeWorldTime.readDayTime" common/src/main/java >/dev/null || fail "Runtime must use RealtimeWorldTime for day-time reads"
grep -R "RealtimeWorldTime.setDayTime" common/src/main/java >/dev/null || fail "Runtime must use RealtimeWorldTime for day-time writes"
grep -R "RealtimeServerState.tickCount" common/src/main/java >/dev/null || fail "Runtime must de-duplicate per-level tick events through RealtimeServerState"

if grep -R "ServerWorldEvents" fabric/src/main/java quilt/src/main/java -n 2>/dev/null; then
  fail "Fabric/Quilt source must not use ServerWorldEvents; Fabric API 26.1.x does not expose it in the lifecycle package"
fi

if grep -R "net.minecraftforge.eventbus.api.SubscribeEvent\|@SubscribeEvent\|MinecraftForge.EVENT_BUS" forge/src/main/java -n; then
  fail "Forge source must not use old event-bus registration"
fi

if grep -R "event.getLevel()" forge/src/main/java -n; then
  fail "Forge source must not call event.getLevel(); it is not stable across the whole range"
fi

tmpdir="$(mktemp -d)"
trap 'rm -rf "${tmpdir}"' EXIT
javac -d "${tmpdir}" \
  common/src/main/java/com/realtime/common/RealtimeConfig.java \
  common/src/main/java/com/realtime/common/RealtimeMath.java \
  common/src/main/java/com/realtime/common/RealtimeConstants.java \
  common/src/main/java/com/realtime/common/RealtimeLog.java

echo "Build matrix static verification passed."
