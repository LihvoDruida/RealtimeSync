package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cross-version world time access.
 *
 * <p>1.21.x exposes day-time through level/level-data APIs, while 26.1.x moved
 * time to the World Clock system. This helper first uses direct/reflection APIs
 * when available, then falls back to a command-backed clock mode for 26.1.x.</p>
 */
public final class RealtimeWorldTime {
    private static final long TICKS_PER_DAY = 24000L;
    private static final ConcurrentMap<Class<?>, TimeAccessor> ACCESSORS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> LEVEL_DATA_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> DIMENSION_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Long> FALLBACK_TIMES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Boolean> CLOCKS_PAUSED = new ConcurrentHashMap<>();

    private RealtimeWorldTime() {
    }

    public static long readOverworldTime(MinecraftServer server) {
        ServerLevel fallback = null;
        for (ServerLevel level : server.getAllLevels()) {
            if (fallback == null) {
                fallback = level;
            }
            if (dimensionId(level).equals("minecraft:overworld")) {
                return readDayTimeOrFallback(level, fallbackTime("minecraft:overworld", 0L));
            }
        }

        if (fallback != null) {
            String id = dimensionId(fallback);
            return readDayTimeOrFallback(fallback, fallbackTime(id, 0L));
        }

        return 0L;
    }

    public static long readDayTimeOrFallback(ServerLevel level, long fallback) {
        try {
            return readDayTime(level);
        } catch (RuntimeException ignored) {
            return fallbackTime(dimensionId(level), fallback);
        }
    }

    public static long readDayTime(ServerLevel level) {
        TimeAccessor accessor = ACCESSORS.computeIfAbsent(level.getClass(), ignored -> resolveAccessor(level));
        return accessor.read(level);
    }

    public static boolean setDayTime(MinecraftServer server, ServerLevel level, long dayTime, RealtimeLog logger) {
        long normalized = Math.floorMod(dayTime, TICKS_PER_DAY);
        String dimensionId = dimensionId(level);

        TimeAccessor accessor = ACCESSORS.get(level.getClass());
        if (accessor == null) {
            try {
                accessor = ACCESSORS.computeIfAbsent(level.getClass(), ignored -> resolveAccessor(level));
            } catch (RuntimeException ignored) {
                accessor = null;
            }
        }

        if (accessor != null) {
            try {
                accessor.write(level, normalized);
                FALLBACK_TIMES.put(dimensionId, normalized);
                return true;
            } catch (RuntimeException ignored) {
                // Fall through to command-backed 26.1 world clock mode.
            }
        }

        if (setClockTime(server, dimensionId, normalized, logger)) {
            FALLBACK_TIMES.put(dimensionId, normalized);
            return true;
        }

        logger.warn("Could not set time for {}: neither level accessors nor world-clock commands are available.", dimensionId);
        return false;
    }

    public static boolean pauseClock(MinecraftServer server, ServerLevel level, RealtimeLog logger) {
        String dimensionId = dimensionId(level);
        String clockId = clockIdForDimension(dimensionId);
        if (CLOCKS_PAUSED.putIfAbsent(clockId, Boolean.TRUE) != null) {
            return true;
        }

        boolean paused = RealtimeCommands.execute(server, "time of " + clockId + " pause", logger);
        if (!paused) {
            CLOCKS_PAUSED.remove(clockId);
        }
        return paused;
    }

    public static String dimensionId(ServerLevel level) {
        Method method = DIMENSION_METHODS.computeIfAbsent(level.getClass(), type -> findZeroArgMethod(type, "dimension"));
        Object dimension = method == null ? null : invoke(method, level);
        return normalizeDimensionId(String.valueOf(dimension));
    }

    static String normalizeDimensionId(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        int registrySeparator = normalized.lastIndexOf(" / ");
        if (registrySeparator >= 0) {
            normalized = normalized.substring(registrySeparator + 3);
        } else {
            int bracket = normalized.lastIndexOf('[');
            if (bracket >= 0 && bracket + 1 < normalized.length()) {
                normalized = normalized.substring(bracket + 1);
            }
        }
        if (normalized.endsWith("]")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isBlank() ? "minecraft:overworld" : normalized;
    }

    private static boolean setClockTime(MinecraftServer server, String dimensionId, long dayTime, RealtimeLog logger) {
        String clockId = clockIdForDimension(dimensionId);
        if (RealtimeCommands.execute(server, "time of " + clockId + " set " + dayTime, logger)) {
            return true;
        }

        // Some dimensions only expose their default clock through the legacy command shape.
        // Keep this as a silent fallback for non-overworld/custom dimensions.
        return RealtimeCommands.execute(server, "time set " + dayTime, logger);
    }

    private static String clockIdForDimension(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return "minecraft:overworld";
        }
        if (dimensionId.equals("minecraft:the_nether") || dimensionId.equals("minecraft:the_end")) {
            return dimensionId;
        }
        return dimensionId;
    }

    private static long fallbackTime(String dimensionId, long fallback) {
        return FALLBACK_TIMES.getOrDefault(dimensionId, Math.floorMod(fallback, TICKS_PER_DAY));
    }

    private static TimeAccessor resolveAccessor(ServerLevel sampleLevel) {
        Method directGet = findGetter(sampleLevel.getClass());
        Method directSet = findSetter(sampleLevel.getClass());
        if (directGet != null && directSet != null) {
            return TimeAccessor.direct(directGet, directSet);
        }

        Method levelDataGetter = resolveLevelDataGetter(sampleLevel.getClass());
        if (levelDataGetter != null) {
            Object levelData = invoke(levelDataGetter, sampleLevel);
            if (levelData != null) {
                Method dataGet = findGetter(levelData.getClass());
                Method dataSet = findSetter(levelData.getClass());
                if (dataGet != null && dataSet != null) {
                    return TimeAccessor.levelData(levelDataGetter, dataGet, dataSet);
                }
            }
        }

        throw new IllegalStateException("Could not resolve a compatible day-time accessor for " + sampleLevel.getClass().getName());
    }

    private static Method resolveLevelDataGetter(Class<?> levelClass) {
        Method cached = LEVEL_DATA_METHODS.get(levelClass);
        if (cached != null) {
            return cached;
        }

        for (String name : new String[] {"getLevelData", "getData", "serverLevelData", "levelData"}) {
            Method method = findZeroArgMethod(levelClass, name);
            if (method != null) {
                LEVEL_DATA_METHODS.put(levelClass, method);
                return method;
            }
        }
        return null;
    }

    private static Method findGetter(Class<?> type) {
        for (String name : new String[] {"getDayTime", "getGameTime", "dayTime", "timeOfDay"}) {
            Method exact = findZeroArgMethod(type, name);
            if (exact != null && isNumericReturn(exact)) {
                return exact;
            }
        }

        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0 || !isNumericReturn(method)) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            if ((name.contains("day") && name.contains("time")) || name.contains("clock")) {
                method.setAccessible(true);
                return method;
            }
        }

        return null;
    }

    private static Method findSetter(Class<?> type) {
        for (String name : new String[] {"setDayTime", "setGameTime", "setTimeOfDay", "setClockTime"}) {
            Method exact = findOneArgMethod(type, name);
            if (exact != null && acceptsLongLike(exact.getParameterTypes()[0])) {
                return exact;
            }
        }

        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 1 || !acceptsLongLike(method.getParameterTypes()[0])) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("set") && ((name.contains("day") && name.contains("time")) || name.contains("clock"))) {
                method.setAccessible(true);
                return method;
            }
        }

        return null;
    }

    private static Method findZeroArgMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static Method findOneArgMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static boolean isNumericReturn(Method method) {
        Class<?> returnType = method.getReturnType();
        return returnType == long.class || returnType == int.class || Number.class.isAssignableFrom(returnType);
    }

    private static boolean acceptsLongLike(Class<?> type) {
        return type == long.class || type == Long.class || type == int.class || type == Integer.class;
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalStateException("Failed to invoke time accessor " + method, exception);
        }
    }

    private static final class TimeAccessor {
        private final Method directGet;
        private final Method directSet;
        private final Method levelDataGetter;
        private final Method levelDataGet;
        private final Method levelDataSet;

        private TimeAccessor(Method directGet, Method directSet, Method levelDataGetter, Method levelDataGet, Method levelDataSet) {
            this.directGet = directGet;
            this.directSet = directSet;
            this.levelDataGetter = levelDataGetter;
            this.levelDataGet = levelDataGet;
            this.levelDataSet = levelDataSet;
        }

        static TimeAccessor direct(Method get, Method set) {
            return new TimeAccessor(get, set, null, null, null);
        }

        static TimeAccessor levelData(Method levelDataGetter, Method get, Method set) {
            return new TimeAccessor(null, null, levelDataGetter, get, set);
        }

        long read(ServerLevel level) {
            Object value;
            if (directGet != null) {
                value = invoke(directGet, level);
            } else {
                Object levelData = invoke(levelDataGetter, level);
                value = invoke(levelDataGet, levelData);
            }

            if (value instanceof Number number) {
                return number.longValue();
            }
            throw new IllegalStateException("Day-time getter returned non-numeric value: " + value);
        }

        void write(ServerLevel level, long dayTime) {
            if (directSet != null) {
                invoke(directSet, level, convertArgument(directSet, dayTime));
                return;
            }

            Object levelData = invoke(levelDataGetter, level);
            invoke(levelDataSet, levelData, convertArgument(levelDataSet, dayTime));
        }

        private Object convertArgument(Method method, long dayTime) {
            Class<?> parameterType = method.getParameterTypes()[0];
            if (parameterType == int.class || parameterType == Integer.class) {
                return (int) dayTime;
            }
            return dayTime;
        }
    }
}
