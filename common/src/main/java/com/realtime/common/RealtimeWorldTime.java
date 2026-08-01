package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.IdentityHashMap;
import java.util.Map;

/** Safe Minecraft 1.21.x access to absolute world day-time and dimension identifiers. */
public final class RealtimeWorldTime {
    private static final DimensionIdAccess DIMENSION_ACCESS = new ProfileDimensionIdAccess();
    private static final String OVERWORLD_DIMENSION_ID = "minecraft:overworld";
    private static final String UNRESOLVED = "";
    private static final int MAX_CACHED_DIMENSIONS = 256;

    /**
     * Resolving a dimension identifier allocates a ResourceLocation string and validates it.
     * The result never changes for a given level instance, so it is cached by identity and
     * cleared on every server lifecycle transition.
     */
    private static final Map<ServerLevel, String> DIMENSION_ID_CACHE = new IdentityHashMap<>();

    private RealtimeWorldTime() {
    }

    public static void resetRuntimeState() {
        DIMENSION_ID_CACHE.clear();
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
            if (OVERWORLD_DIMENSION_ID.equals(dimensionId(level))) {
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
        String cached = DIMENSION_ID_CACHE.get(level);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        String resolved;
        try {
            resolved = RealtimeIdentifiers.normalize(DIMENSION_ACCESS.dimensionId(level));
        } catch (RuntimeException exception) {
            resolved = null;
        }

        if (DIMENSION_ID_CACHE.size() >= MAX_CACHED_DIMENSIONS) {
            // Defensive bound for servers that create and discard dimensions at runtime.
            DIMENSION_ID_CACHE.clear();
        }
        DIMENSION_ID_CACHE.put(level, resolved == null ? UNRESOLVED : resolved);
        return resolved;
    }

    static String normalizeIdentifier(String raw) {
        return RealtimeIdentifiers.normalize(raw);
    }

    private static String safeDimensionLabel(ServerLevel level) {
        String dimensionId = dimensionId(level);
        return dimensionId == null ? "<unresolved-dimension>" : dimensionId;
    }
}
