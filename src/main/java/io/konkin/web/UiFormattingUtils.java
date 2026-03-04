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

package io.konkin.web;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class UiFormattingUtils {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TS_MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TS_LOG_MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyy MM dd HH:mm").withZone(ZoneOffset.UTC);
    private static final Set<String> SUPPORTED_COIN_ICONS = Set.of("bitcoin", "ethereum", "monero", "litecoin");

    private UiFormattingUtils() {
    }

    public static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    public static String normalizeState(String state) {
        if (state == null || state.isBlank()) {
            return "UNKNOWN";
        }
        return state.trim().toUpperCase(Locale.ROOT);
    }

    public static String abbreviateId(String id) {
        if (id == null || id.isBlank()) {
            return "-";
        }
        String trimmed = id.trim();
        if (trimmed.length() <= 5) {
            return trimmed;
        }
        return trimmed.substring(0, 5) + "...";
    }

    public static String firstFive(String id) {
        if (id == null || id.isBlank()) {
            return "-";
        }
        String trimmed = id.trim();
        return trimmed.length() <= 5 ? trimmed : trimmed.substring(0, 5);
    }

    public static String coinIconName(String coin) {
        if (coin == null || coin.isBlank()) {
            return "";
        }
        String normalized = coin.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_COIN_ICONS.contains(normalized) ? normalized : "";
    }

    public static String toStatusClass(String stateLower) {
        if ("completed".equals(stateLower) || "approved".equals(stateLower)) {
            return "approved";
        }
        if (
                "failed".equals(stateLower)
                        || "denied".equals(stateLower)
                        || "cancelled".equals(stateLower)
                        || "timed_out".equals(stateLower)
                        || "rejected".equals(stateLower)
                        || "expired".equals(stateLower)
        ) {
            return "cancelled";
        }
        return "pending";
    }

    public static String formatInstantMinute(Instant instant) {
        return instant == null ? "-" : TS_MINUTE_FORMAT.format(instant);
    }

    public static String formatLogMinute(Instant instant) {
        return instant == null ? "-" : TS_LOG_MINUTE_FORMAT.format(instant);
    }

    public static String formatInstant(Instant instant) {
        return instant == null ? "-" : TS_FORMAT.format(instant);
    }

    public static String formatRemaining(Instant expiresAt, Instant now) {
        if (expiresAt == null) {
            return "-";
        }

        long seconds = Duration.between(now, expiresAt).getSeconds();
        if (seconds <= 0) {
            return "expired";
        }
        if (seconds < 60) {
            return "in " + seconds + "sec";
        }

        long minutes = Math.max(1L, (seconds + 59) / 60);
        if (minutes < 60) {
            return "in " + minutes + "min";
        }

        long hours = Math.max(1L, (minutes + 59) / 60);
        if (hours < 48) {
            return "in " + hours + "h";
        }

        long days = Math.max(1L, (hours + 23) / 24);
        return "in " + days + "d";
    }

    public static String formatDurationFriendly(Duration duration) {
        if (duration == null) {
            return "-";
        }

        long totalSeconds = duration.getSeconds();
        long days = totalSeconds / 86_400;
        totalSeconds %= 86_400;
        long hours = totalSeconds / 3_600;
        totalSeconds %= 3_600;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;

        List<String> parts = new ArrayList<>();
        if (days > 0) {
            parts.add(days + "d");
        }
        if (hours > 0) {
            parts.add(hours + "h");
        }
        if (minutes > 0) {
            parts.add(minutes + "m");
        }
        if (seconds > 0) {
            parts.add(seconds + "s");
        }

        if (parts.isEmpty()) {
            return "0s";
        }

        if (parts.size() > 2) {
            return String.join(" ", parts.subList(0, 2));
        }

        return String.join(" ", parts);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);

    public static String toPrettyJson(Map<String, Object> source) {
        try {
            return JSON.writeValueAsString(source);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\n  \"error\": \"failed to render details\"\n}";
        }
    }
}