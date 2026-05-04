#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROFILES_DIR = ROOT / "buildProfiles"

EXPECTED_PROFILES = [
    "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
    "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
    "26.1", "26.1.1", "26.1.2",
]

NEXT_PROFILE = {
    "1.21": "1.21.1",
    "1.21.1": "1.21.2",
    "1.21.2": "1.21.3",
    "1.21.3": "1.21.4",
    "1.21.4": "1.21.5",
    "1.21.5": "1.21.6",
    "1.21.6": "1.21.7",
    "1.21.7": "1.21.8",
    "1.21.8": "1.21.9",
    "1.21.9": "1.21.10",
    "1.21.10": "1.21.11",
    "1.21.11": "1.21.12",
    "26.1": "26.1.1",
    "26.1.1": "26.1.2",
    "26.1.2": "26.1.3",
}

EXPECTED_FABRIC_API = {
    "1.21": "0.102.0+1.21",
    "1.21.1": "0.116.6+1.21.1",
    "1.21.2": "0.106.1+1.21.2",
    "1.21.3": "0.108.0+1.21.3",
    "1.21.4": "0.119.4+1.21.4",
    "1.21.5": "0.128.2+1.21.5",
    "1.21.6": "0.128.2+1.21.6",
    "1.21.7": "0.128.2+1.21.7",
    "1.21.8": "0.130.0+1.21.8",
    "1.21.9": "0.134.1+1.21.9",
    "1.21.10": "0.138.4+1.21.10",
    "1.21.11": "0.141.3+1.21.11",
    "26.1": "0.145.1+26.1",
    "26.1.1": "0.145.4+26.1.1",
    "26.1.2": "0.148.0+26.1.2",
}

EXPECTED_FORGE = {
    "1.21": "51.0.33",
    "1.21.1": "52.1.14",
    "1.21.2": "unsupported",
    "1.21.3": "53.1.10",
    "1.21.4": "54.1.16",
    "1.21.5": "55.1.10",
    "1.21.6": "56.0.9",
    "1.21.7": "57.0.3",
    "1.21.8": "58.1.18",
    "1.21.9": "59.0.5",
    # 60.1.0 is intentionally pinned because 60.1.9 can fail in ForgeGradle Mavenizer on GitHub-hosted runners.
    "1.21.10": "60.1.0",
    "1.21.11": "61.1.5",
    "26.1": "62.0.9",
    "26.1.1": "63.0.2",
    "26.1.2": "64.0.7",
}

EXPECTED_NEOFORGE = {
    "1.21": "21.0.160",
    "1.21.1": "21.1.172",
    "1.21.2": "21.2.0-beta",
    "1.21.3": "21.3.87",
    "1.21.4": "21.4.147",
    "1.21.5": "21.5.95",
    "1.21.6": "21.6.16-beta",
    "1.21.7": "21.7.20-beta",
    "1.21.8": "21.8.39",
    "1.21.9": "21.9.9-beta",
    "1.21.10": "21.10.48-beta",
    "1.21.11": "21.11.0-beta",
    "26.1": "26.1.0.19-beta",
    "26.1.1": "26.1.1.15-beta",
    "26.1.2": "26.1.2.36-beta",
}

EXPECTED_LOADER = "0.18.4"


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
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


def version_major(profile: str) -> str:
    return profile.split(".", 1)[0]


def main() -> int:
    profile_files = sorted(p.name for p in PROFILES_DIR.glob("*.properties") if not p.name.endswith(".example"))
    expected_files = sorted(f"{profile}.properties" for profile in EXPECTED_PROFILES)
    if profile_files != expected_files:
        fail(f"buildProfiles set mismatch. expected={expected_files}, actual={profile_files}")

    workflow = (ROOT / ".github/workflows/package.yml").read_text(encoding="utf-8")

    for profile in EXPECTED_PROFILES:
        path = PROFILES_DIR / f"{profile}.properties"
        props = read_properties(path)
        next_profile = NEXT_PROFILE[profile]

        if require(props, path, "minecraft_version") != profile:
            fail(f"{path}: minecraft_version must equal profile name {profile}")
        if require(props, path, "minecraft_compat_label") != profile:
            fail(f"{path}: minecraft_compat_label must equal {profile}")
        if require(props, path, "minecraft_version_range_fabric") != f">={profile} <{next_profile}":
            fail(f"{path}: wrong Fabric Minecraft range")
        if require(props, path, "minecraft_version_range_mods_toml") != f"[{profile},{next_profile})":
            fail(f"{path}: wrong mods.toml Minecraft range")

        expected_java = "25" if profile.startswith("26.") else "21"
        if require(props, path, "java_version") != expected_java:
            fail(f"{path}: java_version must be {expected_java}")
        if require(props, path, "curseforge_java_versions") != f"Java {expected_java}":
            fail(f"{path}: curseforge_java_versions must be Java {expected_java}")

        for loader in ("fabric", "quilt", "forge", "neoforge"):
            value = require(props, path, f"enable_{loader}")
            if value not in {"true", "false"}:
                fail(f"{path}: enable_{loader} must be true or false")

        if profile == "1.21.2" and props["enable_forge"] != "false":
            fail("1.21.2 must keep Forge disabled; no official Forge artifact is available for that profile")
        if profile != "1.21.2" and props["enable_forge"] != "true":
            fail(f"{path}: Forge must be enabled for this profile")

        if require(props, path, "fabric_loader_version") != EXPECTED_LOADER:
            fail(f"{path}: fabric_loader_version must be {EXPECTED_LOADER}")
        if require(props, path, "loader_version") != EXPECTED_LOADER:
            fail(f"{path}: loader_version must be {EXPECTED_LOADER}")
        if require(props, path, "fabric_version") != EXPECTED_FABRIC_API[profile]:
            fail(f"{path}: fabric_version must be {EXPECTED_FABRIC_API[profile]}")
        if props["fabric_version"].endswith("+") or props["fabric_version"] in {"0.+", "+"} or "+" not in props["fabric_version"]:
            fail(f"{path}: Fabric API must be exact and include the +minecraft suffix")

        if require(props, path, "forge_version") != EXPECTED_FORGE[profile]:
            fail(f"{path}: forge_version must be {EXPECTED_FORGE[profile]}")
        if require(props, path, "neoforge_version") != EXPECTED_NEOFORGE[profile]:
            fail(f"{path}: neoforge_version must be {EXPECTED_NEOFORGE[profile]}")
        if re.search(r"[.+]$", props["neoforge_version"]):
            fail(f"{path}: neoforge_version must be exact, not a wildcard/range")

        if profile.startswith("26."):
            if require(props, path, "neoforge_loader_version") != "[1,)":
                fail(f"{path}: 26.x NeoForge must use javafml loader version [1,)")
            if require(props, path, "neoforge_version_range") != f"[{profile},)":
                fail(f"{path}: 26.x NeoForge dependency range must be [{profile},)")
        else:
            expected_loader_major = EXPECTED_NEOFORGE[profile].split(".", 2)[:2]
            if not require(props, path, "neoforge_loader_version").startswith(f"[{'.'.join(expected_loader_major)},"):
                fail(f"{path}: NeoForge loader range should track {'.'.join(expected_loader_major)}")

        if f"- '{profile}'" not in workflow:
            fail(f"Workflow matrix does not include profile {profile}")

    fallback = read_properties(ROOT / "gradle.properties")
    if fallback.get("fabric_version") != EXPECTED_FABRIC_API["1.21.5"]:
        fail("gradle.properties fallback fabric_version must mirror buildProfiles/1.21.5.properties")
    if fallback.get("neoforge_version") != EXPECTED_NEOFORGE["1.21.5"]:
        fail("gradle.properties fallback neoforge_version must mirror buildProfiles/1.21.5.properties")

    print("Build profile dependency validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
