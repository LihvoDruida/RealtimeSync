# Compatibility — Minecraft 26.1.x

This branch supports only Minecraft `26.1.x`:

```text
26.1
26.1.1
26.1.2
```

Branch target: `mc-26.1.x`.

## Java

All active profiles use Java `25`.

## Shared Minecraft runtime ranges

Minecraft dependency ranges are derived for every loader from `minecraft_runtime_min_version`, `minecraft_runtime_max_version` and `minecraft_accept_beta=true`:

```text
26.1   -> Fabric/Quilt >=26.1-0-beta <26.1.1     -> Forge/NeoForge [26.1-0-beta,26.1.1)
26.1.1 -> Fabric/Quilt >=26.1.1-0-beta <26.1.2   -> Forge/NeoForge [26.1.1-0-beta,26.1.2)
26.1.2 -> Fabric/Quilt >=26.1.2-0-beta <26.1.3   -> Forge/NeoForge [26.1.2-0-beta,26.1.3)
```

This keeps Fabric, Quilt, Forge and NeoForge metadata compatible with beta runtime builds without opening the jar to the next Minecraft profile.

## Fabric / Quilt-compatible

Fabric and Quilt-compatible artifacts use Fabric Loom without remap mappings:

```gradle
apply plugin: 'net.fabricmc.fabric-loom'
implementation "net.fabricmc:fabric-loader:${project.loader_version}"
implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"
```

Each profile pins an exact Fabric API artifact. The runtime metadata declares Fabric API as `>=${fabric_version}`.

## Forge

Forge profiles are enabled for all active versions:

```text
26.1   -> Forge 62.0.9
26.1.1 -> Forge 63.0.2
26.1.2 -> Forge 64.0.8
```

Forge builds use ForgeGradle 7 and `minecraft.dependency(...)`.

## NeoForge

NeoForge profiles are enabled for all active versions. `loaderVersion` stays hard-coded to the JavaFML language loader range:

```toml
loaderVersion="[1,)"
```

NeoForge runtime compatibility is derived from `neoforge_runtime_min_version` plus `neoforge_accept_beta=true`:

```text
26.1   -> min 26.1   -> [26.1.0.0-beta,)
26.1.1 -> min 26.1.1 -> [26.1.1.0-beta,)
26.1.2 -> min 26.1.2 -> [26.1.2.0-beta,)
```

That dotted lower bound accepts the current `*.0-beta` / `*.NN-beta` NeoForge runtimes and keeps the same jar compatible with later stable NeoForge runtimes in the same Minecraft line.


## Release trigger

The `mc-26.1.x` GitHub Actions workflow is release-only. It runs only when a tag matching `v*` is pushed. Branch pushes and manual workflow dispatch are intentionally disabled so every produced artifact has a real release version.

## Guardrails

`bash scripts/verify-build-matrix.sh` checks:

- profile and compatibility lock consistency;
- Fabric/Quilt official-namespace build shape;
- ForgeGradle 7 DSL usage;
- NeoForge JavaFML metadata split;
- loader metadata boundaries;
- CI matrix generation;
- common runtime reflective access rules.
