#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  -h|--help)
    printf '%s\n' 'Usage: scripts/verify-build-matrix.sh
Run profile, entrypoint, dependency, source, and core safety validations.'
    exit 0
    ;;
  "") ;;
  *)
    echo "ERROR: unknown argument: $1" >&2
    exit 2
    ;;
esac

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -s config/build-compatibility.lock.json ]] || fail "Missing config/build-compatibility.lock.json"
python3 scripts/validate-build-profiles.py
python3 scripts/validate-entrypoints.py
python3 scripts/validate-dependency-artifacts.py
python3 scripts/validate-build-invocations.py
python3 -B -m py_compile \
  scripts/generate-ci-matrix.py \
  scripts/validate-build-profiles.py \
  scripts/validate-entrypoints.py \
  scripts/validate-dependency-artifacts.py \
  scripts/validate-jar-metadata.py \
  scripts/check-ci-matrix.py \
  scripts/validate-source-tree.py \
  scripts/check-fabric-loom-1-21.py \
  scripts/validate-build-invocations.py
find scripts -type d -name __pycache__ -prune -exec rm -rf {} +

matrix_file="$(mktemp)"
trap 'rm -f "${matrix_file}"' EXIT
python3 scripts/generate-ci-matrix.py >"${matrix_file}"
python3 scripts/check-ci-matrix.py "${matrix_file}"
python3 scripts/check-fabric-loom-1-21.py
bash -n scripts/build.sh scripts/build-all-profiles.sh scripts/ci-read-profile.sh scripts/verify-build-matrix.sh
bash scripts/run-core-tests.sh

grep -q "gradle-9.4.0-bin.zip" gradle/wrapper/gradle-wrapper.properties || fail "Gradle wrapper must stay on 9.4.0"
grep -q "id 'net.fabricmc.fabric-loom-remap' version '1.15.5' apply false" build.gradle || fail "Root build must use Fabric Loom Remap 1.15.5"
grep -q "id 'net.minecraftforge.gradle' version '7.0.25' apply false" build.gradle || fail "Root build must use ForgeGradle 7.0.25"
grep -q "id 'net.neoforged.moddev' version '2.0.141' apply false" build.gradle || fail "Root build must use NeoForge ModDev 2.0.141"
grep -q "common/src/mc12111/java" build.gradle || fail "Build must select the 1.21.11 registry adapter"
grep -q "common/src/mc121legacy/java" build.gradle || fail "Build must select the legacy 1.21-1.21.10 adapter"

if grep -q "workflow_dispatch\|branches:" .github/workflows/package.yml; then
  fail "Release workflow must run only from v* tags"
fi
grep -q "'v\*'" .github/workflows/package.yml || fail "Release workflow must trigger on v* tags"
grep -q "generate-ci-matrix.py --github-output" .github/workflows/package.yml || fail "Workflow must generate matrix from compatibility lock"
grep -q "fromJson(needs.prepare-matrix.outputs.build_matrix)" .github/workflows/package.yml || fail "Workflow must consume generated matrix"
grep -q "validate-source-tree.py" .github/workflows/package.yml || fail "Workflow must validate source cleanliness"
grep -q "run-core-tests.sh" .github/workflows/package.yml || fail "Workflow must run core unit tests"
grep -q "fail-mode: fail" .github/workflows/package.yml || fail "CurseForge publication failures must fail visibly"
if grep -q "continue-on-error: true" .github/workflows/package.yml; then
  fail "Release publication must not hide failures with continue-on-error"
fi

if grep -R -E --exclude="*.example" "^(fabric_version|forge_version|neoforge_version)=.*\+$" buildProfiles gradle.properties -n; then
  fail "Dynamic loader dependency versions are forbidden"
fi

# Safety invariants for Minecraft time.
if grep -RInE --include='*.java' 'time of |getGameTime\(|setGameTime\(' common fabric forge neoforge; then
  fail "Minecraft 26.x clock commands and gameTime access are forbidden in the 1.21.x branch"
fi
if grep -RInE --include='*.java' 'ScheduledExecutorService|scheduleAtFixedRate' forge; then
  fail "Forge must use official server tick events, not a background scheduler"
fi
if grep -RIn --include='*.java' 'LevelTickEvent' neoforge; then
  fail "NeoForge must use one ServerTickEvent per server tick"
fi
if grep -n 'floorMod\|%[[:space:]]*24000' common/src/main/java/com/realtime/common/RealtimeWorldTime.java; then
  fail "RealtimeWorldTime.setDayTime must preserve absolute dayTime"
fi
if grep -RIn 'import net.minecraft.world.level.GameRules' common/src/main/java; then
  fail "Version-specific GameRules imports belong only in profile adapters"
fi
if grep -RIn 'import net.minecraft.world.level.gamerules.GameRules' common/src/main/java; then
  fail "1.21.11 GameRules import belongs only in the profile adapter"
fi
grep -q 'GameRules.RULE_DAYLIGHT' common/src/mc121legacy/java/com/realtime/common/ProfileDaylightRuleAccess.java || fail "Legacy adapter must use RULE_DAYLIGHT"
grep -q 'GameRules.ADVANCE_TIME' common/src/mc12111/java/com/realtime/common/ProfileDaylightRuleAccess.java || fail "1.21.11 adapter must use ADVANCE_TIME"
grep -q 'dimension().location()' common/src/mc121legacy/java/com/realtime/common/ProfileDimensionIdAccess.java || fail "Legacy dimension adapter must use ResourceKey.location()"
grep -q 'dimension().identifier()' common/src/mc12111/java/com/realtime/common/ProfileDimensionIdAccess.java || fail "1.21.11 dimension adapter must use ResourceKey.identifier()"
grep -q 'source.hasPermission(2)' common/src/mc121legacy/java/com/realtime/common/ProfileCommandPermissionAccess.java || fail "Legacy command adapter must use permission level 2"
grep -q 'Commands.LEVEL_GAMEMASTERS' common/src/mc12111/java/com/realtime/common/ProfileCommandPermissionAccess.java || fail "1.21.11 command adapter must use PermissionSet checks"
grep -q 'Commands.literal("realtimesync")' common/src/main/java/com/realtime/common/RealtimeStatusCommand.java || fail "Diagnostic status command is missing"
grep -q 'level.setDayTime(absoluteDayTime)' common/src/main/java/com/realtime/common/RealtimeWorldTime.java || fail "Absolute dayTime write is missing"
grep -q 'gameRules.restoreAll(server)' common/src/main/java/com/realtime/common/RealtimeController.java || fail "Managed gamerule must be restored on stop/policy change"
grep -q 'Files.newBufferedReader(path, StandardCharsets.UTF_8)' common/src/main/java/com/realtime/common/RealtimeConfig.java || fail "Config must read UTF-8"
grep -q 'StandardCopyOption.ATOMIC_MOVE' common/src/main/java/com/realtime/common/RealtimeConfig.java || fail "Config saves must attempt atomic move"

echo "Build matrix static verification passed."
