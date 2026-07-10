#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLACEHOLDER_RE = re.compile(r"\$\{([A-Za-z0-9_]+)}")

BASE_KEYS = {"version", "minecraft_version", "minecraft_profile", "loader"}
LOADERS = {
    "fabric": {
        "loader_label": "fabric",
        "metadata": ROOT / "fabric/src/main/resources/fabric.mod.json",
        "property_sources": {
            "loader_version": "loader_version",
            "fabric_version": "fabric_version",
            "minecraft_version_range_fabric": "minecraft_version_range_fabric",
            "java_version": "java_version",
        },
    },
    "quilt": {
        "loader_label": "quilt-compatible",
        "metadata": ROOT / "fabric/src/main/resources/fabric.mod.json",
        "property_sources": {
            "loader_version": "loader_version",
            "fabric_version": "fabric_version",
            "minecraft_version_range_fabric": "minecraft_version_range_fabric",
            "java_version": "java_version",
        },
    },
    "forge": {
        "loader_label": "forge",
        "metadata": ROOT / "forge/src/main/resources/META-INF/mods.toml",
        "property_sources": {
            "forge_loader_version": "forge_loader_version",
            "minecraft_version_range": "minecraft_version_range_mods_toml",
        },
    },
    "neoforge": {
        "loader_label": "neoforge",
        "metadata": ROOT / "neoforge/src/main/resources/META-INF/neoforge.mods.toml",
        "property_sources": {
            "neoforge_version_range": "neoforge_version_range",
            "minecraft_version_range": "minecraft_version_range_mods_toml",
        },
    },
}


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def read_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            fail(f"{path.relative_to(ROOT)} contains an invalid property line: {raw!r}")
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def template_text(path: Path) -> str:
    if not path.is_file():
        fail(f"Missing resource template: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def placeholders(path: Path) -> set[str]:
    return set(PLACEHOLDER_RE.findall(template_text(path)))


def expand_for_validation(path: Path, values: dict[str, str], target: str) -> None:
    text = template_text(path)

    def replace(match: re.Match[str]) -> str:
        key = match.group(1)
        if key not in values:
            fail(f"{target}: missing value for ${{{key}}} used by {path.relative_to(ROOT)}")
        return values[key]

    expanded = PLACEHOLDER_RE.sub(replace, text)
    remaining = PLACEHOLDER_RE.findall(expanded)
    if remaining:
        fail(f"{target}: unexpanded placeholders remain in {path.relative_to(ROOT)}: {sorted(set(remaining))}")


def profile_sort_key(path: Path) -> tuple[int, ...]:
    return tuple(int(part) for part in path.stem.split("."))


def main() -> int:
    root_gradle = (ROOT / "build.gradle").read_text(encoding="utf-8")
    for marker in (
        "ext.resourceExpansionValues",
        "values.put('version'",
        "values.put('minecraft_version'",
        "values.put('minecraft_profile'",
        "values.put('loader'",
    ):
        if marker not in root_gradle:
            fail(f"build.gradle is missing canonical resource expansion marker: {marker}")

    common_template = ROOT / "common/src/main/resources/realtime-build.properties"
    common_keys = placeholders(common_template)
    missing_base = common_keys - BASE_KEYS
    if missing_base:
        fail(
            f"{common_template.relative_to(ROOT)} uses placeholders not supplied by the canonical base map: "
            f"{sorted(missing_base)}"
        )

    for loader, config in LOADERS.items():
        gradle_path = ROOT / loader / "build.gradle"
        gradle_text = gradle_path.read_text(encoding="utf-8")
        expected_call = f"resourceExpansionValues('{config['loader_label']}'"
        if expected_call not in gradle_text:
            fail(f"{gradle_path.relative_to(ROOT)} must use {expected_call}")
        if "filesMatching('realtime-build.properties')" not in gradle_text:
            fail(f"{gradle_path.relative_to(ROOT)} does not expand realtime-build.properties")

        extras = set(config["property_sources"])
        available = BASE_KEYS | extras
        required = common_keys | placeholders(config["metadata"])
        missing = required - available
        if missing:
            fail(
                f"{loader}: resource templates require unavailable placeholders {sorted(missing)}. "
                f"Available keys: {sorted(available)}"
            )

        for key in extras:
            if not re.search(rf"\b{re.escape(key)}\s*:", gradle_text):
                fail(f"{gradle_path.relative_to(ROOT)} does not provide resource key {key}")

    combinations = 0
    for profile_path in sorted((ROOT / "buildProfiles").glob("*.properties"), key=profile_sort_key):
        profile = profile_path.stem
        props = read_properties(profile_path)
        minecraft_version = props.get("minecraft_version")
        if not minecraft_version:
            fail(f"{profile_path.relative_to(ROOT)} is missing minecraft_version")

        for loader, config in LOADERS.items():
            if props.get(f"enable_{loader}") != "true":
                continue

            values = {
                "version": "9.9.9-validation",
                "minecraft_version": minecraft_version,
                "minecraft_profile": profile,
                "loader": str(config["loader_label"]),
            }
            for output_key, source_key in config["property_sources"].items():
                source_value = props.get(source_key)
                if source_value is None or not source_value.strip():
                    fail(
                        f"{profile_path.relative_to(ROOT)} is missing {source_key}, required as "
                        f"{output_key} for {loader} resource expansion"
                    )
                values[output_key] = source_value

            target = f"Minecraft {profile} / {loader}"
            expand_for_validation(common_template, values, target)
            expand_for_validation(config["metadata"], values, target)
            combinations += 1

    print(
        "Resource template expansion validation passed for "
        f"{combinations} enabled Minecraft profile/loader combinations."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
