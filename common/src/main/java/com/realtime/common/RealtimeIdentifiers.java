package com.realtime.common;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Single source of truth for namespaced dimension identifiers.
 *
 * <p>The pattern is compiled once. {@code String.matches} used to be called on the
 * per-update code path, which recompiled the regular expression on every dimension
 * of every synchronization pass.</p>
 */
public final class RealtimeIdentifiers {
    private static final Pattern NAMESPACED_IDENTIFIER =
            Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private RealtimeIdentifiers() {
    }

    /** Returns a trimmed, lower-cased namespaced identifier, or {@code null} when invalid. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String value = toLowerCaseIfNeeded(trimmed);
        return NAMESPACED_IDENTIFIER.matcher(value).matches() ? value : null;
    }

    /** Avoids allocating a new string for the common already-lower-case case. */
    private static String toLowerCaseIfNeeded(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current >= 'A' && current <= 'Z') {
                return value.toLowerCase(Locale.ROOT);
            }
        }
        return value;
    }
}
