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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    private boolean daylightCycleRuleWarningShown = false;

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
        boolean changed = false;
        for (ServerLevel level : server.getAllLevels()) {
            changed |= setBooleanGameRule(level, server, false, "DO_DAYLIGHT_CYCLE", "ADVANCE_TIME", "RULE_DAYLIGHT", "RULE_ADVANCE_TIME", "field_19396");
        }

        if (!changed) {
            warnMissingDaylightCycleRuleOnce();
        }
    }

    private void warnMissingDaylightCycleRuleOnce() {
        if (daylightCycleRuleWarningShown) {
            return;
        }

        daylightCycleRuleWarningShown = true;
        LOGGER.warn("Could not disable vanilla daylight cycle: no compatible daylight gamerule key was found.");
    }

    private boolean setBooleanGameRule(ServerLevel level, MinecraftServer server, boolean value, String... keyFieldNames) {
        Object gameRules = level.getGameRules();
        Class<?> gameRulesClass = gameRules.getClass();

        for (String keyFieldName : keyFieldNames) {
            try {
                Field keyField = findField(gameRulesClass, keyFieldName);
                if (keyField == null) {
                    continue;
                }

                keyField.setAccessible(true);
                Object key = keyField.get(null);
                if (invokeDirectGameRuleSetter(gameRulesClass, gameRules, key, server, value)) {
                    return true;
                }

                Object rule = invokeGetRule(gameRulesClass, gameRules, key);
                if (rule != null && invokeBooleanRuleSetter(rule, server, value)) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next Minecraft 1.21.x gamerule key name.
            }
        }

        return false;
    }

    private Field findField(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }

        return null;
    }

    private boolean invokeDirectGameRuleSetter(Class<?> gameRulesClass, Object gameRules, Object key, MinecraftServer server, boolean value)
            throws ReflectiveOperationException {
        for (Method method : gameRulesClass.getMethods()) {
            if (!method.getName().equals("setValue") || method.getParameterCount() != 3) {
                continue;
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!parameterTypes[0].isAssignableFrom(key.getClass())) {
                continue;
            }
            if (!parameterTypes[1].isAssignableFrom(Boolean.class)) {
                continue;
            }
            if (!parameterTypes[2].isAssignableFrom(server.getClass())) {
                continue;
            }

            method.invoke(gameRules, key, value, server);
            return true;
        }

        return false;
    }

    private Object invokeGetRule(Class<?> gameRulesClass, Object gameRules, Object key) throws ReflectiveOperationException {
        for (Method method : gameRulesClass.getMethods()) {
            if ((!method.getName().equals("get") && !method.getName().equals("getRule")) || method.getParameterCount() != 1) {
                continue;
            }

            Class<?> parameterType = method.getParameterTypes()[0];
            if (!parameterType.isAssignableFrom(key.getClass())) {
                continue;
            }

            return method.invoke(gameRules, key);
        }

        return null;
    }

    private boolean invokeBooleanRuleSetter(Object rule, MinecraftServer server, boolean value) throws ReflectiveOperationException {
        for (Method method : rule.getClass().getMethods()) {
            if (!method.getName().equals("set") || method.getParameterCount() != 2) {
                continue;
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            if (parameterTypes[0] != boolean.class && parameterTypes[0] != Boolean.class) {
                continue;
            }
            if (!parameterTypes[1].isAssignableFrom(server.getClass())) {
                continue;
            }

            method.invoke(rule, value, server);
            return true;
        }

        return false;
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
