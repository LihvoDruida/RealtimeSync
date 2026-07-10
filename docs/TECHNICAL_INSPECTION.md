# Realtime Sync 1.21.x — technical inspection and modernization report

Date: 2026-07-10
Scope: Minecraft 1.21 through 1.21.11; Fabric, Fabric-compatible Quilt artifact, Forge, and NeoForge.

## Verification vocabulary

- **Inspected** — source/configuration was reviewed statically.
- **Compiled** — Java compiler or Gradle compiled the stated scope.
- **Unit-tested** — deterministic tests ran without Minecraft server startup.
- **Runtime-tested** — a real dedicated/integrated Minecraft server was started and scenarios were exercised.

These labels are not interchangeable.

## Findings and resolution status

| Severity | Component | Problem | Consequence | Resolution | Verified |
|---|---|---|---|---|---|
| CRITICAL | World time | The old code truncated targets to `0..23999` before `setDayTime` | World day count and moon phase could reset | Added `AbsoluteDayTime`; all writes now use absolute `dayTime` | Unit-tested; 1.21.11 common/profile compiled |
| CRITICAL | World time | Reflection candidates included `getGameTime`/`setGameTime` | Could corrupt the server lifetime counter and unrelated timers | Removed the generic reflection layer and all `gameTime` candidates | Static guardrail passed |
| CRITICAL | 1.21.11 gamerules | Legacy `doDaylightCycle` reflection did not match registry-backed `minecraft:advance_time` | Vanilla time could remain active or rule writes could silently fail | Added exact profile adapter using `GameRules.ADVANCE_TIME` and generic `get/set` | Compiled against cached 1.21.11 merged API |
| CRITICAL | Version isolation | Minecraft 26.x `time of ...` command syntax existed in the 1.21.x branch | Both direct and fallback paths could fail on 1.21.x | Removed command-based clock control from the branch | Static guardrail passed |
| CRITICAL | Sleep/gamerule ownership | With an initially disabled daylight rule, `respectSleep=true` could leave night permanently frozen | Players could not complete vanilla sleep progression | Added a temporary, ownership-aware sleep window that enables vanilla time only during sleep and restores safely | Inspected; 1.21.11 common/profile compiled; runtime still required |
| HIGH | Gamerule lifecycle | Rule writes were not safely owned/restored | Server could be left with a changed gamerule or overwrite an admin change | Added `MANAGED`, `REQUIRE_OFF`, `IGNORE`; original-value tracking; external-change release; stop/disable restoration | Inspected; 1.21.11 common/profile compiled |
| HIGH | Smooth sync | Correction was based on a fixed amount per update | Behavior changed with TPS and update interval | Correction now uses monotonic elapsed time, fractional carry, rate caps and offline catch-up bounds | Unit-tested at simulated 20/10/5 TPS |
| HIGH | Monotonic progression | Any small backward host-clock adjustment could be interpreted as “next day” | Instant mode could jump almost 24 hours forward | Added half-day wrap discrimination: true `23999 -> 0` wraps, small rollback freezes until catch-up | Unit-tested |
| HIGH | Forge lifecycle | A custom 50 ms scheduler polled the server outside the normal tick lifecycle | Queue buildup, uneven timing, thread lifecycle risk | Replaced with official server post-tick event | Inspected; full Forge compile blocked |
| HIGH | NeoForge lifecycle | Per-level post-tick events required deduplication | Work could be invoked once per level and was harder to reason about | Replaced with server post-tick event | API inspected; full NeoForge loader compile blocked |
| HIGH | Dimension handling | Reflection/string fallback could classify an unresolved dimension as Overworld | Wrong worlds could be synchronized | Added profile-specific `ResourceKey` adapters; unresolved IDs are skipped and warned once | 1.21.11 adapter compiled; legacy profiles statically inspected |
| HIGH | Command lifecycle | Registering diagnostics only after server start would not be robust across command dispatcher rebuilds | `/reload` could lose the command | Loader command-registration events now register `/realtimesync status` | Inspected; 1.21.11 common/profile compiled |
| HIGH | Config reload | Reload detection used only mtime and failed reloads could replace working settings | Missed edits or unsafe fallback to defaults | Added mtime + size + CRC32 fingerprint and last-known-good retention | Unit-tested in core scope; controller inspected |
| HIGH | Config encoding | Config was written as UTF-8 but read with the byte-oriented Properties path | Non-ASCII text could be corrupted | Reads and writes now use explicit UTF-8 readers/writers | Unit-tested |
| HIGH | Legacy TOML | TOML was parsed as Java Properties | Quoting/sections/comments could be misread | Added a narrow explicit legacy TOML parser, known-key filter, backup and canonical migration | Unit-tested |
| MEDIUM | Custom day length | Progress/restart behavior was underspecified and TPS-sensitive | Jumps or resets after restart/reload | Added monotonic absolute clock and `CONTINUE_FROM_WORLD`, `RESET_TO_CONFIGURED_TIME`, `PERSIST_REAL_ELAPSED` | Unit-tested for absolute progression; runtime persistence not tested |
| MEDIUM | Diagnostics | No supported way to inspect active mode/target/gamerule adapter | Troubleshooting required log/code inspection | Added operator-only `/realtimesync status` with profile-specific permission adapter | Common/profile compiled for 1.21.11 |
| MEDIUM | Gradle | NeoForge task actions accessed `project` during execution | Gradle 10 incompatibility warnings | Captured immutable/provider-backed values at configuration time | Diff inspected; Gradle execution blocked |
| MEDIUM | CI scripts | Matrix checker depended on a fixed `/tmp` file and several scripts lacked help | Non-portable CI and confusing direct invocation | Added positional path/`--help`; wrapper scripts use temporary output safely | Executed successfully |
| MEDIUM | Release workflow | CurseForge publish failures were hidden by permissive failure handling | Incomplete release could look successful | Removed blanket continuation and made publish failures visible | Workflow statically inspected |
| MEDIUM | Source packaging | Original archive contained build reports, classes and compiled JARs | Dirty, oversized and misleading source release | Added source-tree validator and clean Git-archive packaging | Clean archive validation passed |
| MEDIUM | Tests | No deterministic tests for clock math/config migration | Regressions at day boundaries were easy | Added core Java test harness and CI execution | Tests passed |
| LOW | Quilt labelling | Quilt output could be described as a native implementation | Compatibility claim was overstated | Documentation now says Fabric-compatible Quilt artifact | Inspected |
| LOW | Logging | Debug/performance output could become noisy | Larger logs and avoidable formatting | Added 60-second aggregate performance logging behind a flag | Inspected |

## Main architecture after the patch

### Build-profile adapters

The root build selects exactly one compatibility source directory:

- `common/src/mc121legacy/java` for Minecraft 1.21–1.21.10;
- `common/src/mc12111/java` for Minecraft 1.21.11.

The selected implementation supplies:

- `ProfileDaylightRuleAccess`;
- `ProfileDimensionIdAccess`;
- `ProfileCommandPermissionAccess`.

This removes runtime guessing and prevents a semantically unrelated method from being selected by reflection.

### Time model

- Only `ServerLevel.getDayTime()` / `setDayTime(long)` are used.
- Absolute day and time-of-day are separated through `AbsoluteDayTime`.
- `PRESERVE_MONOTONIC` never decreases absolute time and treats only a large circular crossing as a true day wrap.
- `PRESERVE_CURRENT_DAY` intentionally permits time-of-day corrections inside the current day.
- `REAL_DATE_ANCHOR` maps the configured real date and timezone to an absolute Minecraft day.
- Smooth correction uses `System.nanoTime()` elapsed duration, not server TPS.

### Gamerule ownership

- `MANAGED` stores the initial value and restores only a value still owned by the mod.
- `REQUIRE_OFF` warns but never writes.
- `IGNORE` neither reads nor writes for management purposes.
- A manual admin change while the mod owns the rule releases ownership.
- Sleep temporarily enables vanilla progression and restores the managed state only when the expected temporary value is still present.

### Loader lifecycle

- Fabric/Fabric-compatible Quilt: command registration callback, `SERVER_STARTED`, `END_SERVER_TICK`, `SERVER_STOPPED`.
- Forge: command registration event, server started, server post-tick, server stopped.
- NeoForge: command registration event, server started, server post-tick, server stopped.
- No custom scheduler, timer or executor remains.

## Config migration

Canonical additions:

```properties
daylightRulePolicy=MANAGED
dayProgressionPolicy=PRESERVE_MONOTONIC
zoneId=system
timeOffsetMinutes=0
realDateAnchor=1970-01-01
smoothMaxCorrectionTicksPerSecond=1200
smoothSnapThresholdTicks=20
smoothCatchupDivisor=240
smoothLargeJumpPolicy=GRADUAL
maximumOfflineCatchUpSeconds=300
customClockRestartPolicy=CONTINUE_FROM_WORLD
debugPerformanceLogging=false
```

Deprecated keys are accepted and rewritten to canonical UTF-8 properties:

- `forceDaylightCycleOff`;
- `offsetHours`;
- `maxSmoothStepTicks`;
- `minutesPerMinecraftDay`.

The legacy `realtime.toml` file is not passed to `Properties.load`. Only known flat keys are migrated; unsupported sections/keys are warned and skipped. The original file remains and a `.bak` copy is attempted.

## Validation commands and results

Passed:

```text
python3 scripts/validate-build-profiles.py
  Build profile dependency validation passed.

python3 scripts/validate-entrypoints.py
  Entrypoint, profile adapter and loader metadata validation passed.

python3 scripts/validate-dependency-artifacts.py
  Offline lock validation passed; 71 artifact coordinates checked.

python3 scripts/generate-ci-matrix.py | scripts/check-ci-matrix.py
  47 supported build entries.

python3 scripts/check-fabric-loom-1-21.py
  Fabric/Quilt 1.21.x Loom validation passed.

bash scripts/run-core-tests.sh
  Core logic tests passed.

bash scripts/verify-build-matrix.sh
  Static build matrix verification passed.

python3 scripts/validate-source-tree.py
  Clean source tree passed after generated artifacts were removed.

git diff --check
  Passed.
```

Additional compile check:

```text
Java 21 javac against cached NeoForge 1.21.11 merged Minecraft API:
  common/src/main/java
  common/src/mc12111/java
Result: PASS
```

The compile check used minimal external stubs only for unavailable Brigadier/Log4j artifacts; Minecraft/NeoForge-facing symbols came from the cached 1.21.11 merged JAR. It is narrower than a Gradle loader build.

## Gradle build blocker

A complete Gradle build was attempted, but the wrapper distribution was unavailable locally and DNS/network access failed:

```text
Downloading https://services.gradle.org/distributions/gradle-9.4.0-bin.zip
java.net.UnknownHostException: services.gradle.org
```

Therefore these claims are deliberately **not** made:

- no full Fabric/Quilt/Forge/NeoForge JAR build was completed;
- no remap/reobfuscation task was completed;
- no dedicated server was started;
- no multiplayer, sleep, `/reload`, custom-dimension, low-TPS or mod-conflict runtime scenario was executed.

## Compatibility matrix

| Minecraft | Enabled artifacts | Fabric Loader | Fabric API | Forge | NeoForge | Static matrix | Compile | Runtime |
|---|---|---:|---:|---:|---:|---|---|---|
| 1.21 | Fabric, Quilt-compatible, Forge, NeoForge | 0.18.4 | 0.102.0+1.21 | 51.0.33 | 21.0.160 | PASS | Not run | Not run |
| 1.21.1 | Fabric, Quilt-compatible, Forge, NeoForge | 0.18.4 | 0.116.6+1.21.1 | 52.1.14 | 21.1.172 | PASS | Not run | Not run |
| 1.21.2 | Fabric, Quilt-compatible, NeoForge | 0.18.4 | 0.106.1+1.21.2 | Unsupported | 21.2.0-beta | PASS | Not run | Not run |
| 1.21.3 | Fabric, Quilt-compatible, Forge, NeoForge | 0.18.4 | 0.108.0+1.21.3 | 53.1.10 | 21.3.87 | PASS | Not run | Not run |
| 1.21.4 | Fabric, Quilt-compatible, Forge, NeoForge | 0.18.4 | 0.119.4+1.21.4 | 54.1.16 | 21.4.147 | PASS | Not run | Not run |
| 1.21.5 | Fabric, Quilt-compatible, Forge, NeoForge | 0.18.4 | 0.128.2+1.21.5 | 55.1.10 | 21.5.95 | PASS | Not run | Not run |
| 1.21.6 | Fabric, Quilt-compatible, Forge, NeoForge | 0.18.4 | 0.128.2+1.21.6 | 56.0.9 | 21.6.16-beta | PASS | Not run | Not run |
| 1.21.7 | Fabric, Quilt-compatible, Forge, NeoForge | 0.18.4 | 0.128.2+1.21.7 | 57.0.3 | 21.7.20-beta | PASS | Not run | Not run |
| 1.21.8 | Fabric, Quilt-compatible, Forge, NeoForge | 0.18.4 | 0.130.0+1.21.8 | 58.1.18 | 21.8.39 | PASS | Not run | Not run |
| 1.21.9 | Fabric, Quilt-compatible, Forge, NeoForge | 0.18.4 | 0.134.1+1.21.9 | 59.0.5 | 21.9.9-beta | PASS | Not run | Not run |
| 1.21.10 | Fabric, Quilt-compatible, Forge, NeoForge | 0.18.4 | 0.138.4+1.21.10 | 60.1.0 | 21.10.48-beta | PASS | Not run | Not run |
| 1.21.11 | Fabric, Quilt-compatible, Forge, NeoForge | 0.18.4 | 0.141.3+1.21.11 | 61.1.5 | 21.11.0-beta | PASS | Common/profile PASS; loader Gradle blocked | Not run |

## Dependency decision

The dependency pins were intentionally not raised without a complete matrix build. As of 2026-07-10, official repositories list newer 1.21.11 options than the current pins, including Fabric API `0.141.4+1.21.11`, Fabric Loader `0.19.3`, and Forge `61.1.9`. Changing minimums or release pins without compiling all declared combinations would replace known reproducible coordinates with unverified ones.

## Files changed by area

Core architecture:

- `AbsoluteDayTime.java`
- `RealtimeMath.java`
- `RealtimeController.java`
- `RealtimeWorldTime.java`
- `RealtimeGameRules.java`
- `RealtimeConfig.java`
- `RealtimePersistentState.java`
- `RealtimeStatusCommand.java`
- compatibility interfaces and profile implementations
- removed `RealtimeReflection.java` and the old command fallback class

Loader integration:

- Fabric entrypoint and build script
- Forge entrypoint and build script
- NeoForge entrypoint and build script
- Quilt-compatible build script

Quality/release:

- core tests
- matrix/profile/entrypoint/source validators
- GitHub Actions release behavior
- `.gitignore`
- README, versioning, compatibility, presets and migration documentation

## Remaining runtime acceptance tests

Before publishing a release, run the full Gradle matrix in a networked or fully cached environment and then start real servers for at least:

1. 1.21, 1.21.4, 1.21.5, 1.21.10 and 1.21.11;
2. Fabric, Forge and NeoForge boundaries;
3. a real Quilt Loader instance using the Fabric-compatible artifact;
4. an existing world with a large absolute day count;
5. sleep with initial gamerule both true and false;
6. manual gamerule changes before, during and after sleep;
7. `/reload` and `/realtimesync status`;
8. Nether, End and a custom dimension;
9. 5/10/20 TPS simulation or induced lag;
10. conflict with another time-changing mod;
11. restart under every custom-clock restart policy.

Until those pass, the patch is **inspected, core-unit-tested and partially API-compiled**, not runtime-certified.
