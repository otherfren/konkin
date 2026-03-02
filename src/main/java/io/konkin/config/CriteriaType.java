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
