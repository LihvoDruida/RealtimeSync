package com.realtime.common;

import java.lang.reflect.Method;

/** Shared reflection helpers for Minecraft APIs that move between mappings/loaders. */
final class RealtimeReflection {
    private RealtimeReflection() {
    }

    static Method findZeroArgMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    static Method findOneArgMethod(Class<?> type, String name) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    static boolean isNumeric(Class<?> type) {
        return type == int.class || type == long.class || Number.class.isAssignableFrom(type);
    }

    static boolean acceptsLongLike(Class<?> type) {
        return type == long.class || type == Long.class || type == int.class || type == Integer.class;
    }

    static boolean acceptsBooleanValue(Class<?> type) {
        return type == boolean.class || type == Boolean.class || type.isAssignableFrom(Boolean.class);
    }
}
