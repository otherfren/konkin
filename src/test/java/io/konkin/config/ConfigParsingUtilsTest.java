package io.konkin.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ConfigParsingUtilsTest {

    // ── parseDuration ──

    @Test void parseDurationIso() {
        assertEquals(Duration.ofHours(24), ConfigParsingUtils.parseDuration("PT24H", "key"));
    }

    @Test void parseDurationHuman() {
        assertEquals(Duration.ofHours(2), ConfigParsingUtils.parseDuration("2h", "key"));
    }

    @Test void parseDurationHumanComposite() {
        assertEquals(Duration.ofSeconds(7 * 86400 + 3600), ConfigParsingUtils.parseDuration("1 week and 1 hour", "key"));
    }

    @Test void parseDurationTrimsWhitespace() {
        assertEquals(Duration.ofMinutes(30), ConfigParsingUtils.parseDuration("  30m  ", "key"));
    }

    @Test void parseDurationNullThrows() {
        assertThrows(IllegalStateException.class, () -> ConfigParsingUtils.parseDuration(null, "key"));
    }

    @Test void parseDurationBlankThrows() {
        assertThrows(IllegalStateException.class, () -> ConfigParsingUtils.parseDuration("  ", "key"));
    }

    @Test void parseDurationInvalidThrows() {
        assertThrows(IllegalStateException.class, () -> ConfigParsingUtils.parseDuration("banana", "key"));
    }

    @Test void parseDurationZeroThrows() {
        assertThrows(IllegalStateException.class, () -> ConfigParsingUtils.parseDuration("PT0S", "key"));
    }

    @Test void parseDurationNegativeThrows() {
        assertThrows(IllegalStateException.class, () -> ConfigParsingUtils.parseDuration("PT-1H", "key"));
    }

    // ── parseIsoDurationOrNull ──

    @Test void parseIsoDurationValid() {
        assertEquals(Duration.ofMinutes(5), ConfigParsingUtils.parseIsoDurationOrNull("PT5M"));
    }

    @Test void parseIsoDurationNull() {
        assertNull(ConfigParsingUtils.parseIsoDurationOrNull(null));
    }

    @Test void parseIsoDurationBlank() {
        assertNull(ConfigParsingUtils.parseIsoDurationOrNull("  "));
    }

    @Test void parseIsoDurationInvalid() {
        assertNull(ConfigParsingUtils.parseIsoDurationOrNull("not-iso"));
    }

    // ── parseHumanDurationOrNull ──

    @Test void parseHumanNull() {
        assertNull(ConfigParsingUtils.parseHumanDurationOrNull(null));
    }

    @Test void parseHumanBlank() {
        assertNull(ConfigParsingUtils.parseHumanDurationOrNull(""));
    }

    @Test void parseHumanWeeks() {
        assertEquals(Duration.ofDays(14), ConfigParsingUtils.parseHumanDurationOrNull("2 weeks"));
    }

    @Test void parseHumanDays() {
        assertEquals(Duration.ofDays(3), ConfigParsingUtils.parseHumanDurationOrNull("3days"));
    }

    @Test void parseHumanHours() {
        assertEquals(Duration.ofHours(5), ConfigParsingUtils.parseHumanDurationOrNull("5hrs"));
    }

    @Test void parseHumanMinutes() {
        assertEquals(Duration.ofMinutes(10), ConfigParsingUtils.parseHumanDurationOrNull("10mins"));
    }

    @Test void parseHumanSeconds() {
        assertEquals(Duration.ofSeconds(45), ConfigParsingUtils.parseHumanDurationOrNull("45secs"));
    }

    @Test void parseHumanComposite() {
        assertEquals(Duration.ofSeconds(86400 + 7200 + 180), ConfigParsingUtils.parseHumanDurationOrNull("1d, 2h and 3m"));
    }

    @Test void parseHumanGarbageInMiddle() {
        assertNull(ConfigParsingUtils.parseHumanDurationOrNull("1h garbage 2m"));
    }

    @Test void parseHumanTrailingGarbage() {
        assertNull(ConfigParsingUtils.parseHumanDurationOrNull("1h xyz"));
    }

    @Test void parseHumanZeroAmount() {
        assertNull(ConfigParsingUtils.parseHumanDurationOrNull("0h"));
    }

    @Test void parseHumanNoMatch() {
        assertNull(ConfigParsingUtils.parseHumanDurationOrNull("xyz"));
    }

    @Test void parseHumanOverflowLargeNumberReturnsNull() {
        // Number exceeds Long.MAX_VALUE, parseLong fails, returns null
        assertNull(ConfigParsingUtils.parseHumanDurationOrNull("9999999999999999999 weeks"));
    }

    @Test void parseHumanOverflowMultiplyThrows() {
        // Fits in long but multiplication overflows
        assertThrows(IllegalStateException.class,
                () -> ConfigParsingUtils.parseHumanDurationOrNull("999999999999999 weeks"));
    }

    // ── unitToSeconds ──

    @Test void unitToSecondsAllUnits() {
        assertEquals(604800, ConfigParsingUtils.unitToSeconds("w"));
        assertEquals(604800, ConfigParsingUtils.unitToSeconds("week"));
        assertEquals(604800, ConfigParsingUtils.unitToSeconds("weeks"));
        assertEquals(86400, ConfigParsingUtils.unitToSeconds("d"));
        assertEquals(86400, ConfigParsingUtils.unitToSeconds("day"));
        assertEquals(86400, ConfigParsingUtils.unitToSeconds("days"));
        assertEquals(3600, ConfigParsingUtils.unitToSeconds("h"));
        assertEquals(3600, ConfigParsingUtils.unitToSeconds("hr"));
        assertEquals(3600, ConfigParsingUtils.unitToSeconds("hrs"));
        assertEquals(3600, ConfigParsingUtils.unitToSeconds("hour"));
        assertEquals(3600, ConfigParsingUtils.unitToSeconds("hours"));
        assertEquals(60, ConfigParsingUtils.unitToSeconds("m"));
        assertEquals(60, ConfigParsingUtils.unitToSeconds("min"));
        assertEquals(60, ConfigParsingUtils.unitToSeconds("mins"));
        assertEquals(60, ConfigParsingUtils.unitToSeconds("minute"));
        assertEquals(60, ConfigParsingUtils.unitToSeconds("minutes"));
        assertEquals(1, ConfigParsingUtils.unitToSeconds("s"));
        assertEquals(1, ConfigParsingUtils.unitToSeconds("sec"));
        assertEquals(1, ConfigParsingUtils.unitToSeconds("secs"));
        assertEquals(1, ConfigParsingUtils.unitToSeconds("second"));
        assertEquals(1, ConfigParsingUtils.unitToSeconds("seconds"));
    }

    @Test void unitToSecondsUnknownThrows() {
        assertThrows(IllegalStateException.class, () -> ConfigParsingUtils.unitToSeconds("fortnight"));
    }

    // ── parseBooleanOrDefault ──

    @Test void parseBooleanNull() {
        assertTrue(ConfigParsingUtils.parseBooleanOrDefault(null, true, "k"));
        assertFalse(ConfigParsingUtils.parseBooleanOrDefault(null, false, "k"));
    }

    @Test void parseBooleanFromBoolean() {
        assertTrue(ConfigParsingUtils.parseBooleanOrDefault(Boolean.TRUE, false, "k"));
        assertFalse(ConfigParsingUtils.parseBooleanOrDefault(Boolean.FALSE, true, "k"));
    }

    @Test void parseBooleanFromString() {
        assertTrue(ConfigParsingUtils.parseBooleanOrDefault("true", false, "k"));
        assertFalse(ConfigParsingUtils.parseBooleanOrDefault("false", true, "k"));
        assertTrue(ConfigParsingUtils.parseBooleanOrDefault("  TRUE  ", false, "k"));
    }

    @Test void parseBooleanInvalidStringThrows() {
        assertThrows(IllegalStateException.class, () -> ConfigParsingUtils.parseBooleanOrDefault("yes", false, "k"));
    }

    @Test void parseBooleanInvalidTypeThrows() {
        assertThrows(IllegalStateException.class, () -> ConfigParsingUtils.parseBooleanOrDefault(42, false, "k"));
    }

    // ── parseIntOrDefault ──

    @Test void parseIntNull() {
        assertEquals(7, ConfigParsingUtils.parseIntOrDefault(null, 7, "k"));
    }

    @Test void parseIntFromNumber() {
        assertEquals(42, ConfigParsingUtils.parseIntOrDefault(42, 0, "k"));
        assertEquals(3, ConfigParsingUtils.parseIntOrDefault(3L, 0, "k"));
    }

    @Test void parseIntFromString() {
        assertEquals(99, ConfigParsingUtils.parseIntOrDefault("99", 0, "k"));
        assertEquals(5, ConfigParsingUtils.parseIntOrDefault("  5  ", 0, "k"));
    }

    @Test void parseIntInvalidStringThrows() {
        assertThrows(IllegalStateException.class, () -> ConfigParsingUtils.parseIntOrDefault("abc", 0, "k"));
    }

    @Test void parseIntInvalidTypeThrows() {
        assertThrows(IllegalStateException.class, () -> ConfigParsingUtils.parseIntOrDefault(true, 0, "k"));
    }

    // ── parseDouble ──

    @Test void parseDoubleFromNumber() {
        assertEquals(3.14, ConfigParsingUtils.parseDouble(3.14, "k"), 0.001);
        assertEquals(5.0, ConfigParsingUtils.parseDouble(5, "k"), 0.001);
    }

    @Test void parseDoubleFromString() {
        assertEquals(2.5, ConfigParsingUtils.parseDouble("2.5", "k"), 0.001);
        assertEquals(7.0, ConfigParsingUtils.parseDouble("  7  ", "k"), 0.001);
    }

    @Test void parseDoubleInvalidStringThrows() {
        assertThrows(IllegalStateException.class, () -> ConfigParsingUtils.parseDouble("xyz", "k"));
    }

    @Test void parseDoubleInvalidTypeThrows() {
        assertThrows(IllegalStateException.class, () -> ConfigParsingUtils.parseDouble(true, "k"));
    }

    // ── normalizeString ──

    @Test void normalizeStringNull() {
        assertNull(ConfigParsingUtils.normalizeString(null));
    }

    @Test void normalizeStringTrims() {
        assertEquals("hello", ConfigParsingUtils.normalizeString("  hello  "));
    }

    @Test void normalizeStringFromNumber() {
        assertEquals("42", ConfigParsingUtils.normalizeString(42));
    }
}
