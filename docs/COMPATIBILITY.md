# Compatibility policy

## Supported policy

This branch supports only Minecraft `1.21.x` profiles:

```txt
1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5,
1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11
```

The `mc-1.21.x` branch must not contain active `26.x` build profiles, dependency lock entries, or CI matrix entries.

## Updating dependencies

When changing a loader dependency:

1. Update `buildProfiles/<minecraft-version>.properties`.
2. Update `config/build-compatibility.lock.json`.
3. Run the local validation commands:

```bash
python3 scripts/validate-build-profiles.py
python3 scripts/validate-dependency-artifacts.py
bash scripts/verify-build-matrix.sh
```

Use the online Maven probe only when you need to verify remote artifacts:

```bash
python3 scripts/validate-dependency-artifacts.py --online
```

## Fabric and Quilt builds

Fabric and Quilt use `net.fabricmc.fabric-loom-remap` with `loom.officialMojangMappings()` for every active `1.21.x` profile.

Rules:

- keep Fabric Loader and Fabric API pinned exactly;
- keep the Fabric API suffix matched to the selected Minecraft version;
- use `modImplementation` for Fabric Loader and Fabric API;
- do not reintroduce a `26.x` Loom branch in this repository branch.

## Runtime compatibility guards

The mod avoids direct APIs that moved or changed across the `1.21.x` profile range:

- daylight gamerules are resolved reflectively instead of importing `GameRules` directly;
- day-time reads and writes go through `RealtimeWorldTime`;
- dimension IDs are normalized through reflection;
- Fabric/Quilt entrypoints use `ServerTickEvents.END_SERVER_TICK` and let the common controller perform first-tick initialization.

## Forge and NeoForge notes

Forge `1.21.2` is disabled because no matching normal Forge artifact is available for that profile. Forge `1.21.10` is pinned to `60.1.0` because newer artifacts can fail during ForgeGradle Mavenizer resolution on GitHub-hosted runners.

NeoForge versions are pinned exactly. Do not use wildcard versions such as `21.x.+` with ModDev/NeoForm because they can resolve as literal userdev artifact coordinates in this setup.
