#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

expected_profiles=(
  "1.21"
  "1.21.1"
  "1.21.2"
  "1.21.3"
  "1.21.4"
  "1.21.5"
  "1.21.6"
  "1.21.7"
  "1.21.8"
  "1.21.9"
  "1.21.10"
  "1.21.11"
)

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

for profile in "${expected_profiles[@]}"; do
  file="buildProfiles/${profile}.properties"
  [[ -s "${file}" ]] || fail "Missing profile ${file}"
  grep -q "^minecraft_version=${profile}$" "${file}" || fail "Profile ${file} has wrong minecraft_version"
  grep -q "^enable_fabric=" "${file}" || fail "Profile ${file} missing enable_fabric"
  grep -q "^enable_quilt=" "${file}" || fail "Profile ${file} missing enable_quilt"
  grep -q "^enable_forge=" "${file}" || fail "Profile ${file} missing enable_forge"
  grep -q "^enable_neoforge=" "${file}" || fail "Profile ${file} missing enable_neoforge"
  grep -q -- "- '${profile}'" .github/workflows/package.yml || fail "Workflow matrix missing ${profile}"

  if grep -qE '^neoforge_version=.*\+$' "${file}"; then
    fail "Profile ${file} uses dynamic NeoForge version; ModDev/NeoForm needs an exact version"
  fi
done

grep -q "gradle-9.3.0-bin.zip" gradle/wrapper/gradle-wrapper.properties || fail "Gradle wrapper must be 9.3.0 for ForgeGradle 7"
grep -q "id 'net.fabricmc.fabric-loom-remap' version '1.15.5' apply false" build.gradle || fail "Root build must use Fabric Loom Remap 1.15.5"
grep -q "apply plugin: 'net.fabricmc.fabric-loom-remap'" fabric/build.gradle || fail "Fabric module must use Fabric Loom Remap"
grep -q "apply plugin: 'net.fabricmc.fabric-loom-remap'" quilt/build.gradle || fail "Quilt module must use Fabric Loom Remap"
grep -q "id 'net.minecraftforge.gradle' version '7.0.25' apply false" build.gradle || fail "Root build must use ForgeGradle 7.0.25"
grep -q "^enable_forge=false$" buildProfiles/1.21.2.properties || fail "Forge must stay disabled for 1.21.2 unless a real Forge artifact is added"

if grep -R --exclude="verify-build-matrix.sh" "gradle-9.2.1-bin.zip\|Gradle Wrapper 9.2.1\|Gradle wrapper is pinned to 9.2.1" -n .; then
  fail "Old Gradle 9.2.1 references remain"
fi


if grep -R --exclude="verify-build-matrix.sh" "workingDirectory[[:space:]]" -n forge/build.gradle; then
  fail "ForgeGradle 7 does not support old workingDirectory(...) run DSL"
fi

grep -q "workingDir.convention" forge/build.gradle || fail "Forge module must use ForgeGradle 7 workingDir.convention(...)"
grep -q "implementation minecraft.dependency" forge/build.gradle || fail "Forge module must use ForgeGradle 7 minecraft.dependency(...)"
grep -q "maven fg.forgeMaven" forge/build.gradle || fail "Forge module must add ForgeGradle 7 Forge Maven helper"
grep -q "maven fg.minecraftLibsMaven" forge/build.gradle || fail "Forge module must add ForgeGradle 7 Minecraft libs Maven helper"


if grep -R "import net.minecraft.world.level.GameRules" common fabric forge neoforge -n; then
  fail "Direct GameRules imports are not compatible across all 1.21.x mappings"
fi

if grep -R "performPrefixedCommand\|gamerule doDaylightCycle\|gamerule minecraft:advance_time" common fabric forge neoforge -n; then
  fail "Sources must not change daylight gamerules through commands; use reflective GameRules mutation to avoid 1.21.x gamerule rename/runtime log spam"
fi

grep -R "DO_DAYLIGHT_CYCLE" common/src/main/java >/dev/null || fail "Common runtime must try the legacy daylight gamerule key DO_DAYLIGHT_CYCLE"
grep -R "ADVANCE_TIME" common/src/main/java >/dev/null || fail "Common runtime must try the 1.21.11 daylight gamerule key ADVANCE_TIME"

if grep -R "net.minecraftforge.eventbus.api.SubscribeEvent\|@SubscribeEvent\|MinecraftForge.EVENT_BUS" forge/src/main/java -n; then
  fail "Forge source must not use MinecraftForge.EVENT_BUS or old annotation event-bus registration; Forge 1.21.6+ uses EventBus 7 migration helpers"
fi

if grep -R "event.getLevel()" forge/src/main/java -n; then
  fail "Forge source must not call event.getLevel(); TickEvent.LevelTickEvent.Post does not expose it across the whole 1.21.x range"
fi


# CI package 2 guardrails: matrix is split by Minecraft profile and loader, and jars are validated before upload.
grep -q "build-loader:" .github/workflows/package.yml || fail "Workflow must have a build-loader matrix job"
grep -q "loader:" .github/workflows/package.yml || fail "Workflow matrix must include loader axis"
grep -q -- "- fabric" .github/workflows/package.yml || fail "Workflow loader matrix missing fabric"
grep -q -- "- quilt" .github/workflows/package.yml || fail "Workflow loader matrix missing quilt"
grep -q -- "- forge" .github/workflows/package.yml || fail "Workflow loader matrix missing forge"
grep -q -- "- neoforge" .github/workflows/package.yml || fail "Workflow loader matrix missing neoforge"
grep -q "validate-jar-metadata.py" .github/workflows/package.yml || fail "Workflow must validate jar metadata before artifact upload"
grep -q "sha256sum" .github/workflows/package.yml || fail "Workflow must generate sha256 checksums for release artifacts"
grep -q "metadata.json" .github/workflows/package.yml || fail "Workflow must stage metadata JSON for release artifacts"
grep -q "pattern: realtime-sync-\*-jar" .github/workflows/package.yml || fail "GitHub release job must download all per-loader artifacts by pattern"
grep -q "curseforge-publish:" .github/workflows/package.yml || fail "Workflow must have isolated CurseForge publish matrix job"
grep -q "continue-on-error: true" .github/workflows/package.yml || fail "CurseForge publish job must not block completed loader builds"
grep -q "fail-mode: warn" .github/workflows/package.yml || fail "mc-publish must warn instead of failing the workflow for external publish errors"
if grep -q "buildAllLoaders" .github/workflows/package.yml; then
  fail "Workflow must not build all loaders inside one matrix job; use mc_profile x loader isolation"
fi
python3 -m py_compile scripts/validate-jar-metadata.py
bash -n scripts/ci-read-profile.sh

grep -q "ServerLifecycleHooks.getCurrentServer" forge/src/main/java/com/realtime/forge/RealtimeForge.java || fail "Forge source must use ServerLifecycleHooks-based ticking for cross-1.21.x compatibility"
grep -q "ScheduledExecutorService" forge/src/main/java/com/realtime/forge/RealtimeForge.java || fail "Forge source must schedule safe server-thread ticks without Forge event-bus APIs"


grep -q "game-version-filter: none" .github/workflows/package.yml || fail "CurseForge mc-publish must use game-version-filter: none to avoid Mojang manifest fetch failures during publish"

if grep -n "game-version-filter: releases" .github/workflows/package.yml; then
  fail "CurseForge publish must not use game-version-filter: releases; it fetches Mojang version_manifest_v2.json during publish"
fi


grep -q "throw new GradleException" build.gradle || fail "Missing mcProfile must fail instead of silently falling back to gradle.properties"
if grep -nE '^neoforge_version=.*\+$' gradle.properties README.md VERSIONING.md buildProfiles/*.properties; then
  fail "NeoForge versions must be exact in active profiles, fallback config and docs"
fi

grep -q "final class RealtimeController" common/src/main/java/com/realtime/common/RealtimeController.java || fail "Common runtime controller is missing"
grep -q "final class RealtimeGameRules" common/src/main/java/com/realtime/common/RealtimeGameRules.java || fail "Common GameRules helper is missing"
grep -q "cachedResolution" common/src/main/java/com/realtime/common/RealtimeGameRules.java || fail "RealtimeGameRules must cache its reflective resolver"
grep -q "DAYLIGHT_RULE_GUARD_INTERVAL_TICKS" common/src/main/java/com/realtime/common/RealtimeController.java || fail "Daylight gamerule guard must be low-frequency, not every sync tick"

grep -q "SYNC_MODE_SMOOTH" common/src/main/java/com/realtime/common/RealtimeConfig.java || fail "RealtimeConfig must support syncMode=smooth"
grep -q "calculateSmoothTicks" common/src/main/java/com/realtime/common/RealtimeMath.java || fail "RealtimeMath must provide smooth time catch-up"
grep -q "syncDimensions" common/src/main/java/com/realtime/common/RealtimeConfig.java || fail "RealtimeConfig must support syncDimensions allowlist"
grep -q "ignoredDimensions" common/src/main/java/com/realtime/common/RealtimeConfig.java || fail "RealtimeConfig must support ignoredDimensions denylist"
grep -q "shouldSyncLevel" common/src/main/java/com/realtime/common/RealtimeController.java || fail "RealtimeController must own dimension filtering"
grep -q "respectSleep" common/src/main/java/com/realtime/common/RealtimeConfig.java || fail "RealtimeConfig must support respectSleep"
grep -q "overrideSleepTime" common/src/main/java/com/realtime/common/RealtimeConfig.java || fail "RealtimeConfig must support overrideSleepTime"
grep -q "shouldSkipForSleep" common/src/main/java/com/realtime/common/RealtimeController.java || fail "RealtimeController must own sleep-aware sync"

for source in fabric/src/main/java/com/realtime/fabric/RealtimeFabric.java forge/src/main/java/com/realtime/forge/RealtimeForge.java neoforge/src/main/java/com/realtime/neoforge/RealtimeNeoForge.java; do
  grep -q "RealtimeController" "${source}" || fail "${source} must delegate runtime behavior to RealtimeController"
done

if grep -R "RealtimeMath\|setBooleanGameRule\|invokeBooleanRuleSetter\|findDirectGameRuleSetter\|calculateSmoothTicks\|shouldSyncLevel\|shouldSkipForSleep" fabric/src/main/java forge/src/main/java neoforge/src/main/java -n; then
  fail "Loader entrypoints must not duplicate common sync math, dimension filtering, sleep handling or GameRules reflection"
fi

bash -n scripts/build-all-profiles.sh

echo "Build matrix static verification passed."
