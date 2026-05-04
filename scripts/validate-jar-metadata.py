#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path

PLACEHOLDER_RE = re.compile(r"\$\{[^}]+}")


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def read_text(jar: zipfile.ZipFile, path: str) -> str:
    try:
        return jar.read(path).decode("utf-8")
    except KeyError:
        fail(f"Missing metadata file in jar: {path}")
    except UnicodeDecodeError as exc:
        fail(f"Metadata file is not UTF-8: {path}: {exc}")


def assert_no_placeholders(text: str, path: str) -> None:
    match = PLACEHOLDER_RE.search(text)
    if match:
        fail(f"Unexpanded Gradle placeholder {match.group(0)!r} remains in {path}")


def assert_contains(text: str, expected: str, path: str) -> None:
    if expected not in text:
        fail(f"Expected {expected!r} in {path}")


def validate_fabric_like(jar: zipfile.ZipFile, loader: str, minecraft_version: str, mod_version: str) -> None:
    path = "fabric.mod.json"
    text = read_text(jar, path)
    assert_no_placeholders(text, path)

    try:
        metadata = json.loads(text)
    except json.JSONDecodeError as exc:
        fail(f"Invalid JSON in {path}: {exc}")

    if metadata.get("id") != "realtime":
        fail(f"{path} has wrong mod id: {metadata.get('id')!r}")
    if metadata.get("version") != mod_version:
        fail(f"{path} has version {metadata.get('version')!r}, expected {mod_version!r}")

    depends = metadata.get("depends") or {}
    minecraft_range = str(depends.get("minecraft", ""))
    if minecraft_version not in minecraft_range:
        fail(f"{path} minecraft dependency {minecraft_range!r} does not mention {minecraft_version!r}")
    if "java" not in depends:
        fail(f"{path} missing java dependency")

    entrypoints = metadata.get("entrypoints") or {}
    main = entrypoints.get("main") or []
    if "com.realtime.fabric.RealtimeFabric" not in main:
        fail(f"{path} missing Fabric entrypoint")

    if loader == "quilt":
        # Quilt-compatible artifact intentionally reuses the Fabric entrypoint/source set.
        # Keep this explicit so accidental metadata format changes are caught.
        assert_contains(text, "com.realtime.fabric.RealtimeFabric", path)


def validate_mods_toml(jar: zipfile.ZipFile, path: str, platform_mod_id: str, minecraft_version: str, mod_version: str) -> None:
    text = read_text(jar, path)
    assert_no_placeholders(text, path)
    assert_contains(text, 'modId="realtime"', path)
    assert_contains(text, f'version="{mod_version}"', path)
    assert_contains(text, f'modId="{platform_mod_id}"', path)
    assert_contains(text, 'modId="minecraft"', path)
    assert_contains(text, minecraft_version, path)


def validate_common_entries(jar: zipfile.ZipFile, loader: str, jar_path: Path) -> None:
    names = set(jar.namelist())
    if "assets/realtime/icon.png" not in names:
        fail(f"{jar_path} missing assets/realtime/icon.png")
    if not any(name.endswith("RealtimeController.class") for name in names):
        fail(f"{jar_path} does not contain common RealtimeController class")
    if loader in {"fabric", "quilt"} and "fabric.mod.json" not in names:
        fail(f"{jar_path} missing fabric.mod.json")
    if loader == "forge" and "META-INF/mods.toml" not in names:
        fail(f"{jar_path} missing META-INF/mods.toml")
    if loader == "neoforge" and "META-INF/neoforge.mods.toml" not in names:
        fail(f"{jar_path} missing META-INF/neoforge.mods.toml")


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate RealtimeSync release jar metadata.")
    parser.add_argument("--loader", required=True, choices=["fabric", "quilt", "forge", "neoforge"])
    parser.add_argument("--minecraft-version", required=True)
    parser.add_argument("--mod-version", required=True)
    parser.add_argument("--jar", required=True, type=Path)
    args = parser.parse_args()

    if not args.jar.is_file():
        fail(f"Jar does not exist: {args.jar}")
    if args.jar.name.endswith("-sources.jar"):
        fail(f"Expected runtime jar, got sources jar: {args.jar}")
    if args.mod_version not in args.jar.name:
        fail(f"Jar filename {args.jar.name!r} does not contain mod version {args.mod_version!r}")
    if args.minecraft_version not in args.jar.name:
        fail(f"Jar filename {args.jar.name!r} does not contain Minecraft version {args.minecraft_version!r}")

    try:
        with zipfile.ZipFile(args.jar) as jar:
            validate_common_entries(jar, args.loader, args.jar)
            if args.loader in {"fabric", "quilt"}:
                validate_fabric_like(jar, args.loader, args.minecraft_version, args.mod_version)
            elif args.loader == "forge":
                validate_mods_toml(jar, "META-INF/mods.toml", "forge", args.minecraft_version, args.mod_version)
            elif args.loader == "neoforge":
                validate_mods_toml(jar, "META-INF/neoforge.mods.toml", "neoforge", args.minecraft_version, args.mod_version)
    except zipfile.BadZipFile as exc:
        fail(f"Invalid jar/zip file {args.jar}: {exc}")

    print(f"Validated {args.loader} metadata for Minecraft {args.minecraft_version}: {args.jar}")


if __name__ == "__main__":
    main()
