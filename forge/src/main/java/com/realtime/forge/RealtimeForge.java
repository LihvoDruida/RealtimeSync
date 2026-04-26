package com.realtime.forge;

import com.realtime.common.Log4jRealtimeLog;
import com.realtime.common.RealtimeConfig;
import com.realtime.common.RealtimeConstants;
import com.realtime.common.RealtimeLog;
import com.realtime.common.RealtimeMath;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.tick.LevelTickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod(RealtimeConstants.MOD_ID)
public final class RealtimeForge {
    private static final Logger LOGGER = LogManager.getLogger(RealtimeConstants.MOD_NAME);
    private static final RealtimeLog LOG = new Log4jRealtimeLog(LOGGER);
    private static final int CONFIG_RELOAD_CHECK_INTERVAL_TICKS = 100;

    private RealtimeConfig config = new RealtimeConfig();
    private final RealtimeMath timeMath = new RealtimeMath();

    private final Path configPath;
    private final Path legacyConfigPath;
    private long configLastModified = -1L;
    private int tickCounter = 0;
    private int configReloadTickCounter = 0;

    public RealtimeForge() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        configPath = configDir.resolve("realtime.properties");
        legacyConfigPath = configDir.resolve("realtime.toml");
        reloadConfig(true);

        MinecraftForge.EVENT_BUS.register(this);

        LOGGER.info("{} loaded for Forge. Config: {}", RealtimeConstants.MOD_NAME, configPath.toAbsolutePath());
    }

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        MinecraftServer server = level.getServer();
        if (server == null) {
            return;
        }

        reloadConfig(false);
        if (config.forceDaylightCycleOff) {
            disableDaylightCycle(level, server);
        }

        tickCounter = Math.max(0, config.updateInterval - 1);
        syncServerTime(server);
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }

        // Run once per server tick instead of once per loaded dimension.
        if (!level.dimension().equals(Level.OVERWORLD)) {
            return;
        }

        MinecraftServer server = level.getServer();
        if (server != null) {
            onServerTick(server);
        }
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
                for (ServerLevel level : server.getAllLevels()) {
                    disableDaylightCycle(level, server);
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

    private void disableDaylightCycle(ServerLevel level, MinecraftServer server) {
        GameRules.BooleanValue rule = level.getGameRules().getRule(GameRules.RULE_DAYLIGHT);
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
