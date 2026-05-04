# Minecraft version build profiles

RealtimeSync publishes **one jar per Minecraft version and per loader**. Do not mark one artifact as compatible with the whole 1.21 range unless it was compiled and tested against every version in that range.

## Covered profiles

The repository currently contains profiles for:

```txt
1.21, 1.21.1, 1.21.2, 1.21.3, 1.21.4, 1.21.5,
1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10, 1.21.11
```

Every profile lives in:

```txt
buildProfiles/<minecraft-version>.properties
```

## Build a single profile

```bash
./gradlew -PmcProfile=1.21.11 clean buildAllLoaders
```

## Build all profiles locally

```bash
./scripts/build-all-profiles.sh
```

## Build only selected profiles

```bash
./scripts/build-all-profiles.sh 1.21.5 1.21.10 1.21.11
```

## Build one loader

```bash
./gradlew -PmcProfile=1.21.11 clean buildFabric
./gradlew -PmcProfile=1.21.11 clean buildQuilt
./gradlew -PmcProfile=1.21.11 clean buildForge
./gradlew -PmcProfile=1.21.11 clean buildNeoForge
```

## Profile keys

Each profile controls:

- `minecraft_version`
- `minecraft_compat_label`
- Fabric / Quilt-compatible Minecraft range
- Forge / NeoForge Minecraft range
- Java toolchain version
- enabled loader switches: `enable_fabric`, `enable_quilt`, `enable_forge`, `enable_neoforge`
- Fabric Loader version
- Fabric API dynamic resolver version
- Forge version and loader range
- NeoForge version and loader range

## Fabric API resolution

Fabric API uses:

```properties
fabric_version=0.+
```

The Gradle build rejects Fabric API candidates that do not end with the selected Minecraft version suffix. For example, the `1.21.11` profile only accepts Fabric API versions ending in:

```txt
+1.21.11
```

This avoids accidentally compiling a `1.21.11` Fabric build against a `26.x` or older `1.21.x` Fabric API artifact.

## Mappings

Fabric and Quilt-compatible builds use Mojang official mappings through Loom:

```gradle
mappings loom.officialMojangMappings()
```

That keeps Fabric source names close to Forge/NeoForge source names and removes the need to maintain a Yarn version for every Minecraft profile.

## Quilt artifact

The Quilt jar is a separate artifact name built from the Fabric-compatible source path. It is intended for Quilt setups that can load Fabric API-compatible mods. It is not a native QSL-only rewrite.

## Forge 1.21.2

`buildProfiles/1.21.2.properties` has:

```properties
enable_forge=false
```

Forge 1.21.2 is disabled because the normal Forge 1.21.x release line does not provide a matching official Forge artifact for Minecraft `1.21.2`. NeoForge 21.2 exists for Minecraft `1.21.2`, so the NeoForge build remains enabled.

## Adding another Minecraft version

1. Copy the closest profile in `buildProfiles/`.
2. Update `minecraft_version`, ranges and loader versions.
3. Keep ranges exact: `[current,next)` and `>=current <next`.
4. Run the local build for that one profile.
5. Add the profile to the GitHub Actions matrix only after it builds.
6. Publish each loader jar separately.

## About Minecraft 26.x+

A `26.1.2.properties.example` file is included only as a starting point for the new version format. It is not enabled in the release matrix. Enable it only after checking current loader support and compiling all enabled targets.
