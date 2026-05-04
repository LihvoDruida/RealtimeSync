package com.realtime.common;

import net.minecraft.server.MinecraftServer;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reflection-backed Minecraft command execution used only as a compatibility fallback.
 *
 * <p>Minecraft 26.1 replaced the old level day-time methods with the World Clock
 * system. The public command syntax is the most stable surface for controlling
 * clocks across Fabric/Quilt/Forge/NeoForge while direct APIs are still moving.</p>
 */
public final class RealtimeCommands {
    private static final ConcurrentMap<Class<?>, Method> GET_COMMANDS_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> CREATE_SOURCE_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> SUPPRESS_OUTPUT_METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Class<?>, Method> COMMAND_EXECUTE_METHODS = new ConcurrentHashMap<>();

    private RealtimeCommands() {
    }

    public static boolean execute(MinecraftServer server, String command, RealtimeLog logger) {
        try {
            Method getCommands = GET_COMMANDS_METHODS.computeIfAbsent(server.getClass(), type -> findZeroArgMethod(type, "getCommands"));
            Method createSource = CREATE_SOURCE_METHODS.computeIfAbsent(server.getClass(), type -> findZeroArgMethod(type, "createCommandSourceStack"));
            if (getCommands == null || createSource == null) {
                logger.warn("Could not execute Minecraft command '{}': command API was not found for {}.", command, server.getClass().getName());
                return false;
            }

            Object commands = getCommands.invoke(server);
            Object source = createSource.invoke(server);
            Object suppressedSource = suppressOutput(source);
            Method execute = COMMAND_EXECUTE_METHODS.computeIfAbsent(commands.getClass(), type -> findCommandExecuteMethod(type, suppressedSource.getClass()));
            if (execute == null) {
                logger.warn("Could not execute Minecraft command '{}': no compatible execute method was found on {}.", command, commands.getClass().getName());
                return false;
            }

            Object result = execute.invoke(commands, suppressedSource, command);
            if (result instanceof Number number) {
                return number.intValue() >= 0;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.warn("Could not execute Minecraft command '{}': {}", command, exception.toString());
            return false;
        }
    }

    private static Object suppressOutput(Object source) {
        if (source == null) {
            return null;
        }

        Method method = SUPPRESS_OUTPUT_METHODS.computeIfAbsent(source.getClass(), type -> findZeroArgMethod(type, "withSuppressedOutput"));
        if (method == null) {
            return source;
        }

        try {
            Object suppressed = method.invoke(source);
            return suppressed == null ? source : suppressed;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return source;
        }
    }

    private static Method findCommandExecuteMethod(Class<?> type, Class<?> sourceClass) {
        Method exact = findTwoArgStringMethod(type, "performPrefixedCommand", sourceClass);
        if (exact != null) {
            return exact;
        }

        exact = findTwoArgStringMethod(type, "performCommand", sourceClass);
        if (exact != null) {
            return exact;
        }

        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (!params[0].isAssignableFrom(sourceClass) || params[1] != String.class) {
                continue;
            }
            String name = method.getName().toLowerCase(Locale.ROOT);
            if (name.contains("command") || name.contains("perform")) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static Method findTwoArgStringMethod(Class<?> type, String name, Class<?> sourceClass) {
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params[0].isAssignableFrom(sourceClass) && params[1] == String.class) {
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
}
