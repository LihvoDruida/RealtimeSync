package com.realtime.common;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Properties;

/** Small internal state file used only by PERSIST_REAL_ELAPSED custom-clock policy. */
final class RealtimePersistentState {
    private RealtimePersistentState() {
    }

    static Snapshot load(Path path, RealtimeLog logger) {
        if (!Files.exists(path)) {
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
            double ticks = Double.parseDouble(properties.getProperty("customAbsoluteTicks"));
            long savedEpochMillis = Long.parseLong(properties.getProperty("savedEpochMillis"));
            if (!Double.isFinite(ticks)) {
                throw new NumberFormatException("customAbsoluteTicks is not finite");
            }
            // Sleep realignment keys are optional so state files written by older builds still load.
            double sleepOffsetTicks = readOptionalDouble(properties, "sleepOffsetTicks");
            double sleepRealignRatePerSecond = readOptionalDouble(properties, "sleepRealignRatePerSecond");
            return new Snapshot(ticks, Instant.ofEpochMilli(savedEpochMillis), sleepOffsetTicks, sleepRealignRatePerSecond);
        } catch (IOException | RuntimeException exception) {
            logger.warn("Ignoring invalid RealtimeSync persistent state. {}", exception.getMessage());
            return null;
        }
    }

    private static double readOptionalDouble(Properties properties, String key) {
        String raw = properties.getProperty(key);
        if (raw == null) {
            return 0.0D;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? value : 0.0D;
        } catch (NumberFormatException exception) {
            return 0.0D;
        }
    }

    static void save(
            Path path,
            double customAbsoluteTicks,
            Instant savedAt,
            double sleepOffsetTicks,
            double sleepRealignRatePerSecond,
            RealtimeLog logger
    ) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String content = "# Internal RealtimeSync state; do not edit while the server is running.\n"
                    + "customAbsoluteTicks=" + customAbsoluteTicks + "\n"
                    + "savedEpochMillis=" + savedAt.toEpochMilli() + "\n"
                    + "sleepOffsetTicks=" + sleepOffsetTicks + "\n"
                    + "sleepRealignRatePerSecond=" + sleepRealignRatePerSecond + "\n";
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            logger.warn("Failed to save RealtimeSync persistent state. {}", exception.getMessage());
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Best effort only.
            }
        }
    }

    record Snapshot(
            double customAbsoluteTicks,
            Instant savedAt,
            double sleepOffsetTicks,
            double sleepRealignRatePerSecond
    ) {
    }
}
