#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "config/build-compatibility.lock.json"
PROFILES = ROOT / "buildProfiles"

EXPECTED = {
    "1.21": "legacy",
    "1.21.1": "legacy",
    "1.21.2": "legacy",
    "1.21.3": "legacy",
    "1.21.4": "legacy",
    "1.21.5": "legacy",
    "1.21.6": "eventbus7",
    "1.21.7": "eventbus7",
    "1.21.8": "eventbus7",
    "1.21.9": "record-events",
    "1.21.10": "record-events",
    "1.21.11": "record-events",
}


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


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


def main() -> int:
    argparse.ArgumentParser(description="Validate Forge EventBus API source boundaries across Minecraft 1.21.x.").parse_args()
    lock = json.loads(LOCK.read_text(encoding="utf-8"))

    for profile, expected_api in EXPECTED.items():
        props = read_properties(PROFILES / f"{profile}.properties")
        actual = props.get("forge_event_api")
        if actual != expected_api:
            fail(f"{profile}: forge_event_api={actual!r}, expected {expected_api!r}")
        locked = lock["profiles"][profile]["loaders"]["forge"].get("eventApi")
        if locked != expected_api:
            fail(f"{profile}: compatibility lock eventApi={locked!r}, expected {expected_api!r}")

    legacy = (ROOT / "forge/src/legacy/java/com/realtime/forge/RealtimeForge.java").read_text(encoding="utf-8")
    eventbus7 = (ROOT / "forge/src/eventbus7/java/com/realtime/forge/RealtimeForge.java").read_text(encoding="utf-8")
    records = (ROOT / "forge/src/record-events/java/com/realtime/forge/RealtimeForge.java").read_text(encoding="utf-8")

    if "MinecraftForge.EVENT_BUS.addListener" not in legacy or "event.getServer()" not in legacy:
        fail("legacy Forge entrypoint must use MinecraftForge.EVENT_BUS and getServer()")
    if "MinecraftForge.EVENT_BUS" in eventbus7 or ".BUS.addListener" not in eventbus7 or "event.getServer()" not in eventbus7:
        fail("EventBus 7 Forge entrypoint must use event-local BUS fields and getServer()")
    if "MinecraftForge.EVENT_BUS" in records or ".BUS.addListener" not in records or "event.server()" not in records:
        fail("record-events Forge entrypoint must use event-local BUS fields and server()")

    build = (ROOT / "forge/build.gradle").read_text(encoding="utf-8")
    for needle in ("rootProject.ext.forge_event_api", 'forge/src/${forgeEventApi}/java', "sourceSets.main.java.srcDir(forgeEntrypointSource)"):
        if needle not in build:
            fail(f"forge/build.gradle missing source routing guard: {needle}")

    print("Forge EventBus API boundary validation passed for Minecraft 1.21-1.21.11.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
