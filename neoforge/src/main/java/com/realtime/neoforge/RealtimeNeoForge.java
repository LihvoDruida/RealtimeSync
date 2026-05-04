package com.realtime.neoforge;

import com.realtime.common.Log4jRealtimeLog;
import com.realtime.common.RealtimeConfig;
import com.realtime.common.RealtimeConstants;
import com.realtime.common.RealtimeLog;
import com.realtime.common.RealtimeMath;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

@Mod(RealtimeConstants.MOD_ID)
public final class RealtimeNeoForge {
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
    private boolean daylightCycleRuleWarningShown = false;

    public RealtimeNeoForge() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        configPath = configDir.resolve("realtime.properties");
        legacyConfigPath = configDir.resolve("realtime.toml");
        reloadConfig(true);

        NeoForge.EVENT_BUS.addListener(this::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(this::onLevelTick);

        LOGGER.info("{} loaded for NeoForge. Config: {}", RealtimeConstants.MOD_NAME, configPath.toAbsolutePath());
    }

    private void onLevelLoad(LevelEvent.Load event) {
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

    private void onLevelTick(LevelTickEvent.Post event) {
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
        if (!setBooleanGameRule(level, server, false, "DO_DAYLIGHT_CYCLE", "ADVANCE_TIME", "RULE_DAYLIGHT", "RULE_ADVANCE_TIME", "field_19396")) {
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
