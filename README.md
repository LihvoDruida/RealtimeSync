# RealtimeSync

**RealtimeSync** is a lightweight server-side Minecraft mod that synchronizes Minecraft world time with the server's real system time or with a configurable custom day duration.

The project is built as a **multi-loader, multi-version** Gradle project. Release builds are generated per Minecraft version and per loader.

## Supported Minecraft range

Current profiles cover:

```txt
1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5,
1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11
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
- Per-version build profiles for the full 1.21 through 1.21.11 range.
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

Example:

```properties
enabled=true
forceDaylightCycleOff=true
syncAllWorlds=true
updateInterval=60
offsetHours=0
customDayLengthMinutes=0
debugLogging=false
```

### Options

| Key | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Enables or disables the mod without removing it. |
| `forceDaylightCycleOff` | `true` | Keeps vanilla `doDaylightCycle` disabled so the mod controls time cleanly. |
| `syncAllWorlds` | `true` | Syncs all loaded dimensions. Set `false` to sync only the Overworld. |
| `updateInterval` | `60` | Ticks between syncs. `20` ticks = 1 second. |
| `offsetHours` | `0` | Shifts real-time sync by `-23..23` hours. |
| `customDayLengthMinutes` | `0` | `0` means real clock sync. Values above `0` set a custom Minecraft day length. |
| `debugLogging` | `false` | Enables verbose sync logs. |

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

neoforge_version=21.11.+
neoforge_loader_version=[21.11,)
```

Fabric API is resolved dynamically but filtered so Gradle only accepts Fabric API builds whose version ends with the selected Minecraft version, for example `+1.21.11` for Minecraft `1.21.11`.

## GitHub Actions release flow

`.github/workflows/package.yml` builds every profile in the 1.21–1.21.11 range.

For each profile it:

1. Resolves the mod version from the Git tag.
2. Reads `buildProfiles/<mcProfile>.properties`.
3. Builds all enabled loader artifacts.
4. Verifies expected jars.
5. Uploads artifacts.
6. Publishes release files on tag builds.
7. Publishes Fabric, Quilt, Forge and NeoForge files to CurseForge when secrets are configured.

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

Requires Java 21 and Gradle Wrapper 8.14.3 or newer because Fabric Loom 1.11.x requires Gradle 8.14+. Use `./gradlew` from this repository.
