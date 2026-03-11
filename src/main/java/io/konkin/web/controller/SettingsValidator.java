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

package io.konkin.web.controller;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates settings update requests per section.
 * Returns null on success, or an error message string on failure.
 */
final class SettingsValidator {

    private static final Set<String> VALID_LOG_LEVELS = Set.of("trace", "debug", "info", "warn", "error");

    private static final Set<String> SERVER_FIELDS = Set.of(
            "host", "port", "log-level", "log-file", "log-rotate-max-size-mb", "secrets-dir"
    );

    private static final Set<String> DATABASE_FIELDS = Set.of(
            "url", "user", "password", "pool-size"
    );

    private static final Set<String> WEB_UI_FIELDS = Set.of(
            "password-protection.enabled", "auto-reload.enabled", "auto-reload.assets-enabled"
    );

    private static final Set<String> REST_API_FIELDS = Set.of("enabled");

    private static final Set<String> TELEGRAM_FIELDS = Set.of(
            "enabled", "auto-deny-timeout", "api-base-url"
    );

    private static final Set<String> AGENT_FIELDS = Set.of(
            "visible", "bind", "port"
    );

    private static final Set<String> DEBUG_FIELDS = Set.of("enabled", "seed-fake-data");

    private static final Set<String> COIN_FIELDS = Set.of(
            "enabled",
            "auth.web-ui", "auth.rest-api", "auth.telegram",
            "auth.min-approvals-required",
            "auth.auto-accept", "auth.auto-deny",
            "auth.veto-channels",
            "auth.mcp-auth-channels"
    );

    private static final Set<String> VALID_CRITERIA_TYPES = Set.of(
            "value-gt", "value-lt", "cumulated-value-gt", "cumulated-value-lt"
    );

    private static final Set<String> CUMULATED_TYPES = Set.of(
            "cumulated-value-gt", "cumulated-value-lt"
    );

    private static final Pattern HUMAN_DURATION_PART = Pattern.compile(
            "(?i)(\\d+)\\s*(weeks?|w|days?|d|hours?|hrs?|hr|h|minutes?|mins?|min|m|seconds?|secs?|sec|s)"
    );

    private SettingsValidator() {
    }

    static String validateServer(Map<String, Object> values) {
        String unknownField = rejectUnknownFields(values, SERVER_FIELDS);
        if (unknownField != null) return unknownField;

        if (values.containsKey("port")) {
            String err = requireInt(values.get("port"), "port", 1, 65535);
            if (err != null) return err;
        }

        if (values.containsKey("log-level")) {
            Object raw = values.get("log-level");
            if (!(raw instanceof String s) || !VALID_LOG_LEVELS.contains(s.toLowerCase(Locale.ROOT))) {
                return "log-level must be one of: " + VALID_LOG_LEVELS;
            }
        }

        if (values.containsKey("log-rotate-max-size-mb")) {
            String err = requireInt(values.get("log-rotate-max-size-mb"), "log-rotate-max-size-mb", 1, Integer.MAX_VALUE);
            if (err != null) return err;
        }

        if (values.containsKey("host")) {
            String err = requireNonBlankString(values.get("host"), "host");
            if (err != null) return err;
        }

        if (values.containsKey("log-file")) {
            String err = requireNonBlankString(values.get("log-file"), "log-file");
            if (err != null) return err;
        }

        if (values.containsKey("secrets-dir")) {
            String err = requireNonBlankString(values.get("secrets-dir"), "secrets-dir");
            if (err != null) return err;
        }

        return null;
    }

    static String validateDatabase(Map<String, Object> values) {
        String unknownField = rejectUnknownFields(values, DATABASE_FIELDS);
        if (unknownField != null) return unknownField;

        if (values.containsKey("pool-size")) {
            String err = requireInt(values.get("pool-size"), "pool-size", 1, 100);
            if (err != null) return err;
        }

        return null;
    }

    static String validateWebUi(Map<String, Object> values) {
        String unknownField = rejectUnknownFields(values, WEB_UI_FIELDS);
        if (unknownField != null) return unknownField;

        for (String key : values.keySet()) {
            String err = requireBoolean(values.get(key), key);
            if (err != null) return err;
        }

        return null;
    }

    static String validateRestApi(Map<String, Object> values) {
        String unknownField = rejectUnknownFields(values, REST_API_FIELDS);
        if (unknownField != null) return unknownField;

        if (values.containsKey("enabled")) {
            String err = requireBoolean(values.get("enabled"), "enabled");
            if (err != null) return err;
        }

        return null;
    }

    static String validateTelegram(Map<String, Object> values) {
        String unknownField = rejectUnknownFields(values, TELEGRAM_FIELDS);
        if (unknownField != null) return unknownField;

        if (values.containsKey("enabled")) {
            String err = requireBoolean(values.get("enabled"), "enabled");
            if (err != null) return err;
        }

        if (values.containsKey("auto-deny-timeout")) {
            String err = requireDuration(values.get("auto-deny-timeout"), "auto-deny-timeout");
            if (err != null) return err;
        }

        if (values.containsKey("api-base-url")) {
            String err = requireNonBlankString(values.get("api-base-url"), "api-base-url");
            if (err != null) return err;
        }

        return null;
    }

    static String validateAgent(Map<String, Object> values) {
        String unknownField = rejectUnknownFields(values, AGENT_FIELDS);
        if (unknownField != null) return unknownField;

        if (values.containsKey("visible")) {
            String err = requireBoolean(values.get("visible"), "visible");
            if (err != null) return err;
        }

        if (values.containsKey("port")) {
            String err = requireInt(values.get("port"), "port", 1, 65535);
            if (err != null) return err;
        }

        if (values.containsKey("bind")) {
            String err = requireNonBlankString(values.get("bind"), "bind");
            if (err != null) return err;
        }

        return null;
    }

    static String validateDebug(Map<String, Object> values) {
        String unknownField = rejectUnknownFields(values, DEBUG_FIELDS);
        if (unknownField != null) return unknownField;

        for (String key : values.keySet()) {
            String err = requireBoolean(values.get(key), key);
            if (err != null) return err;
        }

        return null;
    }

    static String validateCoin(Map<String, Object> values) {
        String unknownField = rejectUnknownFields(values, COIN_FIELDS);
        if (unknownField != null) return unknownField;

        if (values.containsKey("enabled")) {
            String err = requireBoolean(values.get("enabled"), "enabled");
            if (err != null) return err;
        }

        for (String key : new String[]{"auth.web-ui", "auth.rest-api", "auth.telegram"}) {
            if (values.containsKey(key)) {
                String err = requireBoolean(values.get(key), key);
                if (err != null) return err;
            }
        }

        if (values.containsKey("auth.min-approvals-required")) {
            String err = requireInt(values.get("auth.min-approvals-required"), "auth.min-approvals-required", 1, 100);
            if (err != null) return err;
        }

        for (String ruleField : new String[]{"auth.auto-accept", "auth.auto-deny"}) {
            if (values.containsKey(ruleField)) {
                String err = validateRuleList(values.get(ruleField), ruleField);
                if (err != null) return err;
            }
        }

        if (values.containsKey("auth.veto-channels")) {
            String err = validateStringList(values.get("auth.veto-channels"), "auth.veto-channels");
            if (err != null) return err;
        }

        if (values.containsKey("auth.mcp-auth-channels")) {
            String err = validateStringList(values.get("auth.mcp-auth-channels"), "auth.mcp-auth-channels");
            if (err != null) return err;
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static String validateRuleList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            return field + " must be an array of rule objects";
        }
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> rule)) {
                return field + "[" + i + "] must be a rule object with type, value, and optional period";
            }
            Map<String, Object> ruleMap = (Map<String, Object>) rule;

            Object typeObj = ruleMap.get("type");
            if (!(typeObj instanceof String typeStr) || !VALID_CRITERIA_TYPES.contains(typeStr.toLowerCase(Locale.ROOT))) {
                return field + "[" + i + "].type must be one of: " + VALID_CRITERIA_TYPES;
            }

            Object valueObj = ruleMap.get("value");
            double numVal;
            if (valueObj instanceof Number n) {
                numVal = n.doubleValue();
            } else if (valueObj instanceof String s) {
                try { numVal = Double.parseDouble(s); } catch (NumberFormatException e) {
                    return field + "[" + i + "].value must be a positive number";
                }
            } else {
                return field + "[" + i + "].value must be a positive number";
            }
            if (numVal <= 0) {
                return field + "[" + i + "].value must be > 0";
            }

            if (CUMULATED_TYPES.contains(typeStr.toLowerCase(Locale.ROOT))) {
                Object periodObj = ruleMap.get("period");
                if (periodObj == null || (periodObj instanceof String ps && ps.isBlank())) {
                    return field + "[" + i + "].period is required for cumulated rule types";
                }
                if (periodObj instanceof String ps) {
                    if (parseDurationOrNull(ps) == null) {
                        return field + "[" + i + "].period must be a valid duration (e.g. '5m', '1h', '24h')";
                    }
                }
            }
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String rejectUnknownFields(Map<String, Object> values, Set<String> allowed) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                return "Unknown field: " + key;
            }
        }
        return null;
    }

    private static String requireBoolean(Object value, String field) {
        if (value instanceof Boolean) return null;
        return field + " must be a boolean (true/false)";
    }

    private static String requireInt(Object value, String field, int min, int max) {
        int intVal;
        if (value instanceof Number n) {
            intVal = n.intValue();
        } else if (value instanceof String s) {
            try {
                intVal = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return field + " must be an integer";
            }
        } else {
            return field + " must be an integer";
        }
        if (intVal < min || intVal > max) {
            return field + " must be between " + min + " and " + max;
        }
        return null;
    }

    private static String requireNonBlankString(Object value, String field) {
        if (!(value instanceof String s) || s.isBlank()) {
            return field + " must be a non-empty string";
        }
        return null;
    }

    private static String validateStringList(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            return field + " must be an array of strings";
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof String s) || s.isBlank()) {
                return field + "[" + i + "] must be a non-empty string";
            }
        }
        return null;
    }

    private static String requireDuration(Object value, String field) {
        if (!(value instanceof String s) || s.isBlank()) {
            return field + " must be a duration string (e.g. '5m', '1h', '24h')";
        }
        if (parseDurationOrNull(s) == null) {
            return field + " must be a valid duration (e.g. '5m', '1h', '24h')";
        }
        return null;
    }

    private static Duration parseDurationOrNull(String value) {
        try {
            Duration d = Duration.parse(value);
            return d.isPositive() ? d : null;
        } catch (DateTimeParseException ignored) {
        }

        Matcher matcher = HUMAN_DURATION_PART.matcher(value.toLowerCase(Locale.ROOT));
        if (matcher.find()) {
            return Duration.ofSeconds(1); // valid format, actual parsing happens during config reload
        }
        return null;
    }
}
