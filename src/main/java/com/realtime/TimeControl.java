package com.realtime;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.GameRules;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class TimeControl implements ModInitializer {
    public static final String MOD_ID = "realtime";
    public static final Logger LOGGER = LogManager.getLogger("RealtimeSync");

    private static final long TICKS_PER_DAY = 24000L;
    private static final long SECONDS_PER_DAY = 86400L;
    private static final long MINECRAFT_DAY_START_SECONDS = 6L * 60L * 60L;
    private static final int CONFIG_RELOAD_CHECK_INTERVAL_TICKS = 100;

    public static RealtimeConfig CONFIG = new RealtimeConfig();

    private Path configPath;
    private Path legacyConfigPath;
    private long configLastModified = -1L;
    private int tickCounter = 0;
    private int configReloadTickCounter = 0;
    private double customTicks = 0.0D;
    private boolean customTicksInitialized = false;

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        configPath = configDir.resolve("realtime.properties");
        legacyConfigPath = configDir.resolve("realtime.toml");
        reloadConfig(true);

        ServerWorldEvents.LOAD.register(this::onWorldLoad);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        LOGGER.info("RealtimeSync loaded. Config: {}", configPath.toAbsolutePath());
    }

    private void onWorldLoad(MinecraftServer server, ServerWorld world) {
        reloadConfig(false);
        if (CONFIG.forceDaylightCycleOff) {
            disableDaylightCycle(world, server);
        }

        tickCounter = Math.max(0, CONFIG.updateInterval - 1);
        syncServerTime(server);
    }

    private void onServerTick(MinecraftServer server) {
        checkConfigReload();

        if (!CONFIG.enabled) {
            return;
        }

        tickCounter++;
        if (tickCounter < CONFIG.updateInterval) {
            return;
        }

        tickCounter = 0;
        syncServerTime(server);
    }

    private void syncServerTime(MinecraftServer server) {
        if (!CONFIG.enabled) {
            return;
        }

        try {
            if (CONFIG.forceDaylightCycleOff) {
                for (ServerWorld world : server.getWorlds()) {
                    disableDaylightCycle(world, server);
                }
            }

            long ticks = CONFIG.customDayLengthMinutes > 0
                    ? calculateCustomTicks(server)
                    : calculateRealtimeTicks();

            applyTime(server, ticks);

            if (CONFIG.debugLogging) {
                LOGGER.info("Synced world time to {} ticks. Mode: {}.", ticks,
                        CONFIG.customDayLengthMinutes > 0 ? "custom-day-length" : "real-time");
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to synchronize Minecraft time.", exception);
        }
    }

    private long calculateRealtimeTicks() {
        customTicksInitialized = false;

        LocalTime realTime = ZonedDateTime.now(ZoneId.systemDefault())
                .plusHours(CONFIG.offsetHours)
                .toLocalTime();

        long secondsFromMinecraftMorning = Math.floorMod(
                realTime.toSecondOfDay() - MINECRAFT_DAY_START_SECONDS,
                SECONDS_PER_DAY
        );

        return Math.floorMod(Math.round(secondsFromMinecraftMorning * (TICKS_PER_DAY / (double) SECONDS_PER_DAY)), TICKS_PER_DAY);
    }

    private long calculateCustomTicks(MinecraftServer server) {
        if (!customTicksInitialized) {
            ServerWorld overworld = server.getOverworld();
            customTicks = overworld == null ? 0.0D : Math.floorMod(overworld.getTimeOfDay(), TICKS_PER_DAY);
            customTicksInitialized = true;
        }

        double ticksPerUpdate = CONFIG.updateInterval * TICKS_PER_DAY / (CONFIG.customDayLengthMinutes * 60.0D * 20.0D);
        customTicks = (customTicks + ticksPerUpdate) % TICKS_PER_DAY;
        return (long) customTicks;
    }

    private void applyTime(MinecraftServer server, long ticks) {
        if (CONFIG.syncAllWorlds) {
            for (ServerWorld world : server.getWorlds()) {
                world.setTimeOfDay(ticks);
            }
            return;
        }

        ServerWorld overworld = server.getOverworld();
        if (overworld == null) {
            LOGGER.warn("Cannot synchronize time: overworld is not available yet.");
            return;
        }
        overworld.setTimeOfDay(ticks);
    }

    private void disableDaylightCycle(ServerWorld world, MinecraftServer server) {
        GameRules.BooleanRule rule = world.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE);
        if (rule.get()) {
            rule.set(false, server);
        }
    }

    private void checkConfigReload() {
        configReloadTickCounter++;
        if (configReloadTickCounter < CONFIG_RELOAD_CHECK_INTERVAL_TICKS) {
            return;
        }

        configReloadTickCounter = 0;
        reloadConfig(false);
    }

    private void reloadConfig(boolean force) {
        if (configPath == null) {
            return;
        }

        long modifiedTime = readModifiedTime(configPath);
        if (!force && modifiedTime == configLastModified) {
            return;
        }

        CONFIG = RealtimeConfig.loadOrCreate(configPath, legacyConfigPath, LOGGER);
        configLastModified = readModifiedTime(configPath);
        tickCounter = Math.min(tickCounter, Math.max(0, CONFIG.updateInterval - 1));
        customTicksInitialized = false;

        if (!force) {
            LOGGER.info("RealtimeSync config reloaded.");
        }
    }

    private long readModifiedTime(Path path) {
        if (!Files.exists(path)) {
            return -1L;
        }

        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            LOGGER.warn("Failed to read RealtimeSync config timestamp.", exception);
            return -1L;
        }
    }
}
