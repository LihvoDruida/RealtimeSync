# RealtimeSync

**Synchronizes Minecraft in-game time with the server's system time or with a custom day duration.**

RealtimeSync is a lightweight **server-side Minecraft mod**. It disables Minecraft's vanilla daylight cycle and manually controls world time based on either the real server clock or a configurable custom day length.

## Supported loaders

RealtimeSync uses a multi-loader project layout. Do **not** upload one universal jar for every loader; publish the correct jar for each loader.

| Loader | Artifact | CurseForge loader tag | Notes |
| --- | --- | --- | --- |
| Fabric | `realtime-sync-fabric-<mc-version>-<mod-version>.jar` | Fabric | Requires Fabric API. |
| Quilt | `realtime-sync-fabric-<mc-version>-<mod-version>.jar` | Quilt | Uses the Fabric-compatible jar. |
| Forge | `realtime-sync-forge-<mc-version>-<mod-version>.jar` | Forge | Native Forge entrypoint and `META-INF/mods.toml`. |
| NeoForge | `realtime-sync-neoforge-<mc-version>-<mod-version>.jar` | NeoForge | Native NeoForge entrypoint and `META-INF/neoforge.mods.toml`. |

## Features

- **Server-side only** — players do not need to install the mod on the client.
- **Minecraft 1.21.5 build target** — release files are published with the exact Minecraft version they are built against.
- Separate **Fabric/Quilt**, **Forge**, and **NeoForge** jars.
- **No mixins** — safer compatibility with other mods and server environments.
- **No Cloth Config / AutoConfig dependency** — uses a simple built-in config file.
- **Real-time sync mode** — matches Minecraft time to the server's system clock.
- **Custom day length mode** — allows a full Minecraft day to last a custom number of real-world minutes.
- **Multi-dimension support** — can synchronize all loaded worlds/dimensions, not only the Overworld.
- **Hot config reload** — the config is checked automatically about every 5 seconds.
- **Legacy config migration** — old `config/realtime.toml` values are migrated once to the new `config/realtime.properties` file.

## Compatibility

Current release target:

- **Minecraft**: `1.21.5`
- **Java**: `21+`
- **Fabric**: Fabric Loader `0.16.13+` and Fabric API `0.119.9+1.21.5`
- **Quilt**: supported through the Fabric-compatible jar
- **Forge**: Forge `55.1.10` for Minecraft `1.21.5`
- **NeoForge**: NeoForge `21.5.30-beta+`
- **Environment**: dedicated server and integrated singleplayer

The loader metadata still uses safe Minecraft ranges where supported, but CurseForge files should be uploaded against the exact Minecraft version that was built and tested.

## Configuration

The config file is shared between all loaders:

```txt
config/realtime.properties
```

Example config:

```properties
enabled=true
forceDaylightCycleOff=true
syncAllWorlds=true
updateInterval=60
offsetHours=0
customDayLengthMinutes=0
debugLogging=false
```

### `enabled`

- **Type**: Boolean
- **Default**: `true`
- **Description**: Enables or disables RealtimeSync without removing the mod.

### `forceDaylightCycleOff`

- **Type**: Boolean
- **Default**: `true`
- **Description**: Keeps the vanilla `doDaylightCycle` gamerule disabled so Minecraft does not fight against the mod's manual time control.

### `syncAllWorlds`

- **Type**: Boolean
- **Default**: `true`
- **Description**: Synchronizes time for all loaded worlds/dimensions, including the Overworld, Nether, End and custom dimensions. Set this to `false` if you want to synchronize only the Overworld.

### `updateInterval`

- **Type**: Integer
- **Default**: `60`
- **Range**: `1` to `36000`
- **Description**: The interval, in server ticks, between time updates. `20` ticks = 1 second, so `60` means the time is updated every 3 seconds.

### `offsetHours`

- **Type**: Integer
- **Default**: `0`
- **Range**: `-23` to `23`
- **Description**: Shifts real-time synchronization by the given number of hours. Positive values move time forward, negative values move it backward. Example: `offsetHours=2` shifts the in-game time 2 hours ahead of the server clock.

### `customDayLengthMinutes`

- **Type**: Integer
- **Default**: `0`
- **Range**: `0` to `10080`
- **Description**: Sets a custom duration for a full Minecraft day in real-world minutes. If set to `0`, the mod uses the server's system time. Example: `customDayLengthMinutes=1` makes a full Minecraft day last 1 real minute.

### `debugLogging`

- **Type**: Boolean
- **Default**: `false`
- **Description**: Enables detailed log messages for each time synchronization operation. Useful for debugging, but not recommended for normal gameplay servers.

## Legacy `realtime.toml` migration

Older versions used:

```txt
config/realtime.toml
```

The new version uses:

```txt
config/realtime.properties
```

If `realtime.properties` does not exist yet, RealtimeSync will try to read the old `realtime.toml` file once and create a new `realtime.properties` file with the supported values.

After migration, edit `realtime.properties` instead of `realtime.toml`.

## How to install

### Fabric

1. Download the Fabric jar.
2. Place it in the `mods` folder of your Fabric server.
3. Install Fabric API on the server.
4. Start the server once to generate the config file.
5. Edit `config/realtime.properties` if needed.

### Quilt

1. Download the Fabric jar.
2. Place it in the `mods` folder of your Quilt server.
3. Install the Fabric API / QFAPI setup required by your Quilt instance.
4. Start the server once to generate the config file.
5. Edit `config/realtime.properties` if needed.

### Forge

1. Download the Forge jar.
2. Place it in the `mods` folder of your Forge server.
3. Start the server once to generate the config file.
4. Edit `config/realtime.properties` if needed.

### NeoForge

1. Download the NeoForge jar.
2. Place it in the `mods` folder of your NeoForge server.
3. Start the server once to generate the config file.
4. Edit `config/realtime.properties` if needed.

## Building from source

Build everything:

```bash
./gradlew clean buildAllLoaders
```

Build only Fabric / Quilt-compatible jar:

```bash
./gradlew clean buildFabric
```

Build only Forge jar:

```bash
./gradlew clean buildForge
```

Build only NeoForge jar:

```bash
./gradlew clean buildNeoForge
```

Output jars:

```txt
fabric/build/libs/
forge/build/libs/
neoforge/build/libs/
```

## CurseForge publishing

See [`CURSEFORGE.md`](CURSEFORGE.md) for the exact file mapping and project settings. In short:

- Project loader checkboxes: **Fabric**, **Quilt**, **Forge**, **NeoForge**.
- Release files: upload one Fabric/Quilt jar, one Forge jar, and one NeoForge jar.
- Game version for this release: **Minecraft 1.21.5**.
- Java version: **Java 21**.

## Notes

- This mod is **server-side only**. Client installation is not required.
- The mod controls time manually, so `doDaylightCycle` is disabled by default.
- Config changes are usually picked up automatically within about 5 seconds.
- Use `customDayLengthMinutes=0` for real server clock synchronization.
- Use `customDayLengthMinutes>0` for a custom day duration.
- All loader builds share the same config file and the same time calculation logic.

## License

This project is currently licensed under **CC0-1.0**, according to the included [`LICENSE`](LICENSE) file and loader metadata.
