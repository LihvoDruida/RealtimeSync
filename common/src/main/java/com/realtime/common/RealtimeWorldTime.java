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
 * <p>The 1.21.x and 26.1.x mapped APIs do not expose the same convenience methods on
 * {@link ServerLevel}. This class deliberately avoids compile-time calls such as
 * {@code ServerLevel#getDayTime()} and {@code ServerLevel#setDayTime(long)}. It resolves the
 * available public API once per runtime class and then reuses the cached accessor.</p>
 */
public final class RealtimeWorldTime {
    private static final long TICKS_PER_DAY = 24000L;
    private static final ConcurrentMap<Class<?>, TimeAccessor> ACCESSORS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> LEVEL_DATA_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> DIMENSION_METHODS = new ConcurrentHashMap<>();

    private RealtimeWorldTime() {
    }

    public static long readOverworldTime(MinecraftServer server) {
        ServerLevel fallback = null;
        for (ServerLevel level : server.getAllLevels()) {
            if (fallback == null) {
                fallback = level;
            }
            if (dimensionId(level).equals("minecraft:overworld")) {
                return readDayTime(level);
            }
        }

        if (fallback != null) {
            return readDayTime(fallback);
        }

        return 0L;
    }

    public static long readDayTime(ServerLevel level) {
        TimeAccessor accessor = ACCESSORS.computeIfAbsent(level.getClass(), ignored -> resolveAccessor(level));
        return accessor.read(level);
    }

    public static void setDayTime(ServerLevel level, long dayTime) {
        TimeAccessor accessor = ACCESSORS.computeIfAbsent(level.getClass(), ignored -> resolveAccessor(level));
        accessor.write(level, Math.floorMod(dayTime, TICKS_PER_DAY));
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
        return normalized;
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

        Method exact = findZeroArgMethod(levelClass, "getLevelData");
        if (exact != null) {
            LEVEL_DATA_METHODS.put(levelClass, exact);
            return exact;
        }

        Method fallback = findZeroArgMethod(levelClass, "getData");
        if (fallback != null) {
            LEVEL_DATA_METHODS.put(levelClass, fallback);
        }
        return fallback;
    }

    private static Method findGetter(Class<?> type) {
        Method exact = findZeroArgMethod(type, "getDayTime");
        if (exact != null && isNumericReturn(exact)) {
            return exact;
        }

        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0 || !isNumericReturn(method)) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (name.contains("day") && name.contains("time")) {
                method.setAccessible(true);
                return method;
            }
        }

        return null;
    }

    private static Method findSetter(Class<?> type) {
        Method exact = findOneArgMethod(type, "setDayTime");
        if (exact != null && acceptsLongLike(exact.getParameterTypes()[0])) {
            return exact;
        }

        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 1 || !acceptsLongLike(method.getParameterTypes()[0])) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (name.startsWith("set") && name.contains("day") && name.contains("time")) {
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
