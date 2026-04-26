package com.realtime.common;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public final class RealtimeMath {
    public static final long TICKS_PER_DAY = 24000L;
    private static final long SECONDS_PER_DAY = 86400L;
    private static final long MINECRAFT_DAY_START_SECONDS = 6L * 60L * 60L;

    private double customTicks = 0.0D;
    private boolean customTicksInitialized = false;

    public long calculateRealtimeTicks(int offsetHours) {
        customTicksInitialized = false;

        LocalTime realTime = ZonedDateTime.now(ZoneId.systemDefault())
                .plusHours(offsetHours)
                .toLocalTime();

        long secondsFromMinecraftMorning = Math.floorMod(
                realTime.toSecondOfDay() - MINECRAFT_DAY_START_SECONDS,
                SECONDS_PER_DAY
        );

        return Math.floorMod(Math.round(secondsFromMinecraftMorning * (TICKS_PER_DAY / (double) SECONDS_PER_DAY)), TICKS_PER_DAY);
    }

    public long calculateCustomTicks(long currentOverworldTime, int updateIntervalTicks, int customDayLengthMinutes) {
        if (!customTicksInitialized) {
            customTicks = Math.floorMod(currentOverworldTime, TICKS_PER_DAY);
            customTicksInitialized = true;
        }

        double ticksPerUpdate = updateIntervalTicks * TICKS_PER_DAY / (customDayLengthMinutes * 60.0D * 20.0D);
        customTicks = (customTicks + ticksPerUpdate) % TICKS_PER_DAY;
        return (long) customTicks;
    }

    public void resetCustomTicks() {
        customTicksInitialized = false;
    }
}
