package com.realtime.fabric;

import com.realtime.common.Log4jRealtimeLog;
import com.realtime.common.RealtimeConfig;
import com.realtime.common.RealtimeConstants;
import com.realtime.common.RealtimeLog;
import com.realtime.common.RealtimeMath;
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

public final class RealtimeFabric implements ModInitializer {
    private static final Logger LOGGER = LogManager.getLogger(RealtimeConstants.MOD_NAME);
    private static final RealtimeLog LOG = new Log4jRealtimeLog(LOGGER);
    private static final int CONFIG_RELOAD_CHECK_INTERVAL_TICKS = 100;

    private RealtimeConfig config = new RealtimeConfig();
    private final RealtimeMath timeMath = new RealtimeMath();

    private Path configPath;
    private Path legacyConfigPath;
    private long configLastModified = -1L;
    private int tickCounter = 0;
    private int configReloadTickCounter = 0;

    @Override
    public void onInitialize() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        configPath = configDir.resolve("realtime.properties");
        legacyConfigPath = configDir.resolve("realtime.toml");
        reloadConfig(true);

        ServerWorldEvents.LOAD.register(this::onWorldLoad);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        LOGGER.info("{} loaded for Fabric/Quilt-compatible environments. Config: {}",
                RealtimeConstants.MOD_NAME,
                configPath.toAbsolutePath());
    }

    private void onWorldLoad(MinecraftServer server, ServerWorld world) {
        reloadConfig(false);
        if (config.forceDaylightCycleOff) {
            disableDaylightCycle(world, server);
        }

        tickCounter = Math.max(0, config.updateInterval - 1);
        syncServerTime(server);
    }

    private void onServerTick(MinecraftServer server) {
        checkConfigReload();

        if (!config.enabled) {
            return;
        }

        tickCounter++;
        if (tickCounter < config.updateInterval) {
            return;
        }

        tickCounter = 0;
        syncServerTime(server);
    }

    private void syncServerTime(MinecraftServer server) {
        if (!config.enabled) {
            return;
        }

        try {
            if (config.forceDaylightCycleOff) {
                for (ServerWorld world : server.getWorlds()) {
                    disableDaylightCycle(world, server);
                }
            }

            long ticks = config.customDayLengthMinutes > 0
                    ? timeMath.calculateCustomTicks(readOverworldTime(server), config.updateInterval, config.customDayLengthMinutes)
                    : timeMath.calculateRealtimeTicks(config.offsetHours);

            applyTime(server, ticks);

            if (config.debugLogging) {
                LOGGER.info("Synced world time to {} ticks. Mode: {}.", ticks,
                        config.customDayLengthMinutes > 0 ? "custom-day-length" : "real-time");
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to synchronize Minecraft time.", exception);
        }
    }

    private long readOverworldTime(MinecraftServer server) {
        ServerWorld overworld = server.getOverworld();
        return overworld == null ? 0L : overworld.getTimeOfDay();
    }

    private void applyTime(MinecraftServer server, long ticks) {
        if (config.syncAllWorlds) {
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

        config = RealtimeConfig.loadOrCreate(configPath, legacyConfigPath, LOG);
        configLastModified = readModifiedTime(configPath);
        tickCounter = Math.min(tickCounter, Math.max(0, config.updateInterval - 1));
        timeMath.resetCustomTicks();

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
