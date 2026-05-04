# RealtimeSync

**RealtimeSync** is a lightweight server-side Minecraft mod that synchronizes Minecraft world time with the server's real system time or with a configurable custom day duration.

The project is built as a **multi-loader, multi-version** Gradle project. Release builds are generated per Minecraft version and per loader.

## Supported Minecraft range

Current profiles cover:

```txt
1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5,
1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11, 26.1, 26.1.1, 26.1.2
```

Each version has its own file in `buildProfiles/<minecraft-version>.properties`.

## Supported loaders

Do **not** upload one universal jar for every loader. Publish the correct jar for the correct loader/version.

| Loader | Artifact | Notes |
| --- | --- | --- |
| Fabric | `realtime-sync-fabric-<mc-version>-<mod-version>.jar` | Native Fabric-compatible build. Requires Fabric API. |
| Quilt | `realtime-sync-quilt-<mc-version>-<mod-version>.jar` | Separate Quilt-tagged artifact built from the Fabric-compatible source path. Requires a Quilt setup that can load Fabric API-compatible mods. |
| Forge | `realtime-sync-forge-<mc-version>-<mod-version>.jar` | Native Forge entrypoint and `META-INF/mods.toml`. |
| NeoForge | `realtime-sync-neoforge-<mc-version>-<mod-version>.jar` | Native NeoForge entrypoint and `META-INF/neoforge.mods.toml`. |

### Important Forge note

`buildProfiles/1.21.2.properties` disables Forge with `enable_forge=false`, because there is no matching official Forge `1.21.2` artifact in the normal Forge downloads/Maven line. Fabric, Quilt-compatible and NeoForge builds remain enabled for `1.21.2`.

## Features

- Server-side only; clients do not need the mod installed.
- Per-version build profiles for the full 1.21 through 1.21.11 plus 26.1 through 26.1.2 range.
- Separate Fabric, Quilt-tagged, Forge and NeoForge artifacts.
- No mixins.
- No Cloth Config or AutoConfig dependency.
- Real-time sync mode.
- Custom day length mode.
- Multi-dimension support.
- Hot config reload.
- Legacy `realtime.toml` migration to `realtime.properties`.

## Configuration

The config file is shared between all loaders:

```txt
config/realtime.properties
```

Example realistic-smooth profile:

```properties
enabled=true
forceDaylightCycleOff=true
syncAllWorlds=false
syncDimensions=minecraft:overworld
ignoredDimensions=
syncMode=smooth
maxSmoothStepTicks=12
smoothSnapThresholdTicks=2
smoothCatchupDivisor=240
respectSleep=true
overrideSleepTime=false
updateInterval=20
offsetHours=0
customDayLengthMinutes=0
debugLogging=false
```

### Options

| Key | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Enables or disables the mod without removing it. |
| `forceDaylightCycleOff` | `true` | Keeps vanilla `doDaylightCycle` disabled so the mod controls time cleanly. |
| `syncAllWorlds` | `false` | Syncs every loaded dimension only when `syncDimensions` is empty and this is `true`. The realistic default is Overworld-only. |
| `syncDimensions` | `minecraft:overworld` | Comma-separated allowlist of dimensions to sync. Takes priority over `syncAllWorlds`. Default keeps realistic sky movement only in the Overworld. |
| `ignoredDimensions` | empty | Comma-separated denylist excluded from time sync. Useful for modded/custom dimensions. |
| `syncMode` | `smooth` | `instant` jumps directly to the target time. `smooth` gradually catches up and avoids visible sun/moon jumps. |
| `maxSmoothStepTicks` | `12` | Hard cap for Minecraft ticks changed per sync when `syncMode=smooth`. Lower is smoother; higher catches up faster. |
| `smoothSnapThresholdTicks` | `2` | If the current time is already this close to the target, snap exactly to prevent tiny jitter. |
| `smoothCatchupDivisor` | `240` | Adaptive catch-up softness. Higher values are gentler; lower values catch up faster. |
| `respectSleep` | `true` | Skips time sync while players are sleeping so the mod does not fight sleep mechanics. |
| `overrideSleepTime` | `false` | Forces sync even while players are sleeping. Overrides `respectSleep`. |
| `updateInterval` | `20` | Ticks between syncs. `20` ticks = 1 second. The realistic-smooth profile updates once per second. |
| `offsetHours` | `0` | Shifts real-time sync by `-23..23` hours. |
| `customDayLengthMinutes` | `0` | `0` means real clock sync. Values above `0` set a custom Minecraft day length. |
| `debugLogging` | `false` | Enables verbose sync logs. |

See also: `docs/CONFIG_PRESETS.md` for ready-made realistic, ultra-smooth and fast-catch-up presets.

### Smooth realistic sync

The default profile is tuned for realistic and smooth sun/moon movement:

```properties
syncMode=smooth
updateInterval=20
maxSmoothStepTicks=12
smoothSnapThresholdTicks=2
smoothCatchupDivisor=240
```

How it behaves:

- normal tracking updates once per second and stays close to the real clock;
- small drift snaps only when it is visually unnoticeable;
- large drift is corrected gradually instead of jumping the sky;
- `maxSmoothStepTicks=12` means the fastest catch-up is capped and still visually smooth.

For faster catch-up after long server downtime, raise `maxSmoothStepTicks` to `24` or lower `smoothCatchupDivisor` to `120`. For an even calmer sky, use `maxSmoothStepTicks=6` and keep `smoothCatchupDivisor=240`.

### Dimension filtering

By default, only the Overworld is synced because Nether and End do not have a normal visible day-night sky.

```properties
syncAllWorlds=false
syncDimensions=minecraft:overworld
ignoredDimensions=
```

To sync multiple dimensions, use an explicit allowlist:

```properties
syncDimensions=minecraft:overworld,minecraft:the_nether,minecraft:the_end
ignoredDimensions=some_mod:custom_dimension
```

When `syncDimensions` is set, it becomes the allowlist. `ignoredDimensions` always wins and excludes matching dimensions from sync and daylight-cycle gamerule changes.

### Sleep handling

By default, the mod pauses time sync while players are sleeping:

```properties
respectSleep=true
overrideSleepTime=false
```

Set `overrideSleepTime=true` only if the server should keep enforcing realtime/custom time even during sleep.


### Runtime architecture

RealtimeSync keeps loader entrypoints thin. Fabric, Quilt, Forge and NeoForge only connect loader lifecycle events to the shared `common` runtime:

- `RealtimeController` owns config reloads, sync cadence, smooth/instant time application, dimension filtering and sleep-aware sync.
- `RealtimeGameRules` owns cross-1.21.x daylight gamerule mutation and caches the reflective lookup after the first successful resolution.
- Loader modules should not duplicate sync math, config polling, GameRules reflection or command-based gamerule changes.

The daylight cycle gamerule is applied on world/server start, after config reloads and by a low-frequency safety guard. It is not executed through `/gamerule` commands and is not re-resolved through reflection every sync tick.

## Building

Build the default profile from `gradle.properties`:

```bash
./gradlew clean buildAllLoaders
```

Build one exact Minecraft profile:

```bash
./gradlew -PmcProfile=1.21.11 clean buildAllLoaders
```

Build all profiles:

```bash
./scripts/build-all-profiles.sh
```

Build selected profiles:

```bash
./scripts/build-all-profiles.sh 1.21.5 1.21.10 1.21.11
```

Build one loader for one profile:

```bash
./gradlew -PmcProfile=1.21.11 clean buildFabric
./gradlew -PmcProfile=1.21.11 clean buildQuilt
./gradlew -PmcProfile=1.21.11 clean buildForge
./gradlew -PmcProfile=1.21.11 clean buildNeoForge
```

Output folders:

```txt
fabric/build/libs/
quilt/build/libs/
forge/build/libs/
neoforge/build/libs/
```

## Version profiles

A profile controls the exact Minecraft version, loader dependency versions and enabled loaders.

Example:

```properties
minecraft_version=1.21.11
minecraft_compat_label=1.21.11
minecraft_version_range_fabric=>=1.21.11 <1.21.12
minecraft_version_range_mods_toml=[1.21.11,1.21.12)
java_version=21

enable_fabric=true
enable_quilt=true
enable_forge=true
enable_neoforge=true

loader_version=0.18.4
fabric_version=0.+

forge_version=61.1.5
forge_loader_version=[61,)

neoforge_version=21.11.0-beta
neoforge_loader_version=[21.11,)
```

Fabric API is resolved dynamically but filtered so Gradle only accepts Fabric API builds whose version ends with the selected Minecraft version, for example `+1.21.11` for Minecraft `1.21.11`.

## GitHub Actions release flow

`.github/workflows/package.yml` builds the 1.21–1.21.11 plus 26.1–26.1.2 range with a split matrix:

```txt
mc_profile x loader
```

That means Fabric, Quilt, Forge and NeoForge are isolated per Minecraft version. A broken loader no longer hides which target failed, and disabled targets such as Forge `1.21.2` are skipped before loader-specific dependencies are resolved.

For each enabled matrix target the workflow:

1. Resolves the mod version from the Git tag.
2. Reads `buildProfiles/<mcProfile>.properties` and the current loader switch.
3. Builds only the selected loader jar.
4. Validates jar metadata inside the produced artifact.
5. Uploads the jar, a `.sha256` checksum and a `.metadata.json` file.
6. Collects all loader artifacts into the GitHub Release on tag builds.
7. Publishes each loader/version pair to CurseForge in an isolated publish matrix.

CurseForge publish uses `fail-mode: warn` and a separate matrix job, so a temporary CurseForge/Mojang-side publish failure does not block other built loader artifacts.

Required secrets for CurseForge publication:

```txt
CURSEFORGE_PROJECT_ID
CURSEFORGE_TOKEN
```

## Installation

### Fabric

1. Download the Fabric jar for your exact Minecraft version.
2. Put it into the server `mods` folder.
3. Install Fabric API for the same Minecraft version.
4. Start the server once.
5. Edit `config/realtime.properties` if needed.

### Quilt

1. Download the Quilt jar for your exact Minecraft version.
2. Put it into the server `mods` folder.
3. Use a Quilt setup that can load Fabric API-compatible mods for that Minecraft version.
4. Start the server once.
5. Edit `config/realtime.properties` if needed.

### Forge

1. Download the Forge jar for your exact Minecraft version.
2. Put it into the server `mods` folder.
3. Start the server once.
4. Edit `config/realtime.properties` if needed.

### NeoForge

1. Download the NeoForge jar for your exact Minecraft version.
2. Put it into the server `mods` folder.
3. Start the server once.
4. Edit `config/realtime.properties` if needed.

## License

This project is licensed under **CC0-1.0**, according to the included [`LICENSE`](LICENSE) file and loader metadata.


## Build requirements

Requires the Java version declared by the selected profile: Java 21 for the 1.21.x profiles and Java 25 for the 26.1.x profiles. The bundled Gradle Wrapper is 9.4.0 because the 26.1.x toolchain needs the newer Gradle runtime. Use `./gradlew` from this repository.


## Build toolchain notes

- Gradle wrapper is pinned to 9.4.0 so modern Fabric Loom Remap 1.15.x and ForgeGradle 7.x can resolve Minecraft 1.21.10/1.21.11 correctly.
- ForgeGradle 6.x is not used because it fails on newer Forge 60.x/61.x userdev artifacts.
- A disabled loader in `buildProfiles/<version>.properties` is skipped before its loader-specific dependencies are resolved. This prevents placeholder values such as `forge_version=unsupported` from breaking profile tasks.
- ForgeGradle 7 run configs must use `workingDir.convention(...)` or `workingDir = ...`; the old `workingDirectory(...)` MDK syntax fails during project configuration.
- ForgeGradle 7 Forge dependencies use `implementation minecraft.dependency("net.minecraftforge:forge:${minecraft_version}-${forge_version}")`; do not use the old ForgeGradle 6 `minecraft "..."` dependency notation.


### Build compatibility notes

The 1.21.x matrix intentionally avoids direct `GameRules` imports in loader entrypoints because Mojang mappings and gamerule identifiers are not stable across every 1.21.x profile. The mod also does not call `/gamerule doDaylightCycle false`: Minecraft 1.21.11 renamed gamerules to namespaced IDs such as `minecraft:advance_time`, while older profiles still use `doDaylightCycle`. Instead, the loader entrypoints set the boolean gamerule through reflection and try the known runtime key names (`DO_DAYLIGHT_CYCLE`, `ADVANCE_TIME`, `RULE_DAYLIGHT`, `RULE_ADVANCE_TIME`) plus the stable intermediary field (`field_19396`). Forge avoids the Forge event bus entirely and uses `ServerLifecycleHooks` with a safe server-thread scheduler because Forge 1.21.6+ exposes EventBus 7 migration helpers instead of the older APIs. NeoForge profile versions are pinned exactly; do not use `21.x.+` with ModDev/NeoForm because it is resolved as a literal userdev artifact in this setup.


### CI loader isolation

CI builds each `mc_profile x loader` pair separately using `-PtargetLoader=<loader>`. This prevents metadata-only or Fabric/Quilt/NeoForge jobs from configuring Forge userdev artifacts, which is important for fragile ForgeGradle/Mavenizer versions such as Minecraft `1.21.10`.


## Minecraft 26.1.x profiles

Active profiles are included for `26.1`, `26.1.1` and `26.1.2`. These profiles use Java 25, Fabric Loader `0.18.4`, pinned Fabric API versions, exact Forge versions and exact NeoForge versions. The 26.1.x NeoForge metadata uses `loaderVersion=[1,)` for `javafml` and a separate `neoforge_version_range` dependency range so the mod metadata matches current NeoForge 26.x conventions.

Current pinned profile values:

| Profile | Java | Fabric API | Forge | NeoForge |
| --- | --- | --- | --- | --- |
| `26.1` | 25 | `0.144.3+26.1` | `62.0.9` | `26.1.0.1-beta` |
| `26.1.1` | 25 | `0.145.4+26.1.1` | `63.0.2` | `26.1.1.5-beta` |
| `26.1.2` | 25 | `0.146.1+26.1.2` | `64.0.7` | `26.1.2.36-beta` |
