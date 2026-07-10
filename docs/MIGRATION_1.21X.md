# RealtimeSync 1.21.x migration notes

## Configuration key migration

The following old keys are still read but are deprecated:

| Old key | New key / behavior |
| --- | --- |
| `forceDaylightCycleOff=true` | `daylightRulePolicy=MANAGED` |
| `forceDaylightCycleOff=false` | `daylightRulePolicy=IGNORE` |
| `offsetHours=N` | `timeOffsetMinutes=N*60` |
| `maxSmoothStepTicks=N` | Converted approximately to `smoothMaxCorrectionTicksPerSecond` using `updateInterval` |
| `minutesPerMinecraftDay=N` | `customDayLengthMinutes=N` |
| `zoneId=Europe/Kiev` | `zoneId=Europe/Kyiv` |

After a successful read, deprecated keys and known timezone aliases are rewritten once to the canonical UTF-8 `realtime.properties` format using an atomic replacement. If the rewrite fails, the original file remains available and the migration is attempted again later.

## Legacy TOML

When `realtime.properties` does not exist and `realtime.toml` does:

1. only flat `key = value` entries are parsed;
2. unsupported table syntax is ignored with a warning;
3. the new UTF-8 properties file is written atomically;
4. the original file is retained and copied to `realtime.toml.bak`.

The migration is intentionally narrow. RealtimeSync no longer pretends that a Java Properties parser is a complete TOML parser.

## Absolute day-time change

Old builds could write only `dayTime % 24000`, resetting the absolute world day and moon phase. The fixed build preserves the full absolute value. On first startup, no artificial reset is performed.

`PRESERVE_MONOTONIC` is the safe default. It selects the next forward occurrence of the real-clock time and never lowers the current absolute `dayTime`. A world whose current time is ahead of the target can therefore advance into the next Minecraft day instead of moving backward.

`PRESERVE_CURRENT_DAY` allows within-day backward correction while preserving the day index.

`REAL_DATE_ANCHOR` deliberately maps the calendar to an absolute timeline and may cause a large one-time change when enabled on an existing world.

## Gamerule ownership

`MANAGED` remembers the initial daylight-rule value and restores it only while the current value is still the one written by RealtimeSync. If an administrator changes it externally, the mod releases ownership instead of overwriting the administrator repeatedly.

Minecraft 1.21.11 uses registry gamerule `minecraft:advance_time`; 1.21–1.21.10 use the legacy daylight rule adapter.

## Smooth mode

The old `maxSmoothStepTicks` behavior depended on TPS because the same step was applied per update. The new limit is expressed in ticks per real second and uses `System.nanoTime()` elapsed duration, with a bounded offline/JVM-pause catch-up window.

## Removed behavior

The 1.21.x branch no longer contains:

- Minecraft 26.x `time of` commands;
- `getGameTime()` or `setGameTime()` fallbacks;
- Forge 50 ms background scheduling;
- NeoForge per-level tick deduplication;
- broad gamerule method-name guessing.
