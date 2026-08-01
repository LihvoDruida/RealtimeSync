package com.realtime.common;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

public final class CoreLogicTest {
    private CoreLogicTest() {
    }

    public static void main(String[] args) throws Exception {
        testAbsoluteDayTime();
        testRealtimeConversion();
        testAbsoluteRealtimePolicies();
        testSmoothElapsedTime();
        testCustomClock();
        testUtf8ConfigAndLegacyMigration();
        testDimensionIdentifiers();
        testDimensionFiltering();
        testLargeJumpPauseDetection();
        testSleepRealign();
        testSleepPolicyResolution();
        testSolarSeasons();
        testSeasonalCustomClock();
        System.out.println("Core logic tests passed.");
    }

    private static void testAbsoluteDayTime() {
        assertEquals(0L, AbsoluteDayTime.dayIndex(0L), "day 0");
        assertEquals(0L, AbsoluteDayTime.dayIndex(23_999L), "last tick day 0");
        assertEquals(1L, AbsoluteDayTime.dayIndex(24_000L), "day 1");
        assertEquals(2L, AbsoluteDayTime.dayIndex(48_000L), "day 2");
        assertEquals(5_144L, AbsoluteDayTime.dayIndex(123_456_789L), "large day index");
        assertEquals(24_001L, AbsoluteDayTime.compose(1L, 1L), "compose absolute time");
        assertEquals(24_000L, AbsoluteDayTime.nearestForward(23_999L, 0L), "midnight moves forward");
        assertEquals(1L, AbsoluteDayTime.shortestCircularDelta(23_999L, 0L), "forward wrap delta");
        assertEquals(-1L, AbsoluteDayTime.shortestCircularDelta(0L, 23_999L), "backward wrap delta");
    }

    private static void testRealtimeConversion() {
        assertEquals(0L, RealtimeMath.timeOfDayFromLocalTime(LocalTime.of(6, 0)), "06:00");
        assertEquals(6_000L, RealtimeMath.timeOfDayFromLocalTime(LocalTime.NOON), "12:00");
        assertEquals(12_000L, RealtimeMath.timeOfDayFromLocalTime(LocalTime.of(18, 0)), "18:00");
        assertEquals(18_000L, RealtimeMath.timeOfDayFromLocalTime(LocalTime.MIDNIGHT), "00:00");
        assertEquals(17_999L, RealtimeMath.timeOfDayFromLocalTime(LocalTime.of(23, 59, 59)), "23:59:59 does not wrap early");
        assertEquals(17_999L, RealtimeMath.timeOfDayFromLocalTime(LocalTime.MAX), "last nanosecond does not wrap early");
    }

    private static void testAbsoluteRealtimePolicies() throws Exception {
        RealtimeConfig monotonic = new RealtimeConfig();
        monotonic.dayProgressionPolicy = RealtimeConfig.DAY_PROGRESSION_PRESERVE_MONOTONIC;
        RealtimeMath math = new RealtimeMath(Clock.fixed(Instant.parse("2026-07-10T03:00:00Z"), ZoneOffset.UTC), () -> 0L);
        assertEquals(48_000L, math.resolveRealtimeAbsoluteTarget(47_999L, 0L, monotonic), "monotonic 23999 -> 0 advances day");
        assertEquals(48_100L, math.resolveRealtimeAbsoluteTarget(48_100L, 50L, monotonic), "small host-clock rollback does not jump a day");
        assertEquals(72_000L, math.resolveRealtimeAbsoluteTarget(71_999L, 0L, monotonic), "large circular wrap advances day");

        RealtimeConfig anchor = configForAnchorTest();
        RealtimeMath beforeMidnight = new RealtimeMath(Clock.fixed(Instant.parse("2026-07-10T23:59:59Z"), ZoneOffset.UTC), () -> 0L);
        long beforeTarget = beforeMidnight.calculateRealtimeTimeOfDay(ZoneId.of("UTC"), 0);
        long beforeAbsolute = beforeMidnight.resolveRealtimeAbsoluteTarget(0L, beforeTarget, anchor);
        RealtimeMath atMidnight = new RealtimeMath(Clock.fixed(Instant.parse("2026-07-11T00:00:00Z"), ZoneOffset.UTC), () -> 0L);
        long midnightTarget = atMidnight.calculateRealtimeTimeOfDay(ZoneId.of("UTC"), 0);
        long midnightAbsolute = atMidnight.resolveRealtimeAbsoluteTarget(0L, midnightTarget, anchor);
        assertEquals(1L, midnightAbsolute - beforeAbsolute, "real-date anchor stays continuous at midnight");
    }

    private static void testSmoothElapsedTime() {
        RealtimeConfig config = new RealtimeConfig();
        config.smoothMaxCorrectionTicksPerSecond = 100;
        config.smoothSnapThresholdTicks = 0;
        config.smoothCatchupDivisor = 1;
        config.maximumOfflineCatchUpSeconds = 300;

        long twentyTpsResult = simulateSmooth(config, 20, 0.05D);
        long tenTpsResult = simulateSmooth(config, 10, 0.1D);
        long fiveTpsResult = simulateSmooth(config, 5, 0.2D);
        assertWithin(2L, twentyTpsResult, tenTpsResult, "smooth 20 vs 10 TPS");
        assertWithin(2L, twentyTpsResult, fiveTpsResult, "smooth 20 vs 5 TPS");

        RealtimeMath math = new RealtimeMath(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> 0L);
        RealtimeMath.SmoothState state = new RealtimeMath.SmoothState();
        long capped = math.calculateSmoothAbsoluteTicks(0L, 20_000L, 10_000.0D, config, state);
        assertTrue(capped <= 30_000L, "offline correction remains bounded");
    }

    private static long simulateSmooth(RealtimeConfig config, int updates, double elapsed) {
        RealtimeMath math = new RealtimeMath(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> 0L);
        RealtimeMath.SmoothState state = new RealtimeMath.SmoothState();
        long current = 0L;
        for (int index = 0; index < updates; index++) {
            current = math.calculateSmoothAbsoluteTicks(current, 10_000L, elapsed, config, state);
        }
        return current;
    }

    private static void testCustomClock() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        RealtimeMath math = new RealtimeMath(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), nanos::get);
        assertEquals(48_000L, math.calculateCustomAbsoluteTicks(48_000L, 20, 300, RealtimeSolar.UNIFORM_DAYLIGHT_FRACTION), "custom initializes from absolute world time");
        nanos.addAndGet(60_000_000_000L);
        assertEquals(49_200L, math.calculateCustomAbsoluteTicks(48_000L, 20, 300, RealtimeSolar.UNIFORM_DAYLIGHT_FRACTION), "one real minute in 20-minute day");
        assertEquals(2L, AbsoluteDayTime.dayIndex(49_200L), "custom clock preserves day count");
    }


    private static void testDimensionIdentifiers() {
        assertEquals("minecraft:overworld", RealtimeIdentifiers.normalize("minecraft:overworld"), "plain identifier");
        assertEquals("minecraft:overworld", RealtimeIdentifiers.normalize("  Minecraft:OverWorld  "), "trim and lower-case");
        assertEquals("example:deep/moon", RealtimeIdentifiers.normalize("example:deep/moon"), "path separators allowed");
        assertTrue(RealtimeIdentifiers.normalize(null) == null, "null identifier");
        assertTrue(RealtimeIdentifiers.normalize("") == null, "empty identifier");
        assertTrue(RealtimeIdentifiers.normalize("   ") == null, "blank identifier");
        assertTrue(RealtimeIdentifiers.normalize("overworld") == null, "missing namespace");
        assertTrue(RealtimeIdentifiers.normalize("mine craft:overworld") == null, "space inside identifier");
        assertTrue(RealtimeIdentifiers.normalize("майнкрафт:світ") == null, "non-ascii identifier");
    }

    private static void testDimensionFiltering() throws Exception {
        Path directory = Files.createTempDirectory("realtime-dimension-test");
        Path config = directory.resolve("realtime.properties");
        Files.writeString(config,
                "syncAllWorlds=true\n"
                        + "syncDimensions=Minecraft:Overworld, example:moon ,,not-an-id\n"
                        + "ignoredDimensions=example:moon\n",
                StandardCharsets.UTF_8);
        RealtimeConfig loaded = RealtimeConfig.loadOrCreate(config, null, new TestLog());

        assertEquals(2L, loaded.syncDimensionSet().size(), "invalid identifiers are dropped");
        assertTrue(loaded.syncDimensionSet().contains("minecraft:overworld"), "allowlist normalized");
        assertTrue(loaded.syncDimensionSet().contains("example:moon"), "allowlist keeps custom dimension");
        assertTrue(!loaded.syncDimensionSet().contains("not-an-id"), "malformed identifier rejected");
        assertTrue(loaded.ignoredDimensionSet().contains("example:moon"), "denylist normalized");
        assertEquals("minecraft:overworld,example:moon", loaded.syncDimensions, "canonical allowlist order preserved");
    }

    private static void testLargeJumpPauseDetection() {
        RealtimeConfig config = new RealtimeConfig();
        config.smoothLargeJumpPolicy = RealtimeConfig.LARGE_JUMP_PAUSE_AND_WARN;
        RealtimeMath math = new RealtimeMath(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> 0L);

        assertTrue(math.isPausedLargeJump(0L, 30_000L, config), "difference beyond one day pauses");
        assertTrue(!math.isPausedLargeJump(0L, 20_000L, config), "difference within one day does not pause");

        RealtimeConfig gradual = new RealtimeConfig();
        gradual.smoothLargeJumpPolicy = RealtimeConfig.LARGE_JUMP_GRADUAL;
        assertTrue(!math.isPausedLargeJump(0L, 30_000L, gradual), "GRADUAL never reports a pause");
    }

    private static void testSolarSeasons() {
        // At the equator the sun is up for half the day all year round.
        assertNear(0.5D, RealtimeSolar.daylightFraction(172, 0.0D), 0.01D, "equator midsummer");
        assertNear(0.5D, RealtimeSolar.daylightFraction(355, 0.0D), 0.01D, "equator midwinter");

        // Mid-northern latitude: long summer days, long winter nights.
        double summer = RealtimeSolar.daylightFraction(172, 50.0D);
        double winter = RealtimeSolar.daylightFraction(355, 50.0D);
        double equinox = RealtimeSolar.daylightFraction(80, 50.0D);
        assertNear(0.673D, summer, 0.02D, "50N around the June solstice is about 16 hours of daylight");
        assertNear(0.327D, winter, 0.02D, "50N around the December solstice is about 8 hours of daylight");
        assertNear(0.5D, equinox, 0.02D, "50N at the equinox is an even split");
        assertTrue(summer > equinox && equinox > winter, "daylight shrinks from summer to winter");

        // The southern hemisphere is mirrored.
        assertNear(winter, RealtimeSolar.daylightFraction(172, -50.0D), 0.01D, "June is winter south of the equator");
        assertEquals("WINTER", RealtimeSolar.seasonName(6, -50.0D), "June is winter in the south");
        assertEquals("SUMMER", RealtimeSolar.seasonName(6, 50.0D), "June is summer in the north");

        // Polar extremes are clamped so neither half of the cycle can stall.
        assertNear(1.0D, RealtimeSolar.daylightFraction(172, 80.0D), 0.0D, "midnight sun");
        assertNear(0.0D, RealtimeSolar.daylightFraction(355, 80.0D), 0.0D, "polar night");
        assertNear(0.75D, RealtimeSolar.clampedDaylightFraction(172, 80.0D, 0.25D, 0.75D), 0.001D, "midnight sun is clamped");
        assertNear(0.25D, RealtimeSolar.clampedDaylightFraction(355, 80.0D, 0.25D, 0.75D), 0.001D, "polar night is clamped");
    }

    private static void testSeasonalCustomClock() {
        AtomicLong nanos = new AtomicLong(0L);
        RealtimeMath math = new RealtimeMath(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), nanos::get);

        // A 60-minute day where three quarters of the cycle is daylight: 45 real minutes
        // must carry the clock exactly from sunrise to sunset, and 15 more to sunrise again.
        int dayLengthMinutes = 60;
        double fraction = 0.75D;
        assertEquals(0L, math.calculateCustomAbsoluteTicks(0L, dayLengthMinutes, 7_200, fraction), "clock starts at sunrise");

        nanos.addAndGet(45L * 60L * 1_000_000_000L);
        assertEquals(RealtimeSolar.DAYLIGHT_END_TICK,
                math.calculateCustomAbsoluteTicks(0L, dayLengthMinutes, 7_200, fraction),
                "45 real minutes of a 75% daylight day reach sunset");

        nanos.addAndGet(15L * 60L * 1_000_000_000L);
        assertEquals(AbsoluteDayTime.TICKS_PER_DAY,
                math.calculateCustomAbsoluteTicks(0L, dayLengthMinutes, 7_200, fraction),
                "the remaining 15 real minutes cover the whole night");

        // An update that spans sunset uses both rates, so a full cycle still takes exactly
        // one configured day even when it is consumed in a single step.
        AtomicLong single = new AtomicLong(0L);
        RealtimeMath spanning = new RealtimeMath(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), single::get);
        spanning.calculateCustomAbsoluteTicks(0L, dayLengthMinutes, 7_200, fraction);
        single.addAndGet(60L * 60L * 1_000_000_000L);
        assertEquals(AbsoluteDayTime.TICKS_PER_DAY,
                spanning.calculateCustomAbsoluteTicks(0L, dayLengthMinutes, 7_200, fraction),
                "one real hour is exactly one Minecraft day regardless of update size");
    }

    private static void assertNear(double expected, double actual, double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(message + ": expected " + expected + " +/- " + tolerance + " but was " + actual);
        }
    }

    private static void testSleepRealign() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        RealtimeMath math = new RealtimeMath(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), nanos::get);

        // Real clock at 01:00 maps to tick 19000. A vanilla sleep skip lands on 06:00, tick 0.
        long realTimeOfDay = RealtimeMath.timeOfDayFromLocalTime(LocalTime.of(1, 0));
        assertEquals(19_000L, realTimeOfDay, "01:00 maps to tick 19000");

        math.beginSleepRealign(0L, realTimeOfDay, 60, nanos.get());
        assertEquals(5_000L, math.sleepOffsetTicks(), "offset adopts the skipped morning");
        assertEquals(0L, math.applySleepOffset(realTimeOfDay), "target matches the post-sleep world time");
        assertTrue(math.isRealigningAfterSleep(), "realignment window is active");
        assertTrue(math.sleepRealignSpeedMultiplier() > 1.0D, "world clock runs faster than real time");

        nanos.addAndGet(1_800_000_000_000L);
        math.advanceSleepOffset(nanos.get(), 3_600);
        assertTrue(math.sleepOffsetTicks() > 5_000L, "offset only ever grows, so the sun never moves backwards");
        assertTrue(math.isRealigningAfterSleep(), "window still active halfway through");

        nanos.addAndGet(1_800_000_000_000L);
        math.advanceSleepOffset(nanos.get(), 3_600);
        assertEquals(0L, math.sleepOffsetTicks(), "offset laps a full day and clears");
        assertTrue(!math.isRealigningAfterSleep(), "realignment finished");
        assertEquals(realTimeOfDay, math.applySleepOffset(realTimeOfDay), "world is back on real time");

        // A skip that lands exactly on the real time of day needs no realignment at all.
        math.beginSleepRealign(realTimeOfDay, realTimeOfDay, 60, nanos.get());
        assertTrue(!math.isRealigningAfterSleep(), "no offset means no realignment window");
    }

    private static void testSleepPolicyResolution() throws Exception {
        assertEquals(RealtimeConfig.SLEEP_POLICY_REALTIME_ONLY, new RealtimeConfig().sleepPolicy, "REALTIME_ONLY is the default policy");
        assertEquals(RealtimeConfig.SLEEP_POLICY_REALIGN, sleepPolicyFor("sleepPolicy=realign\n"), "lower-case policy is normalized");
        assertEquals(RealtimeConfig.SLEEP_POLICY_VANILLA, sleepPolicyFor("sleepPolicy=VANILLA\n"), "VANILLA policy is accepted");
        assertEquals(RealtimeConfig.SLEEP_POLICY_REALIGN, sleepPolicyFor("sleepPolicy=nonsense\n"), "invalid policy falls back to REALIGN");
        assertEquals(RealtimeConfig.SLEEP_POLICY_REALTIME_ONLY, sleepPolicyFor("respectSleep=false\n"), "legacy respectSleep=false still wins");
        assertEquals(RealtimeConfig.SLEEP_POLICY_REALTIME_ONLY, sleepPolicyFor("overrideSleepTime=true\n"), "legacy overrideSleepTime=true still wins");
    }

    private static String sleepPolicyFor(String contents) throws Exception {
        Path directory = Files.createTempDirectory("realtime-sleep-policy-test");
        Path config = directory.resolve("realtime.properties");
        Files.writeString(config, contents, StandardCharsets.UTF_8);
        return RealtimeConfig.loadOrCreate(config, null, new TestLog()).effectiveSleepPolicy();
    }

    private static RealtimeConfig configForAnchorTest() throws Exception {
        Path directory = Files.createTempDirectory("realtime-anchor-test");
        Path config = directory.resolve("realtime.properties");
        Files.writeString(config,
                "dayProgressionPolicy=REAL_DATE_ANCHOR\n"
                        + "zoneId=UTC\n"
                        + "realDateAnchor=2026-07-10\n",
                StandardCharsets.UTF_8);
        return RealtimeConfig.loadOrCreate(config, null, new TestLog());
    }

    private static void testUtf8ConfigAndLegacyMigration() throws Exception {
        Path directory = Files.createTempDirectory("realtime-config-test");
        Path config = directory.resolve("realtime.properties");
        Path legacy = directory.resolve("realtime.toml");
        TestLog log = new TestLog();

        Files.writeString(legacy,
                "# Український UTF-8 коментар\n"
                        + "enabled = true\n"
                        + "offsetHours = 2\n"
                        + "forceDaylightCycleOff = true\n"
                        + "maxSmoothStepTicks = 12\n"
                        + "updateInterval = 20\n"
                        + "zoneId = \"Europe/Kiev\"\n"
                        + "syncDimensions = \"minecraft:overworld,example:moon\"\n",
                StandardCharsets.UTF_8);

        RealtimeConfig migrated = RealtimeConfig.loadOrCreate(config, legacy, log);
        assertEquals(120L, migrated.timeOffsetMinutes, "offsetHours migration");
        assertEquals(RealtimeConfig.DAYLIGHT_POLICY_MANAGED, migrated.daylightRulePolicy, "gamerule policy migration");
        assertEquals(12L, migrated.smoothMaxCorrectionTicksPerSecond, "legacy smooth step preserves one-second update behavior");
        assertEquals("Europe/Kyiv", migrated.zoneId, "legacy timezone alias migration");
        assertTrue(Files.exists(legacy.resolveSibling("realtime.toml.bak")), "legacy backup created");
        String canonicalConfig = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(canonicalConfig.contains("timeOffsetMinutes=120"), "new UTF-8 config written");
        assertTrue(canonicalConfig.contains("zoneId=Europe/Kyiv"), "canonical timezone written");
        assertTrue(!canonicalConfig.contains("Europe/Kiev"), "deprecated timezone alias removed");
        assertTrue(!Files.exists(config.resolveSibling("realtime.properties.tmp")), "atomic temp cleaned");

        Files.writeString(config, "zoneId=Not/A_Real_Zone\n", StandardCharsets.UTF_8);
        RealtimeConfig reloaded = RealtimeConfig.reload(config, migrated, log);
        assertEquals("system", reloaded.zoneId, "invalid timezone falls back safely");
    }

    private static void assertEquals(long expected, long actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertWithin(long tolerance, long first, long second, String label) {
        if (Math.abs(first - second) > tolerance) {
            throw new AssertionError(label + ": first=" + first + ", second=" + second);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label);
        }
    }

    private static final class TestLog implements RealtimeLog {
        @Override
        public void info(String message, Object... args) {
        }

        @Override
        public void warn(String message, Object... args) {
        }

        @Override
        public void error(String message, Throwable throwable) {
            throw new AssertionError(message, throwable);
        }
    }
}
