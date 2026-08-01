package com.realtime.common;

/**
 * Real-world day/night proportions for a given calendar day and latitude.
 *
 * <p>Minecraft cannot move sunrise and sunset: the sun angle is the tick value, daylight is
 * always ticks 0 to {@link #DAYLIGHT_END_TICK} and night is always the rest. Seasons are
 * therefore expressed by varying how much <em>real</em> time each half of the Minecraft day
 * is allowed to take, which is what {@link #daylightFraction} provides.</p>
 *
 * <p>The model is the standard sunrise equation using solar declination and the hour angle.
 * It ignores atmospheric refraction and the angular radius of the solar disc, so results run
 * roughly ten minutes shorter than published sunrise/sunset tables. That is well inside the
 * resolution of a compressed Minecraft day.</p>
 */
public final class RealtimeSolar {
    /** Tick at which Minecraft daylight ends and dusk begins. */
    public static final long DAYLIGHT_END_TICK = 12_000L;
    /** Daylight fraction that reproduces a uniform clock: half the day, half the night. */
    public static final double UNIFORM_DAYLIGHT_FRACTION = 0.5D;

    private static final double AXIAL_TILT_DEGREES = 23.44D;
    private static final double DAYS_PER_YEAR = 365.24D;
    /** Days between the December solstice and January 1st. */
    private static final double SOLSTICE_OFFSET_DAYS = 10.0D;

    private RealtimeSolar() {
    }

    /**
     * Fraction of a 24 hour day that is daylight, in [0, 1].
     *
     * @param dayOfYear 1 to 366
     * @param latitudeDegrees positive north, negative south
     */
    public static double daylightFraction(int dayOfYear, double latitudeDegrees) {
        double declination = Math.toRadians(-AXIAL_TILT_DEGREES)
                * Math.cos(2.0D * Math.PI / DAYS_PER_YEAR * (dayOfYear + SOLSTICE_OFFSET_DAYS));
        double latitude = Math.toRadians(clampLatitude(latitudeDegrees));
        double cosHourAngle = -Math.tan(latitude) * Math.tan(declination);
        if (!Double.isFinite(cosHourAngle) || cosHourAngle <= -1.0D) {
            return 1.0D; // Midnight sun.
        }
        if (cosHourAngle >= 1.0D) {
            return 0.0D; // Polar night.
        }
        return Math.acos(cosHourAngle) / Math.PI;
    }

    /**
     * Daylight fraction clamped to a usable range.
     *
     * <p>Polar latitudes produce fractions of zero or one, which would stop the Minecraft
     * clock in one half of the cycle. The clamp keeps both halves finite while preserving the
     * seasonal shape everywhere else.</p>
     */
    public static double clampedDaylightFraction(
            int dayOfYear,
            double latitudeDegrees,
            double minimumFraction,
            double maximumFraction
    ) {
        double low = Math.min(minimumFraction, maximumFraction);
        double high = Math.max(minimumFraction, maximumFraction);
        low = Math.max(0.01D, Math.min(0.99D, low));
        high = Math.max(0.01D, Math.min(0.99D, high));
        return Math.max(low, Math.min(high, daylightFraction(dayOfYear, latitudeDegrees)));
    }

    /** Meteorological season for the given month, flipped for the southern hemisphere. */
    public static String seasonName(int month, double latitudeDegrees) {
        String northern = switch (month) {
            case 3, 4, 5 -> "SPRING";
            case 6, 7, 8 -> "SUMMER";
            case 9, 10, 11 -> "AUTUMN";
            default -> "WINTER";
        };
        if (latitudeDegrees >= 0.0D) {
            return northern;
        }
        return switch (northern) {
            case "SPRING" -> "AUTUMN";
            case "SUMMER" -> "WINTER";
            case "AUTUMN" -> "SPRING";
            default -> "SUMMER";
        };
    }

    /** Real seconds of daylight in a compressed Minecraft day of {@code dayLengthMinutes}. */
    public static double daylightSeconds(int dayLengthMinutes, double daylightFraction) {
        return dayLengthMinutes * 60.0D * daylightFraction;
    }

    private static double clampLatitude(double latitudeDegrees) {
        if (!Double.isFinite(latitudeDegrees)) {
            return 0.0D;
        }
        return Math.max(-89.9D, Math.min(89.9D, latitudeDegrees));
    }
}
