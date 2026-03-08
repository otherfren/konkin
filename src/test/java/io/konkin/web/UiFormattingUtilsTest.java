package io.konkin.web;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UiFormattingUtilsTest {

    // ── safe ──

    @Test void safeNull() { assertEquals("-", UiFormattingUtils.safe(null)); }
    @Test void safeBlank() { assertEquals("-", UiFormattingUtils.safe("  ")); }
    @Test void safeValue() { assertEquals("hello", UiFormattingUtils.safe("hello")); }

    // ── normalizeState ──

    @Test void normalizeStateNull() { assertEquals("UNKNOWN", UiFormattingUtils.normalizeState(null)); }
    @Test void normalizeStateBlank() { assertEquals("UNKNOWN", UiFormattingUtils.normalizeState("")); }
    @Test void normalizeStateValue() { assertEquals("PENDING", UiFormattingUtils.normalizeState("  pending  ")); }

    // ── abbreviateId ──

    @Test void abbreviateIdNull() { assertEquals("-", UiFormattingUtils.abbreviateId(null)); }
    @Test void abbreviateIdBlank() { assertEquals("-", UiFormattingUtils.abbreviateId("  ")); }
    @Test void abbreviateIdShort() { assertEquals("abc", UiFormattingUtils.abbreviateId("abc")); }
    @Test void abbreviateIdExactFive() { assertEquals("abcde", UiFormattingUtils.abbreviateId("abcde")); }
    @Test void abbreviateIdLong() { assertEquals("abcde...", UiFormattingUtils.abbreviateId("abcdefgh")); }

    // ── firstFive ──

    @Test void firstFiveNull() { assertEquals("-", UiFormattingUtils.firstFive(null)); }
    @Test void firstFiveBlank() { assertEquals("-", UiFormattingUtils.firstFive("  ")); }
    @Test void firstFiveShort() { assertEquals("ab", UiFormattingUtils.firstFive("ab")); }
    @Test void firstFiveLong() { assertEquals("abcde", UiFormattingUtils.firstFive("abcdefgh")); }

    // ── coinIconName ──

    @Test void coinIconNull() { assertEquals("", UiFormattingUtils.coinIconName(null)); }
    @Test void coinIconBlank() { assertEquals("", UiFormattingUtils.coinIconName("")); }
    @Test void coinIconBitcoin() { assertEquals("bitcoin", UiFormattingUtils.coinIconName("Bitcoin")); }
    @Test void coinIconMonero() { assertEquals("monero", UiFormattingUtils.coinIconName("MONERO")); }
    @Test void coinIconLitecoin() { assertEquals("litecoin", UiFormattingUtils.coinIconName("litecoin")); }
    @Test void coinIconEthereum() { assertEquals("ethereum", UiFormattingUtils.coinIconName("Ethereum")); }
    @Test void coinIconUnknown() { assertEquals("", UiFormattingUtils.coinIconName("dogecoin")); }

    // ── toStatusClass ──

    @Test void statusClassCompleted() { assertEquals("approved", UiFormattingUtils.toStatusClass("completed")); }
    @Test void statusClassApproved() { assertEquals("approved", UiFormattingUtils.toStatusClass("approved")); }
    @Test void statusClassFailed() { assertEquals("cancelled", UiFormattingUtils.toStatusClass("failed")); }
    @Test void statusClassDenied() { assertEquals("cancelled", UiFormattingUtils.toStatusClass("denied")); }
    @Test void statusClassCancelled() { assertEquals("cancelled", UiFormattingUtils.toStatusClass("cancelled")); }
    @Test void statusClassTimedOut() { assertEquals("cancelled", UiFormattingUtils.toStatusClass("timed_out")); }
    @Test void statusClassRejected() { assertEquals("cancelled", UiFormattingUtils.toStatusClass("rejected")); }
    @Test void statusClassExpired() { assertEquals("cancelled", UiFormattingUtils.toStatusClass("expired")); }
    @Test void statusClassPending() { assertEquals("pending", UiFormattingUtils.toStatusClass("pending")); }
    @Test void statusClassUnknown() { assertEquals("pending", UiFormattingUtils.toStatusClass("something")); }

    // ── formatInstant ──

    @Test void formatInstantNull() { assertEquals("-", UiFormattingUtils.formatInstant(null)); }
    @Test void formatInstantValue() {
        Instant i = Instant.parse("2026-01-15T10:30:00Z");
        assertEquals("2026-01-15 10:30:00 UTC", UiFormattingUtils.formatInstant(i));
    }

    // ── formatInstantMinute ──

    @Test void formatInstantMinuteNull() { assertEquals("-", UiFormattingUtils.formatInstantMinute(null)); }
    @Test void formatInstantMinuteValue() {
        Instant i = Instant.parse("2026-01-15T10:30:45Z");
        assertEquals("2026-01-15 10:30", UiFormattingUtils.formatInstantMinute(i));
    }

    // ── formatLogMinute ──

    @Test void formatLogMinuteNull() { assertEquals("-", UiFormattingUtils.formatLogMinute(null)); }
    @Test void formatLogMinuteValue() {
        Instant i = Instant.parse("2026-01-15T10:30:45Z");
        assertEquals("2026 01 15 10:30", UiFormattingUtils.formatLogMinute(i));
    }

    // ── formatRemaining ──

    @Test void formatRemainingNull() { assertEquals("-", UiFormattingUtils.formatRemaining(null, Instant.now())); }

    @Test void formatRemainingExpired() {
        Instant now = Instant.now();
        assertEquals("expired", UiFormattingUtils.formatRemaining(now.minusSeconds(1), now));
    }

    @Test void formatRemainingSeconds() {
        Instant now = Instant.now();
        String result = UiFormattingUtils.formatRemaining(now.plusSeconds(30), now);
        assertTrue(result.contains("sec"));
    }

    @Test void formatRemainingMinutes() {
        Instant now = Instant.now();
        String result = UiFormattingUtils.formatRemaining(now.plusSeconds(300), now);
        assertTrue(result.contains("min"));
    }

    @Test void formatRemainingHours() {
        Instant now = Instant.now();
        String result = UiFormattingUtils.formatRemaining(now.plusSeconds(7200), now);
        assertTrue(result.contains("h"));
    }

    @Test void formatRemainingDays() {
        Instant now = Instant.now();
        String result = UiFormattingUtils.formatRemaining(now.plusSeconds(200_000), now);
        assertTrue(result.contains("d"));
    }

    // ── formatDurationFriendly ──

    @Test void formatDurationNull() { assertEquals("-", UiFormattingUtils.formatDurationFriendly(null)); }
    @Test void formatDurationZero() { assertEquals("0s", UiFormattingUtils.formatDurationFriendly(Duration.ZERO)); }
    @Test void formatDurationSeconds() { assertEquals("45s", UiFormattingUtils.formatDurationFriendly(Duration.ofSeconds(45))); }
    @Test void formatDurationMinutes() { assertEquals("5m", UiFormattingUtils.formatDurationFriendly(Duration.ofMinutes(5))); }
    @Test void formatDurationHours() { assertEquals("2h", UiFormattingUtils.formatDurationFriendly(Duration.ofHours(2))); }
    @Test void formatDurationDays() { assertEquals("3d", UiFormattingUtils.formatDurationFriendly(Duration.ofDays(3))); }
    @Test void formatDurationComposite() { assertEquals("1d 2h", UiFormattingUtils.formatDurationFriendly(Duration.ofSeconds(86400 + 7200 + 180 + 5))); }
    @Test void formatDurationTwoParts() { assertEquals("2h 30m", UiFormattingUtils.formatDurationFriendly(Duration.ofMinutes(150))); }

    // ── toPrettyJson ──

    @Test void toPrettyJsonSimple() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", "value");
        String json = UiFormattingUtils.toPrettyJson(map);
        assertTrue(json.contains("\"key\""));
        assertTrue(json.contains("\"value\""));
    }
}
