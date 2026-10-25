package org.bitwisemadness.tensuraxaerointegration.concentration;

import java.util.Optional;

/**
 * Baseline-relative band boundaries, expressed as multipliers of the
 * authoritative baseline.
 *
 * <p>Multipliers rather than absolute values keep the classification aligned
 * with a server whose {@code tensura/area_magicule_config} baseline is not the
 * default 500. Every value is configurable; the defaults are presentation
 * choices and are documented as such.</p>
 *
 * <p>Boundaries are inclusive on the lower band: a concentration of exactly
 * {@code baseline * slightlyElevatedMultiplier} is still
 * {@link ConcentrationClassification#SLIGHTLY_ELEVATED}. The comparison is done
 * with multiplication (not division) so the boundary cannot drift through
 * floating-point rounding.</p>
 */
public record ConcentrationThresholds(
        double slightlyElevatedMultiplier,
        double moderatelyElevatedMultiplier,
        double highlyElevatedMultiplier,
        double exceptionallyElevatedMultiplier) {

    public ConcentrationThresholds {
        requireMultiplier("slightlyElevatedMultiplier", slightlyElevatedMultiplier);
        requireMultiplier("moderatelyElevatedMultiplier", moderatelyElevatedMultiplier);
        requireMultiplier("highlyElevatedMultiplier", highlyElevatedMultiplier);
        requireMultiplier("exceptionallyElevatedMultiplier", exceptionallyElevatedMultiplier);
        if (!(slightlyElevatedMultiplier < moderatelyElevatedMultiplier
                && moderatelyElevatedMultiplier < highlyElevatedMultiplier
                && highlyElevatedMultiplier < exceptionallyElevatedMultiplier)) {
            throw new IllegalArgumentException("thresholds must increase strictly: "
                    + slightlyElevatedMultiplier + ", " + moderatelyElevatedMultiplier + ", "
                    + highlyElevatedMultiplier + ", " + exceptionallyElevatedMultiplier);
        }
    }

    private static void requireMultiplier(String name, double value) {
        if (!Double.isFinite(value) || value < 1.0) {
            throw new IllegalArgumentException(name + " must be a finite multiplier >= 1.0, was " + value);
        }
    }

    /**
     * The shipped defaults: 1.25x / 2x / 4x / 8x the baseline. Nothing in
     * Tensura defines these numbers; they only spread the configured palette
     * across the observed range.
     */
    public static ConcentrationThresholds defaults() {
        return new ConcentrationThresholds(1.25, 2.0, 4.0, 8.0);
    }

    /**
     * Classifies a concentration against a baseline, or empty when either input
     * cannot be classified (non-finite, or a baseline of zero/negative).
     *
     * <p>Renderers use this form so an invalid baseline hides the overlay rather
     * than mislabelling every chunk as "normal".</p>
     */
    public Optional<ConcentrationClassification> classifyIfValid(double concentration, double baseline) {
        if (!Double.isFinite(concentration) || concentration < 0.0
                || !Double.isFinite(baseline) || baseline <= 0.0) {
            return Optional.empty();
        }
        if (concentration <= baseline) {
            return Optional.of(ConcentrationClassification.NORMAL);
        }
        if (concentration <= baseline * this.slightlyElevatedMultiplier) {
            return Optional.of(ConcentrationClassification.SLIGHTLY_ELEVATED);
        }
        if (concentration <= baseline * this.moderatelyElevatedMultiplier) {
            return Optional.of(ConcentrationClassification.MODERATELY_ELEVATED);
        }
        if (concentration <= baseline * this.highlyElevatedMultiplier) {
            return Optional.of(ConcentrationClassification.HIGHLY_ELEVATED);
        }
        return Optional.of(ConcentrationClassification.EXCEPTIONALLY_ELEVATED);
    }

    /**
     * Classification with an explicit fallback: invalid input is reported as
     * {@link ConcentrationClassification#NORMAL}.
     *
     * <p>Only for callers that have already validated their baseline. Rendering
     * code should use {@link #classifyIfValid}.</p>
     */
    public ConcentrationClassification classify(double concentration, double baseline) {
        return classifyIfValid(concentration, baseline).orElse(ConcentrationClassification.NORMAL);
    }
}
