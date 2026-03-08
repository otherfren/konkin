package io.konkin.web;

import io.konkin.db.entity.PageResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebUtilsTest {

    // ── parsePositiveInt ──

    @Test void parsePositiveIntNull() { assertEquals(5, WebUtils.parsePositiveInt(null, 5)); }
    @Test void parsePositiveIntBlank() { assertEquals(5, WebUtils.parsePositiveInt("  ", 5)); }
    @Test void parsePositiveIntValid() { assertEquals(10, WebUtils.parsePositiveInt("10", 5)); }
    @Test void parsePositiveIntZero() { assertEquals(1, WebUtils.parsePositiveInt("0", 5)); }
    @Test void parsePositiveIntNegative() { assertEquals(1, WebUtils.parsePositiveInt("-5", 5)); }
    @Test void parsePositiveIntInvalid() { assertEquals(5, WebUtils.parsePositiveInt("abc", 5)); }

    // ── defaultIfBlank ──

    @Test void defaultIfBlankNull() { assertEquals("fb", WebUtils.defaultIfBlank(null, "fb")); }
    @Test void defaultIfBlankEmpty() { assertEquals("fb", WebUtils.defaultIfBlank("  ", "fb")); }
    @Test void defaultIfBlankValue() { assertEquals("val", WebUtils.defaultIfBlank("val", "fb")); }

    // ── firstNonBlank ──

    @Test void firstNonBlankNull() { assertEquals("", WebUtils.firstNonBlank((String[]) null)); }
    @Test void firstNonBlankEmpty() { assertEquals("", WebUtils.firstNonBlank()); }
    @Test void firstNonBlankAllBlank() { assertEquals("", WebUtils.firstNonBlank(null, "  ", "")); }
    @Test void firstNonBlankFindsFirst() { assertEquals("hello", WebUtils.firstNonBlank(null, "  ", "hello", "world")); }

    // ── maskIdentifier ──

    @Test void maskIdentifierNull() { assertEquals("-", WebUtils.maskIdentifier(null)); }
    @Test void maskIdentifierBlank() { assertEquals("-", WebUtils.maskIdentifier("  ")); }
    @Test void maskIdentifierShort() { assertEquals("****", WebUtils.maskIdentifier("abc")); }
    @Test void maskIdentifierExactFour() { assertEquals("****", WebUtils.maskIdentifier("abcd")); }
    @Test void maskIdentifierLong() { assertEquals("ab****gh", WebUtils.maskIdentifier("abcdefgh")); }

    // ── pageMetaFrom ──

    @Test void pageMetaFromBasic() {
        PageResult<String> result = new PageResult<>(List.of("a"), 1, 10, 1, 1, "id", "asc");
        Map<String, Object> meta = WebUtils.pageMetaFrom(result);
        assertEquals(1, meta.get("page"));
        assertEquals(10, meta.get("pageSize"));
        assertEquals(1L, meta.get("totalRows"));
        assertEquals(1, meta.get("totalPages"));
        assertEquals("id", meta.get("sortBy"));
        assertEquals("asc", meta.get("sortDir"));
        assertFalse((Boolean) meta.get("hasPrev"));
        assertFalse((Boolean) meta.get("hasNext"));
    }

    @Test void pageMetaFromMiddlePage() {
        PageResult<String> result = new PageResult<>(List.of("a"), 2, 10, 30, 3, "id", "desc");
        Map<String, Object> meta = WebUtils.pageMetaFrom(result);
        assertTrue((Boolean) meta.get("hasPrev"));
        assertTrue((Boolean) meta.get("hasNext"));
        assertEquals(1, meta.get("prevPage"));
        assertEquals(3, meta.get("nextPage"));
    }

    @Test void pageMetaFromLastPage() {
        PageResult<String> result = new PageResult<>(List.of("a"), 3, 10, 30, 3, "id", "desc");
        Map<String, Object> meta = WebUtils.pageMetaFrom(result);
        assertTrue((Boolean) meta.get("hasPrev"));
        assertFalse((Boolean) meta.get("hasNext"));
    }
}
