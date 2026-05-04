# Minecraft version build profiles

RealtimeSync publishes **one jar per Minecraft version and per loader**. Do not mark one artifact as compatible with the whole 1.21 range unless it was compiled and tested against every version in that range.

## Covered profiles

The repository currently contains profiles for:

```txt
1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5,
1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1, 26.1.1, 26.1.2
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
fabric_version=0.128.2+1.21.5
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

`26.1`, `26.1.1` and `26.1.2` are active profiles. `26.1.2.properties.example` is kept only as a reference template and must not be treated as the source of truth; use `config/build-compatibility.lock.json` plus the active profile files instead.


## Build tool compatibility

The project wrapper is pinned to Gradle 9.4.0. Do not downgrade it while using Fabric Loom Remap 1.15.x and ForgeGradle 7.x, because the 1.21.10/1.21.11 toolchains need the newer Gradle/plugin stack.


## Build toolchain notes

- Gradle wrapper is pinned to 9.4.0 so modern Fabric Loom Remap 1.15.x and ForgeGradle 7.x can resolve Minecraft 1.21.10/1.21.11 correctly.
- ForgeGradle 6.x is not used because it fails on newer Forge 60.x/61.x userdev artifacts.
- A disabled loader in `buildProfiles/<version>.properties` is skipped before its loader-specific dependencies are resolved. This prevents placeholder values such as `forge_version=unsupported` from breaking profile tasks.


## Build toolchain guardrails

- Use Gradle Wrapper `9.4.0` or newer with ForgeGradle 7. Gradle `9.3.0` is no longer enough for the 26.1.x toolchain, and Gradle `9.2.1` is not enough for ForgeGradle 7 and fails during `:forge` configuration.
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


## Minecraft 26.1.x profiles

Active profiles are included for `26.1`, `26.1.1` and `26.1.2`. These profiles use Java 25, Fabric Loader `0.18.4`, pinned Fabric API versions, exact Forge versions and exact NeoForge versions. The 26.1.x NeoForge metadata uses `loaderVersion=[1,)` for `javafml` and a separate `neoforge_version_range` dependency range so the mod metadata matches current NeoForge 26.x conventions.

Current pinned profile values:

| Profile | Java | Fabric API | Forge | NeoForge |
| --- | --- | --- | --- | --- |
| `26.1` | 25 | `0.145.1+26.1` | `62.0.9` | `26.1.0.19-beta` |
| `26.1.1` | 25 | `0.145.4+26.1.1` | `63.0.2` | `26.1.1.15-beta` |
| `26.1.2` | 25 | `0.148.0+26.1.2` | `64.0.7` | `26.1.2.36-beta` |


## Verified dependency profiles

Active `buildProfiles/*.properties` are dependency-locked. Run:

```bash
python3 scripts/validate-build-profiles.py
bash scripts/verify-build-matrix.sh
```

Important rules:

- Fabric API is pinned per Minecraft profile instead of `0.+` to avoid non-reproducible releases.
- Forge `1.21.2` is intentionally disabled because the active Forge downloads list does not provide a normal Forge artifact for that Minecraft version.
- Forge `1.21.10` is pinned to `60.1.0` instead of latest `60.1.9`, because `60.1.9` can fail in ForgeGradle Mavenizer on GitHub-hosted runners.
- Minecraft `26.1.x` profiles use Java 25 and exact Fabric/Forge/NeoForge artifacts.


### Minecraft 26.1.x time API compatibility

Minecraft 26.1.x no longer exposes the same `ServerLevel#getDayTime()` / `ServerLevel#setDayTime(...)` convenience methods in the mapped API used by every loader build. RealtimeSync reads and writes day time through the reflective compatibility helper (`RealtimeWorldTime`) so the same common runtime compiles for both `1.21.x` and `26.1.x`, even when direct `ServerLevel` convenience methods disappear or move.


## Compatibility lock and supported matrix

Supported Minecraft/loader combinations are controlled by `config/build-compatibility.lock.json`. CI no longer hardcodes the full matrix in YAML; it generates the matrix with `scripts/generate-ci-matrix.py`, so disabled or unsupported combinations are skipped before Gradle resolves loader dependencies.

Before changing a loader dependency, update both the relevant `buildProfiles/<version>.properties` file and the compatibility lock, then run:

```bash
python3 scripts/validate-build-profiles.py
python3 scripts/validate-dependency-artifacts.py
bash scripts/verify-build-matrix.sh
```

Use `python3 scripts/validate-dependency-artifacts.py --online` when you need to probe Maven/Fabric/Forge/NeoForge repositories directly. See `docs/COMPATIBILITY.md` for the full process.
## Minecraft 26.1.x Fabric/Quilt builds

Minecraft 26.1.x is treated differently from 1.21.x for Fabric-compatible builds.
Fabric Loader and Fabric API exist for 26.1.x, but the build script must not request `loom.officialMojangMappings()` for those profiles.
The 26.1.x game jars are already in the official namespace, so the Fabric and Quilt modules switch to `net.fabricmc.fabric-loom`, remove the `mappings` dependency, and use standard `implementation` dependencies.

For 1.21.x, the modules still use `net.fabricmc.fabric-loom-remap` with explicit `loom.officialMojangMappings()`.

