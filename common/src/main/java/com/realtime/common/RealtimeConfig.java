package com.realtime.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** UTF-8, atomically-saved configuration with explicit legacy migration. */
public final class RealtimeConfig {
    public static final String SYNC_MODE_INSTANT = "instant";
    public static final String SYNC_MODE_SMOOTH = "smooth";

    public static final String DAYLIGHT_POLICY_MANAGED = "MANAGED";
    public static final String DAYLIGHT_POLICY_REQUIRE_OFF = "REQUIRE_OFF";
    public static final String DAYLIGHT_POLICY_IGNORE = "IGNORE";

    public static final String DAY_PROGRESSION_PRESERVE_MONOTONIC = "PRESERVE_MONOTONIC";
    public static final String DAY_PROGRESSION_PRESERVE_CURRENT_DAY = "PRESERVE_CURRENT_DAY";
    public static final String DAY_PROGRESSION_REAL_DATE_ANCHOR = "REAL_DATE_ANCHOR";

    public static final String LARGE_JUMP_GRADUAL = "GRADUAL";
    public static final String LARGE_JUMP_SNAP = "SNAP";
    public static final String LARGE_JUMP_PAUSE_AND_WARN = "PAUSE_AND_WARN";

    /** Sleeping never changes the clock; the world stays pinned to real time. */
    public static final String SLEEP_POLICY_REALTIME_ONLY = "REALTIME_ONLY";
    /** Vanilla performs the skip and the next update pulls the clock straight back to real time. */
    public static final String SLEEP_POLICY_VANILLA = "VANILLA";
    /** Vanilla performs the skip, the mod adopts it and realigns forward over a configured window. */
    public static final String SLEEP_POLICY_REALIGN = "REALIGN";

    public static final String CUSTOM_RESTART_CONTINUE_FROM_WORLD = "CONTINUE_FROM_WORLD";
    public static final String CUSTOM_RESTART_RESET_TO_CONFIGURED_TIME = "RESET_TO_CONFIGURED_TIME";
    public static final String CUSTOM_RESTART_PERSIST_REAL_ELAPSED = "PERSIST_REAL_ELAPSED";

    private static final int MIN_UPDATE_INTERVAL_TICKS = 1;
    private static final int MAX_UPDATE_INTERVAL_TICKS = 20 * 60 * 30;
    private static final int MAX_CUSTOM_DAY_LENGTH_MINUTES = 60 * 24 * 7;
    private static final int MAX_OFFSET_MINUTES = 60 * 24 * 14;
    private static final int MAX_CORRECTION_TICKS_PER_SECOND = 24000;
    private static final int MAX_SNAP_THRESHOLD_TICKS = 12000;
    private static final int MAX_CATCHUP_DIVISOR = 24000;
    private static final int MAX_OFFLINE_CATCHUP_SECONDS = 60 * 60 * 24;
    private static final int MIN_SLEEP_REALIGN_MINUTES = 1;
    private static final int MAX_SLEEP_REALIGN_MINUTES = 60 * 48;

    private static final Map<String, String> TIME_ZONE_ALIASES = Map.of(
            "Europe/Kiev", "Europe/Kyiv"
    );

    private static final Set<String> LEGACY_TOML_KEYS = Set.of(
            "enabled", "syncAllWorlds", "syncDimensions", "ignoredDimensions", "syncMode",
            "daylightRulePolicy", "forceDaylightCycleOff", "dayProgressionPolicy",
            "zoneId", "timeOffsetMinutes", "offsetHours", "realDateAnchor",
            "smoothMaxCorrectionTicksPerSecond", "maxSmoothStepTicks", "smoothSnapThresholdTicks",
            "smoothCatchupDivisor", "smoothLargeJumpPolicy", "maximumOfflineCatchUpSeconds",
            "respectSleep", "overrideSleepTime", "sleepPolicy", "sleepRealignMinutes",
            "updateInterval", "customDayLengthMinutes",
            "minutesPerMinecraftDay", "customClockRestartPolicy", "debugLogging",
            "debugPerformanceLogging"
    );

    public boolean enabled = true;
    public boolean syncAllWorlds = false;
    public String syncDimensions = "minecraft:overworld";
    public String ignoredDimensions = "";
    public String syncMode = SYNC_MODE_SMOOTH;
    public String daylightRulePolicy = DAYLIGHT_POLICY_MANAGED;
    public String dayProgressionPolicy = DAY_PROGRESSION_PRESERVE_MONOTONIC;
    public String zoneId = "system";
    public int timeOffsetMinutes = 0;
    public String realDateAnchor = "1970-01-01";
    public int smoothMaxCorrectionTicksPerSecond = 1200;
    public int smoothSnapThresholdTicks = 20;
    public int smoothCatchupDivisor = 240;
    public String smoothLargeJumpPolicy = LARGE_JUMP_GRADUAL;
    public int maximumOfflineCatchUpSeconds = 300;
    public boolean respectSleep = true;
    public boolean overrideSleepTime = false;
    public String sleepPolicy = SLEEP_POLICY_REALIGN;
    public int sleepRealignMinutes = 360;
    public int updateInterval = 20;
    public int customDayLengthMinutes = 0;
    public String customClockRestartPolicy = CUSTOM_RESTART_CONTINUE_FROM_WORLD;
    public boolean debugLogging = false;
    public boolean debugPerformanceLogging = false;

    private Set<String> syncDimensionSet = Collections.emptySet();
    private Set<String> ignoredDimensionSet = Collections.emptySet();
    private ZoneId resolvedZoneId = ZoneId.systemDefault();
    private LocalDate resolvedRealDateAnchor = LocalDate.of(1970, 1, 1);

    private boolean migrationRequired;

    public static RealtimeConfig defaults(RealtimeLog logger) {
        RealtimeConfig defaults = new RealtimeConfig();
        defaults.validate(logger);
        return defaults;
    }

    public static RealtimeConfig loadOrCreate(Path path, Path legacyTomlPath, RealtimeLog logger) {
        if (!Files.exists(path) && legacyTomlPath != null && Files.exists(legacyTomlPath)) {
            try {
                RealtimeConfig migrated = fromProperties(readLegacyToml(legacyTomlPath, logger), logger);
                if (!migrated.save(path, logger)) {
                    throw new IOException("could not write realtime.properties");
                }
                boolean backupCreated = backupLegacy(legacyTomlPath, logger);
                logger.info(backupCreated
                        ? "Migrated legacy realtime.toml to UTF-8 realtime.properties and created realtime.toml.bak."
                        : "Migrated legacy realtime.toml to UTF-8 realtime.properties; the original realtime.toml was retained but a .bak copy could not be created.");
                return migrated;
            } catch (IOException | RuntimeException exception) {
                logger.warn("Legacy realtime.toml migration failed; the original file was left untouched. {}", exception.getMessage());
            }
        }

        if (!Files.exists(path)) {
            RealtimeConfig defaults = defaults(logger);
            defaults.save(path, logger);
            return defaults;
        }

        try {
            RealtimeConfig loaded = loadExisting(path, logger);
            loaded.saveCanonicalMigrationIfNeeded(path, logger);
            return loaded;
        } catch (IOException | RuntimeException exception) {
            logger.warn("Failed to read RealtimeSync config; safe defaults are used for initial startup. {}", exception.getMessage());
            RealtimeConfig defaults = defaults(logger);
            return defaults;
        }
    }

    /** Reloads an existing file without replacing the last known-good configuration on failure. */
    public static RealtimeConfig reload(Path path, RealtimeConfig current, RealtimeLog logger) {
        try {
            RealtimeConfig loaded = loadExisting(path, logger);
            loaded.saveCanonicalMigrationIfNeeded(path, logger);
            return loaded;
        } catch (IOException | RuntimeException exception) {
            logger.warn("RealtimeSync config reload failed; keeping the last known-good configuration. {}", exception.getMessage());
            return current;
        }
    }

    private static RealtimeConfig loadExisting(Path path, RealtimeLog logger) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return fromProperties(properties, logger);
    }

    private static RealtimeConfig fromProperties(Properties properties, RealtimeLog logger) {
        RealtimeConfig config = new RealtimeConfig();
        config.enabled = readBoolean(properties, "enabled", config.enabled, logger);
        config.syncAllWorlds = readBoolean(properties, "syncAllWorlds", config.syncAllWorlds, logger);
        config.syncDimensions = readString(properties, "syncDimensions", config.syncDimensions);
        config.ignoredDimensions = readString(properties, "ignoredDimensions", config.ignoredDimensions);
        config.syncMode = readString(properties, "syncMode", config.syncMode);
        config.daylightRulePolicy = readString(properties, "daylightRulePolicy", config.daylightRulePolicy);
        if (!properties.containsKey("daylightRulePolicy") && properties.containsKey("forceDaylightCycleOff")) {
            boolean oldValue = readBoolean(properties, "forceDaylightCycleOff", true, logger);
            config.daylightRulePolicy = oldValue ? DAYLIGHT_POLICY_MANAGED : DAYLIGHT_POLICY_IGNORE;
            config.migrationRequired = true;
            logger.warn("Config key forceDaylightCycleOff is deprecated; migrated in memory to daylightRulePolicy={}.", config.daylightRulePolicy);
        }
        config.dayProgressionPolicy = readString(properties, "dayProgressionPolicy", config.dayProgressionPolicy);
        config.zoneId = readString(properties, "zoneId", config.zoneId);
        String canonicalZoneId = TIME_ZONE_ALIASES.get(config.zoneId);
        if (canonicalZoneId != null) {
            logger.warn("Config timezone alias {} is deprecated; migrated to {}.", config.zoneId, canonicalZoneId);
            config.zoneId = canonicalZoneId;
            config.migrationRequired = true;
        }
        config.timeOffsetMinutes = readInt(properties, "timeOffsetMinutes", config.timeOffsetMinutes, logger);
        if (!properties.containsKey("timeOffsetMinutes") && properties.containsKey("offsetHours")) {
            int oldHours = readInt(properties, "offsetHours", 0, logger);
            long oldMinutes = oldHours * 60L;
            config.timeOffsetMinutes = oldMinutes > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : oldMinutes < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) oldMinutes;
            config.migrationRequired = true;
            logger.warn("Config key offsetHours is deprecated; migrated in memory to timeOffsetMinutes={}.", config.timeOffsetMinutes);
        }
        config.realDateAnchor = readString(properties, "realDateAnchor", config.realDateAnchor);
        config.smoothMaxCorrectionTicksPerSecond = readInt(properties, "smoothMaxCorrectionTicksPerSecond", config.smoothMaxCorrectionTicksPerSecond, logger);
        if (!properties.containsKey("smoothMaxCorrectionTicksPerSecond") && properties.containsKey("maxSmoothStepTicks")) {
            int oldStep = readInt(properties, "maxSmoothStepTicks", 12, logger);
            int oldInterval = readInt(properties, "updateInterval", config.updateInterval, logger);
            double updatesPerSecond = 20.0D / Math.max(1, oldInterval);
            config.smoothMaxCorrectionTicksPerSecond = Math.max(1, (int) Math.round(oldStep * updatesPerSecond));
            config.migrationRequired = true;
            logger.warn("Config key maxSmoothStepTicks={} with updateInterval={} is deprecated; migrated to smoothMaxCorrectionTicksPerSecond={}.", oldStep, oldInterval, config.smoothMaxCorrectionTicksPerSecond);
        }
        config.smoothSnapThresholdTicks = readInt(properties, "smoothSnapThresholdTicks", config.smoothSnapThresholdTicks, logger);
        config.smoothCatchupDivisor = readInt(properties, "smoothCatchupDivisor", config.smoothCatchupDivisor, logger);
        config.smoothLargeJumpPolicy = readString(properties, "smoothLargeJumpPolicy", config.smoothLargeJumpPolicy);
        config.maximumOfflineCatchUpSeconds = readInt(properties, "maximumOfflineCatchUpSeconds", config.maximumOfflineCatchUpSeconds, logger);
        config.sleepPolicy = readString(properties, "sleepPolicy", config.sleepPolicy);
        config.sleepRealignMinutes = readInt(properties, "sleepRealignMinutes", config.sleepRealignMinutes, logger);
        config.respectSleep = readBoolean(properties, "respectSleep", config.respectSleep, logger);
        config.overrideSleepTime = readBoolean(properties, "overrideSleepTime", config.overrideSleepTime, logger);
        config.updateInterval = readInt(properties, "updateInterval", config.updateInterval, logger);
        config.customDayLengthMinutes = readInt(properties, "customDayLengthMinutes", config.customDayLengthMinutes, logger);
        config.customDayLengthMinutes = readCustomDayLengthAlias(properties, config.customDayLengthMinutes, logger);
        if (!properties.containsKey("customDayLengthMinutes") && properties.containsKey("minutesPerMinecraftDay")) {
            config.migrationRequired = true;
        }
        config.customClockRestartPolicy = readString(properties, "customClockRestartPolicy", config.customClockRestartPolicy);
        config.debugLogging = readBoolean(properties, "debugLogging", config.debugLogging, logger);
        config.debugPerformanceLogging = readBoolean(properties, "debugPerformanceLogging", config.debugPerformanceLogging, logger);
        config.validate(logger);
        return config;
    }

    private void saveCanonicalMigrationIfNeeded(Path path, RealtimeLog logger) {
        if (!migrationRequired) {
            return;
        }
        if (save(path, logger)) {
            migrationRequired = false;
            logger.info("Rewrote deprecated RealtimeSync config keys to the canonical UTF-8 format.");
        }
    }

    public boolean save(Path path, RealtimeLog logger) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(temporary, toFileContent(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            logger.warn("Failed to atomically save RealtimeSync config. {}", exception.getMessage());
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best effort cleanup only.
            }
            return false;
        }
    }

    /**
     * Resolves the sleep behaviour actually in force. The legacy {@code respectSleep} and
     * {@code overrideSleepTime} switches keep working and win over {@code sleepPolicy}.
     */
    public String effectiveSleepPolicy() {
        if (!respectSleep || overrideSleepTime) {
            return SLEEP_POLICY_REALTIME_ONLY;
        }
        return sleepPolicy;
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

    public ZoneId resolvedZoneId() {
        return resolvedZoneId;
    }

    public LocalDate resolvedRealDateAnchor() {
        return resolvedRealDateAnchor;
    }

    private void validate(RealtimeLog logger) {
        updateInterval = clampWithWarning("updateInterval", updateInterval, MIN_UPDATE_INTERVAL_TICKS, MAX_UPDATE_INTERVAL_TICKS, logger);
        timeOffsetMinutes = clampWithWarning("timeOffsetMinutes", timeOffsetMinutes, -MAX_OFFSET_MINUTES, MAX_OFFSET_MINUTES, logger);
        customDayLengthMinutes = clampWithWarning("customDayLengthMinutes", customDayLengthMinutes, 0, MAX_CUSTOM_DAY_LENGTH_MINUTES, logger);
        smoothMaxCorrectionTicksPerSecond = clampWithWarning("smoothMaxCorrectionTicksPerSecond", smoothMaxCorrectionTicksPerSecond, 1, MAX_CORRECTION_TICKS_PER_SECOND, logger);
        smoothSnapThresholdTicks = clampWithWarning("smoothSnapThresholdTicks", smoothSnapThresholdTicks, 0, MAX_SNAP_THRESHOLD_TICKS, logger);
        smoothCatchupDivisor = clampWithWarning("smoothCatchupDivisor", smoothCatchupDivisor, 1, MAX_CATCHUP_DIVISOR, logger);
        maximumOfflineCatchUpSeconds = clampWithWarning("maximumOfflineCatchUpSeconds", maximumOfflineCatchUpSeconds, 1, MAX_OFFLINE_CATCHUP_SECONDS, logger);
        sleepRealignMinutes = clampWithWarning("sleepRealignMinutes", sleepRealignMinutes, MIN_SLEEP_REALIGN_MINUTES, MAX_SLEEP_REALIGN_MINUTES, logger);

        syncMode = normalizeEnum("syncMode", syncMode, Set.of(SYNC_MODE_INSTANT, SYNC_MODE_SMOOTH), SYNC_MODE_SMOOTH, false, logger);
        daylightRulePolicy = normalizeEnum("daylightRulePolicy", daylightRulePolicy, Set.of(DAYLIGHT_POLICY_MANAGED, DAYLIGHT_POLICY_REQUIRE_OFF, DAYLIGHT_POLICY_IGNORE), DAYLIGHT_POLICY_MANAGED, true, logger);
        dayProgressionPolicy = normalizeEnum("dayProgressionPolicy", dayProgressionPolicy, Set.of(DAY_PROGRESSION_PRESERVE_MONOTONIC, DAY_PROGRESSION_PRESERVE_CURRENT_DAY, DAY_PROGRESSION_REAL_DATE_ANCHOR), DAY_PROGRESSION_PRESERVE_MONOTONIC, true, logger);
        smoothLargeJumpPolicy = normalizeEnum("smoothLargeJumpPolicy", smoothLargeJumpPolicy, Set.of(LARGE_JUMP_GRADUAL, LARGE_JUMP_SNAP, LARGE_JUMP_PAUSE_AND_WARN), LARGE_JUMP_GRADUAL, true, logger);
        sleepPolicy = normalizeEnum("sleepPolicy", sleepPolicy, Set.of(SLEEP_POLICY_REALTIME_ONLY, SLEEP_POLICY_VANILLA, SLEEP_POLICY_REALIGN), SLEEP_POLICY_REALIGN, true, logger);
        customClockRestartPolicy = normalizeEnum("customClockRestartPolicy", customClockRestartPolicy, Set.of(CUSTOM_RESTART_CONTINUE_FROM_WORLD, CUSTOM_RESTART_RESET_TO_CONFIGURED_TIME, CUSTOM_RESTART_PERSIST_REAL_ELAPSED), CUSTOM_RESTART_CONTINUE_FROM_WORLD, true, logger);

        try {
            resolvedZoneId = "system".equalsIgnoreCase(zoneId.trim()) ? ZoneId.systemDefault() : ZoneId.of(zoneId.trim());
            zoneId = "system".equalsIgnoreCase(zoneId.trim()) ? "system" : resolvedZoneId.getId();
        } catch (DateTimeException exception) {
            logger.warn("Config value zoneId={} is invalid. Using system timezone {}.", zoneId, ZoneId.systemDefault().getId());
            zoneId = "system";
            resolvedZoneId = ZoneId.systemDefault();
        }

        try {
            resolvedRealDateAnchor = LocalDate.parse(realDateAnchor.trim());
            realDateAnchor = resolvedRealDateAnchor.toString();
        } catch (DateTimeException exception) {
            logger.warn("Config value realDateAnchor={} is invalid. Using 1970-01-01.", realDateAnchor);
            resolvedRealDateAnchor = LocalDate.of(1970, 1, 1);
            realDateAnchor = resolvedRealDateAnchor.toString();
        }

        syncDimensionSet = parseDimensionSet(syncDimensions, "syncDimensions", logger);
        ignoredDimensionSet = parseDimensionSet(ignoredDimensions, "ignoredDimensions", logger);
        syncDimensions = String.join(",", syncDimensionSet);
        ignoredDimensions = String.join(",", ignoredDimensionSet);

        if (overrideSleepTime && respectSleep) {
            logger.warn("Both respectSleep=true and overrideSleepTime=true are set; overrideSleepTime takes precedence and sleepPolicy is treated as {}.", SLEEP_POLICY_REALTIME_ONLY);
        }

        if (SLEEP_POLICY_REALIGN.equals(effectiveSleepPolicy()) && customDayLengthMinutes > 0) {
            logger.warn("sleepPolicy={} has no effect while customDayLengthMinutes={} drives the clock; sleep skips are ignored in custom-day-length mode.",
                    SLEEP_POLICY_REALIGN, customDayLengthMinutes);
        }

        if (SLEEP_POLICY_REALIGN.equals(effectiveSleepPolicy())
                && DAY_PROGRESSION_REAL_DATE_ANCHOR.equals(dayProgressionPolicy)) {
            logger.warn("sleepPolicy={} shifts only the time of day. dayProgressionPolicy={} pins the day index to the real calendar, so the Minecraft day may roll over while the sleep offset is active.",
                    SLEEP_POLICY_REALIGN, DAY_PROGRESSION_REAL_DATE_ANCHOR);
        }

        if (syncAllWorlds && !syncDimensionSet.isEmpty()) {
            logger.warn("syncAllWorlds=true is ignored because syncDimensions is not empty. Only {} will be synchronized. Clear syncDimensions to synchronize every dimension.",
                    syncDimensions);
        }
    }

    private String toFileContent() {
        return "# RealtimeSync configuration (UTF-8)\n"
                + "# Existing offsetHours, maxSmoothStepTicks and forceDaylightCycleOff keys are migrated on load.\n\n"
                + "enabled=" + enabled + "\n"
                + "daylightRulePolicy=" + daylightRulePolicy + "\n"
                + "dayProgressionPolicy=" + dayProgressionPolicy + "\n"
                + "zoneId=" + zoneId + "\n"
                + "timeOffsetMinutes=" + timeOffsetMinutes + "\n"
                + "realDateAnchor=" + realDateAnchor + "\n\n"
                + "syncAllWorlds=" + syncAllWorlds + "\n"
                + "syncDimensions=" + syncDimensions + "\n"
                + "ignoredDimensions=" + ignoredDimensions + "\n"
                + "syncMode=" + syncMode + "\n"
                + "updateInterval=" + updateInterval + "\n\n"
                + "smoothMaxCorrectionTicksPerSecond=" + smoothMaxCorrectionTicksPerSecond + "\n"
                + "smoothSnapThresholdTicks=" + smoothSnapThresholdTicks + "\n"
                + "smoothCatchupDivisor=" + smoothCatchupDivisor + "\n"
                + "smoothLargeJumpPolicy=" + smoothLargeJumpPolicy + "\n"
                + "maximumOfflineCatchUpSeconds=" + maximumOfflineCatchUpSeconds + "\n\n"
                + "customDayLengthMinutes=" + customDayLengthMinutes + "\n"
                + "customClockRestartPolicy=" + customClockRestartPolicy + "\n"
                + "respectSleep=" + respectSleep + "\n"
                + "overrideSleepTime=" + overrideSleepTime + "\n"
                + "sleepPolicy=" + sleepPolicy + "\n"
                + "sleepRealignMinutes=" + sleepRealignMinutes + "\n"
                + "debugLogging=" + debugLogging + "\n"
                + "debugPerformanceLogging=" + debugPerformanceLogging + "\n";
    }

    private static Properties readLegacyToml(Path path, RealtimeLog logger) throws IOException {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = stripTomlComment(line).trim();
                if (trimmed.isEmpty() || trimmed.startsWith("[")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    logger.warn("Ignoring unsupported legacy TOML line {}.", lineNumber);
                    continue;
                }
                String key = trimmed.substring(0, separator).trim();
                String value = unquote(trimmed.substring(separator + 1).trim());
                if (!key.matches("[A-Za-z0-9_.-]+") || !LEGACY_TOML_KEYS.contains(key)) {
                    logger.warn("Ignoring unsupported legacy TOML key {} on line {}.", key, lineNumber);
                    continue;
                }
                properties.setProperty(key, value);
            }
        }
        return properties;
    }

    private static boolean backupLegacy(Path legacyPath, RealtimeLog logger) {
        Path backup = legacyPath.resolveSibling(legacyPath.getFileName() + ".bak");
        try {
            if (!Files.exists(backup)) {
                Files.copy(legacyPath, backup);
            }
            return true;
        } catch (IOException exception) {
            logger.warn("Could not create legacy config backup {}. {}", backup.getFileName(), exception.getMessage());
            return false;
        }
    }

    private static String stripTomlComment(String line) {
        boolean inQuote = false;
        char quote = 0;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if ((current == '\'' || current == '"') && (index == 0 || line.charAt(index - 1) != '\\')) {
                if (!inQuote) {
                    inQuote = true;
                    quote = current;
                } else if (quote == current) {
                    inQuote = false;
                }
            } else if (current == '#' && !inQuote) {
                return line.substring(0, index);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback, RealtimeLog logger) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value.trim())) {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim())) {
            return false;
        }
        logger.warn("Config value {}={} is not a boolean. Using {}.", key, value, fallback);
        return fallback;
    }

    private static int readInt(Properties properties, String key, int fallback, RealtimeLog logger) {
        String value = properties.getProperty(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            logger.warn("Config value {}={} is not an integer. Using {}.", key, value, fallback);
            return fallback;
        }
    }

    private static String readString(Properties properties, String key, String fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : value.trim();
    }

    private static int readCustomDayLengthAlias(Properties properties, int fallback, RealtimeLog logger) {
        String alias = properties.getProperty("minutesPerMinecraftDay");
        if (alias == null || properties.containsKey("customDayLengthMinutes")) {
            return fallback;
        }
        try {
            logger.warn("Config key minutesPerMinecraftDay is deprecated; use customDayLengthMinutes.");
            return Integer.parseInt(alias.trim());
        } catch (NumberFormatException exception) {
            logger.warn("Config value minutesPerMinecraftDay={} is not an integer. Using {}.", alias, fallback);
            return fallback;
        }
    }

    private static Set<String> parseDimensionSet(String raw, String key, RealtimeLog logger) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String entry : raw.split(",")) {
            String normalized = normalizeDimensionIdentifier(entry);
            if (normalized == null) {
                logger.warn("Ignoring invalid dimension identifier {} in {}.", entry.trim(), key);
            } else {
                result.add(normalized);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    static String normalizeDimensionIdentifier(String raw) {
        return RealtimeIdentifiers.normalize(raw);
    }

    private static String normalizeEnum(String key, String value, Set<String> supported, String fallback, boolean uppercase, RealtimeLog logger) {
        String normalized = value == null ? "" : value.trim();
        normalized = uppercase ? normalized.toUpperCase(Locale.ROOT) : normalized.toLowerCase(Locale.ROOT);
        if (supported.contains(normalized)) {
            return normalized;
        }
        logger.warn("Config value {}={} is invalid. Using {}.", key, value, fallback);
        return fallback;
    }

    private static int clampWithWarning(String key, int value, int minimum, int maximum, RealtimeLog logger) {
        int clamped = Math.max(minimum, Math.min(maximum, value));
        if (clamped != value) {
            logger.warn("Config value {}={} is out of range. Using {}.", key, value, clamped);
        }
        return clamped;
    }
}
