package com.realtime.common;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Time calculations kept independent from Minecraft runtime classes for deterministic tests. */
public final class RealtimeMath {
    public static final long TICKS_PER_DAY = AbsoluteDayTime.TICKS_PER_DAY;
    private static final long NANOS_PER_DAY = 86_400_000_000_000L;
    private static final long MINECRAFT_MIDNIGHT_TICK = 18_000L;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;
    /** Minecraft ticks the real clock advances per real second (24000 ticks per 24 hours). */
    private static final double REAL_TICKS_PER_SECOND = TICKS_PER_DAY / 86_400.0D;
    private static final int MAX_CUSTOM_CLOCK_SEGMENTS = 64;

    private final Clock clock;
    private final LongSupplier nanoTime;

    private double customAbsoluteTicks;
    private long lastCustomUpdateNanos = Long.MIN_VALUE;

    /**
     * Ticks the world clock currently runs ahead of the real clock after a vanilla sleep skip.
     * Always in [0, TICKS_PER_DAY). Realignment increases the offset until it laps a full day
     * and becomes zero again, so the world clock only ever moves forward.
     */
    private double sleepOffsetTicks;
    private double sleepRealignRatePerSecond;
    private long lastSleepOffsetNanos = Long.MIN_VALUE;

    public RealtimeMath() {
        this(Clock.systemUTC(), System::nanoTime);
    }

    RealtimeMath(Clock clock, LongSupplier nanoTime) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    public Instant now() {
        return clock.instant();
    }

    public long nowNanos() {
        return nanoTime.getAsLong();
    }

    public ZonedDateTime currentDateTime(ZoneId zoneId, int offsetMinutes) {
        return ZonedDateTime.ofInstant(clock.instant(), zoneId).plusMinutes(offsetMinutes);
    }

    public long calculateRealtimeTimeOfDay(ZoneId zoneId, int offsetMinutes) {
        return timeOfDayFromLocalTime(currentDateTime(zoneId, offsetMinutes).toLocalTime());
    }

    static long timeOfDayFromLocalTime(LocalTime realTime) {
        long ticksSinceRealMidnight = Math.floorDiv(
                Math.multiplyExact(realTime.toNanoOfDay(), TICKS_PER_DAY),
                NANOS_PER_DAY
        );
        return Math.floorMod(ticksSinceRealMidnight + MINECRAFT_MIDNIGHT_TICK, TICKS_PER_DAY);
    }

    public long resolveRealtimeAbsoluteTarget(
            long currentAbsolute,
            long targetTimeOfDay,
            RealtimeConfig config
    ) {
        if (RealtimeConfig.DAY_PROGRESSION_PRESERVE_CURRENT_DAY.equals(config.dayProgressionPolicy)) {
            return AbsoluteDayTime.preserveCurrentDay(currentAbsolute, targetTimeOfDay);
        }

        if (RealtimeConfig.DAY_PROGRESSION_REAL_DATE_ANCHOR.equals(config.dayProgressionPolicy)) {
            ZonedDateTime realNow = currentDateTime(config.resolvedZoneId(), config.timeOffsetMinutes);
            long realDay = ChronoUnit.DAYS.between(config.resolvedRealDateAnchor(), realNow.toLocalDate());
            // Vanilla dayTime tick 0 is 06:00. From 06:00 onward the time-of-day value has wrapped,
            // so it belongs to the next absolute Minecraft day relative to the local calendar date.
            long minecraftDay = Math.addExact(realDay, targetTimeOfDay < MINECRAFT_MIDNIGHT_TICK ? 1L : 0L);
            return AbsoluteDayTime.compose(minecraftDay, targetTimeOfDay);
        }

        return AbsoluteDayTime.preserveMonotonic(currentAbsolute, targetTimeOfDay);
    }

    public long calculateCustomAbsoluteTicks(
            long currentAbsolute,
            int customDayLengthMinutes,
            int maximumCatchUpSeconds,
            double daylightFraction
    ) {
        return calculateCustomAbsoluteTicks(currentAbsolute, customDayLengthMinutes, maximumCatchUpSeconds,
                daylightFraction, nanoTime.getAsLong());
    }

    /**
     * Advances the custom clock.
     *
     * <p>{@code daylightFraction} is the share of the compressed day that should be daylight.
     * Minecraft fixes sunrise and sunset to tick values, so seasons are produced by spending
     * a different amount of real time on ticks 0 to {@link RealtimeSolar#DAYLIGHT_END_TICK}
     * than on the rest of the cycle. A fraction of {@code 0.5} yields a uniform clock.</p>
     */
    long calculateCustomAbsoluteTicks(
            long currentAbsolute,
            int customDayLengthMinutes,
            int maximumCatchUpSeconds,
            double daylightFraction,
            long nowNanos
    ) {
        if (customDayLengthMinutes <= 0) {
            resetCustomClock();
            return currentAbsolute;
        }

        if (lastCustomUpdateNanos == Long.MIN_VALUE) {
            customAbsoluteTicks = currentAbsolute;
            lastCustomUpdateNanos = nowNanos;
            return currentAbsolute;
        }

        long elapsedNanos = nonNegativeElapsed(lastCustomUpdateNanos, nowNanos);
        lastCustomUpdateNanos = nowNanos;
        double elapsedSeconds = elapsedNanos / NANOS_PER_SECOND;
        if (maximumCatchUpSeconds >= 0) {
            elapsedSeconds = Math.min(elapsedSeconds, maximumCatchUpSeconds);
        }

        advanceCustomClock(elapsedSeconds, customDayLengthMinutes, daylightFraction);
        if (!Double.isFinite(customAbsoluteTicks) || customAbsoluteTicks > Long.MAX_VALUE || customAbsoluteTicks < Long.MIN_VALUE) {
            customAbsoluteTicks = currentAbsolute;
        }
        return (long) Math.floor(customAbsoluteTicks);
    }

    /**
     * Adds {@code elapsedSeconds} of real time to the custom clock, walking segment by segment
     * so an update that spans sunset or sunrise uses the correct rate on each side.
     */
    private void advanceCustomClock(double elapsedSeconds, int customDayLengthMinutes, double daylightFraction) {
        double daySeconds = customDayLengthMinutes * 60.0D;
        double fraction = sanitizeDaylightFraction(daylightFraction);
        double daylightRate = RealtimeSolar.DAYLIGHT_END_TICK / (daySeconds * fraction);
        double nightRate = (TICKS_PER_DAY - RealtimeSolar.DAYLIGHT_END_TICK) / (daySeconds * (1.0D - fraction));
        if (!Double.isFinite(daylightRate) || !Double.isFinite(nightRate) || daylightRate <= 0.0D || nightRate <= 0.0D) {
            customAbsoluteTicks += elapsedSeconds * (TICKS_PER_DAY / daySeconds);
            return;
        }

        double remainingSeconds = elapsedSeconds;
        // Each iteration consumes at least one half of the cycle, so the bound only matters
        // for very large catch-up windows.
        for (int segment = 0; segment < MAX_CUSTOM_CLOCK_SEGMENTS && remainingSeconds > 0.0D; segment++) {
            double timeOfDay = customAbsoluteTicks - Math.floor(customAbsoluteTicks / TICKS_PER_DAY) * TICKS_PER_DAY;
            boolean daylight = timeOfDay < RealtimeSolar.DAYLIGHT_END_TICK;
            double rate = daylight ? daylightRate : nightRate;
            double boundary = daylight ? RealtimeSolar.DAYLIGHT_END_TICK : TICKS_PER_DAY;
            double ticksToBoundary = boundary - timeOfDay;
            double secondsToBoundary = ticksToBoundary / rate;

            if (secondsToBoundary > remainingSeconds) {
                customAbsoluteTicks += remainingSeconds * rate;
                return;
            }
            customAbsoluteTicks += ticksToBoundary;
            remainingSeconds -= secondsToBoundary;
        }

        if (remainingSeconds > 0.0D) {
            // Catch-up far longer than the segment bound: finish at the average rate.
            customAbsoluteTicks += remainingSeconds * (TICKS_PER_DAY / daySeconds);
        }
    }

    private static double sanitizeDaylightFraction(double daylightFraction) {
        if (!Double.isFinite(daylightFraction)) {
            return RealtimeSolar.UNIFORM_DAYLIGHT_FRACTION;
        }
        return Math.max(0.01D, Math.min(0.99D, daylightFraction));
    }

    /** Day of the year for the configured zone, used to derive the seasonal daylight share. */
    public int currentDayOfYear(ZoneId zoneId, int offsetMinutes) {
        return currentDateTime(zoneId, offsetMinutes).getDayOfYear();
    }

    /** Calendar month for the configured zone, used to name the current season. */
    public int currentMonth(ZoneId zoneId, int offsetMinutes) {
        return currentDateTime(zoneId, offsetMinutes).getMonthValue();
    }

    public long calculateSmoothAbsoluteTicks(
            long currentAbsolute,
            long targetAbsolute,
            double elapsedSeconds,
            RealtimeConfig config,
            SmoothState state
    ) {
        long delta = safeSubtract(targetAbsolute, currentAbsolute);
        long absoluteDelta = safeAbs(delta);
        int direction = Long.compare(delta, 0L);
        if (absoluteDelta <= config.smoothSnapThresholdTicks) {
            state.reset();
            return targetAbsolute;
        }
        if (state.direction != 0 && state.direction != direction) {
            state.fractionalCorrection = 0.0D;
        }
        state.direction = direction;

        if (absoluteDelta > TICKS_PER_DAY) {
            if (RealtimeConfig.LARGE_JUMP_SNAP.equals(config.smoothLargeJumpPolicy)) {
                state.reset();
                return targetAbsolute;
            }
            if (RealtimeConfig.LARGE_JUMP_PAUSE_AND_WARN.equals(config.smoothLargeJumpPolicy)) {
                return currentAbsolute;
            }
        }

        double boundedElapsed = Math.max(0.0D, elapsedSeconds);
        boundedElapsed = Math.min(boundedElapsed, config.maximumOfflineCatchUpSeconds);
        if (boundedElapsed == 0.0D) {
            return currentAbsolute;
        }

        double adaptiveRate = Math.max(1.0D, absoluteDelta / (double) Math.max(1, config.smoothCatchupDivisor));
        double correctionRate = Math.min(config.smoothMaxCorrectionTicksPerSecond, adaptiveRate);
        double available = correctionRate * boundedElapsed + state.fractionalCorrection;
        long magnitude = Math.min(absoluteDelta, (long) Math.floor(available));
        state.fractionalCorrection = available - magnitude;
        if (magnitude <= 0L) {
            return currentAbsolute;
        }
        return delta > 0L ? safeAdd(currentAbsolute, magnitude) : safeAdd(currentAbsolute, -magnitude);
    }

    public double elapsedSeconds(long previousNanos, long currentNanos, int maximumCatchUpSeconds) {
        if (previousNanos == Long.MIN_VALUE) {
            return 0.0D;
        }
        double elapsed = nonNegativeElapsed(previousNanos, currentNanos) / NANOS_PER_SECOND;
        return Math.min(elapsed, Math.max(0, maximumCatchUpSeconds));
    }

    public boolean isPausedLargeJump(long currentAbsolute, long targetAbsolute, RealtimeConfig config) {
        return RealtimeConfig.LARGE_JUMP_PAUSE_AND_WARN.equals(config.smoothLargeJumpPolicy)
                && safeAbs(safeSubtract(targetAbsolute, currentAbsolute)) > TICKS_PER_DAY;
    }

    public void initializeCustomClock(double absoluteTicks, long nowNanos) {
        customAbsoluteTicks = absoluteTicks;
        lastCustomUpdateNanos = nowNanos;
    }

    public double customClockValue() {
        return customAbsoluteTicks;
    }

    public void resetCustomClock() {
        lastCustomUpdateNanos = Long.MIN_VALUE;
        customAbsoluteTicks = 0.0D;
    }

    /**
     * Accepts the world time produced by a vanilla sleep skip and schedules a monotonic
     * realignment back to real time.
     *
     * <p>Closing the gap by moving the world clock backwards would rewind the sun, so the
     * offset is instead grown forward until it wraps a whole Minecraft day. The world clock
     * therefore runs faster than real time for {@code realignMinutes} and then continues at
     * exactly real-time speed.</p>
     */
    public void beginSleepRealign(long worldTimeOfDay, long realTimeOfDay, int realignMinutes, long nowNanos) {
        long offset = Math.floorMod(worldTimeOfDay - realTimeOfDay, TICKS_PER_DAY);
        if (offset == 0L) {
            resetSleepRealign();
            return;
        }
        sleepOffsetTicks = offset;
        double seconds = Math.max(1.0D, realignMinutes * 60.0D);
        sleepRealignRatePerSecond = (TICKS_PER_DAY - offset) / seconds;
        lastSleepOffsetNanos = nowNanos;
    }

    /** Restores a realignment window that was interrupted by a server restart. */
    public void restoreSleepRealign(double offsetTicks, double ratePerSecond, long nowNanos) {
        if (!Double.isFinite(offsetTicks) || !Double.isFinite(ratePerSecond)
                || offsetTicks <= 0.0D || offsetTicks >= TICKS_PER_DAY || ratePerSecond <= 0.0D) {
            resetSleepRealign();
            return;
        }
        sleepOffsetTicks = offsetTicks;
        sleepRealignRatePerSecond = ratePerSecond;
        lastSleepOffsetNanos = nowNanos;
    }

    /** Advances the realignment. Ends the window once a full day has been recovered. */
    public void advanceSleepOffset(long nowNanos, int maximumCatchUpSeconds) {
        if (sleepRealignRatePerSecond <= 0.0D) {
            return;
        }
        if (lastSleepOffsetNanos == Long.MIN_VALUE) {
            lastSleepOffsetNanos = nowNanos;
            return;
        }
        double elapsedSeconds = nonNegativeElapsed(lastSleepOffsetNanos, nowNanos) / NANOS_PER_SECOND;
        if (maximumCatchUpSeconds >= 0) {
            elapsedSeconds = Math.min(elapsedSeconds, maximumCatchUpSeconds);
        }
        lastSleepOffsetNanos = nowNanos;
        sleepOffsetTicks += sleepRealignRatePerSecond * elapsedSeconds;
        if (!Double.isFinite(sleepOffsetTicks) || sleepOffsetTicks >= TICKS_PER_DAY) {
            resetSleepRealign();
        }
    }

    /** Shifts a real time-of-day value by the active sleep offset. */
    public long applySleepOffset(long realTimeOfDay) {
        if (sleepOffsetTicks <= 0.0D) {
            return Math.floorMod(realTimeOfDay, TICKS_PER_DAY);
        }
        return Math.floorMod(realTimeOfDay + (long) Math.floor(sleepOffsetTicks), TICKS_PER_DAY);
    }

    public long sleepOffsetTicks() {
        return (long) Math.floor(sleepOffsetTicks);
    }

    public double sleepOffsetTicksExact() {
        return sleepOffsetTicks;
    }

    public double sleepRealignRatePerSecond() {
        return sleepRealignRatePerSecond;
    }

    public boolean isRealigningAfterSleep() {
        return sleepRealignRatePerSecond > 0.0D;
    }

    /** Remaining realignment time in seconds, or {@code 0} when no window is active. */
    public long sleepRealignRemainingSeconds() {
        if (sleepRealignRatePerSecond <= 0.0D) {
            return 0L;
        }
        return (long) Math.ceil((TICKS_PER_DAY - sleepOffsetTicks) / sleepRealignRatePerSecond);
    }

    /** Multiplier the world clock currently runs at compared to real time. */
    public double sleepRealignSpeedMultiplier() {
        if (sleepRealignRatePerSecond <= 0.0D) {
            return 1.0D;
        }
        return 1.0D + sleepRealignRatePerSecond / REAL_TICKS_PER_SECOND;
    }

    public void resetSleepRealign() {
        sleepOffsetTicks = 0.0D;
        sleepRealignRatePerSecond = 0.0D;
        lastSleepOffsetNanos = Long.MIN_VALUE;
    }

    public void resetRealtimeAnchor() {
        // Kept as a compatibility hook for controller reloads. Current policies are stateless.
    }

    private static long nonNegativeElapsed(long previous, long current) {
        if (current < previous) {
            return 0L;
        }
        return current - previous;
    }

    private static long safeSubtract(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException exception) {
            return left >= right ? Long.MAX_VALUE : Long.MIN_VALUE + 1L;
        }
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return right >= 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    private static long safeAbs(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    public static final class SmoothState {
        private double fractionalCorrection;
        private int direction;

        public void reset() {
            fractionalCorrection = 0.0D;
            direction = 0;
        }
    }
}
