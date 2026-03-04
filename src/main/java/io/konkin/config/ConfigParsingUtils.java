/*
 * Copyright 2026 Peter Geschel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.konkin.config;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static helper methods for parsing TOML values into typed Java objects.
 */
final class ConfigParsingUtils {

    static final Pattern HUMAN_DURATION_PART_PATTERN = Pattern.compile(
            "(?i)(\\d+)\\s*(weeks?|w|days?|d|hours?|hrs?|hr|h|minutes?|mins?|min|m|seconds?|secs?|sec|s)"
    );

    private ConfigParsingUtils() {
    }

    static Duration parseDuration(String value, String keyPath) {
        String normalized = value == null ? "" : value.trim();

        Duration duration = parseIsoDurationOrNull(normalized);
        if (duration == null) {
            duration = parseHumanDurationOrNull(normalized);
        }

        if (duration == null) {
            throw new IllegalStateException(
                    "Invalid config: " + keyPath +
                            " must be a duration like '24h', '7d 2h', '7 days and 2 hours' or ISO-8601 (e.g. PT24H)."
            );
        }

        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalStateException("Invalid config: " + keyPath + " must be greater than 0 seconds.");
        }

        return duration;
    }

    static Duration parseIsoDurationOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    static Duration parseHumanDurationOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.toLowerCase(Locale.ROOT)
                .replace(',', ' ')
                .replaceAll("\\band\\b", " ");

        Matcher matcher = HUMAN_DURATION_PART_PATTERN.matcher(normalized);

        long totalSeconds = 0L;
        int cursor = 0;
        boolean foundAtLeastOnePart = false;

        while (matcher.find()) {
            String gap = normalized.substring(cursor, matcher.start()).trim();
            if (!gap.isEmpty()) {
                return null;
            }

            long amount;
            try {
                amount = Long.parseLong(matcher.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (amount <= 0L) {
                return null;
            }

            long unitSeconds = unitToSeconds(matcher.group(2));
            try {
                totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(amount, unitSeconds));
            } catch (ArithmeticException e) {
                throw new IllegalStateException("Invalid config: duration value is too large.", e);
            }

            cursor = matcher.end();
            foundAtLeastOnePart = true;
        }

        if (!foundAtLeastOnePart) {
            return null;
        }

        String tail = normalized.substring(cursor).trim();
        if (!tail.isEmpty()) {
            return null;
        }

        return Duration.ofSeconds(totalSeconds);
    }

    static long unitToSeconds(String rawUnit) {
        String unit = rawUnit.toLowerCase(Locale.ROOT);
        return switch (unit) {
            case "w", "week", "weeks" -> 7L * 24L * 60L * 60L;
            case "d", "day", "days" -> 24L * 60L * 60L;
            case "h", "hr", "hrs", "hour", "hours" -> 60L * 60L;
            case "m", "min", "mins", "minute", "minutes" -> 60L;
            case "s", "sec", "secs", "second", "seconds" -> 1L;
            default -> throw new IllegalStateException("Unsupported duration unit: " + rawUnit);
        };
    }

    static boolean parseBooleanOrDefault(Object rawValue, boolean defaultValue, String keyName) {
        if (rawValue == null) {
            return defaultValue;
        }

        if (rawValue instanceof Boolean booleanValue) {
            return booleanValue;
        }

        if (rawValue instanceof String stringValue) {
            String normalized = stringValue.trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized)) {
                return true;
            }
            if ("false".equals(normalized)) {
                return false;
            }
        }

        throw new IllegalStateException("Invalid config: " + keyName + " must be a boolean.");
    }

    static int parseIntOrDefault(Object rawValue, int defaultValue, String keyName) {
        if (rawValue == null) {
            return defaultValue;
        }

        if (rawValue instanceof Number numberValue) {
            return numberValue.intValue();
        }

        if (rawValue instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid config: " + keyName + " must be an integer.", e);
            }
        }

        throw new IllegalStateException("Invalid config: " + keyName + " must be an integer.");
    }

    static double parseDouble(Object value, String keyPath) {
        if (value instanceof Number numberValue) {
            return numberValue.doubleValue();
        }

        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue.trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid config: " + keyPath + " must be a numeric value.", e);
            }
        }

        throw new IllegalStateException("Invalid config: " + keyPath + " must be a numeric value.");
    }

    static String normalizeString(Object value) {
        if (value == null) {
            return null;
        }
        return value.toString().trim();
    }
}