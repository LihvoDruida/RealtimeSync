package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.IdentityHashMap;
import java.util.Map;

/** Owns daylight gamerule changes and restores only values still owned by this mod. */
public final class RealtimeGameRules {
    private static final boolean MANAGED_VALUE = false;
    private static final boolean SLEEP_VALUE = true;

    private final RealtimeLog logger;
    private final DaylightRuleAccess access = new ProfileDaylightRuleAccess();
    private final Map<Object, ManagedState> managedStates = new IdentityHashMap<>();
    private boolean unavailableWarningShown;
    private boolean requireOffWarningShown;

    public RealtimeGameRules(RealtimeLog logger) {
        this.logger = logger;
    }

    public String adapterName() {
        return access.adapterName();
    }

    public String describeState(ServerLevel level) {
        try {
            Object identity = RealtimeWorldAccess.gameRulesIdentity(level);
            ManagedState state = managedStates.get(identity);
            boolean advancing = access.isTimeAdvancing(level);
            boolean owned = state != null
                    && !state.ownershipLost
                    && (state.changedByMod || state.sleepWindowActive);
            return "advancing=" + advancing
                    + ", ownedByRealtimeSync=" + owned
                    + ", sleepOverride=" + (state != null && state.sleepWindowActive);
        } catch (RuntimeException exception) {
            return "unavailable(" + exception.getClass().getSimpleName() + ")";
        }
    }

    public boolean applyPolicy(ServerLevel level, MinecraftServer server, String policy) {
        if (RealtimeConfig.DAYLIGHT_POLICY_IGNORE.equals(policy)) {
            return true;
        }

        try {
            boolean current = access.isTimeAdvancing(level);
            if (RealtimeConfig.DAYLIGHT_POLICY_REQUIRE_OFF.equals(policy)) {
                if (current && !requireOffWarningShown) {
                    requireOffWarningShown = true;
                    logger.warn("RealtimeSync requires the daylight gamerule to be off, but it is currently enabled. Time writes may compete with vanilla progression.");
                }
                return !current;
            }

            return manage(level, server, current);
        } catch (RuntimeException exception) {
            warnUnavailableOnce(exception);
            return false;
        }
    }

    /**
     * Temporarily enables vanilla time progression while players are sleeping.
     * This is needed even when the administrator's original gamerule value was false;
     * otherwise respectSleep=true can deadlock the night forever.
     */
    public void beginSleepWindow(MinecraftServer server) {
        for (ManagedState state : managedStates.values()) {
            if (state.ownershipLost || state.level == null || state.sleepWindowActive) {
                continue;
            }
            try {
                boolean current = access.isTimeAdvancing(state.level);
                state.sleepWindowActive = true;
                state.expectedSleepValue = SLEEP_VALUE;
                if (current != SLEEP_VALUE) {
                    access.setTimeAdvancing(state.level, server, SLEEP_VALUE);
                    if (access.isTimeAdvancing(state.level) != SLEEP_VALUE) {
                        state.sleepWindowActive = false;
                        continue;
                    }
                }
                // The normal managed write is suspended while vanilla handles sleeping.
                state.changedByMod = false;
            } catch (RuntimeException exception) {
                warnUnavailableOnce(exception);
            }
        }
    }

    /** Ends the temporary sleep override without overwriting an administrator change made during sleep. */
    public void endSleepWindow(MinecraftServer server) {
        for (ManagedState state : managedStates.values()) {
            if (!state.sleepWindowActive || state.level == null) {
                continue;
            }
            try {
                boolean current = access.isTimeAdvancing(state.level);
                state.sleepWindowActive = false;
                if (current != state.expectedSleepValue) {
                    state.changedByMod = false;
                    state.ownershipLost = true;
                    logger.warn("Daylight gamerule changed externally during the RealtimeSync sleep window. Ownership was released and the administrator value will be respected until restart or config reload.");
                    continue;
                }

                access.setTimeAdvancing(state.level, server, MANAGED_VALUE);
                boolean applied = access.isTimeAdvancing(state.level) == MANAGED_VALUE;
                state.changedByMod = applied && state.initialValue != MANAGED_VALUE;
            } catch (RuntimeException exception) {
                state.sleepWindowActive = false;
                warnUnavailableOnce(exception);
            }
        }
    }

    public void restoreAll(MinecraftServer server) {
        restoreOwnedValues(server, true);
    }

    public void clearRuntimeState() {
        managedStates.clear();
        unavailableWarningShown = false;
        requireOffWarningShown = false;
    }

    private boolean manage(ServerLevel level, MinecraftServer server, boolean current) {
        Object identity = RealtimeWorldAccess.gameRulesIdentity(level);
        ManagedState state = managedStates.computeIfAbsent(identity, ignored -> new ManagedState(level, current));
        state.level = level;

        if (state.ownershipLost || state.sleepWindowActive) {
            return false;
        }

        if (state.changedByMod && current != MANAGED_VALUE) {
            state.changedByMod = false;
            state.ownershipLost = true;
            logger.warn("Daylight gamerule was changed externally while RealtimeSync managed it. Ownership was released and the administrator value will be respected until restart or config reload.");
            return false;
        }

        if (current == MANAGED_VALUE) {
            return true;
        }

        access.setTimeAdvancing(level, server, MANAGED_VALUE);
        boolean applied = access.isTimeAdvancing(level) == MANAGED_VALUE;
        state.changedByMod = applied && state.initialValue != MANAGED_VALUE;
        return applied;
    }

    private void restoreOwnedValues(MinecraftServer server, boolean clear) {
        for (ManagedState state : managedStates.values()) {
            if (state.ownershipLost || state.level == null) {
                continue;
            }
            try {
                boolean current = access.isTimeAdvancing(state.level);
                if (state.sleepWindowActive) {
                    // Only revert a sleep value that is still exactly the value written by us.
                    if (current == state.expectedSleepValue) {
                        access.setTimeAdvancing(state.level, server, state.initialValue);
                    }
                    state.sleepWindowActive = false;
                    state.changedByMod = false;
                    continue;
                }
                if (state.changedByMod && current == MANAGED_VALUE) {
                    access.setTimeAdvancing(state.level, server, state.initialValue);
                }
                state.changedByMod = false;
            } catch (RuntimeException exception) {
                warnUnavailableOnce(exception);
            }
        }
        if (clear) {
            managedStates.clear();
        }
    }

    private void warnUnavailableOnce(RuntimeException exception) {
        if (unavailableWarningShown) {
            return;
        }
        unavailableWarningShown = true;
        logger.warn("Could not access the daylight gamerule using adapter {}: {}", access.adapterName(), exception.toString());
    }

    private static final class ManagedState {
        private ServerLevel level;
        private final boolean initialValue;
        private boolean changedByMod;
        private boolean ownershipLost;
        private boolean sleepWindowActive;
        private boolean expectedSleepValue;

        private ManagedState(ServerLevel level, boolean initialValue) {
            this.level = level;
            this.initialValue = initialValue;
        }
    }
}
