# Compatibility and dependency control

RealtimeSync does not rely on loose dependency versions for release builds. Every supported Minecraft version and loader combination is controlled by:

- `config/build-compatibility.lock.json` — the source of truth for supported build combinations and pinned dependency coordinates.
- `buildProfiles/*.properties` — Gradle-facing profile files mirrored from the compatibility lock.
- `scripts/validate-build-profiles.py` — verifies every profile against the lock.
- `scripts/generate-ci-matrix.py` — generates the GitHub Actions matrix only from supported combinations.
- `scripts/validate-dependency-artifacts.py` — validates dependency artifact coordinates offline, with optional online Maven probing.

## Supported policy

A loader/version pair is built only when both are true:

1. `config/build-compatibility.lock.json` has `supported=true` for the pair.
2. The matching `buildProfiles/<version>.properties` has `enable_<loader>=true`.

If a pair is known to be unavailable or unstable, keep it disabled in both places. For example, Forge for Minecraft `1.21.2` stays disabled because there is no real supported Forge artifact for that exact Minecraft version.

## Updating dependencies

Do not edit only one profile by hand and assume CI will catch everything later. Use this checklist:

1. Update the exact version in `buildProfiles/<version>.properties`.
2. Update the matching entry in `config/build-compatibility.lock.json`.
3. Run:

```bash
python3 scripts/validate-build-profiles.py
python3 scripts/validate-dependency-artifacts.py
bash scripts/verify-build-matrix.sh
```

4. For a real dependency availability check, run:

```bash
python3 scripts/validate-dependency-artifacts.py --online
```

The online probe checks Maven/Fabric/Forge/NeoForge artifact URLs. It is intentionally optional in normal tag builds so temporary repository/network outages do not block already-known-good releases.

## Runtime compatibility guards

The mod avoids direct APIs that moved between `1.21.x` and `26.1.x`:

- World time uses `RealtimeWorldTime` reflection instead of direct `ServerLevel#getDayTime()` / `setDayTime(...)`.
- Daylight cycle gamerules use `RealtimeGameRules` reflection instead of `/gamerule` commands.
- Dimension IDs use normalized reflective keys instead of `ResourceKey#location()`.
- NeoForge per-level tick events are de-duplicated through reflective server tick count handling.

These guards are enforced by `scripts/verify-build-matrix.sh`.
## Minecraft 26.1.x Fabric/Quilt builds

Minecraft 26.1.x is treated differently from 1.21.x for Fabric-compatible builds.
Fabric Loader and Fabric API exist for 26.1.x, but the build script must not request `loom.officialMojangMappings()` for those profiles.
The 26.1.x game jars are already in the official namespace, so the Fabric and Quilt modules switch to `net.fabricmc.fabric-loom`, remove the `mappings` dependency, and use standard `implementation` dependencies.

For 1.21.x, the modules still use `net.fabricmc.fabric-loom-remap` with explicit `loom.officialMojangMappings()`.


### Fabric/Quilt lifecycle compatibility

Fabric/Quilt entrypoints intentionally use only `ServerTickEvents.END_SERVER_TICK`.
Do not reintroduce `ServerWorldEvents.LOAD`: Fabric API `26.1.x` does not expose
that class in `net.fabricmc.fabric.api.event.lifecycle.v1`, so using it breaks the
`26.1`, `26.1.1`, and `26.1.2` Fabric/Quilt builds. The common controller performs
initial daylight-cycle guarding and the first sync from the server tick path instead.

### Minecraft 26.1.x time compatibility

26.1.x moves vanilla time control to World Clocks. RealtimeSync therefore uses direct accessors only when they exist and falls back to server commands for the clock API. The fallback commands are intentionally limited to `/time` world-clock commands and do not use gamerule commands.
