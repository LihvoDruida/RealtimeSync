package com.realtime.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class RealtimeConfig {
    private static final int MIN_UPDATE_INTERVAL_TICKS = 1;
    private static final int MAX_UPDATE_INTERVAL_TICKS = 20 * 60 * 30; // 30 minutes
    private static final int MAX_CUSTOM_DAY_LENGTH_MINUTES = 60 * 24 * 7; // 7 real days

    public boolean enabled = true;
    public boolean forceDaylightCycleOff = true;
    public boolean syncAllWorlds = true;
    public int updateInterval = 60;
    public int offsetHours = 0;
    public int customDayLengthMinutes = 0;
    public boolean debugLogging = false;

    public static RealtimeConfig loadOrCreate(Path path, Path legacyTomlPath, RealtimeLog logger) {
        RealtimeConfig config = new RealtimeConfig();
        Path sourcePath = Files.exists(path) ? path : legacyTomlPath;

        if (sourcePath == null || !Files.exists(sourcePath)) {
            config.save(path, logger);
            return config;
        }

        Properties properties = new Properties();
        try (InputStream inputStream = Files.newInputStream(sourcePath)) {
            properties.load(inputStream);
        } catch (IOException exception) {
            logger.warn("Failed to read RealtimeSync config. Defaults will be used. {}", exception.getMessage());
            config.save(path, logger);
            return config;
        }

        config.enabled = readBoolean(properties, "enabled", config.enabled, logger);
        config.forceDaylightCycleOff = readBoolean(properties, "forceDaylightCycleOff", config.forceDaylightCycleOff, logger);
        config.syncAllWorlds = readBoolean(properties, "syncAllWorlds", config.syncAllWorlds, logger);
        config.updateInterval = readInt(properties, "updateInterval", config.updateInterval, logger);
        config.offsetHours = readInt(properties, "offsetHours", config.offsetHours, logger);
        config.customDayLengthMinutes = readInt(properties, "customDayLengthMinutes", config.customDayLengthMinutes, logger);
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

    private void validate(RealtimeLog logger) {
        int originalUpdateInterval = updateInterval;
        int originalOffsetHours = offsetHours;
        int originalCustomDayLength = customDayLengthMinutes;

        updateInterval = clamp(updateInterval, MIN_UPDATE_INTERVAL_TICKS, MAX_UPDATE_INTERVAL_TICKS);
        offsetHours = clamp(offsetHours, -23, 23);
        customDayLengthMinutes = clamp(customDayLengthMinutes, 0, MAX_CUSTOM_DAY_LENGTH_MINUTES);

        if (originalUpdateInterval != updateInterval) {
            logger.warn("Config value updateInterval={} is out of range. Using {}.", originalUpdateInterval, updateInterval);
        }
        if (originalOffsetHours != offsetHours) {
            logger.warn("Config value offsetHours={} is out of range. Using {}.", originalOffsetHours, offsetHours);
        }
        if (originalCustomDayLength != customDayLengthMinutes) {
            logger.warn("Config value customDayLengthMinutes={} is out of range. Using {}.", originalCustomDayLength, customDayLengthMinutes);
        }
    }

    private String toFileContent() {
        return "# RealtimeSync configuration\n"
                + "# enabled: true/false - master switch for the mod.\n"
                + "enabled=" + enabled + "\n\n"
                + "# forceDaylightCycleOff: true/false - keeps Minecraft's vanilla daylight cycle disabled.\n"
                + "forceDaylightCycleOff=" + forceDaylightCycleOff + "\n\n"
                + "# syncAllWorlds: true/false - true syncs overworld, nether, end and custom dimensions.\n"
                + "syncAllWorlds=" + syncAllWorlds + "\n\n"
                + "# updateInterval: ticks between time syncs. 20 ticks = 1 second. Minimum: 1.\n"
                + "updateInterval=" + updateInterval + "\n\n"
                + "# offsetHours: real-time offset from server system time. Range: -23..23.\n"
                + "offsetHours=" + offsetHours + "\n\n"
                + "# customDayLengthMinutes: 0 = real clock sync. Greater than 0 = custom Minecraft day length in real minutes.\n"
                + "customDayLengthMinutes=" + customDayLengthMinutes + "\n\n"
                + "# debugLogging: true/false - enables detailed time sync logs.\n"
                + "debugLogging=" + debugLogging + "\n";
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback, RealtimeLog logger) {
        String rawValue = properties.getProperty(key);
        if (rawValue == null || rawValue.isBlank()) {
            return fallback;
        }

        String normalized = rawValue.trim().toLowerCase();
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
