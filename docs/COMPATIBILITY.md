# Compatibility policy

## Supported branch

This branch contains only Minecraft `1.21.x` profiles:

```txt
1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5,
1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11
```

Minecraft 26.x APIs and build profiles must remain on a separate branch.

## Profile-specific runtime API

The branch does not guess GameRules methods through a broad reflection chain:

- `common/src/mc121legacy` compiles the legacy `GameRules.RULE_DAYLIGHT` adapter for 1.21–1.21.10;
- `common/src/mc12111` compiles the registry-backed `GameRules.ADVANCE_TIME` adapter for 1.21.11;
- absolute time reads/writes use direct `ServerLevel.getDayTime()` and `ServerLevel.setDayTime(long)`;
- `gameTime` is forbidden in synchronization code;
- ResourceKey identifiers use build-profile adapters: direct `location()` on 1.21–1.21.10 and direct `identifier()` on 1.21.11.

The build profile, not runtime guessing, selects the GameRules implementation.

## Loader lifecycle

- Fabric/Quilt-compatible: `SERVER_STARTED`, `END_SERVER_TICK`, `SERVER_STOPPED`.
- Forge: official post-server-tick lifecycle; no executor or polling thread.
- NeoForge: one post-server-tick event; no per-level tick deduplication.

Quilt is a separately named Fabric-compatible artifact. It is not described as a native Quilt implementation because there is no dedicated Quilt entrypoint/API integration.

## Updating dependencies

When changing a loader dependency:

1. Update `buildProfiles/<minecraft-version>.properties`.
2. Update `config/build-compatibility.lock.json`.
3. Run:

```bash
python3 scripts/validate-build-profiles.py
python3 scripts/validate-dependency-artifacts.py
bash scripts/verify-build-matrix.sh
```

Use the online repository probe only when coordinates change:

```bash
python3 scripts/validate-dependency-artifacts.py --online
```

Do not raise a minimum Loader/API version merely because a newer artifact exists. Every changed profile must compile and pass startup tests first.

## Forge and NeoForge notes

Forge `1.21.2` remains disabled because the compatibility lock has no supported normal Forge artifact for that exact Minecraft profile. NeoForge language-loader and runtime dependency ranges remain separate in metadata.

## Release policy

Release CI runs only for `v*` tags. Build failures prevent dependent release jobs. CurseForge publication uses a failing mode rather than `continue-on-error`, so incomplete mandatory releases are visible.
