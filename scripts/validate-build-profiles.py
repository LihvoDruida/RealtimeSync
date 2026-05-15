#!/usr/bin/env python3
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROFILES_DIR = ROOT / "buildProfiles"
LOCK_PATH = ROOT / "config/build-compatibility.lock.json"


def fail(message: str) -> None:
    print(f"ERROR: {message}", flush=True)
    raise SystemExit(1)


def read_properties(path: Path) -> dict[str, str]:
    props: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"{path}: invalid property line: {raw}")
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def require(props: dict[str, str], path: Path, key: str) -> str:
    if key not in props or props[key] == "":
        fail(f"{path}: missing required property {key}")
    return props[key]


def main() -> int:
    if not LOCK_PATH.is_file():
        fail(f"Missing compatibility lock: {LOCK_PATH}")

    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    if lock.get("schema") != 1:
        fail("config/build-compatibility.lock.json must use schema=1")

    loaders = lock.get("loaders")
    if loaders != ["fabric", "quilt", "forge", "neoforge"]:
        fail("Compatibility lock must define loaders in order: fabric, quilt, forge, neoforge")

    expected_profiles = lock.get("profileOrder")
    if not expected_profiles:
        fail("Compatibility lock profileOrder is empty")

    profile_files = sorted(p.name for p in PROFILES_DIR.glob("*.properties") if not p.name.endswith(".example"))
    expected_files = sorted(f"{profile}.properties" for profile in expected_profiles)
    if profile_files != expected_files:
        fail(f"buildProfiles set mismatch. expected={expected_files}, actual={profile_files}")

    workflow = (ROOT / ".github/workflows/package.yml").read_text(encoding="utf-8")
    if "generate-ci-matrix.py --github-output" not in workflow:
        fail("Workflow must generate matrix from config/build-compatibility.lock.json")

    for profile in expected_profiles:
        path = PROFILES_DIR / f"{profile}.properties"
        props = read_properties(path)
        locked = lock["profiles"].get(profile)
        if not locked:
            fail(f"Compatibility lock missing profile {profile}")

        minecraft_version = require(props, path, "minecraft_version")
        if minecraft_version != profile or locked.get("minecraftVersion") != profile:
            fail(f"{path}: minecraft_version and lock minecraftVersion must equal profile name {profile}")

        next_profile = locked.get("nextMinecraftVersion")
        if not next_profile:
            fail(f"Compatibility lock {profile}: missing nextMinecraftVersion")
        if require(props, path, "minecraft_compat_label") != profile:
            fail(f"{path}: minecraft_compat_label must equal {profile}")
        if require(props, path, "minecraft_version_range_fabric") != f">={profile} <{next_profile}":
            fail(f"{path}: wrong Fabric Minecraft range")
        if require(props, path, "minecraft_version_range_mods_toml") != f"[{profile},{next_profile})":
            fail(f"{path}: wrong mods.toml Minecraft range")

        expected_java = str(locked.get("javaVersion"))
        if require(props, path, "java_version") != expected_java:
            fail(f"{path}: java_version must be {expected_java}")
        if require(props, path, "curseforge_java_versions") != f"Java {expected_java}":
            fail(f"{path}: curseforge_java_versions must be Java {expected_java}")

        if profile != "1.21" and not profile.startswith("1.21."):
            fail(f"Compatibility lock {profile}: mc-1.21.x branch may only contain Minecraft 1.21.x profiles")

        expected_mapping_mode = locked.get("fabricMappings")
        if expected_mapping_mode != "official-mojang-with-loom-remap":
            fail(f"Compatibility lock {profile}: 1.21.x Fabric/Quilt builds must use official Mojang mappings through Loom Remap")

        if require(props, path, "fabric_loader_version") != locked.get("fabricLoader"):
            fail(f"{path}: fabric_loader_version disagrees with compatibility lock")
        if require(props, path, "loader_version") != locked.get("fabricLoader"):
            fail(f"{path}: loader_version disagrees with compatibility lock")
        if require(props, path, "fabric_version") != locked.get("fabricApi"):
            fail(f"{path}: fabric_version disagrees with compatibility lock")
        if props["fabric_version"].endswith("+") or props["fabric_version"] in {"0.+", "+"} or "+" not in props["fabric_version"]:
            fail(f"{path}: Fabric API must be exact and include the +minecraft suffix")

        for loader in loaders:
            value = require(props, path, f"enable_{loader}")
            if value not in {"true", "false"}:
                fail(f"{path}: enable_{loader} must be true or false")
            supported = bool(locked["loaders"][loader].get("supported"))
            if (value == "true") != supported:
                fail(f"{path}: enable_{loader}={value} disagrees with compatibility lock supported={supported}")

        forge = locked["loaders"]["forge"]
        if require(props, path, "forge_version") != forge.get("version"):
            fail(f"{path}: forge_version disagrees with compatibility lock")
        if require(props, path, "forge_loader_version") != forge.get("loaderRange"):
            fail(f"{path}: forge_loader_version disagrees with compatibility lock")
        if props["enable_forge"] == "true" and props["forge_version"] == "unsupported":
            fail(f"{path}: supported Forge profile cannot use forge_version=unsupported")
        if props["enable_forge"] == "false" and props["forge_version"] != "unsupported":
            fail(f"{path}: disabled Forge profile should use forge_version=unsupported to avoid accidental resolution")

        neoforge = locked["loaders"]["neoforge"]
        if require(props, path, "neoforge_version") != neoforge.get("version"):
            fail(f"{path}: neoforge_version disagrees with compatibility lock")
        if re.search(r"[.+]$", props["neoforge_version"]):
            fail(f"{path}: neoforge_version must be exact, not a wildcard/range")
        neoforge_loader_range = require(props, path, "neoforge_loader_version")
        if neoforge_loader_range != neoforge.get("loaderRange"):
            fail(f"{path}: neoforge_loader_version disagrees with compatibility lock")
        if neoforge_loader_range != "[1,)":
            fail(f"{path}: NeoForge modLoader=javafml loaderVersion must describe the javafml language loader range [1,), not the NeoForge runtime line")
        expected_neoforge_range = neoforge.get("versionRange")
        actual_neoforge_range = require(props, path, "neoforge_version_range")
        if not expected_neoforge_range or actual_neoforge_range != expected_neoforge_range:
            fail(f"{path}: neoforge_version_range disagrees with compatibility lock")
        if not actual_neoforge_range.startswith("[21."):
            fail(f"{path}: neoforge_version_range must describe the NeoForge runtime line, for example [21.5,)")

        guards = locked.get("compatibilityGuards") or {}
        for key in ("timeAccess", "gamerules", "dimensions", "serverTicks"):
            if key not in guards:
                fail(f"Compatibility lock {profile}: missing guard {key}")

    fallback = read_properties(ROOT / "gradle.properties")
    baseline = lock["profiles"].get("1.21.5")
    if fallback.get("fabric_version") != baseline.get("fabricApi"):
        fail("gradle.properties fallback fabric_version must mirror buildProfiles/1.21.5.properties")
    if fallback.get("neoforge_version") != baseline["loaders"]["neoforge"].get("version"):
        fail("gradle.properties fallback neoforge_version must mirror buildProfiles/1.21.5.properties")
    if fallback.get("neoforge_loader_version") != baseline["loaders"]["neoforge"].get("loaderRange"):
        fail("gradle.properties fallback neoforge_loader_version must mirror buildProfiles/1.21.5.properties")
    if fallback.get("neoforge_version_range") != baseline["loaders"]["neoforge"].get("versionRange"):
        fail("gradle.properties fallback neoforge_version_range must mirror buildProfiles/1.21.5.properties")

    fabric_mod_json = (ROOT / "fabric/src/main/resources/fabric.mod.json").read_text(encoding="utf-8")
    if '"fabric-api": ">=${fabric_version}"' not in fabric_mod_json:
        fail("fabric.mod.json must declare Fabric API as a minimum runtime dependency using >=${fabric_version}")

    for gradle_file in (ROOT / "fabric/build.gradle", ROOT / "quilt/build.gradle"):
        content = gradle_file.read_text(encoding="utf-8")
        if "inputs.property 'fabric_version', project.fabric_version" not in content:
            fail(f"{gradle_file}: processResources must track fabric_version")
        if "fabric_version: project.fabric_version" not in content:
            fail(f"{gradle_file}: processResources must expand fabric_version into fabric.mod.json")

    neoforge_toml = (ROOT / "neoforge/src/main/resources/META-INF/neoforge.mods.toml").read_text(encoding="utf-8")
    if 'loaderVersion="[1,)"' not in neoforge_toml:
        fail("neoforge.mods.toml must hard-code javafml loaderVersion=[1,) so NeoForge runtime ranges cannot leak into language-provider checks")
    if 'loaderVersion="${' in neoforge_toml:
        fail("neoforge.mods.toml must not expand loaderVersion from Gradle properties")
    if 'versionRange="${neoforge_version_range}"' not in neoforge_toml:
        fail("neoforge.mods.toml must use neoforge_version_range for the NeoForge runtime dependency")

    neoforge_gradle = (ROOT / "neoforge/build.gradle").read_text(encoding="utf-8")
    if "neoforgeDependencyVersionRange" in neoforge_gradle or ": project.neoforge_loader_version" in neoforge_gradle:
        fail("neoforge/build.gradle must not fall back from neoforge_version_range to neoforge_loader_version")

    print("Build profile dependency validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
