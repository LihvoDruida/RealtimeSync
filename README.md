# RealtimeSync — Minecraft 26.1.x branch

This branch is dedicated to Minecraft `26.1.x` only.

Supported profiles:

```text
26.1
26.1.1
26.1.2
```

Active branch target: `mc-26.1.x`.

## Loader support

Every active profile builds separate jars for:

```text
Fabric
Quilt-compatible
Forge
NeoForge
```

The CI matrix is generated from `config/build-compatibility.lock.json`, so profile files and the lock file must stay in sync.

## Required Java

Minecraft `26.1.x` profiles use Java `25`.

Local check:

```bash
java -version
```

## Build commands

Build all enabled loaders for the default profile:

```bash
./gradlew clean buildAllLoaders
```

Build all loaders for Minecraft `26.1.2`:

```bash
./gradlew -PmcProfile=26.1.2 clean buildAllLoaders
```

Build one loader:

```bash
./gradlew -PmcProfile=26.1.2 clean buildFabric
./gradlew -PmcProfile=26.1.2 clean buildQuilt
./gradlew -PmcProfile=26.1.2 clean buildForge
./gradlew -PmcProfile=26.1.2 clean buildNeoForge
```

For Windows PowerShell:

```powershell
.\gradlew.bat -PmcProfile=26.1.2 -PtargetLoader=neoforge buildNeoForge --no-daemon
```

## Build profile example

```properties
minecraft_version=26.1.2
minecraft_compat_label=26.1.2
minecraft_version_range_fabric=>=26.1.2 <26.1.3
minecraft_version_range_mods_toml=[26.1.2,26.1.3)
java_version=25
curseforge_java_versions=Java 25

fabric_loader_version=0.19.2
loader_version=0.19.2
fabric_version=0.145.4+26.1.2

forge_version=64.0.8
forge_loader_version=[64,)

neoforge_version=26.1.2.48-beta
neoforge_loader_version=[1,)
neoforge_version_range=[26.1.2,)
```

## Fabric and Quilt notes

Minecraft `26.1.x` is handled as an official-namespace target. Fabric and Quilt-compatible builds use:

```gradle
apply plugin: 'net.fabricmc.fabric-loom'
implementation "net.fabricmc:fabric-loader:${project.loader_version}"
implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
```

Do not add remap-only mappings or `modImplementation` to this branch.

`fabric.mod.json` keeps the Fabric API dependency strict:

```json
"fabric-api": ">=${fabric_version}"
```

This prevents users from loading the mod with a missing or too old Fabric API and getting a vague entrypoint crash.

## NeoForge notes

NeoForge metadata must keep JavaFML and NeoForge runtime ranges separate:

```toml
modLoader="javafml"
loaderVersion="[1,)"
```

The NeoForge runtime dependency is stored separately:

```toml
[[dependencies.realtime]]
modId="neoforge"
versionRange="${neoforge_version_range}"
```

Do not put `26.1.x` into `loaderVersion`. That recreates the `needs language provider javafml@...` loading error.

## Runtime compatibility policy

Common runtime code avoids direct calls to APIs that are fragile across loaders:

- daylight gamerules are resolved through reflection;
- dimension IDs are normalized through the common helper;
- day-time reads and writes go through `RealtimeWorldTime`;
- static runtime state is reset on server stop;
- Fabric/Quilt initialization stays on the server tick path with lifecycle cleanup.

## Validation

Run the full static validation suite:

```bash
bash scripts/verify-build-matrix.sh
```

Individual checks:

```bash
python3 scripts/validate-build-profiles.py
python3 scripts/validate-entrypoints.py
python3 scripts/validate-dependency-artifacts.py
python3 scripts/generate-ci-matrix.py
python3 scripts/check-fabric-loom-26.py
```

The online Maven probe is optional:

```bash
python3 scripts/validate-dependency-artifacts.py --online
```
