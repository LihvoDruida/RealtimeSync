# RealtimeSync

Fabric mod that synchronizes Minecraft world time with the server system clock or a configurable custom day length.

## Compatibility

- Minecraft: `1.21.x` (`>=1.21 <1.22`)
- Java: `21+`
- Loader: Fabric Loader `0.16.10+`
- Required: Fabric API

The mod avoids mixins and client-only code, so it is safer for dedicated servers and integrated singleplayer worlds.

## Config

Config file: `config/realtime.properties`

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

- `enabled` — enables or disables the mod.
- `forceDaylightCycleOff` — keeps vanilla daylight cycle disabled.
- `syncAllWorlds` — syncs all loaded dimensions. Set to `false` to sync only Overworld.
- `updateInterval` — sync interval in ticks. `20` ticks = 1 second.
- `offsetHours` — shifts real-time sync by `-23..23` hours.
- `customDayLengthMinutes` — `0` uses real clock sync. Any value above `0` uses a custom Minecraft day length.
- `debugLogging` — logs every sync operation.

The config is checked automatically every 5 seconds, so server restarts are usually not needed for simple changes.

## Migration note

Older versions used `config/realtime.toml` through Cloth Config / AutoConfig. If `realtime.properties` does not exist yet, the mod reads the old TOML-style file once and writes a new `realtime.properties` file with the same supported values.
