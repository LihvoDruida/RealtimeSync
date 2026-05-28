package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RealtimeGameRules {
    private static final List<String> DAYLIGHT_RULE_FIELD_NAMES = List.of(
            "DO_DAYLIGHT_CYCLE",
            "ADVANCE_TIME",
            "RULE_DAYLIGHT",
            "RULE_ADVANCE_TIME",
            "field_19396"
    );

    private final RealtimeLog logger;
    private final ConcurrentMap<Class<?>, Resolution> resolvedByGameRulesClass = new ConcurrentHashMap<>();
    private final Set<Class<?>> unresolvedGameRulesClasses = ConcurrentHashMap.newKeySet();
    private volatile boolean missingRuleWarningShown = false;

    public RealtimeGameRules(RealtimeLog logger) {
        this.logger = logger;
    }

    public void resetWarningState() {
        missingRuleWarningShown = false;
    }

    public boolean disableDaylightCycle(ServerLevel level, MinecraftServer server) {
        return setDaylightCycle(level, server, false);
    }

    public boolean disableDaylightCycle(MinecraftServer server) {
        boolean appliedToAnyLevel = false;
        for (ServerLevel level : server.getAllLevels()) {
            appliedToAnyLevel |= setDaylightCycle(level, server, false);
        }
        return appliedToAnyLevel;
    }

    private boolean setDaylightCycle(ServerLevel level, MinecraftServer server, boolean value) {
        Object gameRules = RealtimeWorldAccess.gameRules(level);
        if (gameRules == null) {
            return false;
        }

        Class<?> gameRulesClass = gameRules.getClass();
        Resolution cached = resolvedByGameRulesClass.get(gameRulesClass);
        if (cached != null) {
            return cached.trySet(gameRules, server, value);
        }
        if (unresolvedGameRulesClasses.contains(gameRulesClass)) {
            warnMissingRuleOnce(gameRulesClass, true);
            return false;
        }

        Resolution resolved = resolve(gameRules, server, value);
        if (resolved == null) {
            unresolvedGameRulesClasses.add(gameRulesClass);
            warnMissingRuleOnce(gameRulesClass, false);
            return false;
        }

        resolvedByGameRulesClass.put(gameRulesClass, resolved);
        return true;
    }

    private Resolution resolve(Object gameRules, MinecraftServer server, boolean value) {
        Class<?> gameRulesClass = gameRules.getClass();

        for (String fieldName : DAYLIGHT_RULE_FIELD_NAMES) {
            try {
                Field keyField = findField(gameRulesClass, fieldName);
                if (keyField == null) {
                    continue;
                }

                keyField.setAccessible(true);
                Object key = keyField.get(null);
                if (key == null) {
                    continue;
                }

                Method directSetter = findDirectGameRuleSetter(gameRulesClass, key, server);
                if (directSetter != null) {
                    Resolution resolution = Resolution.direct(keyField, directSetter);
                    if (resolution.trySet(gameRules, server, value)) {
                        return resolution;
                    }
                }

                Method getRuleMethod = findGetRuleMethod(gameRulesClass, key);
                if (getRuleMethod == null) {
                    continue;
                }

                Object rule = getRuleMethod.invoke(gameRules, key);
                if (rule == null) {
                    continue;
                }

                Method ruleSetter = findBooleanRuleSetter(rule.getClass(), server);
                if (ruleSetter != null) {
                    Resolution resolution = Resolution.rule(keyField, getRuleMethod, ruleSetter);
                    if (resolution.trySet(gameRules, server, value)) {
                        return resolution;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next Minecraft 1.21.x gamerule key name or setter shape.
            }
        }

        return null;
    }

    private void warnMissingRuleOnce(Class<?> gameRulesClass, boolean cachedFailure) {
        if (missingRuleWarningShown) {
            return;
        }

        missingRuleWarningShown = true;
        String cacheNote = cachedFailure ? " The unavailable lookup is cached for this runtime." : "";
        logger.warn("Could not disable vanilla daylight cycle: no compatible daylight gamerule key was found on {}.{}",
                gameRulesClass.getName(), cacheNote);
    }

    private static Field findField(Class<?> type, String fieldName) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }

        return null;
    }

    private static Method findDirectGameRuleSetter(Class<?> gameRulesClass, Object key, MinecraftServer server) {
        for (Method method : gameRulesClass.getMethods()) {
            if (!method.getName().equals("setValue") || method.getParameterCount() != 3) {
                continue;
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!parameterTypes[0].isAssignableFrom(key.getClass())) {
                continue;
            }
            if (!RealtimeReflection.acceptsBooleanValue(parameterTypes[1])) {
                continue;
            }
            if (!parameterTypes[2].isAssignableFrom(server.getClass())) {
                continue;
            }

            method.setAccessible(true);
            return method;
        }

        return null;
    }

    private static Method findGetRuleMethod(Class<?> gameRulesClass, Object key) {
        for (Method method : gameRulesClass.getMethods()) {
            if ((!method.getName().equals("get") && !method.getName().equals("getRule")) || method.getParameterCount() != 1) {
                continue;
            }

            Class<?> parameterType = method.getParameterTypes()[0];
            if (!parameterType.isAssignableFrom(key.getClass())) {
                continue;
            }

            method.setAccessible(true);
            return method;
        }

        return null;
    }

    private static Method findBooleanRuleSetter(Class<?> ruleClass, MinecraftServer server) {
        for (Method method : ruleClass.getMethods()) {
            if (!method.getName().equals("set") || method.getParameterCount() != 2) {
                continue;
            }

            Class<?>[] parameterTypes = method.getParameterTypes();
            if (!RealtimeReflection.acceptsBooleanValue(parameterTypes[0])) {
                continue;
            }
            if (!parameterTypes[1].isAssignableFrom(server.getClass())) {
                continue;
            }

            method.setAccessible(true);
            return method;
        }

        return null;
    }

    private static final class Resolution {
        private final Field keyField;
        private final Method directSetter;
        private final Method getRuleMethod;
        private final Method ruleSetter;

        private Resolution(Field keyField, Method directSetter, Method getRuleMethod, Method ruleSetter) {
            this.keyField = keyField;
            this.directSetter = directSetter;
            this.getRuleMethod = getRuleMethod;
            this.ruleSetter = ruleSetter;
        }

        static Resolution direct(Field keyField, Method directSetter) {
            return new Resolution(keyField, directSetter, null, null);
        }

        static Resolution rule(Field keyField, Method getRuleMethod, Method ruleSetter) {
            return new Resolution(keyField, null, getRuleMethod, ruleSetter);
        }

        boolean trySet(Object gameRules, MinecraftServer server, boolean value) {
            try {
                Object key = keyField.get(null);
                if (key == null) {
                    return false;
                }

                if (directSetter != null) {
                    directSetter.invoke(gameRules, key, value, server);
                    return true;
                }

                if (getRuleMethod == null || ruleSetter == null) {
                    return false;
                }

                Object rule = getRuleMethod.invoke(gameRules, key);
                if (rule == null) {
                    return false;
                }

                ruleSetter.invoke(rule, value, server);
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return false;
            }
        }
    }
}
