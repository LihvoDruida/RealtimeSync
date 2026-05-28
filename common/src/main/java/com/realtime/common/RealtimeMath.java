package com.realtime.common;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class RealtimeMath {
    public static final long TICKS_PER_DAY = 24000L;
    private static final long SECONDS_PER_DAY = 86400L;
    private static final long MINECRAFT_DAY_START_SECONDS = 6L * 60L * 60L;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0D;

    private final Clock realtimeClock;

    private double customTicks = 0.0D;
    private long lastCustomUpdateNanos = Long.MIN_VALUE;

    public RealtimeMath() {
        this(Clock.systemDefaultZone());
    }

    RealtimeMath(Clock realtimeClock) {
        this.realtimeClock = realtimeClock;
    }

    public long calculateRealtimeTicks(int offsetHours) {
        resetCustomTicks();

        LocalTime realTime = ZonedDateTime.now(realtimeClock)
                .plusHours(offsetHours)
                .toLocalTime();

        long secondsFromMinecraftMorning = Math.floorMod(
                realTime.toSecondOfDay() - MINECRAFT_DAY_START_SECONDS,
                SECONDS_PER_DAY
        );

        return Math.floorMod(Math.round(secondsFromMinecraftMorning * (TICKS_PER_DAY / (double) SECONDS_PER_DAY)), TICKS_PER_DAY);
    }

    public long calculateCustomTicks(long currentOverworldTime, int customDayLengthMinutes) {
        return calculateCustomTicks(currentOverworldTime, customDayLengthMinutes, System.nanoTime());
    }

    long calculateCustomTicks(long currentOverworldTime, int customDayLengthMinutes, long nowNanos) {
        if (customDayLengthMinutes <= 0) {
            resetCustomTicks();
            return Math.floorMod(currentOverworldTime, TICKS_PER_DAY);
        }

        if (lastCustomUpdateNanos == Long.MIN_VALUE) {
            customTicks = Math.floorMod(currentOverworldTime, TICKS_PER_DAY);
            lastCustomUpdateNanos = nowNanos;
            return (long) customTicks;
        }

        long elapsedNanos = Math.max(0L, nowNanos - lastCustomUpdateNanos);
        lastCustomUpdateNanos = nowNanos;

        double secondsPerCustomDay = customDayLengthMinutes * 60.0D;
        double ticksPerSecond = TICKS_PER_DAY / secondsPerCustomDay;
        customTicks = wrapTicks(customTicks + (elapsedNanos / NANOS_PER_SECOND) * ticksPerSecond);
        return (long) customTicks;
    }

    public long calculateSmoothTicks(long currentDayTime, long targetDayTime, int maxStepTicks, int snapThresholdTicks, int catchupDivisor) {
        long currentWrapped = Math.floorMod(currentDayTime, TICKS_PER_DAY);
        long targetWrapped = Math.floorMod(targetDayTime, TICKS_PER_DAY);
        long delta = shortestDelta(currentWrapped, targetWrapped);
        long absoluteDelta = Math.abs(delta);

        int safeSnapThreshold = Math.max(0, snapThresholdTicks);
        if (absoluteDelta <= safeSnapThreshold) {
            return targetWrapped;
        }

        int safeMaxStep = Math.max(1, maxStepTicks);
        int safeCatchupDivisor = Math.max(1, catchupDivisor);
        long adaptiveStep = Math.max(1L, (long) Math.ceil(absoluteDelta / (double) safeCatchupDivisor));
        long appliedMagnitude = Math.min(safeMaxStep, Math.min(absoluteDelta, adaptiveStep));
        long appliedDelta = delta > 0 ? appliedMagnitude : -appliedMagnitude;

        return Math.floorMod(currentDayTime + appliedDelta, TICKS_PER_DAY);
    }

    private long shortestDelta(long currentWrapped, long targetWrapped) {
        long delta = targetWrapped - currentWrapped;

        if (delta > TICKS_PER_DAY / 2L) {
            delta -= TICKS_PER_DAY;
        } else if (delta < -TICKS_PER_DAY / 2L) {
            delta += TICKS_PER_DAY;
        }

        return delta;
    }

    private static double wrapTicks(double ticks) {
        double wrapped = ticks % TICKS_PER_DAY;
        return wrapped < 0.0D ? wrapped + TICKS_PER_DAY : wrapped;
    }

    public void resetCustomTicks() {
        lastCustomUpdateNanos = Long.MIN_VALUE;
    }
}
