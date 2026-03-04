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

import java.util.Locale;

public enum CriteriaType {
    VALUE_GT("value-gt", false),
    VALUE_LT("value-lt", false),
    CUMULATED_VALUE_GT("cumulated-value-gt", true),
    CUMULATED_VALUE_LT("cumulated-value-lt", true);

    private final String tomlValue;
    private final boolean requiresPeriod;

    CriteriaType(String tomlValue, boolean requiresPeriod) {
        this.tomlValue = tomlValue;
        this.requiresPeriod = requiresPeriod;
    }

    public String tomlValue() {
        return tomlValue;
    }

    public boolean requiresPeriod() {
        return requiresPeriod;
    }

    public static CriteriaType fromTomlValue(String rawType) {
        String normalized = rawType.trim()
                .toLowerCase(Locale.ROOT)
                .replace("_", "-")
                .replace(" ", "");

        return switch (normalized) {
            case "value-gt", "value>", "gt", "greater-than" -> VALUE_GT;
            case "value-lt", "value<", "lt", "less-than" -> VALUE_LT;
            case "cumulated-value-gt", "cumulatedvalue-gt", "cumulated>", "cumulated-greater-than" -> CUMULATED_VALUE_GT;
            case "cumulated-value-lt", "cumulatedvalue-lt", "cumulated<", "cumulated-less-than" -> CUMULATED_VALUE_LT;
            default -> throw new IllegalStateException(
                    "Invalid config: unsupported criteria.type='" + rawType +
                            "'. Supported: value-gt, value-lt, cumulated-value-gt, cumulated-value-lt."
            );
        };
    }
}