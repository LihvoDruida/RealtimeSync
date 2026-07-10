package com.realtime.common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Build metadata embedded into every loader artifact. */
public record RealtimeBuildInfo(String version, String minecraftVersion, String loader) {
    private static final String RESOURCE = "realtime-build.properties";
    private static final RealtimeBuildInfo CURRENT = load();

    public static RealtimeBuildInfo current() {
        return CURRENT;
    }

    private static RealtimeBuildInfo load() {
        Properties properties = new Properties();
        try (InputStream input = RealtimeBuildInfo.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) {
                return unknown();
            }
            properties.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            return new RealtimeBuildInfo(
                    sanitize(properties.getProperty("version")),
                    sanitize(properties.getProperty("minecraft")),
                    sanitize(properties.getProperty("loader"))
            );
        } catch (IOException | RuntimeException ignored) {
            return unknown();
        }
    }

    private static RealtimeBuildInfo unknown() {
        return new RealtimeBuildInfo("unknown", "unknown", "unknown");
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim();
    }
}
