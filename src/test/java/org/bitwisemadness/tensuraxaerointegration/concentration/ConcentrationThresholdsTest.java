package org.bitwisemadness.tensuraxaerointegration.concentration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConcentrationThresholdsTest {

    private static final double BASELINE = 500.0;
    private final ConcentrationThresholds thresholds = ConcentrationThresholds.defaults();

    /** The absolute value of a band boundary, computed independently of the record. */
    private static double boundary(double percent) {
        return BASELINE * (1.0 + percent / 100.0);
    }

    @Test
    void baseline_and_below_are_normal() {
        assertEquals(ConcentrationClassification.NORMAL, thresholds.classify(0.0, BASELINE));
        assertEquals(ConcentrationClassification.NORMAL, thresholds.classify(499.9, BASELINE));
        assertEquals(ConcentrationClassification.NORMAL, thresholds.classify(BASELINE, BASELINE));
    }

    @Test
    void zero_is_not_an_elevated_value() {
        // A valid zero must classify as normal, not as missing data.
        assertEquals(ConcentrationClassification.NORMAL, thresholds.classify(0.0, BASELINE));
    }

    @Test
    void exact_boundaries_belong_to_the_lower_band() {
        assertEquals(ConcentrationClassification.SLIGHTLY_ELEVATED,
                thresholds.classify(boundary(25.0), BASELINE));
        assertEquals(ConcentrationClassification.MODERATELY_ELEVATED,
                thresholds.classify(boundary(100.0), BASELINE));
        assertEquals(ConcentrationClassification.HIGHLY_ELEVATED,
                thresholds.classify(boundary(300.0), BASELINE));
        assertEquals(ConcentrationClassification.EXCEPTIONALLY_ELEVATED,
                thresholds.classify(boundary(700.0), BASELINE));
    }

    @Test
    void just_above_a_boundary_moves_up_a_band() {
        assertEquals(ConcentrationClassification.MODERATELY_ELEVATED,
                thresholds.classify(boundary(25.0) + 0.5, BASELINE));
        assertEquals(ConcentrationClassification.HIGHLY_ELEVATED,
                thresholds.classify(boundary(100.0) + 0.5, BASELINE));
        assertEquals(ConcentrationClassification.EXCEPTIONALLY_ELEVATED,
                thresholds.classify(boundary(300.0) + 0.5, BASELINE));
    }

    @Test
    void a_custom_baseline_shifts_every_band() {
        double customBaseline = 1_000.0;
        assertEquals(ConcentrationClassification.NORMAL, thresholds.classify(1_000.0, customBaseline));
        assertEquals(ConcentrationClassification.SLIGHTLY_ELEVATED,
                thresholds.classify(1_250.0, customBaseline));
    }

    @Test
    void an_unknown_baseline_yields_no_classification() {
        assertTrue(thresholds.classifyIfValid(1_000.0, Double.NaN).isEmpty());
        assertTrue(thresholds.classifyIfValid(1_000.0, 0.0).isEmpty());
        assertTrue(thresholds.classifyIfValid(1_000.0, -5.0).isEmpty());
        assertTrue(thresholds.classifyIfValid(Double.NaN, BASELINE).isEmpty());
        assertTrue(thresholds.classifyIfValid(Double.POSITIVE_INFINITY, BASELINE).isEmpty());
        assertTrue(thresholds.classifyIfValid(-1.0, BASELINE).isEmpty());
    }

    @Test
    void classify_falls_back_to_normal_for_invalid_input() {
        assertEquals(ConcentrationClassification.NORMAL, thresholds.classify(1_000.0, Double.NaN));
    }

    @Test
    void elevated_is_only_true_above_the_first_band() {
        assertEquals(false, ConcentrationClassification.NORMAL.elevated());
        assertEquals(true, ConcentrationClassification.SLIGHTLY_ELEVATED.elevated());
        assertEquals(true, ConcentrationClassification.EXCEPTIONALLY_ELEVATED.elevated());
    }

    @Test
    void bands_are_ordered_from_low_to_high() {
        assertTrue(ConcentrationClassification.SLIGHTLY_ELEVATED
                .compareTo(ConcentrationClassification.HIGHLY_ELEVATED) < 0);
        assertTrue(ConcentrationClassification.EXCEPTIONALLY_ELEVATED
                .compareTo(ConcentrationClassification.MODERATELY_ELEVATED) > 0);
    }

    @Test
    void invalid_threshold_sets_are_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationThresholds(0.0, 0.0, 200.0, 300.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationThresholds(300.0, 200.0, 400.0, 800.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationThresholds(-0.5, 200.0, 400.0, 800.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationThresholds(Double.NaN, 200.0, 400.0, 800.0));
    }

    @Test
    void a_value_far_above_the_top_boundary_stays_exceptional() {
        Optional<ConcentrationClassification> classification =
                thresholds.classifyIfValid(Double.MAX_VALUE, BASELINE);
        assertEquals(Optional.of(ConcentrationClassification.EXCEPTIONALLY_ELEVATED), classification);
    }
}
