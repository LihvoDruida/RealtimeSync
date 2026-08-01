# RealtimeSync configuration presets

## Recommended: realistic smooth

```properties
enabled=true
daylightRulePolicy=MANAGED
dayProgressionPolicy=PRESERVE_MONOTONIC
zoneId=system
timeOffsetMinutes=0
syncAllWorlds=false
syncDimensions=minecraft:overworld
ignoredDimensions=
syncMode=smooth
updateInterval=20
smoothMaxCorrectionTicksPerSecond=1200
smoothSnapThresholdTicks=20
smoothCatchupDivisor=240
smoothLargeJumpPolicy=GRADUAL
maximumOfflineCatchUpSeconds=300
respectSleep=true
sleepPolicy=REALTIME_ONLY
sleepRealignMinutes=360
overrideSleepTime=false
customDayLengthMinutes=60
seasonalDaylight=true
latitude=50.0
seasonalDaylightMinPercent=25
seasonalDaylightMaxPercent=75
idleUpdateInterval=100
maxTicksPerUpdate=40
customClockRestartPolicy=CONTINUE_FROM_WORLD
debugLogging=false
debugPerformanceLogging=false
```

## Ultra-smooth

Use a lower real-time correction ceiling. The result remains independent of server TPS.

```properties
syncMode=smooth
updateInterval=20
smoothMaxCorrectionTicksPerSecond=120
smoothSnapThresholdTicks=4
smoothCatchupDivisor=360
smoothLargeJumpPolicy=GRADUAL
```

## Faster catch-up

```properties
syncMode=smooth
updateInterval=20
smoothMaxCorrectionTicksPerSecond=2400
smoothSnapThresholdTicks=20
smoothCatchupDivisor=120
smoothLargeJumpPolicy=GRADUAL
maximumOfflineCatchUpSeconds=300
```

## Pause on suspicious host-clock jumps

```properties
syncMode=smooth
smoothLargeJumpPolicy=PAUSE_AND_WARN
```

A difference larger than one Minecraft day is not applied until the host clock or policy is corrected.

## Custom 40-minute Minecraft day

```properties
daylightRulePolicy=MANAGED
updateInterval=20
customDayLengthMinutes=40
customClockRestartPolicy=CONTINUE_FROM_WORLD
maximumOfflineCatchUpSeconds=300
```

`updateInterval` is only the application frequency. The custom clock uses monotonic real elapsed time and therefore does not slow down with TPS.

## Calendar-anchored absolute timeline

```properties
dayProgressionPolicy=REAL_DATE_ANCHOR
zoneId=Europe/Kyiv
realDateAnchor=2026-01-01
syncMode=instant
```

This policy intentionally derives the absolute Minecraft day index from the configured calendar anchor. Switching an existing world to it can produce a large one-time absolute `dayTime` change.
