package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Locale;

/** Safe Minecraft 1.21.x access to absolute world day-time and dimension identifiers. */
public final class RealtimeWorldTime {
    private static final DimensionIdAccess DIMENSION_ACCESS = new ProfileDimensionIdAccess();

    private RealtimeWorldTime() {
    }

    public static void resetRuntimeState() {
        // Profile adapters are stateless. Kept as a lifecycle hook for future adapters.
    }

    public static String dimensionAdapterName() {
        return DIMENSION_ACCESS.adapterName();
    }

    public static long readOverworldTime(MinecraftServer server) {
        ServerLevel fallback = null;
        for (ServerLevel level : server.getAllLevels()) {
            if (fallback == null) {
                fallback = level;
            }
            String dimensionId = dimensionId(level);
            if ("minecraft:overworld".equals(dimensionId)) {
                return readDayTime(level);
            }
        }
        return fallback == null ? 0L : readDayTime(fallback);
    }

    public static long readDayTime(ServerLevel level) {
        return level.getDayTime();
    }

    public static long readDayTimeOrFallback(ServerLevel level, long fallback) {
        try {
            return readDayTime(level);
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    public static boolean setDayTime(ServerLevel level, long absoluteDayTime, RealtimeLog logger) {
        try {
            if (level.getDayTime() != absoluteDayTime) {
                level.setDayTime(absoluteDayTime);
            }
            return true;
        } catch (RuntimeException exception) {
            logger.warn("Could not set absolute dayTime for {}: {}", safeDimensionLabel(level), exception.toString());
            return false;
        }
    }

    /** Returns a validated namespaced dimension identifier, or {@code null} when unavailable. */
    public static String dimensionId(ServerLevel level) {
        try {
            return normalizeIdentifier(DIMENSION_ACCESS.dimensionId(level));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static String normalizeIdentifier(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? value : null;
    }

    private static String safeDimensionLabel(ServerLevel level) {
        String dimensionId = dimensionId(level);
        return dimensionId == null ? "<unresolved-dimension>" : dimensionId;
    }
}
