# Versioning and build profiles

## Covered profiles

This branch is dedicated to Minecraft `1.21.x` only:

```txt
1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5,
1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11
```

Do not add `26.x` profiles to `mc-1.21.x`. Keep them in a separate branch if they are needed later.

## Recommended Windows build commands

Build all enabled loaders for Minecraft 1.21.11:

```powershell
.\scripts\build.ps1 -Profile 1.21.11 -Loader all
```

Build one loader:

```powershell
.\scripts\build.ps1 -Profile 1.21.11 -Loader fabric
.\scripts\build.ps1 -Profile 1.21.11 -Loader quilt
.\scripts\build.ps1 -Profile 1.21.11 -Loader forge
.\scripts\build.ps1 -Profile 1.21.11 -Loader neoforge
```

Equivalent direct Gradle invocations:

```powershell
.\gradlew.bat "-PmcProfile=1.21.11" "-PtargetLoader=fabric" clean buildFabric
.\gradlew.bat "-PmcProfile=1.21.11" "-PtargetLoader=quilt" clean buildQuilt
.\gradlew.bat "-PmcProfile=1.21.11" "-PtargetLoader=forge" clean buildForge
.\gradlew.bat "-PmcProfile=1.21.11" "-PtargetLoader=neoforge" clean buildNeoForge
.\gradlew.bat "-PmcProfile=1.21.5" "-PtargetLoader=all" clean buildAllLoaders
```

`targetLoader` isolates the relevant subproject. The settings script also infers it from a single `buildFabric`, `buildQuilt`, `buildForge`, or `buildNeoForge` task, but explicit selection is required in CI and recommended in local automation.

## Linux/macOS build commands

```bash
./scripts/build.sh 1.21.11 fabric
./scripts/build.sh 1.21.11 quilt
./scripts/build.sh 1.21.11 forge
./scripts/build.sh 1.21.11 neoforge
./scripts/build.sh 1.21.5 all
```

## Build all profiles locally

```bash
./scripts/build-all-profiles.sh
```

Windows:

```powershell
.\scripts\build-all-profiles.ps1
```

## Build only selected profiles

```bash
./scripts/build-all-profiles.sh 1.21.5 1.21.10 1.21.11
./scripts/build-all-profiles.sh --loader neoforge 1.21.10 1.21.11
```

```powershell
.\scripts\build-all-profiles.ps1 -Loader neoforge -Profile 1.21.10,1.21.11
```

## Diagnose profile resolution

```powershell
.\gradlew.bat "-PmcProfile=1.21.11" "-PtargetLoader=none" printBuildProfile
```

The output includes `mcProfileSource` and `targetLoader`. A malformed or truncated value such as `1` is rejected before any loader project is configured.

## Profile keys

Each `buildProfiles/<minecraft-version>.properties` file pins one exact Minecraft version and its loader dependencies:

```properties
minecraft_version=1.21.11
minecraft_compat_label=1.21.11
minecraft_version_range_fabric=>=1.21.11 <1.21.12
minecraft_version_range_mods_toml=[1.21.11,1.21.12)
java_version=21
curseforge_java_versions=Java 21

enable_fabric=true
enable_quilt=true
enable_forge=true
enable_neoforge=true

fabric_loader_version=0.18.4
loader_version=0.18.4
fabric_version=0.141.3+1.21.11

forge_version=61.1.5
forge_loader_version=[61,)

neoforge_version=21.11.0-beta
neoforge_loader_version=[1,)
```

## Fabric API resolution

Fabric API must be exact and must include the selected Minecraft suffix, for example `0.141.3+1.21.11`. Wildcards such as `0.+` are forbidden because they make releases non-reproducible and can silently resolve an API for another Minecraft version.

## Mappings

Fabric and Quilt builds on this branch always use `net.fabricmc.fabric-loom-remap` with `loom.officialMojangMappings()`. There is no `26.x` unobfuscated branch in `mc-1.21.x`.

## Forge 1.21.2

Forge is intentionally disabled for `1.21.2` because the normal Forge artifact line does not provide a matching official Forge build for that Minecraft version. Fabric, Quilt-compatible and NeoForge remain enabled.

## Build tool compatibility

- Java: 21 for every active profile.
- Gradle wrapper: 9.4.0.
- Fabric Loom Remap: 1.15.5.
- ForgeGradle: 7.0.25.
- NeoForge ModDev: 2.0.141.

Do not downgrade the wrapper while this branch supports the newer `1.21.10` / `1.21.11` loader toolchains.

## CI matrix and artifact validation

`.github/workflows/package.yml` generates the matrix from `config/build-compatibility.lock.json`. The workflow runs only when a tag matching `v*` is pushed. Branch pushes and manual `workflow_dispatch` are intentionally disabled, so every produced artifact has a real release version.

Run the same validation locally before pushing:

```bash
python3 scripts/validate-build-profiles.py
python3 scripts/validate-dependency-artifacts.py
bash scripts/verify-build-matrix.sh
```

Use this optional online probe when dependency coordinates change:

```bash
python3 scripts/validate-dependency-artifacts.py --online
```

## CurseForge publish guardrail

CurseForge publishing uses the exact Minecraft version from the selected profile and `game-version-filter: none`, so CI does not depend on Mojang manifest filtering during publication.

## Runtime guardrails

- `dayTime` remains absolute; writing `gameTime` or reducing values modulo 24000 is forbidden.
- Minecraft 1.21–1.21.10 and 1.21.11 compile different GameRules adapters.
- Minecraft 26.x `time of` commands are forbidden on this branch.
- Fabric/Quilt-compatible, Forge, and NeoForge all use official server lifecycle/tick events.
- Source archives are rejected when they contain generated build directories, compiled classes, non-wrapper jars, cache files, or reports.

Run `bash scripts/verify-build-matrix.sh` before release. It enforces these invariants in addition to dependency/profile checks.

## Compatibility lock and supported matrix

`config/build-compatibility.lock.json` is the source of truth for supported profiles and loader combinations. When adding or changing a `1.21.x` profile, update both the profile file and the lock file in the same commit.
