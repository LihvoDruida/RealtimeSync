# RealtimeSync configuration presets

## Recommended: realistic-smooth

Best default for public survival servers. The sky follows the real clock smoothly, avoids visible jumps, does not fight sleep, and only controls the Overworld by default.

```properties
enabled=true
forceDaylightCycleOff=true
syncAllWorlds=false
syncDimensions=minecraft:overworld
ignoredDimensions=
syncMode=smooth
updateInterval=20
maxSmoothStepTicks=12
smoothSnapThresholdTicks=2
smoothCatchupDivisor=240
respectSleep=true
overrideSleepTime=false
offsetHours=0
customDayLengthMinutes=0
debugLogging=false
```

## Ultra-smooth

Use when visual smoothness matters more than fast catch-up after downtime.

```properties
syncMode=smooth
updateInterval=20
maxSmoothStepTicks=6
smoothSnapThresholdTicks=1
smoothCatchupDivisor=360
```

## Faster catch-up

Use when the server is often stopped for long periods and you want it to catch up faster after boot.

```properties
syncMode=smooth
updateInterval=20
maxSmoothStepTicks=24
smoothSnapThresholdTicks=2
smoothCatchupDivisor=120
```

## Custom 40-minute Minecraft day

Use when you want the Minecraft day/night cycle to keep moving independently from the real clock.

```properties
forceDaylightCycleOff=true
updateInterval=20
customDayLengthMinutes=40
```

`updateInterval` is not the day length. It is only the sync frequency in Minecraft ticks.

## Exact clock / no smoothing

Use only when exact matching matters more than visual movement.

```properties
syncMode=instant
updateInterval=60
```
