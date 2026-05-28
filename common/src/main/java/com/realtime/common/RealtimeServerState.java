package com.realtime.common;

import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Small reflective helpers for MinecraftServer API points that moved across versions. */
public final class RealtimeServerState {
    public static final long UNKNOWN_TICK = Long.MIN_VALUE;

    private static final ConcurrentMap<Class<?>, Method> TICK_METHODS = new ConcurrentHashMap<>();

    private RealtimeServerState() {
    }

    public static long tickCount(MinecraftServer server) {
        Method method = TICK_METHODS.computeIfAbsent(server.getClass(), RealtimeServerState::findTickMethod);
        if (method == null) {
            return UNKNOWN_TICK;
        }

        try {
            Object value = method.invoke(server);
            if (value instanceof Number number) {
                return number.longValue();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return UNKNOWN_TICK;
        }

        return UNKNOWN_TICK;
    }

    private static Method findTickMethod(Class<?> serverClass) {
        for (String methodName : new String[]{"getTickCount", "getTicks"}) {
            for (Method method : serverClass.getMethods()) {
                if (method.getName().equals(methodName) && method.getParameterCount() == 0 && RealtimeReflection.isNumeric(method.getReturnType())) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

}
