package com.realtime.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.Locale;

public final class RealtimeConfig {
    public static final String SYNC_MODE_INSTANT = "instant";
    public static final String SYNC_MODE_SMOOTH = "smooth";

    private static final int MIN_UPDATE_INTERVAL_TICKS = 1;
    private static final int MAX_UPDATE_INTERVAL_TICKS = 20 * 60 * 30; // 30 minutes
    private static final int MAX_CUSTOM_DAY_LENGTH_MINUTES = 60 * 24 * 7; // 7 real days
    private static final int MIN_SMOOTH_STEP_TICKS = 1;
    private static final int MAX_SMOOTH_STEP_TICKS = 24000;
    private static final int MIN_SMOOTH_SNAP_THRESHOLD_TICKS = 0;
    private static final int MAX_SMOOTH_SNAP_THRESHOLD_TICKS = 1200;
    private static final int MIN_SMOOTH_CATCHUP_DIVISOR = 1;
    private static final int MAX_SMOOTH_CATCHUP_DIVISOR = 24000;

    public boolean enabled = true;
    public boolean forceDaylightCycleOff = true;
    public boolean syncAllWorlds = false;
    public String syncDimensions = "minecraft:overworld";
    public String ignoredDimensions = "";
    public String syncMode = SYNC_MODE_SMOOTH;
    public int maxSmoothStepTicks = 12;
    public int smoothSnapThresholdTicks = 2;
    public int smoothCatchupDivisor = 240;
    public boolean respectSleep = true;
    public boolean overrideSleepTime = false;
    public int updateInterval = 20;
    public int offsetHours = 0;
    public int customDayLengthMinutes = 0;
    public boolean debugLogging = false;

    private Set<String> syncDimensionSet = Collections.emptySet();
    private Set<String> ignoredDimensionSet = Collections.emptySet();

    public static RealtimeConfig loadOrCreate(Path path, Path legacyTomlPath, RealtimeLog logger) {
        RealtimeConfig config = new RealtimeConfig();
        Path sourcePath = Files.exists(path) ? path : legacyTomlPath;

        if (sourcePath == null || !Files.exists(sourcePath)) {
            config.validate(logger);
            config.save(path, logger);
            return config;
        }

        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(sourcePath)) {
            properties.load(inputStream);
        } catch (IOException exception) {
            logger.warn("Failed to read RealtimeSync config. Defaults will be used. {}", exception.getMessage());
            config.validate(logger);
            config.save(path, logger);
            return config;
        }

        config.enabled = readBoolean(properties, "enabled", config.enabled, logger);
        config.forceDaylightCycleOff = readBoolean(properties, "forceDaylightCycleOff", config.forceDaylightCycleOff, logger);
        config.syncAllWorlds = readBoolean(properties, "syncAllWorlds", config.syncAllWorlds, logger);
        config.syncDimensions = readString(properties, "syncDimensions", config.syncDimensions);
        config.ignoredDimensions = readString(properties, "ignoredDimensions", config.ignoredDimensions);
        config.syncMode = readString(properties, "syncMode", config.syncMode);
        config.maxSmoothStepTicks = readInt(properties, "maxSmoothStepTicks", config.maxSmoothStepTicks, logger);
        config.smoothSnapThresholdTicks = readInt(properties, "smoothSnapThresholdTicks", config.smoothSnapThresholdTicks, logger);
        config.smoothCatchupDivisor = readInt(properties, "smoothCatchupDivisor", config.smoothCatchupDivisor, logger);
        config.respectSleep = readBoolean(properties, "respectSleep", config.respectSleep, logger);
        config.overrideSleepTime = readBoolean(properties, "overrideSleepTime", config.overrideSleepTime, logger);
        config.updateInterval = readInt(properties, "updateInterval", config.updateInterval, logger);
        config.offsetHours = readInt(properties, "offsetHours", config.offsetHours, logger);
        config.customDayLengthMinutes = readInt(properties, "customDayLengthMinutes", config.customDayLengthMinutes, logger);
        config.customDayLengthMinutes = readCustomDayLengthAlias(properties, config.customDayLengthMinutes, logger);
        config.debugLogging = readBoolean(properties, "debugLogging", config.debugLogging, logger);
        config.validate(logger);

        if (!Files.exists(path)) {
            config.save(path, logger);
            logger.info("Migrated legacy realtime.toml config to realtime.properties.");
        }

        return config;
    }

    public void save(Path path, RealtimeLog logger) {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, toFileContent(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            logger.warn("Failed to save RealtimeSync config. {}", exception.getMessage());
        }
    }

    public boolean isSmoothSyncMode() {
        return SYNC_MODE_SMOOTH.equals(syncMode);
    }

    public Set<String> syncDimensionSet() {
        return syncDimensionSet;
    }

    public Set<String> ignoredDimensionSet() {
        return ignoredDimensionSet;
    }

    private void validate(RealtimeLog logger) {
        int originalUpdateInterval = updateInterval;
        int originalOffsetHours = offsetHours;
        int originalCustomDayLength = customDayLengthMinutes;
        int originalMaxSmoothStepTicks = maxSmoothStepTicks;
        int originalSmoothSnapThresholdTicks = smoothSnapThresholdTicks;
        int originalSmoothCatchupDivisor = smoothCatchupDivisor;
        String originalSyncMode = syncMode;

        updateInterval = clamp(updateInterval, MIN_UPDATE_INTERVAL_TICKS, MAX_UPDATE_INTERVAL_TICKS);
        offsetHours = clamp(offsetHours, -23, 23);
        customDayLengthMinutes = clamp(customDayLengthMinutes, 0, MAX_CUSTOM_DAY_LENGTH_MINUTES);
        maxSmoothStepTicks = clamp(maxSmoothStepTicks, MIN_SMOOTH_STEP_TICKS, MAX_SMOOTH_STEP_TICKS);
        smoothSnapThresholdTicks = clamp(smoothSnapThresholdTicks, MIN_SMOOTH_SNAP_THRESHOLD_TICKS, MAX_SMOOTH_SNAP_THRESHOLD_TICKS);
        smoothCatchupDivisor = clamp(smoothCatchupDivisor, MIN_SMOOTH_CATCHUP_DIVISOR, MAX_SMOOTH_CATCHUP_DIVISOR);
        syncMode = normalizeSyncMode(syncMode, logger);
        syncDimensionSet = parseDimensionSet(syncDimensions, "syncDimensions", logger);
        ignoredDimensionSet = parseDimensionSet(ignoredDimensions, "ignoredDimensions", logger);
        syncDimensions = joinDimensionSet(syncDimensionSet);
        ignoredDimensions = joinDimensionSet(ignoredDimensionSet);

        if (overrideSleepTime && respectSleep) {
            logger.warn("Both respectSleep=true and overrideSleepTime=true are set. overrideSleepTime wins and time will keep syncing during sleep.");
        }

        if (originalUpdateInterval != updateInterval) {
            logger.warn("Config value updateInterval={} is out of range. Using {}.", originalUpdateInterval, updateInterval);
        }
        if (originalOffsetHours != offsetHours) {
            logger.warn("Config value offsetHours={} is out of range. Using {}.", originalOffsetHours, offsetHours);
        }
        if (originalCustomDayLength != customDayLengthMinutes) {
            logger.warn("Config value customDayLengthMinutes={} is out of range. Using {}.", originalCustomDayLength, customDayLengthMinutes);
        }
        if (originalMaxSmoothStepTicks != maxSmoothStepTicks) {
            logger.warn("Config value maxSmoothStepTicks={} is out of range. Using {}.", originalMaxSmoothStepTicks, maxSmoothStepTicks);
        }
        if (originalSmoothSnapThresholdTicks != smoothSnapThresholdTicks) {
            logger.warn("Config value smoothSnapThresholdTicks={} is out of range. Using {}.", originalSmoothSnapThresholdTicks, smoothSnapThresholdTicks);
        }
        if (originalSmoothCatchupDivisor != smoothCatchupDivisor) {
            logger.warn("Config value smoothCatchupDivisor={} is out of range. Using {}.", originalSmoothCatchupDivisor, smoothCatchupDivisor);
        }
        if (!originalSyncMode.equals(syncMode)) {
            logger.warn("Config value syncMode={} is invalid. Using {}.", originalSyncMode, syncMode);
        }
    }

    private String toFileContent() {
        return "# RealtimeSync configuration\n"
                + "# Default profile: realistic-smooth. It follows the real clock gently instead of jumping the sun/moon.\n"
                + "# Good baseline: syncMode=smooth, updateInterval=20, maxSmoothStepTicks=12, Overworld only.\n\n"
                + "# enabled: true/false - master switch for the mod.\n"
                + "enabled=" + enabled + "\n\n"
                + "# forceDaylightCycleOff: true/false - keeps Minecraft's vanilla daylight cycle disabled.\n"
                + "forceDaylightCycleOff=" + forceDaylightCycleOff + "\n\n"
                + "# syncAllWorlds: true/false - false is more realistic by default because Nether/End have no normal day-night sky.\n"
                + "# syncDimensions takes priority when it is not empty.\n"
                + "syncAllWorlds=" + syncAllWorlds + "\n\n"
                + "# syncDimensions: comma-separated allowlist. Default = Overworld only for realistic behavior.\n"
                + "# Examples: minecraft:overworld or minecraft:overworld,minecraft:the_nether,minecraft:the_end\n"
                + "syncDimensions=" + syncDimensions + "\n\n"
                + "# ignoredDimensions: comma-separated denylist excluded from syncing. Empty = none.\n"
                + "# Example: some_mod:custom_dimension\n"
                + "ignoredDimensions=" + ignoredDimensions + "\n\n"
                + "# syncMode: instant or smooth. smooth is recommended for realistic sun/moon movement.\n"
                + "syncMode=" + syncMode + "\n\n"
                + "# maxSmoothStepTicks: hard cap for Minecraft ticks changed per sync when syncMode=smooth.\n"
                + "# With updateInterval=20 and maxSmoothStepTicks=12, the fastest catch-up is still visually smooth.\n"
                + "maxSmoothStepTicks=" + maxSmoothStepTicks + "\n\n"
                + "# smoothSnapThresholdTicks: if the world is already this close to target, snap exactly to avoid tiny jitter.\n"
                + "smoothSnapThresholdTicks=" + smoothSnapThresholdTicks + "\n\n"
                + "# smoothCatchupDivisor: higher = gentler adaptive catch-up; lower = catches up faster.\n"
                + "# Formula: step ~= drift / smoothCatchupDivisor, capped by maxSmoothStepTicks.\n"
                + "smoothCatchupDivisor=" + smoothCatchupDivisor + "\n\n"
                + "# respectSleep: true skips time sync while players are sleeping, unless overrideSleepTime=true.\n"
                + "respectSleep=" + respectSleep + "\n\n"
                + "# overrideSleepTime: true keeps forcing realtime/custom time even while players are sleeping.\n"
                + "overrideSleepTime=" + overrideSleepTime + "\n\n"
                + "# updateInterval: ticks between time syncs. 20 ticks = 1 second. Realistic-smooth uses 20.\n"
                + "updateInterval=" + updateInterval + "\n\n"
                + "# offsetHours: real-time offset from server system time. Range: -23..23.\n"
                + "offsetHours=" + offsetHours + "\n\n"
                + "# customDayLengthMinutes: 0 = real clock sync. Greater than 0 = custom Minecraft day length in real minutes.\n"
                + "customDayLengthMinutes=" + customDayLengthMinutes + "\n\n"
                + "# debugLogging: true/false - enables detailed time sync logs.\n"
                + "debugLogging=" + debugLogging + "\n";
    }

    private static String normalizeSyncMode(String value, RealtimeLog logger) {
        if (value == null || value.isBlank()) {
            return SYNC_MODE_SMOOTH;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (SYNC_MODE_INSTANT.equals(normalized) || SYNC_MODE_SMOOTH.equals(normalized)) {
            return normalized;
        }

        return SYNC_MODE_SMOOTH;
    }

    private static Set<String> parseDimensionSet(String rawValue, String key, RealtimeLog logger) {
        if (rawValue == null || rawValue.isBlank()) {
            return Collections.emptySet();
        }

        Set<String> values = new LinkedHashSet<>();
        String[] parts = rawValue.split(",");
        for (String part : parts) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) {
                continue;
            }
            if (!value.contains(":")) {
                logger.warn("Ignoring invalid dimension id in {}: {}. Use namespace:path, for example minecraft:overworld.", key, value);
                continue;
            }
            values.add(value);
        }

        return Collections.unmodifiableSet(values);
    }

    private static String joinDimensionSet(Set<String> values) {
        return String.join(",", values);
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback, RealtimeLog logger) {
        String rawValue = properties.getProperty(key);
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }

        String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }

        logger.warn("Invalid boolean config value {}={}. Using {}.", key, rawValue, fallback);
        return fallback;
    }

    private static int readInt(Properties properties, String key, int fallback, RealtimeLog logger) {
        String rawValue = properties.getProperty(key);
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException exception) {
            logger.warn("Invalid integer config value {}={}. Using {}.", key, rawValue, fallback);
            return fallback;
        }
    }

    private static int readCustomDayLengthAlias(Properties properties, int fallback, RealtimeLog logger) {
        if (properties.containsKey("customDayLengthMinutes")) {
            return fallback;
        }

        for (String alias : new String[] {"realTimeMinutes", "dayLengthMinutes", "minecraftDayLengthMinutes"}) {
            String rawValue = properties.getProperty(alias);
            if (rawValue == null || rawValue.isBlank()) {
                continue;
            }

            logger.warn("Config key {} is deprecated/ambiguous. Please use customDayLengthMinutes={} instead.", alias, rawValue.trim());
            return readInt(properties, alias, fallback, logger);
        }

        return fallback;
    }

    private static String readString(Properties properties, String key, String fallback) {
        String rawValue = properties.getProperty(key);
        if (rawValue == null) {
            return fallback;
        }
        return rawValue.trim();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
