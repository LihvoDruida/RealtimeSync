package com.realtime.forge;

import com.realtime.common.Log4jRealtimeLog;
import com.realtime.common.RealtimeConfig;
import com.realtime.common.RealtimeConstants;
import com.realtime.common.RealtimeLog;
import com.realtime.common.RealtimeMath;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod(RealtimeConstants.MOD_ID)
public final class RealtimeForge {
    private static final Logger LOGGER = LogManager.getLogger(RealtimeConstants.MOD_NAME);
    private static final RealtimeLog LOG = new Log4jRealtimeLog(LOGGER);
    private static final int CONFIG_RELOAD_CHECK_INTERVAL_TICKS = 100;

    private final RealtimeMath timeMath = new RealtimeMath();
    private final Path configPath;
    private final Path legacyConfigPath;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean serverWorkQueued = new AtomicBoolean(false);

    private RealtimeConfig config = new RealtimeConfig();
    private MinecraftServer activeServer;
    private long configLastModified = -1L;
    private int tickCounter = 0;
    private int configReloadTickCounter = 0;

    public RealtimeForge() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        configPath = configDir.resolve("realtime.properties");
        legacyConfigPath = configDir.resolve("realtime.toml");
        reloadConfig(true);

        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "RealtimeSync-Forge-Ticker");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleAtFixedRate(this::queueServerTick, 50L, 50L, TimeUnit.MILLISECONDS);

        LOGGER.info("{} loaded for Forge. Config: {}", RealtimeConstants.MOD_NAME, configPath.toAbsolutePath());
    }

    private void queueServerTick() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            activeServer = null;
            serverWorkQueued.set(false);
            return;
        }

        if (!serverWorkQueued.compareAndSet(false, true)) {
            return;
        }

        server.execute(() -> {
            try {
                handleServerTick(server);
            } finally {
                serverWorkQueued.set(false);
            }
        });
    }

    private void handleServerTick(MinecraftServer server) {
        if (activeServer != server) {
            activeServer = server;
            reloadConfig(false);
            tickCounter = Math.max(0, config.updateInterval - 1);
            syncServerTime(server);
            return;
        }

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
                disableDaylightCycle(server);
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

    private void disableDaylightCycle(MinecraftServer server) {
        // Avoid direct GameRules imports: Mojang mappings moved this class/package in newer 1.21.x lines.
        // The command API is stable across the targeted 1.21 profiles and changes the same doDaylightCycle rule.
        server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack(),
                "gamerule doDaylightCycle false"
        );
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
