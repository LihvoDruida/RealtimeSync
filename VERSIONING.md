# Versioning — Minecraft 26.1.x

This branch is dedicated to Minecraft `26.1.x` only.

Supported profiles:

```text
26.1
26.1.1
26.1.2
```

## Release naming

Recommended artifact shape:

```text
realtime-sync-<loader>-<minecraft_version>-<mod_version>.jar
```

Examples:

```text
realtime-sync-fabric-26.1.2-1.4.0.jar
realtime-sync-neoforge-26.1.2-1.4.0.jar
```

## Build examples

```bash
./gradlew -PmcProfile=26.1.2 clean buildAllLoaders
./gradlew -PmcProfile=26.1.2 clean buildFabric
./gradlew -PmcProfile=26.1.2 clean buildQuilt
./gradlew -PmcProfile=26.1.2 clean buildForge
./gradlew -PmcProfile=26.1.2 clean buildNeoForge
```

## Profile rules

Every active profile must define exact versions for:

```properties
fabric_loader_version=
loader_version=
fabric_version=
forge_version=
forge_loader_version=
neoforge_version=
neoforge_loader_version=[1,)
neoforge_version_range=
```

Wildcards are forbidden. Runtime loader metadata must match the selected loader and must not include foreign loader metadata.

## CI

`.github/workflows/package.yml` builds the matrix from `config/build-compatibility.lock.json` on the `mc-26.1.x` branch and on `v*` tags.
