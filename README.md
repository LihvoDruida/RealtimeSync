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

Forge event registration changes inside the 1.21.x line, so the build profile also selects `forge_event_api`:

- `legacy` for Minecraft 1.21–1.21.5: `MinecraftForge.EVENT_BUS` and `getServer()`;
- `eventbus7` for Minecraft 1.21.6–1.21.8: event-local `EventName.BUS` fields and `getServer()`;
- `record-events` for Minecraft 1.21.9–1.21.11: event-local buses and the record accessor `ServerTickEvent.Post.server()`.

Only the matching `forge/src/<forge_event_api>/java` entrypoint is compiled for a profile.

## Features

- Server-side only; clients do not need the mod installed.
- Per-version build profiles for the Minecraft 1.21.x line only.
- Separate Fabric, Quilt-tagged, Forge and NeoForge artifacts.
- No mixins.
- No Cloth Config or AutoConfig dependency.
- Real-time sync mode.
- Custom day length mode.
- Multi-dimension support.
- Hot config reload.
- Legacy `realtime.toml` migration to `realtime.properties`.

## Configuration

All loaders use the same UTF-8 configuration file:

```txt
config/realtime.properties
```

Recommended real-time profile:

```properties
enabled=true
daylightRulePolicy=MANAGED
dayProgressionPolicy=PRESERVE_MONOTONIC
zoneId=system
timeOffsetMinutes=0
realDateAnchor=1970-01-01

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

customDayLengthMinutes=0
customClockRestartPolicy=CONTINUE_FROM_WORLD
respectSleep=true
overrideSleepTime=false
debugLogging=false
debugPerformanceLogging=false
```

### Important options

| Key | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Enables or disables synchronization without removing the mod. |
| `daylightRulePolicy` | `MANAGED` | `MANAGED` temporarily disables vanilla time advancement and restores the original value when ownership ends. `REQUIRE_OFF` only checks and warns. `IGNORE` never reads or writes the rule. Minecraft 1.21.11 uses `minecraft:advance_time`; older profiles use the legacy daylight rule. |
| `dayProgressionPolicy` | `PRESERVE_MONOTONIC` | `PRESERVE_MONOTONIC` never lowers absolute `dayTime`; `PRESERVE_CURRENT_DAY` keeps the current day index; `REAL_DATE_ANCHOR` derives an absolute timeline from `realDateAnchor`. |
| `zoneId` | `system` | IANA timezone such as `Europe/Kyiv`, or `system` to use the host timezone. |
| `timeOffsetMinutes` | `0` | Additional real-clock offset in minutes. |
| `realDateAnchor` | `1970-01-01` | Calendar anchor used only by `REAL_DATE_ANCHOR`. |
| `syncAllWorlds` | `false` | Synchronizes every resolvable loaded dimension only when the allowlist is empty. Setting it to `true` while `syncDimensions` is non-empty has no effect and is reported as a startup warning. |
| `syncDimensions` | `minecraft:overworld` | Comma-separated ResourceLocation allowlist. Unknown identifiers are skipped rather than treated as the Overworld. |
| `ignoredDimensions` | empty | Comma-separated denylist; it always wins over the allowlist. |
| `syncMode` | `smooth` | `instant` writes the absolute target directly. `smooth` corrects drift using real elapsed monotonic time instead of assuming 20 TPS. |
| `updateInterval` | `20` | Server ticks between applications. It controls update frequency, not day length. |
| `smoothMaxCorrectionTicksPerSecond` | `1200` | Maximum correction speed based on actual elapsed seconds. |
| `smoothSnapThresholdTicks` | `20` | Snaps very small remaining differences to avoid jitter. |
| `smoothCatchupDivisor` | `240` | Higher values make adaptive catch-up gentler. |
| `smoothLargeJumpPolicy` | `GRADUAL` | `GRADUAL`, `SNAP`, or `PAUSE_AND_WARN` for differences larger than one Minecraft day. |
| `maximumOfflineCatchUpSeconds` | `300` | Caps elapsed time consumed after JVM pauses or server downtime. |
| `customDayLengthMinutes` | `0` | `0` uses the real clock. Positive values define a custom day duration using monotonic elapsed time. |
| `customClockRestartPolicy` | `CONTINUE_FROM_WORLD` | `CONTINUE_FROM_WORLD`, `RESET_TO_CONFIGURED_TIME`, or `PERSIST_REAL_ELAPSED`. |
| `respectSleep` | `true` | Temporarily releases the managed daylight rule and pauses mod writes **in the dimension where a player is sleeping**, allowing vanilla sleep progression. Other managed dimensions keep synchronizing. |
| `overrideSleepTime` | `false` | Keeps synchronization active during sleep. |
| `debugLogging` | `false` | Enables detailed functional logs. |
| `debugPerformanceLogging` | `false` | Emits aggregated 60-second performance summaries rather than per-tick spam. |

Legacy keys `forceDaylightCycleOff`, `offsetHours`, `maxSmoothStepTicks`, and `minutesPerMinecraftDay` are read for compatibility and rewritten once to the canonical UTF-8 format after a successful migration. The legacy timezone alias `Europe/Kiev` is rewritten as `Europe/Kyiv`. A legacy `realtime.toml` is parsed only for supported flat keys, backed up as `realtime.toml.bak`, and converted atomically to UTF-8 `realtime.properties`.

### Absolute day-time behavior

RealtimeSync writes only `ServerLevel.setDayTime(long)`. It never writes `gameTime`, never truncates the value to `0..23999`, and never executes Minecraft 26.x `time of` commands. This preserves the world day counter, moon phase, scheduled ticks, and unrelated server timers.

In the vanilla coordinate system, `dayTime=0` corresponds to 06:00. Therefore `REAL_DATE_ANCHOR` keeps a continuous absolute timeline across real midnight and rolls the Minecraft day index when the mapped time crosses tick `0`.

### Vanilla dimension scope

Vanilla shares one `GameRules` instance and one `dayTime` counter across all built-in dimensions: non-Overworld levels use `DerivedLevelData`, whose `setDayTime` is a no-op and whose `getGameRules` delegates to the primary level data. Two consequences:

- Disabling the daylight rule for the Overworld disables it for the Nether and the End as well. This is vanilla behavior and cannot be scoped per dimension.
- Adding `minecraft:the_nether` or `minecraft:the_end` to `syncDimensions` has no measurable effect, because writes to those levels do not change any stored value.

Per-dimension synchronization is therefore meaningful only for modded dimensions that carry their own level data.

### Dimension filtering

By default only the Overworld is synchronized:

```properties
syncAllWorlds=false
syncDimensions=minecraft:overworld
ignoredDimensions=
```

Custom dimensions must use exact namespaced identifiers, for example:

```properties
syncDimensions=minecraft:overworld,example:moon
ignoredDimensions=example:timeless_dimension
```

### Runtime architecture

Startup diagnostics include the embedded mod version, Minecraft profile, loader, selected API adapters, managed dimensions, absolute `dayTime` and gamerule ownership state. Configuration loading and migration are deferred until the dedicated server thread starts; loader construction no longer rewrites files from parallel mod-loading workers.


Loader entrypoints are deliberately thin:

- Fabric/Quilt-compatible builds use `SERVER_STARTED`, `END_SERVER_TICK`, `SERVER_STOPPING`, and `SERVER_STOPPED` lifecycle events.
- Gamerule ownership is released in the *stopping* phase (`SERVER_STOPPING` / `ServerStoppingEvent`) on every loader. `SERVER_STOPPED` runs after the levels have been saved and closed, so a restore performed there would never reach `level.dat`.
- Forge uses a build-profile-selected lifecycle entrypoint for the EventBus 6, EventBus 7 class-event, or EventBus 7 record-event API boundary; there is no 50 ms background scheduler.
- NeoForge uses one `ServerTickEvent.Post` per server tick, not one level event per dimension.
- `RealtimeController` owns cadence, sleep suspension, config reload, persistence, and dimension iteration.
- `ProfileDaylightRuleAccess` is selected at build time: legacy API for 1.21–1.21.10 and registry-backed `GameRules.ADVANCE_TIME` for 1.21.11.
- `RealtimeWorldTime` uses direct absolute `dayTime` access, while build-profile dimension adapters call `ResourceKey.location()` on 1.21–1.21.10 and `ResourceKey.identifier()` on 1.21.11.

### Diagnostics command

Operators can run:

```text
/realtimesync status
```

The command reports the mode, timezone, absolute current/target `dayTime`, day index, managed dimensions, gamerule ownership, selected compatibility adapters, and last successful update/reload timestamps. Minecraft 1.21.11 uses the new `PermissionSet` API; older profiles use the legacy level-2 permission check through a build-profile adapter.

See `docs/MIGRATION_1.21X.md`, `docs/COMPATIBILITY.md`, and `docs/CONFIG_PRESETS.md`.

## Local build versioning

Use `-ModVersion` for deployable local jars. Without it, Git checkouts produce `0.0.0-dev+<short-commit>` and source archives without `.git` produce `0.0.0-dev+local`.

## Building

Use Java 21 and run commands from the repository root.

### Windows PowerShell (recommended)

Build all enabled loaders for Minecraft 1.21.11:

```powershell
.\scripts\build.ps1 -Profile 1.21.11 -Loader all
```

Build one loader only:

```powershell
.\scripts\build.ps1 -Profile 1.21.11 -Loader fabric
.\scripts\build.ps1 -Profile 1.21.11 -Loader quilt
.\scripts\build.ps1 -Profile 1.21.11 -Loader forge
.\scripts\build.ps1 -Profile 1.21.11 -Loader neoforge
```

Build a deployable NeoForge JAR with an explicit version:

```powershell
.\scripts\build.ps1 -Profile 1.21.11 -Loader neoforge -ModVersion 1.4.1
```

Build the default Minecraft 1.21.5 profile for all loaders:

```powershell
.\scripts\build.ps1 -Profile 1.21.5 -Loader all
```

The PowerShell wrapper passes `mcProfile` and `targetLoader` as separate quoted native arguments, validates the profile/loader pair, and avoids configuring unrelated loader projects.

### Direct Gradle commands on Windows

These commands are also valid:

```powershell
.\gradlew.bat "-PmcProfile=1.21.11" "-PtargetLoader=fabric" clean buildFabric
.\gradlew.bat "-PmcProfile=1.21.11" "-PtargetLoader=quilt" clean buildQuilt
.\gradlew.bat "-PmcProfile=1.21.11" "-PtargetLoader=forge" clean buildForge
.\gradlew.bat "-PmcProfile=1.21.11" "-PtargetLoader=neoforge" clean buildNeoForge
.\gradlew.bat "-PmcProfile=1.21.5" "-PtargetLoader=all" clean buildAllLoaders
```

Do not omit `targetLoader` in automation. A single-loader task can infer it, but setting it explicitly prevents unrelated loader plugins and dependencies from being configured.

### Linux/macOS

```bash
./scripts/build.sh 1.21.11 fabric
./scripts/build.sh 1.21.11 quilt
./scripts/build.sh 1.21.11 forge
./scripts/build.sh 1.21.11 neoforge
./scripts/build.sh 1.21.5 all
```

Build all profiles:

```bash
./scripts/build-all-profiles.sh
```

Build selected profiles or one loader across profiles:

```bash
./scripts/build-all-profiles.sh 1.21.5 1.21.10 1.21.11
./scripts/build-all-profiles.sh --loader fabric 1.21.10 1.21.11
```

Windows equivalent:

```powershell
.\scripts\build-all-profiles.ps1
.\scripts\build-all-profiles.ps1 -Loader fabric -Profile 1.21.10,1.21.11
```

Inspect the resolved profile without configuring loader projects:

```powershell
.\gradlew.bat "-PmcProfile=1.21.11" "-PtargetLoader=none" printBuildProfile
```

Output folders:

```txt
fabric/build/libs/
quilt/build/libs/
forge/build/libs/
neoforge/build/libs/
```

When running several loader builds separately, use the wrapper scripts or keep the matching `targetLoader`. Otherwise a root `clean` can configure/clean unrelated included projects and make the sequence slower or remove previous outputs.

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
fabric_version=0.141.3+1.21.11

forge_version=61.1.5
forge_loader_version=[61,)

neoforge_version=21.11.0-beta
neoforge_loader_version=[1,)
neoforge_version_range=[21.11,)
```

Fabric API is pinned to an exact artifact per profile and is also declared as a minimum runtime dependency in `fabric.mod.json`, for example `>=0.141.3+1.21.11` for Minecraft `1.21.11`. The version suffix must match the selected Minecraft version.

NeoForge keeps two ranges separate:

- `neoforge_loader_version=[1,)` documents the `javafml` language-loader floor; `neoforge.mods.toml` hard-codes `loaderVersion="[1,)"` so runtime NeoForge ranges cannot leak into this field;
- `neoforge_version_range=[21.x,)` is the actual NeoForge runtime dependency range used by `[[dependencies.realtime]]`.

## GitHub Actions release flow

`.github/workflows/package.yml` builds the 1.21–1.21.11 range only when a Git tag matching `v*` is pushed. Regular pushes to `mc-1.21.x` do not start CI builds, and manual `workflow_dispatch` is disabled for this branch.

```txt
mc_profile x loader
```

That means Fabric, Quilt, Forge and NeoForge are isolated per Minecraft version. A broken loader no longer hides which target failed, and disabled targets such as Forge `1.21.2` are skipped before loader-specific dependencies are resolved.

The tag is the release version source. The workflow fails on non-tag refs instead of producing `0.0.0-dev` artifacts.

Release example:

```bash
git checkout mc-1.21.x
git pull origin mc-1.21.x
git tag v1.4.0
git push origin v1.4.0
```

For each enabled matrix target the workflow:

1. Resolves the mod version from the Git tag.
2. Reads `buildProfiles/<mcProfile>.properties` and the current loader switch.
3. Builds only the selected loader jar.
4. Validates jar metadata inside the produced artifact.
5. Uploads the jar, a `.sha256` checksum and a `.metadata.json` file.
6. Collects all loader artifacts into the GitHub Release on tag builds.
7. Publishes each loader/version pair to CurseForge in an isolated publish matrix.

CurseForge publish uses `fail-mode: fail`. A failed mandatory publication is visible and fails the corresponding matrix job instead of being silently accepted.

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

Requires Java 21 for every active Minecraft `1.21.x` profile. Use the bundled `./gradlew` wrapper from this repository.

## Build toolchain notes

- Active branch target: `mc-1.21.x`.
- Gradle wrapper is pinned to 9.4.0 so Fabric Loom Remap 1.15.x and ForgeGradle 7.x resolve the newer 1.21.x profiles correctly.
- ForgeGradle 6.x is not used because it fails on newer Forge 60.x/61.x userdev artifacts.
- A disabled loader in `buildProfiles/<version>.properties` is skipped before loader-specific dependencies are resolved. This prevents placeholder values such as `forge_version=unsupported` from breaking profile tasks.
- Fabric and Quilt always use `net.fabricmc.fabric-loom-remap` with `loom.officialMojangMappings()` on this branch.

### CI loader isolation

CI builds each `mc_profile x loader` pair separately using `-PtargetLoader=<loader>`. This prevents metadata-only or Fabric/Quilt/NeoForge jobs from configuring Forge userdev artifacts, which is important for fragile ForgeGradle/Mavenizer versions such as Minecraft `1.21.10`.

## Verified dependency profiles

Active `buildProfiles/*.properties` are dependency-locked and limited to Minecraft `1.21.x` only. Run:

```bash
python3 scripts/validate-build-profiles.py
bash scripts/verify-build-matrix.sh
```

Important rules:

- Fabric API is pinned per Minecraft profile instead of `0.+` and exposed as a minimum runtime dependency instead of `*`.
- Forge `1.21.2` is intentionally disabled because the active Forge downloads list does not provide a normal Forge artifact for that Minecraft version.
- Forge `1.21.10` is pinned to `60.1.0` instead of latest `60.1.9`, because `60.1.9` can fail in ForgeGradle Mavenizer on GitHub-hosted runners.
- NeoForge uses hard-coded `loaderVersion=[1,)` for `javafml` and `neoforge_version_range=[21.x,)` for the NeoForge dependency itself.
- Every active profile uses Java 21.

## Compatibility lock and supported matrix

Supported Minecraft/loader combinations are controlled by `config/build-compatibility.lock.json`. CI no longer hardcodes the full matrix in YAML; it generates the matrix with `scripts/generate-ci-matrix.py`, so disabled or unsupported combinations are skipped before Gradle resolves loader dependencies.

Before changing a loader dependency, update both the relevant `buildProfiles/<version>.properties` file and the compatibility lock, then run:

```bash
python3 scripts/validate-build-profiles.py
python3 scripts/validate-dependency-artifacts.py
bash scripts/verify-build-matrix.sh
```

Use `python3 scripts/validate-dependency-artifacts.py --online` when you need to probe Maven/Fabric/Forge/NeoForge repositories directly. See `docs/COMPATIBILITY.md` for the full process.

### Fabric/Quilt lifecycle compatibility

Fabric and the Quilt-compatible artifact use explicit `SERVER_STARTED`, `END_SERVER_TICK`, `SERVER_STOPPING`, and `SERVER_STOPPED` events. Startup initializes the selected profile adapter and managed gamerule state. Shutdown restores only values still owned by the mod, and it does so during `SERVER_STOPPING` so the restored gamerule is still written to disk; `SERVER_STOPPED` only clears runtime caches.
