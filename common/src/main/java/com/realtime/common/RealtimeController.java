package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RealtimeController {
    private static final int CONFIG_RELOAD_CHECK_INTERVAL_TICKS = 100;
    private static final int DAYLIGHT_RULE_GUARD_INTERVAL_TICKS = 20 * 60;
    private static final String OVERWORLD_DIMENSION_ID = "minecraft:overworld";

    private final RealtimeLog logger;
    private final Path configPath;
    private final Path legacyConfigPath;
    private final RealtimeMath timeMath = new RealtimeMath();
    private final RealtimeGameRules gameRules;

    private RealtimeConfig config = new RealtimeConfig();
    private long configLastModified = -1L;
    private int tickCounter = 0;
    private int configReloadTickCounter = 0;
    private int daylightRuleGuardTickCounter = 0;
    private boolean sleepSkipLogged = false;

    public RealtimeController(Path configDir, RealtimeLog logger) {
        this.logger = logger;
        this.configPath = configDir.resolve("realtime.properties");
        this.legacyConfigPath = configDir.resolve("realtime.toml");
        this.gameRules = new RealtimeGameRules(logger);
        reloadConfig(true);
    }

    public Path configPath() {
        return configPath;
    }

    public void onServerStarted(MinecraftServer server) {
        reloadConfig(false);
        tickCounter = Math.max(0, config.updateInterval - 1);
        ensureDaylightCycleOff(server, true);
        syncServerTime(server);
    }

    public void onWorldLoad(MinecraftServer server, ServerLevel level) {
        reloadConfig(false);
        ensureDaylightCycleOff(level, server);
        tickCounter = Math.max(0, config.updateInterval - 1);
        syncServerTime(server);
    }

    public void onServerTick(MinecraftServer server) {
        boolean reloaded = checkConfigReload();
        if (reloaded) {
            ensureDaylightCycleOff(server, true);
        } else {
            ensureDaylightCycleOff(server, false);
        }

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
            long targetTicks = config.customDayLengthMinutes > 0
                    ? timeMath.calculateCustomTicks(readOverworldTime(server), config.updateInterval, config.customDayLengthMinutes)
                    : timeMath.calculateRealtimeTicks(config.offsetHours);

            int syncedWorlds = applyTime(server, targetTicks);

            if (config.debugLogging) {
                logger.info("Synced {} world(s) toward {} ticks. Mode: {}, syncMode: {}.",
                        syncedWorlds,
                        targetTicks,
                        config.customDayLengthMinutes > 0 ? "custom-day-length" : "real-time",
                        config.syncMode);
            }
        } catch (RuntimeException exception) {
            logger.error("Failed to synchronize Minecraft time.", exception);
        }
    }

    private long readOverworldTime(MinecraftServer server) {
        return server.overworld().getDayTime();
    }

    private int applyTime(MinecraftServer server, long targetTicks) {
        int syncedWorlds = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (!shouldSyncLevel(level)) {
                continue;
            }
            if (shouldSkipForSleep(server, level)) {
                continue;
            }

            long ticksToApply = config.isSmoothSyncMode()
                    ? timeMath.calculateSmoothTicks(level.getDayTime(), targetTicks, config.maxSmoothStepTicks)
                    : targetTicks;
            level.setDayTime(ticksToApply);
            syncedWorlds++;
        }
        return syncedWorlds;
    }

    private boolean shouldSyncLevel(ServerLevel level) {
        String dimensionId = dimensionId(level);
        if (config.ignoredDimensionSet().contains(dimensionId)) {
            return false;
        }
        if (!config.syncDimensionSet().isEmpty()) {
            return config.syncDimensionSet().contains(dimensionId);
        }
        if (config.syncAllWorlds) {
            return true;
        }
        return OVERWORLD_DIMENSION_ID.equals(dimensionId);
    }

    private String dimensionId(ServerLevel level) {
        try {
            return level.dimension().location().toString().toLowerCase();
        } catch (RuntimeException exception) {
            return level.dimension().equals(Level.OVERWORLD) ? OVERWORLD_DIMENSION_ID : level.dimension().toString().toLowerCase();
        }
    }

    private boolean shouldSkipForSleep(MinecraftServer server, ServerLevel level) {
        if (!config.respectSleep || config.overrideSleepTime) {
            sleepSkipLogged = false;
            return false;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.level() == level && player.isSleeping()) {
                if (config.debugLogging && !sleepSkipLogged) {
                    logger.info("Skipping time sync while players are sleeping. Set overrideSleepTime=true to force sync during sleep.");
                }
                sleepSkipLogged = true;
                return true;
            }
        }

        sleepSkipLogged = false;
        return false;
    }

    private void ensureDaylightCycleOff(ServerLevel level, MinecraftServer server) {
        if (!config.enabled || !config.forceDaylightCycleOff || !shouldSyncLevel(level)) {
            return;
        }

        gameRules.disableDaylightCycle(level, server);
    }

    private void ensureDaylightCycleOff(MinecraftServer server, boolean force) {
        if (!config.enabled || !config.forceDaylightCycleOff) {
            return;
        }

        if (!force) {
            daylightRuleGuardTickCounter++;
            if (daylightRuleGuardTickCounter < DAYLIGHT_RULE_GUARD_INTERVAL_TICKS) {
                return;
            }
        }

        daylightRuleGuardTickCounter = 0;
        for (ServerLevel level : server.getAllLevels()) {
            if (shouldSyncLevel(level)) {
                gameRules.disableDaylightCycle(level, server);
            }
        }
    }

    private boolean checkConfigReload() {
        configReloadTickCounter++;
        if (configReloadTickCounter < CONFIG_RELOAD_CHECK_INTERVAL_TICKS) {
            return false;
        }

        configReloadTickCounter = 0;
        return reloadConfig(false);
    }

    private boolean reloadConfig(boolean force) {
        long modifiedTime = readModifiedTime(configPath);
        if (!force && modifiedTime == configLastModified) {
            return false;
        }

        config = RealtimeConfig.loadOrCreate(configPath, legacyConfigPath, logger);
        configLastModified = readModifiedTime(configPath);
        tickCounter = Math.min(tickCounter, Math.max(0, config.updateInterval - 1));
        daylightRuleGuardTickCounter = DAYLIGHT_RULE_GUARD_INTERVAL_TICKS;
        sleepSkipLogged = false;
        timeMath.resetCustomTicks();
        gameRules.resetWarningState();

        if (!force) {
            logger.info("RealtimeSync config reloaded.");
        }

        return true;
    }

    private long readModifiedTime(Path path) {
        if (!Files.exists(path)) {
            return -1L;
        }

        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            logger.warn("Failed to read RealtimeSync config timestamp. {}", exception.getMessage());
            return -1L;
        }
    }
}
