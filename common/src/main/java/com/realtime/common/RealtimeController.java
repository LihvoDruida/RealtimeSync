package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RealtimeController {
    private static final int CONFIG_RELOAD_CHECK_INTERVAL_TICKS = 100;
    private static final int DAYLIGHT_RULE_GUARD_INTERVAL_TICKS = 20 * 60;

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
            long ticks = config.customDayLengthMinutes > 0
                    ? timeMath.calculateCustomTicks(readOverworldTime(server), config.updateInterval, config.customDayLengthMinutes)
                    : timeMath.calculateRealtimeTicks(config.offsetHours);

            applyTime(server, ticks);

            if (config.debugLogging) {
                logger.info("Synced world time to {} ticks. Mode: {}.", ticks,
                        config.customDayLengthMinutes > 0 ? "custom-day-length" : "real-time");
            }
        } catch (RuntimeException exception) {
            logger.error("Failed to synchronize Minecraft time.", exception);
        }
    }

    private long readOverworldTime(MinecraftServer server) {
        return server.overworld().getDayTime();
    }

    private void applyTime(MinecraftServer server, long ticks) {
        if (config.syncAllWorlds) {
            for (ServerLevel level : server.getAllLevels()) {
                level.setDayTime(ticks);
            }
            return;
        }

        server.overworld().setDayTime(ticks);
    }

    private void ensureDaylightCycleOff(ServerLevel level, MinecraftServer server) {
        if (!config.enabled || !config.forceDaylightCycleOff) {
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
        gameRules.disableDaylightCycle(server);
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
