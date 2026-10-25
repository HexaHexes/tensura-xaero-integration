package org.bitwisemadness.tensuraxaerointegration.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ConcentrationTextTest {

    @Test
    void whole_numbers_have_no_decimal_point() {
        assertEquals("500", ConcentrationText.format(500.0));
        assertEquals("1,450", ConcentrationText.format(1450.0));
        assertEquals("0", ConcentrationText.format(0.0));
    }

    @Test
    void fractions_round_to_one_decimal() {
        assertEquals("1,450.3", ConcentrationText.format(1450.25));
        assertEquals("1,450.3", ConcentrationText.format(1450.26));
        assertEquals("1,450", ConcentrationText.format(1450.04), "rounds down to a whole number");
        assertEquals("0.5", ConcentrationText.format(0.5));
    }

    @Test
    void large_values_keep_grouping() {
        assertEquals("1,000,000", ConcentrationText.format(1_000_000.0));
    }

    @Test
    void non_finite_values_are_rejected() {
        assertThrows(IllegalArgumentException.class, () -> ConcentrationText.format(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> ConcentrationText.format(Double.POSITIVE_INFINITY));
    }
}
