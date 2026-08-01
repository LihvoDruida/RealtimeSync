package com.realtime.common;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/** Single-server-thread controller. Loader entrypoints must call it only from official server lifecycle/tick events. */
public final class RealtimeController {
    private static final int CONFIG_RELOAD_CHECK_INTERVAL_TICKS = 100;
    private static final int DAYLIGHT_RULE_GUARD_INTERVAL_TICKS = 20 * 60;
    private static final long PERFORMANCE_LOG_INTERVAL_NANOS = 60_000_000_000L;
    private static final String OVERWORLD_DIMENSION_ID = "minecraft:overworld";
    /**
     * Minimum forward jump treated as a vanilla sleep skip. A sleep window lasts about 100
     * ticks of normal vanilla progression, so anything beyond this can only be the skip.
     */
    private static final long SLEEP_SKIP_DETECTION_TICKS = 600L;

    private final RealtimeLog logger;
    private final Path configPath;
    private final Path legacyConfigPath;
    private final Path statePath;
    private final RealtimeMath timeMath = new RealtimeMath();
    private final RealtimeGameRules gameRules;
    private final Map<String, RealtimeMath.SmoothState> smoothStates = new HashMap<>();
    private final Map<String, Long> lastSmoothUpdateNanos = new HashMap<>();
    private final Set<String> unresolvedDimensionWarnings = new HashSet<>();
    private final Set<String> largeJumpWarnings = new HashSet<>();
    /** Reused between updates so the hot path does not allocate a new set every time. */
    private final Set<String> activeDimensionIds = new HashSet<>();

    private RealtimeConfig config;
    private ConfigFingerprint configFingerprint = ConfigFingerprint.missing();
    private int tickCounter;
    private int configReloadTickCounter;
    private int daylightRuleGuardTickCounter;
    private boolean sleepSkipLogged;
    private boolean rulesSuspendedForSleep;
    private long lastProcessedServerTick = RealtimeServerState.UNKNOWN_TICK;
    private MinecraftServer activeServer;
    private boolean customClockInitialized;
    private boolean noManagedDimensionsWarningShown;
    private boolean configStatWarningShown;
    private boolean shutdownRestoreDone;
    private boolean sleepWindowTracking;
    private long preSleepReferenceAbsolute;
    private int lastActiveDimensionCount;
    private Instant lastSuccessfulUpdate;
    private Instant lastConfigReload;

    private long performanceWindowStartNanos;
    private long performanceUpdates;
    private long performanceSkippedUpdates;
    private long performanceTotalNanos;
    private long performanceMaxNanos;
    private long performanceConfigReloads;
    private long performanceGameruleChecks;

    public RealtimeController(Path configDir, RealtimeLog logger) {
        this.logger = logger;
        this.configPath = configDir.resolve("realtime.properties");
        this.legacyConfigPath = configDir.resolve("realtime.toml");
        this.statePath = configDir.resolve("realtime-state.properties");
        this.gameRules = new RealtimeGameRules(logger);
        // Do not read or rewrite configuration from parallel mod-loading workers.
        // The first real load is performed on the dedicated server thread.
        this.config = RealtimeConfig.defaults(logger);
    }

    public Path configPath() {
        return configPath;
    }

    public void registerCommands(com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher) {
        RealtimeStatusCommand.register(dispatcher, this, logger);
    }

    public void onServerStarted(MinecraftServer server) {
        activeServer = server;
        resetRuntimeState(false);
        RealtimeWorldTime.resetRuntimeState();
        reloadConfig(true);
        restoreSleepRealignIfNeeded();
        initializeCustomClockIfNeeded(server);
        manageDaylightRules(server, true);
        syncServerTime(server);
        RealtimeBuildInfo buildInfo = RealtimeBuildInfo.current();
        logger.info("RealtimeSync started. version={}, minecraft={}, loader={}, gameruleAdapter={}, dimensionAdapter={}, commandPermissionAdapter={}, mode={}, zoneId={}, daylightRulePolicy={}, config={}",
                buildInfo.version(),
                buildInfo.minecraftVersion(),
                buildInfo.loader(),
                gameRules.adapterName(),
                RealtimeWorldTime.dimensionAdapterName(),
                RealtimeStatusCommand.permissionAdapterName(),
                config.customDayLengthMinutes > 0 ? "custom-day-length" : config.syncMode,
                config.resolvedZoneId().getId(),
                config.daylightRulePolicy,
                configPath.getFileName());
        logInitialState(server);
    }

    /**
     * Called while the server is still shutting down. Gamerule ownership must be released
     * here: {@code onServerStopped} runs after every level has been saved and closed, so a
     * restored {@code doDaylightCycle}/{@code advance_time} value written at that point would
     * never reach {@code level.dat} and the world would stay frozen after removing the mod.
     */
    public void onServerStopping(MinecraftServer server) {
        if (activeServer != server) {
            return;
        }
        persistRuntimeState();
        gameRules.restoreAll(server);
        shutdownRestoreDone = true;
    }

    public void onServerStopped(MinecraftServer server) {
        if (activeServer != server) {
            return;
        }
        if (!shutdownRestoreDone) {
            // Loader entrypoint without a stopping hook, or an abrupt shutdown path.
            persistRuntimeState();
            gameRules.restoreAll(server);
        }
        activeServer = null;
        resetRuntimeState(true);
        RealtimeWorldTime.resetRuntimeState();
    }

    public void onWorldLoad(MinecraftServer server, ServerLevel level) {
        if (activeServer == null) {
            activeServer = server;
        }
        reloadConfig(false);
        if (shouldSyncLevel(level)) {
            gameRules.applyPolicy(level, server, config.daylightRulePolicy);
            performanceGameruleChecks++;
        }
        tickCounter = Math.max(0, config.updateInterval - 1);
    }

    public void onServerTick(MinecraftServer server) {
        if (activeServer != server) {
            onServerStarted(server);
            return;
        }
        if (!markServerTick(server)) {
            performanceSkippedUpdates++;
            return;
        }

        boolean reloaded = checkConfigReload();
        manageDaylightRules(server, reloaded);
        if (!config.enabled) {
            performanceSkippedUpdates++;
            maybeLogPerformance();
            return;
        }

        tickCounter++;
        if (tickCounter < config.updateInterval) {
            performanceSkippedUpdates++;
            maybeLogPerformance();
            return;
        }
        tickCounter = 0;
        syncServerTime(server);
        maybeLogPerformance();
    }

    private void syncServerTime(MinecraftServer server) {
        long startedNanos = timeMath.nowNanos();
        try {
            activeDimensionIds.clear();
            Set<String> sleepingDimensions = sleepingDimensionIds(server);
            if (!sleepingDimensions.isEmpty()) {
                if (RealtimeConfig.DAYLIGHT_POLICY_MANAGED.equals(config.daylightRulePolicy)) {
                    // Re-establish states first so a config reload during sleep cannot leave an
                    // originally-disabled gamerule stuck off with no ownership record.
                    for (ServerLevel level : server.getAllLevels()) {
                        if (shouldSyncLevel(level)) {
                            gameRules.applyPolicy(level, server, config.daylightRulePolicy);
                        }
                    }
                    gameRules.beginSleepWindow(server);
                }
                if (!sleepWindowTracking) {
                    sleepWindowTracking = true;
                    preSleepReferenceAbsolute = RealtimeWorldTime.readOverworldTime(server);
                }
                rulesSuspendedForSleep = true;
                sleepSkipLogged = logSleepState(sleepingDimensions, sleepSkipLogged);
                // Only the sleeping dimensions are skipped below; other managed dimensions
                // keep their synchronization instead of freezing server-wide.
            } else if (rulesSuspendedForSleep) {
                gameRules.endSleepWindow(server);
                rulesSuspendedForSleep = false;
                handleSleepWindowClosed(server);
                manageDaylightRules(server, true);
            }

            boolean customMode = config.customDayLengthMinutes > 0;
            long commonCustomTarget = customMode ? calculateCustomTarget(server) : 0L;
            long updateNanos = timeMath.nowNanos();
            boolean wasRealigning = timeMath.isRealigningAfterSleep();
            timeMath.advanceSleepOffset(updateNanos, config.maximumOfflineCatchUpSeconds);
            if (wasRealigning && !timeMath.isRealigningAfterSleep()) {
                logger.info("RealtimeSync finished realigning after a sleep skip; the world clock is back on real time.");
            }
            long realtimeTimeOfDay = customMode ? 0L : currentRealtimeTimeOfDay();
            // REAL_DATE_ANCHOR does not depend on the current world time, so the ZonedDateTime
            // and calendar difference are computed once per update instead of once per dimension.
            boolean sharedRealtimeTarget = !customMode
                    && RealtimeConfig.DAY_PROGRESSION_REAL_DATE_ANCHOR.equals(config.dayProgressionPolicy);
            long commonRealtimeTarget = sharedRealtimeTarget
                    ? timeMath.resolveRealtimeAbsoluteTarget(0L, realtimeTimeOfDay, config)
                    : 0L;
            int managedWorlds = 0;
            int syncedWorlds = 0;
            long lastTargetAbsolute = 0L;

            for (ServerLevel level : server.getAllLevels()) {
                String dimensionId = resolveManagedDimensionId(level);
                if (dimensionId == null || !shouldSyncLevel(dimensionId)) {
                    continue;
                }
                managedWorlds++;
                activeDimensionIds.add(dimensionId);
                if (sleepingDimensions.contains(dimensionId)) {
                    continue;
                }

                long currentAbsolute = RealtimeWorldTime.readDayTime(level);
                long targetAbsolute;
                if (customMode) {
                    targetAbsolute = commonCustomTarget;
                } else if (sharedRealtimeTarget) {
                    targetAbsolute = commonRealtimeTarget;
                } else {
                    targetAbsolute = timeMath.resolveRealtimeAbsoluteTarget(currentAbsolute, realtimeTimeOfDay, config);
                }
                lastTargetAbsolute = targetAbsolute;
                long appliedAbsolute = targetAbsolute;

                if (!customMode && config.isSmoothSyncMode()) {
                    long previousNanos = lastSmoothUpdateNanos.getOrDefault(dimensionId, Long.MIN_VALUE);
                    double elapsedSeconds = timeMath.elapsedSeconds(previousNanos, updateNanos, config.maximumOfflineCatchUpSeconds);
                    lastSmoothUpdateNanos.put(dimensionId, updateNanos);
                    RealtimeMath.SmoothState state = smoothStates.computeIfAbsent(dimensionId, ignored -> new RealtimeMath.SmoothState());
                    appliedAbsolute = timeMath.calculateSmoothAbsoluteTicks(currentAbsolute, targetAbsolute, elapsedSeconds, config, state);
                    if (timeMath.isPausedLargeJump(currentAbsolute, targetAbsolute, config)) {
                        if (largeJumpWarnings.add(dimensionId)) {
                            logger.warn("Time synchronization for {} is paused because the target differs by more than one Minecraft day. Set smoothLargeJumpPolicy=GRADUAL or SNAP after checking the host clock.", dimensionId);
                        }
                    } else if (largeJumpWarnings.remove(dimensionId)) {
                        logger.info("Time synchronization for {} resumed; the target is back within one Minecraft day.", dimensionId);
                    }
                }

                if (RealtimeWorldTime.setDayTime(level, appliedAbsolute, logger)) {
                    syncedWorlds++;
                }
            }

            lastActiveDimensionCount = managedWorlds;
            if (managedWorlds == 0) {
                if (!noManagedDimensionsWarningShown) {
                    noManagedDimensionsWarningShown = true;
                    logger.warn("RealtimeSync found no managed dimensions. Check syncAllWorlds, syncDimensions and ignoredDimensions.");
                }
            } else {
                noManagedDimensionsWarningShown = false;
            }
            if (syncedWorlds > 0) {
                lastSuccessfulUpdate = timeMath.now();
            }
            pruneDimensionState(activeDimensionIds);
            if (config.debugLogging) {
                logger.info("RealtimeSync updated {} of {} managed dimension(s); targetAbsoluteDayTime={}, customMode={}, sleeping={}.",
                        syncedWorlds,
                        managedWorlds,
                        lastTargetAbsolute,
                        customMode,
                        sleepingDimensions);
            }
            if (sleepingDimensions.isEmpty()) {
                sleepSkipLogged = false;
            }
            performanceUpdates++;
        } catch (RuntimeException exception) {
            logger.error("Failed to synchronize Minecraft dayTime.", exception);
        } finally {
            long duration = Math.max(0L, timeMath.nowNanos() - startedNanos);
            performanceTotalNanos += duration;
            performanceMaxNanos = Math.max(performanceMaxNanos, duration);
        }
    }

    /**
     * Drops per-dimension runtime state for dimensions that are no longer loaded or managed.
     * Without this, a server that unloads and recreates dimensions grows these maps forever.
     */
    private void pruneDimensionState(Set<String> activeDimensions) {
        if (smoothStates.size() > activeDimensions.size()) {
            smoothStates.keySet().retainAll(activeDimensions);
        }
        if (lastSmoothUpdateNanos.size() > activeDimensions.size()) {
            lastSmoothUpdateNanos.keySet().retainAll(activeDimensions);
        }
        if (largeJumpWarnings.size() > activeDimensions.size()) {
            largeJumpWarnings.retainAll(activeDimensions);
        }
    }

    /** Real time of day shifted by an active post-sleep realignment offset. */
    private long currentRealtimeTimeOfDay() {
        return timeMath.applySleepOffset(
                timeMath.calculateRealtimeTimeOfDay(config.resolvedZoneId(), config.timeOffsetMinutes));
    }

    /**
     * Decides what to do with the world time vanilla produced while players were sleeping.
     *
     * <p>{@code VANILLA} lets the next update pull the clock straight back to real time.
     * {@code REALIGN} adopts the skip and hands it to {@link RealtimeMath#beginSleepRealign}
     * so the world clock runs fast until it meets real time again, without ever going
     * backwards.</p>
     */
    private void handleSleepWindowClosed(MinecraftServer server) {
        boolean tracked = sleepWindowTracking;
        long before = preSleepReferenceAbsolute;
        sleepWindowTracking = false;
        if (!tracked
                || config.customDayLengthMinutes > 0
                || !RealtimeConfig.SLEEP_POLICY_REALIGN.equals(config.effectiveSleepPolicy())) {
            return;
        }

        long after = RealtimeWorldTime.readOverworldTime(server);
        if (after - before < SLEEP_SKIP_DETECTION_TICKS) {
            // Players left the bed without triggering a vanilla skip.
            return;
        }

        long realTimeOfDay = timeMath.calculateRealtimeTimeOfDay(config.resolvedZoneId(), config.timeOffsetMinutes);
        timeMath.beginSleepRealign(
                AbsoluteDayTime.timeOfDay(after),
                realTimeOfDay,
                config.sleepRealignMinutes,
                timeMath.nowNanos());
        persistRuntimeState();
        logger.info("RealtimeSync accepted a vanilla sleep skip: the world is {} tick(s) ahead of real time and will run at about {}x speed for the next {} minute(s).",
                timeMath.sleepOffsetTicks(),
                String.format(Locale.ROOT, "%.2f", timeMath.sleepRealignSpeedMultiplier()),
                config.sleepRealignMinutes);
    }

    /** Restores a realignment window that was interrupted by a restart. */
    private void restoreSleepRealignIfNeeded() {
        if (config.customDayLengthMinutes > 0
                || !RealtimeConfig.SLEEP_POLICY_REALIGN.equals(config.effectiveSleepPolicy())) {
            return;
        }
        RealtimePersistentState.Snapshot snapshot = RealtimePersistentState.load(statePath, logger);
        if (snapshot == null || snapshot.sleepRealignRatePerSecond() <= 0.0D) {
            return;
        }
        long elapsedSeconds = Math.max(0L, Duration.between(snapshot.savedAt(), timeMath.now()).getSeconds());
        elapsedSeconds = Math.min(elapsedSeconds, config.maximumOfflineCatchUpSeconds);
        double offset = snapshot.sleepOffsetTicks() + snapshot.sleepRealignRatePerSecond() * elapsedSeconds;
        if (offset >= AbsoluteDayTime.TICKS_PER_DAY) {
            timeMath.resetSleepRealign();
            return;
        }
        timeMath.restoreSleepRealign(offset, snapshot.sleepRealignRatePerSecond(), timeMath.nowNanos());
        logger.info("RealtimeSync resumed a sleep realignment window: offset={} tick(s), remaining={} second(s).",
                timeMath.sleepOffsetTicks(),
                timeMath.sleepRealignRemainingSeconds());
    }

    private long calculateCustomTarget(MinecraftServer server) {
        initializeCustomClockIfNeeded(server);
        long current = RealtimeWorldTime.readOverworldTime(server);
        return timeMath.calculateCustomAbsoluteTicks(current, config.customDayLengthMinutes, config.maximumOfflineCatchUpSeconds);
    }

    private void initializeCustomClockIfNeeded(MinecraftServer server) {
        if (customClockInitialized || config.customDayLengthMinutes <= 0) {
            return;
        }
        long current = RealtimeWorldTime.readOverworldTime(server);
        double initial = current;

        if (RealtimeConfig.CUSTOM_RESTART_RESET_TO_CONFIGURED_TIME.equals(config.customClockRestartPolicy)) {
            initial = AbsoluteDayTime.compose(AbsoluteDayTime.dayIndex(current), 0L);
        } else if (RealtimeConfig.CUSTOM_RESTART_PERSIST_REAL_ELAPSED.equals(config.customClockRestartPolicy)) {
            RealtimePersistentState.Snapshot snapshot = RealtimePersistentState.load(statePath, logger);
            if (snapshot != null) {
                long elapsedSeconds = Math.max(0L, Duration.between(snapshot.savedAt(), timeMath.now()).getSeconds());
                elapsedSeconds = Math.min(elapsedSeconds, config.maximumOfflineCatchUpSeconds);
                double ticksPerSecond = AbsoluteDayTime.TICKS_PER_DAY / (config.customDayLengthMinutes * 60.0D);
                initial = snapshot.customAbsoluteTicks() + elapsedSeconds * ticksPerSecond;
            }
        }

        timeMath.initializeCustomClock(initial, timeMath.nowNanos());
        customClockInitialized = true;
    }

    private void persistRuntimeState() {
        boolean customClockNeedsPersistence = customClockInitialized
                && config.customDayLengthMinutes > 0
                && RealtimeConfig.CUSTOM_RESTART_PERSIST_REAL_ELAPSED.equals(config.customClockRestartPolicy);
        if (!customClockNeedsPersistence && !timeMath.isRealigningAfterSleep()) {
            return;
        }
        RealtimePersistentState.save(
                statePath,
                timeMath.customClockValue(),
                timeMath.now(),
                timeMath.sleepOffsetTicksExact(),
                timeMath.sleepRealignRatePerSecond(),
                logger);
    }

    private Set<String> sleepingDimensionIds(MinecraftServer server) {
        if (RealtimeConfig.SLEEP_POLICY_REALTIME_ONLY.equals(config.effectiveSleepPolicy())) {
            return Set.of();
        }
        // Nobody sleeps during the vast majority of updates, so the set is allocated lazily.
        Set<String> sleeping = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!player.isSleeping() || !(player.level() instanceof ServerLevel level)) {
                continue;
            }
            String dimensionId = resolveManagedDimensionId(level);
            if (dimensionId == null || !shouldSyncLevel(dimensionId)) {
                continue;
            }
            if (sleeping == null) {
                sleeping = new HashSet<>(4);
            }
            sleeping.add(dimensionId);
        }
        return sleeping == null ? Set.of() : sleeping;
    }

    private boolean logSleepState(Set<String> sleepingDimensions, boolean wasLogged) {
        if (sleepingDimensions.isEmpty()) {
            return false;
        }
        if (config.debugLogging && !wasLogged) {
            logger.info("RealtimeSync suspended managed daylight rules and skipped time writes in sleeping dimensions: {}", sleepingDimensions);
        }
        return true;
    }

    private void manageDaylightRules(MinecraftServer server, boolean force) {
        if (!force) {
            daylightRuleGuardTickCounter++;
            if (daylightRuleGuardTickCounter < DAYLIGHT_RULE_GUARD_INTERVAL_TICKS) {
                return;
            }
        }
        daylightRuleGuardTickCounter = 0;

        if (!config.enabled || RealtimeConfig.DAYLIGHT_POLICY_IGNORE.equals(config.daylightRulePolicy)) {
            gameRules.restoreAll(server);
            return;
        }

        for (ServerLevel level : server.getAllLevels()) {
            if (shouldSyncLevel(level)) {
                gameRules.applyPolicy(level, server, config.daylightRulePolicy);
                performanceGameruleChecks++;
            }
        }
    }

    private boolean shouldSyncLevel(ServerLevel level) {
        String dimensionId = resolveManagedDimensionId(level);
        return dimensionId != null && shouldSyncLevel(dimensionId);
    }

    private boolean shouldSyncLevel(String dimensionId) {
        if (config.ignoredDimensionSet().contains(dimensionId)) {
            return false;
        }
        if (!config.syncDimensionSet().isEmpty()) {
            return config.syncDimensionSet().contains(dimensionId);
        }
        return config.syncAllWorlds || OVERWORLD_DIMENSION_ID.equals(dimensionId);
    }

    private String resolveManagedDimensionId(ServerLevel level) {
        String dimensionId = RealtimeWorldTime.dimensionId(level);
        if (dimensionId == null) {
            String key = level.getClass().getName();
            if (unresolvedDimensionWarnings.add(key)) {
                logger.warn("Skipping a dimension because its ResourceKey identifier could not be resolved; it will not be treated as the Overworld.");
            }
        }
        return dimensionId;
    }

    private boolean markServerTick(MinecraftServer server) {
        long serverTick = RealtimeServerState.tickCount(server);
        if (serverTick == RealtimeServerState.UNKNOWN_TICK) {
            return true;
        }
        if (serverTick == lastProcessedServerTick) {
            return false;
        }
        lastProcessedServerTick = serverTick;
        return true;
    }

    private boolean checkConfigReload() {
        configReloadTickCounter++;
        if (configReloadTickCounter < CONFIG_RELOAD_CHECK_INTERVAL_TICKS) {
            return false;
        }
        configReloadTickCounter = 0;
        return reloadConfig(false);
    }

    private boolean reloadConfig(boolean force) {
        if (!force) {
            // Polling used to read and checksum the whole file every five seconds on the
            // server thread. A metadata check is enough to prove nothing changed.
            ConfigFingerprint metadata = statFingerprint(configPath);
            if (metadata.sameMetadata(configFingerprint)) {
                return false;
            }
            ConfigFingerprint content = contentFingerprint(configPath);
            if (content.hasContentHash()
                    && configFingerprint.hasContentHash()
                    && content.sameContent(configFingerprint)) {
                // The file was touched or rewritten with identical bytes; absorb the new
                // timestamp so the next poll is a single stat call again.
                configFingerprint = content;
                return false;
            }
        }

        RealtimeConfig previous = config;
        RealtimeConfig loaded = force
                ? RealtimeConfig.loadOrCreate(configPath, legacyConfigPath, logger)
                : RealtimeConfig.reload(configPath, config, logger);
        configFingerprint = contentFingerprint(configPath);
        if (loaded == previous && !force) {
            return false;
        }
        config = loaded;
        lastConfigReload = timeMath.now();
        performanceConfigReloads++;

        if (activeServer != null
                && (previous.enabled && !config.enabled
                || RealtimeConfig.DAYLIGHT_POLICY_MANAGED.equals(previous.daylightRulePolicy)
                && !RealtimeConfig.DAYLIGHT_POLICY_MANAGED.equals(config.daylightRulePolicy))) {
            gameRules.restoreAll(activeServer);
        }
        if (previous.customDayLengthMinutes != config.customDayLengthMinutes
                || !previous.customClockRestartPolicy.equals(config.customClockRestartPolicy)) {
            persistRuntimeState();
            customClockInitialized = false;
            timeMath.resetCustomClock();
        }
        if (!previous.effectiveSleepPolicy().equals(config.effectiveSleepPolicy())
                && !RealtimeConfig.SLEEP_POLICY_REALIGN.equals(config.effectiveSleepPolicy())) {
            timeMath.resetSleepRealign();
        }
        if (!previous.dayProgressionPolicy.equals(config.dayProgressionPolicy)
                || !previous.zoneId.equals(config.zoneId)
                || previous.timeOffsetMinutes != config.timeOffsetMinutes) {
            timeMath.resetRealtimeAnchor();
        }

        tickCounter = Math.min(tickCounter, Math.max(0, config.updateInterval - 1));
        daylightRuleGuardTickCounter = DAYLIGHT_RULE_GUARD_INTERVAL_TICKS;
        lastProcessedServerTick = RealtimeServerState.UNKNOWN_TICK;
        smoothStates.clear();
        lastSmoothUpdateNanos.clear();
        largeJumpWarnings.clear();
        noManagedDimensionsWarningShown = false;
        if (!force) {
            logger.info("RealtimeSync config reloaded successfully.");
        }
        return true;
    }

    public List<String> statusLines(MinecraftServer server) {
        List<String> lines = new ArrayList<>();
        List<String> activeDimensions = new ArrayList<>();
        ServerLevel reference = null;
        for (ServerLevel level : server.getAllLevels()) {
            String dimensionId = resolveManagedDimensionId(level);
            if (dimensionId != null && shouldSyncLevel(dimensionId)) {
                activeDimensions.add(dimensionId);
                if (reference == null || OVERWORLD_DIMENSION_ID.equals(dimensionId)) {
                    reference = level;
                }
            }
        }

        long currentAbsolute = reference == null ? 0L : RealtimeWorldTime.readDayTimeOrFallback(reference, 0L);
        long targetAbsolute;
        if (config.customDayLengthMinutes > 0) {
            targetAbsolute = customClockInitialized ? (long) Math.floor(timeMath.customClockValue()) : currentAbsolute;
        } else {
            targetAbsolute = timeMath.resolveRealtimeAbsoluteTarget(currentAbsolute, currentRealtimeTimeOfDay(), config);
        }

        RealtimeBuildInfo buildInfo = RealtimeBuildInfo.current();
        lines.add("RealtimeSync status version=" + buildInfo.version()
                + ", minecraft=" + buildInfo.minecraftVersion()
                + ", loader=" + buildInfo.loader());
        lines.add("enabled=" + config.enabled
                + ", mode=" + (config.customDayLengthMinutes > 0 ? "custom-day-length" : config.syncMode)
                + ", zoneId=" + config.resolvedZoneId().getId());
        lines.add("currentAbsoluteDayTime=" + currentAbsolute
                + ", dayIndex=" + AbsoluteDayTime.dayIndex(currentAbsolute)
                + ", timeOfDay=" + AbsoluteDayTime.timeOfDay(currentAbsolute));
        lines.add("targetAbsoluteDayTime=" + targetAbsolute
                + ", targetDayIndex=" + AbsoluteDayTime.dayIndex(targetAbsolute)
                + ", targetTimeOfDay=" + AbsoluteDayTime.timeOfDay(targetAbsolute));
        lines.add("dimensions=" + activeDimensions);
        lines.add("daylightRulePolicy=" + config.daylightRulePolicy
                + ", gamerule=" + (reference == null ? "no-managed-dimension" : gameRules.describeState(reference)));
        lines.add("sleepPolicy=" + config.effectiveSleepPolicy()
                + ", sleepOffsetTicks=" + timeMath.sleepOffsetTicks()
                + ", realignRemainingSeconds=" + timeMath.sleepRealignRemainingSeconds()
                + ", clockSpeed=" + String.format(Locale.ROOT, "%.2fx", timeMath.sleepRealignSpeedMultiplier()));
        lines.add("gameruleAdapter=" + gameRules.adapterName()
                + ", dimensionAdapter=" + RealtimeWorldTime.dimensionAdapterName()
                + ", commandPermissionAdapter=" + RealtimeStatusCommand.permissionAdapterName());
        lines.add("lastSuccessfulUpdate=" + formatInstant(lastSuccessfulUpdate)
                + ", lastConfigReload=" + formatInstant(lastConfigReload));
        return List.copyOf(lines);
    }

    private void logInitialState(MinecraftServer server) {
        List<String> managedDimensions = new ArrayList<>();
        ServerLevel reference = null;
        for (ServerLevel level : server.getAllLevels()) {
            String dimensionId = resolveManagedDimensionId(level);
            if (dimensionId == null || !shouldSyncLevel(dimensionId)) {
                continue;
            }
            managedDimensions.add(dimensionId);
            if (reference == null || OVERWORLD_DIMENSION_ID.equals(dimensionId)) {
                reference = level;
            }
        }
        lastActiveDimensionCount = managedDimensions.size();
        if (reference == null) {
            logger.warn("RealtimeSync startup check found no managed dimensions. Check syncAllWorlds, syncDimensions and ignoredDimensions.");
            noManagedDimensionsWarningShown = true;
            return;
        }

        long currentAbsolute = RealtimeWorldTime.readDayTimeOrFallback(reference, 0L);
        long targetAbsolute;
        if (config.customDayLengthMinutes > 0) {
            targetAbsolute = customClockInitialized ? (long) Math.floor(timeMath.customClockValue()) : currentAbsolute;
        } else {
            targetAbsolute = timeMath.resolveRealtimeAbsoluteTarget(currentAbsolute, currentRealtimeTimeOfDay(), config);
        }
        logger.info("RealtimeSync initial state: managedDimensions={}, currentAbsoluteDayTime={}, targetAbsoluteDayTime={}, gamerule={}.",
                managedDimensions,
                currentAbsolute,
                targetAbsolute,
                gameRules.describeState(reference));
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? "never" : instant.toString();
    }

    private void resetRuntimeState(boolean clearGameRuleOwnership) {
        tickCounter = 0;
        configReloadTickCounter = 0;
        daylightRuleGuardTickCounter = 0;
        sleepSkipLogged = false;
        rulesSuspendedForSleep = false;
        shutdownRestoreDone = false;
        sleepWindowTracking = false;
        preSleepReferenceAbsolute = 0L;
        lastProcessedServerTick = RealtimeServerState.UNKNOWN_TICK;
        customClockInitialized = false;
        noManagedDimensionsWarningShown = false;
        configStatWarningShown = false;
        lastActiveDimensionCount = 0;
        lastSuccessfulUpdate = null;
        if (clearGameRuleOwnership) {
            lastConfigReload = null;
        }
        timeMath.resetCustomClock();
        timeMath.resetRealtimeAnchor();
        timeMath.resetSleepRealign();
        smoothStates.clear();
        lastSmoothUpdateNanos.clear();
        unresolvedDimensionWarnings.clear();
        largeJumpWarnings.clear();
        activeDimensionIds.clear();
        resetPerformanceWindow();
        if (clearGameRuleOwnership) {
            gameRules.clearRuntimeState();
        }
    }

    private void maybeLogPerformance() {
        if (!config.debugPerformanceLogging) {
            return;
        }
        long now = timeMath.nowNanos();
        if (performanceWindowStartNanos == 0L) {
            performanceWindowStartNanos = now;
            return;
        }
        if (now - performanceWindowStartNanos < PERFORMANCE_LOG_INTERVAL_NANOS) {
            return;
        }
        long averageMicros = performanceUpdates == 0L ? 0L : performanceTotalNanos / performanceUpdates / 1_000L;
        logger.info("RealtimeSync performance: updates={}, skipped={}, avgMicros={}, maxMicros={}, activeDimensions={}, configReloads={}, gameruleChecks={}.",
                performanceUpdates,
                performanceSkippedUpdates,
                averageMicros,
                performanceMaxNanos / 1_000L,
                lastActiveDimensionCount,
                performanceConfigReloads,
                performanceGameruleChecks);
        resetPerformanceWindow();
    }

    private void resetPerformanceWindow() {
        performanceWindowStartNanos = 0L;
        performanceUpdates = 0L;
        performanceSkippedUpdates = 0L;
        performanceTotalNanos = 0L;
        performanceMaxNanos = 0L;
        performanceConfigReloads = 0L;
        performanceGameruleChecks = 0L;
    }

    /** Metadata-only fingerprint. One stat call, no file content is read. */
    private ConfigFingerprint statFingerprint(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attributes.isRegularFile()) {
                return ConfigFingerprint.missing();
            }
            return new ConfigFingerprint(attributes.lastModifiedTime().toMillis(), attributes.size(), ConfigFingerprint.NO_HASH);
        } catch (NoSuchFileException exception) {
            return ConfigFingerprint.missing();
        } catch (IOException exception) {
            configStatWarningShown = warnOnce(configStatWarningShown,
                    "Could not read RealtimeSync config metadata. {}", exception.getMessage());
            return ConfigFingerprint.missing();
        }
    }

    /** Full fingerprint including a CRC32 of the file content. Used only when metadata changed. */
    private ConfigFingerprint contentFingerprint(Path path) {
        try {
            byte[] content = Files.readAllBytes(path);
            CRC32 crc = new CRC32();
            crc.update(content);
            return new ConfigFingerprint(Files.getLastModifiedTime(path).toMillis(), content.length, crc.getValue());
        } catch (NoSuchFileException exception) {
            return ConfigFingerprint.missing();
        } catch (IOException exception) {
            configStatWarningShown = warnOnce(configStatWarningShown,
                    "Could not fingerprint RealtimeSync config. {}", exception.getMessage());
            return ConfigFingerprint.missing();
        }
    }

    private boolean warnOnce(boolean alreadyWarned, String message, Object... args) {
        if (!alreadyWarned) {
            logger.warn(message, args);
        }
        return true;
    }

    private record ConfigFingerprint(long modifiedMillis, long size, long crc32) {
        private static final long NO_HASH = -2L;

        private static ConfigFingerprint missing() {
            return new ConfigFingerprint(-1L, -1L, -1L);
        }

        private boolean sameMetadata(ConfigFingerprint other) {
            return modifiedMillis == other.modifiedMillis && size == other.size;
        }

        private boolean hasContentHash() {
            return crc32 != NO_HASH;
        }

        private boolean sameContent(ConfigFingerprint other) {
            return size == other.size && crc32 == other.crc32;
        }
    }
}
