#!/usr/bin/env python3
from __future__ import annotations

import argparse
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
    require(java, "ServerLifecycleEvents.SERVER_STARTED.register(controller::onServerStarted)", path)
    require(java, "ServerTickEvents.END_SERVER_TICK.register(controller::onServerTick)", path)
    require(java, "ServerLifecycleEvents.SERVER_STOPPING.register(controller::onServerStopping)", path)
    require(java, "ServerLifecycleEvents.SERVER_STOPPED.register(controller::onServerStopped)", path)
    forbid(java, "ClientTickEvents", path)
    forbid(java, "MinecraftClient", path)

    meta_path = "fabric/src/main/resources/fabric.mod.json"
    meta = read(meta_path)
    require(meta, '"com.realtime.fabric.RealtimeFabric"', meta_path)
    require(meta, '"environment": "*"', meta_path)
    require(meta, '"fabric-api": ">=${fabric_version}"', meta_path)


def validate_forge() -> None:
    legacy_path = "forge/src/legacy/java/com/realtime/forge/RealtimeForge.java"
    legacy = read(legacy_path)
    require(legacy, "@Mod(RealtimeConstants.MOD_ID)", legacy_path)
    require(legacy, "MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands)", legacy_path)
    require(legacy, "MinecraftForge.EVENT_BUS.addListener(this::onServerStarted)", legacy_path)
    require(legacy, "MinecraftForge.EVENT_BUS.addListener(this::onServerTick)", legacy_path)
    require(legacy, "MinecraftForge.EVENT_BUS.addListener(this::onServerStopping)", legacy_path)
    require(legacy, "MinecraftForge.EVENT_BUS.addListener(this::onServerStopped)", legacy_path)
    require(legacy, "controller.onServerTick(event.getServer())", legacy_path)

    eventbus7_path = "forge/src/eventbus7/java/com/realtime/forge/RealtimeForge.java"
    eventbus7 = read(eventbus7_path)
    require(eventbus7, "@Mod(RealtimeConstants.MOD_ID)", eventbus7_path)
    require(eventbus7, "RegisterCommandsEvent.BUS.addListener(this::onRegisterCommands)", eventbus7_path)
    require(eventbus7, "ServerStartedEvent.BUS.addListener(this::onServerStarted)", eventbus7_path)
    require(eventbus7, "TickEvent.ServerTickEvent.Post.BUS.addListener(this::onServerTick)", eventbus7_path)
    require(eventbus7, "ServerStoppingEvent.BUS.addListener(this::onServerStopping)", eventbus7_path)
    require(eventbus7, "ServerStoppedEvent.BUS.addListener(this::onServerStopped)", eventbus7_path)
    require(eventbus7, "controller.onServerTick(event.getServer())", eventbus7_path)
    forbid(eventbus7, "MinecraftForge.EVENT_BUS", eventbus7_path)
    forbid(eventbus7, "event.server()", eventbus7_path)

    record_path = "forge/src/record-events/java/com/realtime/forge/RealtimeForge.java"
    record = read(record_path)
    require(record, "@Mod(RealtimeConstants.MOD_ID)", record_path)
    require(record, "RegisterCommandsEvent.BUS.addListener(this::onRegisterCommands)", record_path)
    require(record, "ServerStartedEvent.BUS.addListener(this::onServerStarted)", record_path)
    require(record, "TickEvent.ServerTickEvent.Post.BUS.addListener(this::onServerTick)", record_path)
    require(record, "ServerStoppingEvent.BUS.addListener(this::onServerStopping)", record_path)
    require(record, "ServerStoppedEvent.BUS.addListener(this::onServerStopped)", record_path)
    require(record, "controller.onServerTick(event.server())", record_path)
    forbid(record, "MinecraftForge.EVENT_BUS", record_path)
    forbid(record, "controller.onServerTick(event.getServer())", record_path)

    main_entrypoint = ROOT / "forge/src/main/java/com/realtime/forge/RealtimeForge.java"
    if main_entrypoint.exists():
        fail("Forge RealtimeForge.java must be profile-specific, not shared from forge/src/main/java")

    for variant_path, java in ((legacy_path, legacy), (eventbus7_path, eventbus7), (record_path, record)):
        require(java, "TickEvent.ServerTickEvent.Post", variant_path)
        forbid(java, "ScheduledExecutorService", variant_path)
        forbid(java, "scheduleAtFixedRate", variant_path)
        forbid(java, "ServerLifecycleHooks.getCurrentServer", variant_path)
        forbid(java, "net.minecraft.client", variant_path)

    build_path = "forge/build.gradle"
    build = read(build_path)
    require(build, "rootProject.ext.forge_event_api", build_path)
    require(build, 'forge/src/${forgeEventApi}/java', build_path)
    require(build, "sourceSets.main.java.srcDir(forgeEntrypointSource)", build_path)

    meta_path = "forge/src/main/resources/META-INF/mods.toml"
    meta = read(meta_path)
    require(meta, 'modLoader="javafml"', meta_path)
    require(meta, 'loaderVersion="${forge_loader_version}"', meta_path)
    require(meta, 'modId="forge"', meta_path)
    require(meta, 'modId="minecraft"', meta_path)


def validate_neoforge() -> None:
    path = "neoforge/src/main/java/com/realtime/neoforge/RealtimeNeoForge.java"
    java = read(path)
    require(java, "@Mod(RealtimeConstants.MOD_ID)", path)
    require(java, "NeoForge.EVENT_BUS.addListener(this::onServerStarted)", path)
    require(java, "NeoForge.EVENT_BUS.addListener(this::onServerTick)", path)
    require(java, "NeoForge.EVENT_BUS.addListener(this::onServerStopping)", path)
    require(java, "NeoForge.EVENT_BUS.addListener(this::onServerStopped)", path)
    require(java, "ServerTickEvent.Post", path)
    forbid(java, "LevelTickEvent", path)
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


def validate_common() -> None:
    path = "common/src/main/java/com/realtime/common/RealtimeController.java"
    java = read(path)
    for signature in (
        "public void onServerStarted(MinecraftServer server)",
        "public void onServerStopping(MinecraftServer server)",
        "public void onServerStopped(MinecraftServer server)",
        "public void onWorldLoad(MinecraftServer server, ServerLevel level)",
        "public void onServerTick(MinecraftServer server)",
    ):
        require(java, signature, path)
    require(java, "gameRules.restoreAll(server)", path)
    require(java, "markServerTick(server)", path)
    require(java, "pruneDimensionState(", path)
    require(java, "handleSleepWindowClosed(server)", path)
    require(java, "restoreSleepRealignIfNeeded()", path)
    require(java, "statFingerprint(configPath)", path)
    forbid(java, "net.fabricmc", path)
    forbid(java, "net.minecraftforge", path)
    forbid(java, "net.neoforged", path)


def validate_hot_path() -> None:
    """Namespaced identifiers must not recompile a regular expression per update."""
    shared_path = "common/src/main/java/com/realtime/common/RealtimeIdentifiers.java"
    shared = read(shared_path)
    require(shared, "Pattern.compile(", shared_path)

    for path in (
        "common/src/main/java/com/realtime/common/RealtimeWorldTime.java",
        "common/src/main/java/com/realtime/common/RealtimeConfig.java",
    ):
        java = read(path)
        require(java, "RealtimeIdentifiers.normalize(", path)
        forbid(java, ".matches(\"[a-z0-9_.-]+:", path)


def validate_sleep_policy() -> None:
    """The sleep policy must stay loader-independent and keep the legacy switches working."""
    config_path = "common/src/main/java/com/realtime/common/RealtimeConfig.java"
    config = read(config_path)
    for constant in ("SLEEP_POLICY_REALTIME_ONLY", "SLEEP_POLICY_VANILLA", "SLEEP_POLICY_REALIGN"):
        require(config, constant, config_path)
    require(config, "public String effectiveSleepPolicy()", config_path)
    require(config, "sleepPolicy=", config_path)
    require(config, "sleepRealignMinutes=", config_path)

    math_path = "common/src/main/java/com/realtime/common/RealtimeMath.java"
    math = read(math_path)
    for member in ("beginSleepRealign(", "advanceSleepOffset(", "applySleepOffset(", "restoreSleepRealign("):
        require(math, member, math_path)
    forbid(math, "net.minecraft", math_path)


def validate_profile_adapters() -> None:
    legacy_path = "common/src/mc121legacy/java/com/realtime/common/ProfileDaylightRuleAccess.java"
    legacy = read(legacy_path)
    require(legacy, "GameRules.RULE_DAYLIGHT", legacy_path)
    require(legacy, ".getBoolean(", legacy_path)
    require(legacy, ".getRule(", legacy_path)

    registry_path = "common/src/mc12111/java/com/realtime/common/ProfileDaylightRuleAccess.java"
    registry = read(registry_path)
    require(registry, "net.minecraft.world.level.gamerules.GameRules", registry_path)
    require(registry, "GameRules.ADVANCE_TIME", registry_path)
    require(registry, ".set(GameRules.ADVANCE_TIME", registry_path)


def validate_resource_boundaries() -> None:
    loader_metadata = {
        "fabric": ["fabric/src/main/resources/fabric.mod.json"],
        "forge": ["forge/src/main/resources/META-INF/mods.toml"],
        "neoforge": ["neoforge/src/main/resources/META-INF/neoforge.mods.toml"],
    }
    for loader, expected in loader_metadata.items():
        resource_root = ROOT / loader / "src/main/resources"
        found = [str(path.relative_to(ROOT)).replace("\\", "/") for path in resource_root.rglob("*") if path.is_file() and path.name in {"fabric.mod.json", "mods.toml", "neoforge.mods.toml"}]
        if sorted(found) != sorted(expected):
            fail(f"{loader}: metadata boundary mismatch. expected={expected}, actual={found}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate loader entrypoints, lifecycle hooks, adapters, and metadata.")
    return parser.parse_args()


def main() -> int:
    parse_args()
    validate_common()
    validate_hot_path()
    validate_sleep_policy()
    validate_profile_adapters()
    validate_fabric_like()
    validate_forge()
    validate_neoforge()
    validate_resource_boundaries()
    print("Entrypoint, profile adapter and loader metadata validation passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
