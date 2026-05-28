package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Cross-version access to world/server helpers that are unstable between Minecraft lines. */
public final class RealtimeWorldAccess {
    private static final ConcurrentMap<Class<?>, Method> SERVER_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> GAME_RULES_METHODS = new ConcurrentHashMap<>();

    private RealtimeWorldAccess() {
    }

    public static MinecraftServer server(ServerLevel level) {
        Method method = SERVER_METHODS.computeIfAbsent(level.getClass(), type -> RealtimeReflection.findZeroArgMethod(type, "getServer"));
        if (method == null) {
            return null;
        }

        Object value = invoke(method, level);
        return value instanceof MinecraftServer server ? server : null;
    }

    public static Object gameRules(ServerLevel level) {
        Method method = GAME_RULES_METHODS.computeIfAbsent(level.getClass(), type -> RealtimeReflection.findZeroArgMethod(type, "getGameRules"));
        if (method == null) {
            return null;
        }
        return invoke(method, level);
    }

    private static Object invoke(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
