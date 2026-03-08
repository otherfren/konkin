package io.konkin.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CriteriaTypeTest {

    @Test void fromTomlValueGt() {
        assertEquals(CriteriaType.VALUE_GT, CriteriaType.fromTomlValue("value-gt"));
        assertEquals(CriteriaType.VALUE_GT, CriteriaType.fromTomlValue("value>"));
        assertEquals(CriteriaType.VALUE_GT, CriteriaType.fromTomlValue("gt"));
        assertEquals(CriteriaType.VALUE_GT, CriteriaType.fromTomlValue("greater-than"));
    }

    @Test void fromTomlValueLt() {
        assertEquals(CriteriaType.VALUE_LT, CriteriaType.fromTomlValue("value-lt"));
        assertEquals(CriteriaType.VALUE_LT, CriteriaType.fromTomlValue("value<"));
        assertEquals(CriteriaType.VALUE_LT, CriteriaType.fromTomlValue("lt"));
        assertEquals(CriteriaType.VALUE_LT, CriteriaType.fromTomlValue("less-than"));
    }

    @Test void fromTomlValueCumulatedGt() {
        assertEquals(CriteriaType.CUMULATED_VALUE_GT, CriteriaType.fromTomlValue("cumulated-value-gt"));
        assertEquals(CriteriaType.CUMULATED_VALUE_GT, CriteriaType.fromTomlValue("cumulated>"));
        assertEquals(CriteriaType.CUMULATED_VALUE_GT, CriteriaType.fromTomlValue("cumulated-greater-than"));
    }

    @Test void fromTomlValueCumulatedLt() {
        assertEquals(CriteriaType.CUMULATED_VALUE_LT, CriteriaType.fromTomlValue("cumulated-value-lt"));
        assertEquals(CriteriaType.CUMULATED_VALUE_LT, CriteriaType.fromTomlValue("cumulated<"));
        assertEquals(CriteriaType.CUMULATED_VALUE_LT, CriteriaType.fromTomlValue("cumulated-less-than"));
    }

    @Test void fromTomlValueNormalizesUnderscores() {
        assertEquals(CriteriaType.CUMULATED_VALUE_GT, CriteriaType.fromTomlValue("cumulated_value_gt"));
    }

    @Test void fromTomlValueCaseInsensitive() {
        assertEquals(CriteriaType.VALUE_GT, CriteriaType.fromTomlValue("VALUE-GT"));
    }

    @Test void fromTomlValueUnknownThrows() {
        assertThrows(IllegalStateException.class, () -> CriteriaType.fromTomlValue("unknown"));
    }

    @Test void requiresPeriod() {
        assertFalse(CriteriaType.VALUE_GT.requiresPeriod());
        assertFalse(CriteriaType.VALUE_LT.requiresPeriod());
        assertTrue(CriteriaType.CUMULATED_VALUE_GT.requiresPeriod());
        assertTrue(CriteriaType.CUMULATED_VALUE_LT.requiresPeriod());
    }

    @Test void tomlValue() {
        assertEquals("value-gt", CriteriaType.VALUE_GT.tomlValue());
        assertEquals("value-lt", CriteriaType.VALUE_LT.tomlValue());
        assertEquals("cumulated-value-gt", CriteriaType.CUMULATED_VALUE_GT.tomlValue());
        assertEquals("cumulated-value-lt", CriteriaType.CUMULATED_VALUE_LT.tomlValue());
    }
}
