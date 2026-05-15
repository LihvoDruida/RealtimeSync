#!/usr/bin/env python3
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def fail(message: str) -> None:
    print(f"ERROR: {message}", flush=True)
    raise SystemExit(1)


def read(path: str) -> str:
    file = ROOT / path
    if not file.is_file():
        fail(f"Missing required file: {path}")
    return file.read_text(encoding="utf-8")


def require(text: str, needle: str, path: str) -> None:
    if needle not in text:
        fail(f"{path}: expected {needle!r}")


def forbid(text: str, needle: str, path: str) -> None:
    if needle in text:
        fail(f"{path}: forbidden {needle!r}")


def validate_fabric_like() -> None:
    path = "fabric/src/main/java/com/realtime/fabric/RealtimeFabric.java"
    java = read(path)
    require(java, "implements ModInitializer", path)
    require(java, "ServerTickEvents.END_SERVER_TICK.register(controller::onServerTick)", path)
    require(java, "ServerLifecycleEvents.SERVER_STOPPED.register(controller::onServerStopped)", path)
    forbid(java, "ClientTickEvents", path)
    forbid(java, "MinecraftClient", path)

    meta_path = "fabric/src/main/resources/fabric.mod.json"
    meta = read(meta_path)
    require(meta, '"entrypoints"', meta_path)
    require(meta, '"main"', meta_path)
    require(meta, '"com.realtime.fabric.RealtimeFabric"', meta_path)
    require(meta, '"environment": "*"', meta_path)
    require(meta, '"fabric-api": ">=${fabric_version}"', meta_path)
    forbid(meta, '"fabric-api": "*"', meta_path)
    forbid(meta, '"client"', meta_path)


def validate_forge() -> None:
    path = "forge/src/main/java/com/realtime/forge/RealtimeForge.java"
    java = read(path)
    require(java, "@Mod(RealtimeConstants.MOD_ID)", path)
    require(java, "public RealtimeForge()", path)
    require(java, "ServerLifecycleHooks.getCurrentServer()", path)
    require(java, "controller.onServerStopped(activeServer)", path)
    require(java, "server.execute(() ->", path)
    forbid(java, "net.minecraft.client", path)

    meta_path = "forge/src/main/resources/META-INF/mods.toml"
    meta = read(meta_path)
    require(meta, 'modLoader="javafml"', meta_path)
    require(meta, 'loaderVersion="${forge_loader_version}"', meta_path)
    require(meta, 'modId="forge"', meta_path)
    require(meta, 'modId="minecraft"', meta_path)
    forbid(meta, 'modId="neoforge"', meta_path)
    forbid(meta, 'neoforge_version', meta_path)


def validate_neoforge() -> None:
    path = "neoforge/src/main/java/com/realtime/neoforge/RealtimeNeoForge.java"
    java = read(path)
    require(java, "@Mod(RealtimeConstants.MOD_ID)", path)
    require(java, "public RealtimeNeoForge()", path)
    require(java, "NeoForge.EVENT_BUS.addListener(this::onLevelTick)", path)
    require(java, "NeoForge.EVENT_BUS.addListener(this::onServerStopped)", path)
    require(java, "ServerStoppedEvent", path)
    forbid(java, "net.minecraft.client", path)
    forbid(java, "net.minecraftforge", path)

    meta_path = "neoforge/src/main/resources/META-INF/neoforge.mods.toml"
    meta = read(meta_path)
    require(meta, 'modLoader="javafml"', meta_path)
    require(meta, 'loaderVersion="[1,)"', meta_path)
    require(meta, 'versionRange="${neoforge_version_range}"', meta_path)
    require(meta, 'modId="neoforge"', meta_path)
    require(meta, 'modId="minecraft"', meta_path)
    forbid(meta, 'loaderVersion="${', meta_path)
    forbid(meta, 'loaderVersion="[21.', meta_path)
    forbid(meta, 'modId="forge"', meta_path)

    gradle_path = "neoforge/build.gradle"
    gradle = read(gradle_path)
    require(gradle, "Missing required neoforge_version_range", gradle_path)
    forbid(gradle, "neoforgeDependencyVersionRange", gradle_path)
    forbid(gradle, ": project.neoforge_loader_version", gradle_path)


def validate_common() -> None:
    path = "common/src/main/java/com/realtime/common/RealtimeController.java"
    java = read(path)
    require(java, "public void onServerStarted(MinecraftServer server)", path)
    require(java, "public void onServerStopped(MinecraftServer server)", path)
    require(java, "public void onWorldLoad(MinecraftServer server, ServerLevel level)", path)
    require(java, "public void onServerTick(MinecraftServer server)", path)
    require(java, "RealtimeWorldTime.resetRuntimeState()", path)
    require(java, "markServerTick(server)", path)
    forbid(java, "net.fabricmc", path)
    forbid(java, "net.minecraftforge", path)
    forbid(java, "net.neoforged", path)


def validate_resource_boundaries() -> None:
    loader_metadata = {
        "fabric": ["fabric/src/main/resources/fabric.mod.json"],
        "forge": ["forge/src/main/resources/META-INF/mods.toml"],
        "neoforge": ["neoforge/src/main/resources/META-INF/neoforge.mods.toml"],
    }
    for loader, expected in loader_metadata.items():
        resource_root = ROOT / loader / "src/main/resources"
        found = [str(path.relative_to(ROOT)).replace("\\\\", "/") for path in resource_root.rglob("*") if path.is_file() and path.name in {"fabric.mod.json", "mods.toml", "neoforge.mods.toml"}]
        if sorted(found) != sorted(expected):
            fail(f"{loader}: metadata boundary mismatch. expected={expected}, actual={found}")


def main() -> int:
    validate_common()
    validate_fabric_like()
    validate_forge()
    validate_neoforge()
    validate_resource_boundaries()
    print("Entrypoint and loader metadata validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
