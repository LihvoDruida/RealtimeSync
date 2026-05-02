# Minecraft version build profiles

RealtimeSync must not publish one jar as compatible with every Minecraft version. Minecraft modding APIs change often enough that the safe release model is one build profile per Minecraft version.

## Build a single profile

```bash
./gradlew -PmcProfile=1.21.5 clean buildAllLoaders
./gradlew -PmcProfile=1.21.10 clean buildAllLoaders
```

The selected profile is read from `buildProfiles/<version>.properties` and controls. The root build uses Fabric Loom 1.11.x because Fabric recommends Loom 1.11 for Minecraft 1.21.9/1.21.10 builds.

It controls:

- `minecraft_version`
- Fabric Loader / Fabric API / Yarn mappings
- Forge version and loader range
- NeoForge version and loader range
- Java toolchain version
- Fabric `fabric.mod.json` Minecraft range
- Forge/NeoForge `mods.toml` Minecraft range

## Build all configured profiles locally

```bash
bash scripts/build-all-profiles.sh
```

## Adding another Minecraft version

1. Copy an existing file from `buildProfiles/`.
2. Update every dependency version for that Minecraft release.
3. Run the local build for that one profile.
4. Add the profile to the GitHub Actions matrix only after the build passes.
5. Publish that profile as its own CurseForge/GitHub file.

Do not widen `minecraft_version_range_fabric` or `minecraft_version_range_mods_toml` unless the jar was actually compiled and tested against the whole range.

## About Minecraft 26.1+

Minecraft 26.1+ is not just another minor 1.21.x target. It requires Java 25 and newer Fabric tooling. Fabric also requires porting from Yarn mappings to the Mojang/unobfuscated mapping setup for 26.1+. The file `buildProfiles/26.1.2.properties.example` is included only as a dependency reference, not as an enabled release profile.
