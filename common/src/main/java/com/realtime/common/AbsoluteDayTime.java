package com.realtime.common;

/** Utilities for working with Minecraft's absolute day-time counter. */
public final class AbsoluteDayTime {
    public static final long TICKS_PER_DAY = 24_000L;

    private AbsoluteDayTime() {
    }

    public static long dayIndex(long absoluteTicks) {
        return Math.floorDiv(absoluteTicks, TICKS_PER_DAY);
    }

    public static long timeOfDay(long absoluteTicks) {
        return Math.floorMod(absoluteTicks, TICKS_PER_DAY);
    }

    public static long compose(long dayIndex, long timeOfDay) {
        return Math.addExact(Math.multiplyExact(dayIndex, TICKS_PER_DAY), Math.floorMod(timeOfDay, TICKS_PER_DAY));
    }

    public static long nearestForward(long currentAbsolute, long targetTimeOfDay) {
        long candidate = compose(dayIndex(currentAbsolute), targetTimeOfDay);
        return candidate < currentAbsolute ? Math.addExact(candidate, TICKS_PER_DAY) : candidate;
    }

    /**
     * Preserves a non-decreasing absolute day counter without treating a small host-clock
     * correction backwards as a jump to the next Minecraft day. A wrap is accepted only
     * when the target crossed more than half of the 24,000-tick circle (for example 23999 -> 0).
     */
    public static long preserveMonotonic(long currentAbsolute, long targetTimeOfDay) {
        long currentTimeOfDay = timeOfDay(currentAbsolute);
        long target = Math.floorMod(targetTimeOfDay, TICKS_PER_DAY);
        if (target >= currentTimeOfDay) {
            return compose(dayIndex(currentAbsolute), target);
        }
        if (currentTimeOfDay - target > TICKS_PER_DAY / 2L) {
            return Math.addExact(compose(dayIndex(currentAbsolute), target), TICKS_PER_DAY);
        }
        // The host clock moved backwards by less than half a day. Freeze until it catches up.
        return currentAbsolute;
    }

    public static long nearestCircular(long currentAbsolute, long targetTimeOfDay) {
        long sameDay = compose(dayIndex(currentAbsolute), targetTimeOfDay);
        long previous = Math.subtractExact(sameDay, TICKS_PER_DAY);
        long next = Math.addExact(sameDay, TICKS_PER_DAY);

        long best = sameDay;
        long bestDistance = distance(currentAbsolute, sameDay);
        long previousDistance = distance(currentAbsolute, previous);
        if (previousDistance < bestDistance) {
            best = previous;
            bestDistance = previousDistance;
        }
        long nextDistance = distance(currentAbsolute, next);
        if (nextDistance < bestDistance) {
            best = next;
        }
        return best;
    }

    public static long preserveCurrentDay(long currentAbsolute, long targetTimeOfDay) {
        return compose(dayIndex(currentAbsolute), targetTimeOfDay);
    }

    public static long shortestCircularDelta(long currentTimeOfDay, long targetTimeOfDay) {
        long current = Math.floorMod(currentTimeOfDay, TICKS_PER_DAY);
        long target = Math.floorMod(targetTimeOfDay, TICKS_PER_DAY);
        long delta = target - current;
        if (delta > TICKS_PER_DAY / 2L) {
            delta -= TICKS_PER_DAY;
        } else if (delta < -TICKS_PER_DAY / 2L) {
            delta += TICKS_PER_DAY;
        }
        return delta;
    }

    private static long distance(long first, long second) {
        try {
            long delta = Math.subtractExact(first, second);
            return delta == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(delta);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
