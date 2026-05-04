# Minecraft version build profiles

RealtimeSync publishes **one jar per Minecraft version and per loader**. Do not mark one artifact as compatible with the whole 1.21 range unless it was compiled and tested against every version in that range.

## Covered profiles

The repository currently contains profiles for:

```txt
1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5,
1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11
```

Every profile lives in:

```txt
buildProfiles/<minecraft-version>.properties
```

## Build a single profile

```bash
./gradlew -PmcProfile=1.21.11 clean buildAllLoaders
```

## Build all profiles locally

```bash
./scripts/build-all-profiles.sh
```

## Build only selected profiles

```bash
./scripts/build-all-profiles.sh 1.21.5 1.21.10 1.21.11
```

## Build one loader

```bash
./gradlew -PmcProfile=1.21.11 clean buildFabric
./gradlew -PmcProfile=1.21.11 clean buildQuilt
./gradlew -PmcProfile=1.21.11 clean buildForge
./gradlew -PmcProfile=1.21.11 clean buildNeoForge
```

## Profile keys

Each profile controls:

- `minecraft_version`
- `minecraft_compat_label`
- Fabric / Quilt-compatible Minecraft range
- Forge / NeoForge Minecraft range
- Java toolchain version
- enabled loader switches: `enable_fabric`, `enable_quilt`, `enable_forge`, `enable_neoforge`
- Fabric Loader version
- Fabric API dynamic resolver version
- Forge version and loader range
- NeoForge version and loader range

## Fabric API resolution

Fabric API uses:

```properties
fabric_version=0.+
```

The Gradle build rejects Fabric API candidates that do not end with the selected Minecraft version suffix. For example, the `1.21.11` profile only accepts Fabric API versions ending in:

```txt
+1.21.11
```

This avoids accidentally compiling a `1.21.11` Fabric build against a `26.x` or older `1.21.x` Fabric API artifact.

## Mappings

Fabric and Quilt-compatible builds use Mojang official mappings through Loom:

```gradle
mappings loom.officialMojangMappings()
```

That keeps Fabric source names close to Forge/NeoForge source names and removes the need to maintain a Yarn version for every Minecraft profile.

## Quilt artifact

The Quilt jar is a separate artifact name built from the Fabric-compatible source path. It is intended for Quilt setups that can load Fabric API-compatible mods. It is not a native QSL-only rewrite.

## Forge 1.21.2

`buildProfiles/1.21.2.properties` has:

```properties
enable_forge=false
```

Forge 1.21.2 is disabled because the normal Forge 1.21.x release line does not provide a matching official Forge artifact for Minecraft `1.21.2`. NeoForge 21.2 exists for Minecraft `1.21.2`, so the NeoForge build remains enabled.

## Adding another Minecraft version

1. Copy the closest profile in `buildProfiles/`.
2. Update `minecraft_version`, ranges and loader versions.
3. Keep ranges exact: `[current,next)` and `>=current <next`.
4. Run the local build for that one profile.
5. Add the profile to the GitHub Actions matrix only after it builds.
6. Publish each loader jar separately.

## About Minecraft 26.x+

A `26.1.2.properties.example` file is included only as a starting point for the new version format. It is not enabled in the release matrix. Enable it only after checking current loader support and compiling all enabled targets.


## Build tool compatibility

The project wrapper is pinned to Gradle 9.3.0. Do not downgrade it while using Fabric Loom Remap 1.15.x and ForgeGradle 7.x, because the 1.21.10/1.21.11 toolchains need the newer Gradle/plugin stack.


## Build toolchain notes

- Gradle wrapper is pinned to 9.3.0 so modern Fabric Loom Remap 1.15.x and ForgeGradle 7.x can resolve Minecraft 1.21.10/1.21.11 correctly.
- ForgeGradle 6.x is not used because it fails on newer Forge 60.x/61.x userdev artifacts.
- A disabled loader in `buildProfiles/<version>.properties` is skipped before its loader-specific dependencies are resolved. This prevents placeholder values such as `forge_version=unsupported` from breaking profile tasks.


## Build toolchain guardrails

- Use Gradle Wrapper `9.3.0` or newer with ForgeGradle 7. Gradle `9.2.1` is not enough and fails during `:forge` configuration.
- ForgeGradle 7 run configs must use `workingDir.convention(...)` or `workingDir = ...`; the old `workingDirectory(...)` MDK syntax fails on `SlimeLauncherOptionsImpl`.
- ForgeGradle 7 dependencies must use `implementation minecraft.dependency("net.minecraftforge:forge:${minecraft_version}-${forge_version}")` instead of the older `minecraft "..."` configuration notation.
- Use `net.fabricmc.fabric-loom-remap` for the `1.21` to `1.21.11` Fabric/Quilt-compatible builds.
- Keep `enable_forge=false` for profiles without a real Forge artifact, for example `1.21.2`; do not use placeholder versions like `unsupported` in an enabled Forge profile.


### Build compatibility notes

The 1.21.x matrix intentionally avoids direct `GameRules` imports in loader entrypoints because Mojang mappings and gamerule identifiers are not stable across every 1.21.x profile. The mod also does not call `/gamerule doDaylightCycle false`: Minecraft 1.21.11 renamed gamerules to namespaced IDs such as `minecraft:advance_time`, while older profiles still use `doDaylightCycle`. Instead, the loader entrypoints set the boolean gamerule through reflection and try the known runtime key names (`DO_DAYLIGHT_CYCLE`, `ADVANCE_TIME`, `RULE_DAYLIGHT`, `RULE_ADVANCE_TIME`) plus the stable intermediary field (`field_19396`). Forge avoids the Forge event bus entirely and uses `ServerLifecycleHooks` with a safe server-thread scheduler because Forge 1.21.6+ exposes EventBus 7 migration helpers instead of the older APIs. NeoForge profile versions are pinned exactly; do not use `21.x.+` with ModDev/NeoForm because it is resolved as a literal userdev artifact in this setup.


## CI matrix and artifact validation

The release workflow uses a real `mc_profile x loader` matrix instead of building every loader inside one job. Each enabled target builds one jar, validates its metadata and uploads three files:

```txt
<jar>.jar
<jar>.jar.sha256
<jar>.jar.metadata.json
```

`scripts/validate-jar-metadata.py` opens the produced jar and checks that:

- loader metadata exists (`fabric.mod.json`, `META-INF/mods.toml` or `META-INF/neoforge.mods.toml`);
- Gradle placeholders such as `${version}` were expanded;
- the mod id is `realtime`;
- the jar metadata contains the expected mod version and Minecraft version;
- the common runtime classes and icon are present.

CurseForge publishing is isolated in its own loader/version matrix with `continue-on-error: true` and `fail-mode: warn`. External publish failures should be visible in logs but must not invalidate already-built jars or block other loader uploads.

## CurseForge publish guardrail

The CurseForge publish steps intentionally set `game-version-filter: none` in `.github/workflows/package.yml`.

Do not switch this back to `releases` for single-version uploads. `mc-publish` may otherwise call Mojang's `version_manifest_v2.json` during the publish step, so a temporary Mojang/Piston metadata fetch failure can break an already-built release.

For this project each matrix job already passes an exact Minecraft version from `buildProfiles/<mcProfile>.properties`, so no Mojang-side version filtering is needed during publishing.

## Runtime guardrails

Shared runtime behavior lives in `common/src/main/java/com/realtime/common`:

- `RealtimeController` handles config reloads, sync cadence and applying time to worlds.
- `RealtimeGameRules` handles daylight-cycle gamerule mutation with a cached reflective resolver.
- Loader entrypoints should stay thin and must not duplicate config polling, GameRules reflection or time-sync math.

Do not reintroduce command-based gamerule changes such as `/gamerule doDaylightCycle false`; Minecraft 1.21.11 renamed daylight-cycle behavior and command syntax can spam runtime logs.

## Runtime feature notes

The shared `common` runtime supports:

- `syncMode=instant` and `syncMode=smooth` across all loader entrypoints.
- `syncDimensions` allowlists and `ignoredDimensions` denylists using full dimension ids such as `minecraft:overworld`.
- `respectSleep` / `overrideSleepTime` sleep handling without loader-specific logic.

Keep these features in `RealtimeController` and `RealtimeConfig`. Loader entrypoints must only forward lifecycle/tick callbacks and must not reimplement smooth sync, dimension filtering or sleep checks.

## Realistic smooth defaults

The generated `realtime.properties` file intentionally uses a realistic-smooth profile:

```properties
syncAllWorlds=false
syncDimensions=minecraft:overworld
syncMode=smooth
updateInterval=20
maxSmoothStepTicks=12
smoothSnapThresholdTicks=2
smoothCatchupDivisor=240
respectSleep=true
overrideSleepTime=false
```

Do not revert these defaults to `syncMode=instant`, `syncAllWorlds=true`, `updateInterval=60`, or `maxSmoothStepTicks=240` unless there is a specific compatibility reason. The goal is realistic sky movement: Overworld only, one-second updates, adaptive catch-up, and no sleep conflicts.


### Forge 1.21.10 CI note

The `1.21.10` Forge profile is pinned to the official recommended Forge `60.1.0` instead of the latest `60.1.9`. The latest userdev can fail during ForgeGradle 7 Mavenizer source recompilation on GitHub-hosted runners. CI also passes `-PtargetLoader=<loader>` so one loader matrix entry never configures unrelated loader projects.
