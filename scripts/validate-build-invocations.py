#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOADERS = {
    "fabric": "buildFabric",
    "quilt": "buildQuilt",
    "forge": "buildForge",
    "neoforge": "buildNeoForge",
}


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def require(path: str, marker: str, description: str) -> None:
    text = (ROOT / path).read_text(encoding="utf-8")
    if marker not in text:
        fail(f"{path}: missing {description}: {marker!r}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate canonical Gradle/PowerShell/CMD build entrypoints."
    )
    parser.parse_args()

    build_gradle = (ROOT / "build.gradle").read_text(encoding="utf-8")
    settings_gradle = (ROOT / "settings.gradle").read_text(encoding="utf-8")

    if "gradle.startParameter.projectProperties.get('mcProfile')" not in build_gradle:
        fail("build.gradle must prioritize the raw -PmcProfile command-line property")
    if "mc_profile_source" not in build_gradle:
        fail("build.gradle must expose the selected profile source for diagnostics")
    if "Invalid Minecraft build profile" not in build_gradle:
        fail("build.gradle must reject malformed/truncated profile values")
    if "projectProperties.get('modVersion')" not in build_gradle:
        fail("build.gradle must support an explicit -PmodVersion override")
    if "0.0.0-dev+${revision ?: 'local'}" not in build_gradle:
        fail("local builds must not publish the ambiguous plain 0.0.0-dev version")
    if "ext.resourceExpansionValues" not in build_gradle or "values.put('minecraft_version'" not in build_gradle:
        fail("build.gradle must provide canonical common resource expansion values")

    for loader, task in LOADERS.items():
        if f"loaderTaskOrSkip('{task}'" not in build_gradle:
            fail(f"build.gradle is missing root task {task}")
        if f"{loader}  : '{task.lower()}'" not in settings_gradle and f"{loader}: '{task.lower()}'" not in settings_gradle:
            # NeoForge alignment contains no predictable number of spaces; use regex below.
            pattern = rf"\b{re.escape(loader)}\s*:\s*'{re.escape(task.lower())}'"
            if not re.search(pattern, settings_gradle):
                fail(f"settings.gradle cannot infer targetLoader from {task}")

    require("build.gradle", "tasks.register('buildAllLoaders')", "buildAllLoaders task")
    require("settings.gradle", "gradle.ext.resolvedTargetLoader = targetLoader", "resolved loader diagnostics")
    require("scripts/build.ps1", '"-PmcProfile=$Profile"', "quoted PowerShell profile argument")
    require("scripts/build.ps1", '"-PtargetLoader=$Loader"', "quoted PowerShell loader argument")
    require("scripts/build.ps1", '"-PmodVersion=$ModVersion"', "PowerShell mod-version override")
    require("scripts/build.cmd", '"-PmcProfile=%PROFILE%"', "quoted CMD profile argument")
    require("scripts/build.cmd", '"-PtargetLoader=%LOADER%"', "quoted CMD loader argument")
    require("scripts/build.cmd", '"-PmodVersion=%MOD_VERSION%"', "CMD mod-version override")
    require("scripts/build.sh", '"-PmcProfile=${profile}"', "quoted Bash profile argument")
    require("scripts/build.sh", '"-PtargetLoader=${loader}"', "quoted Bash loader argument")
    require("scripts/build.sh", '"-PmodVersion=${mod_version}"', "Bash mod-version override")
    require("scripts/build-all-profiles.ps1", "build.ps1", "PowerShell matrix wrapper")
    require("scripts/build-all-profiles.sh", "./scripts/build.sh", "Bash matrix wrapper")

    for path in ("README.md", "VERSIONING.md"):
        require(path, ".\\scripts\\build.ps1 -Profile 1.21.11 -Loader neoforge", "canonical PowerShell example")
        require(path, '"-PtargetLoader=neoforge"', "isolated direct NeoForge invocation")

    require("common/src/main/resources/realtime-build.properties", "version=${version}", "embedded build version")
    require("common/src/main/resources/realtime-build.properties", "minecraft=${minecraft_version}", "embedded Minecraft version")
    loader_labels = {
        "fabric": "fabric",
        "quilt": "quilt-compatible",
        "forge": "forge",
        "neoforge": "neoforge",
    }
    for loader in LOADERS:
        require(f"{loader}/build.gradle", "filesMatching('realtime-build.properties')", "build metadata expansion")
        require(
            f"{loader}/build.gradle",
            f"resourceExpansionValues('{loader_labels[loader]}'",
            "canonical resource expansion map",
        )

    workflow = (ROOT / ".github/workflows/package.yml").read_text(encoding="utf-8")
    if '"-PmcProfile=${{ steps.versions.outputs.mc_profile }}"' not in workflow:
        fail("CI must pass mcProfile as one quoted argument")
    if '"-PtargetLoader=${{ steps.versions.outputs.loader }}"' not in workflow:
        fail("CI must pass targetLoader as one quoted argument")
    if '"-PmodVersion=${{ steps.tag_version.outputs.mod_version }}"' not in workflow:
        fail("CI must pass the tag version directly to Gradle")
    if 'sed -i "s/^mod_version=' in workflow:
        fail("CI must not rewrite gradle.properties to propagate the release version")
    if "validate-resource-expansion.py" not in workflow:
        fail("CI must run resource expansion validation before the build matrix")
    require(
        "scripts/ci-read-profile.sh",
        'REQUESTED_MOD_VERSION="${3:-${MOD_VERSION:-}}"',
        "explicit CI release version input",
    )
    require("scripts/ci-read-profile.sh", "tr -d '\\r'", "CRLF-safe property parsing")
    require("scripts/build.sh", "true\\r?$", "CRLF-safe loader switch parsing")

    profiles = sorted(path.stem for path in (ROOT / "buildProfiles").glob("*.properties"))
    expected = ["1.21"] + [f"1.21.{number}" for number in range(1, 12)]
    if sorted(profiles, key=lambda value: tuple(map(int, value.split(".")))) != expected:
        fail(f"Unexpected profile set: {profiles}")

    print("Build invocation validation passed for PowerShell, CMD, Bash and CI.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
