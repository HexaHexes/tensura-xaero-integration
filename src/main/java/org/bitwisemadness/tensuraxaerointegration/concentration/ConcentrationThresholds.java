package org.bitwisemadness.tensuraxaerointegration.concentration;

import java.util.Optional;

/**
 * Baseline-relative band boundaries, expressed as percentage increases over the
 * authoritative baseline.
 *
 * <p>A boundary of {@code 25} sits 25% above the baseline, i.e. at
 * {@code baseline * 1.25}. Percentages rather than absolute values keep the
 * classification aligned with a server whose {@code tensura/area_magicule_config}
 * baseline is not the default 500; the defaults are presentation choices.</p>
 *
 * <p>Boundaries are inclusive on the lower band: a concentration of exactly
 * {@code baseline * (1 + slightlyElevatedPercent / 100)} is still
 * {@link ConcentrationClassification#SLIGHTLY_ELEVATED}.</p>
 */
public record ConcentrationThresholds(
        double slightlyElevatedPercent,
        double moderatelyElevatedPercent,
        double highlyElevatedPercent,
        double exceptionallyElevatedPercent) {

    public ConcentrationThresholds {
        requirePercent("slightlyElevatedPercent", slightlyElevatedPercent);
        requirePercent("moderatelyElevatedPercent", moderatelyElevatedPercent);
        requirePercent("highlyElevatedPercent", highlyElevatedPercent);
        requirePercent("exceptionallyElevatedPercent", exceptionallyElevatedPercent);
        if (!(slightlyElevatedPercent < moderatelyElevatedPercent
                && moderatelyElevatedPercent < highlyElevatedPercent
                && highlyElevatedPercent < exceptionallyElevatedPercent)) {
            throw new IllegalArgumentException("thresholds must increase strictly: "
                    + slightlyElevatedPercent + ", " + moderatelyElevatedPercent + ", "
                    + highlyElevatedPercent + ", " + exceptionallyElevatedPercent);
        }
    }

    private static void requirePercent(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be a finite percentage > 0, was " + value);
        }
    }

    private static double boundary(double baseline, double percent) {
        return baseline * (1.0 + percent / 100.0);
    }

    /**
     * The shipped defaults: +25% / +100% / +300% / +700% over the baseline, i.e.
     * 1.25x / 2x / 4x / 8x. Nothing in Tensura defines these numbers; they only
     * spread the configured palette across the observed range.
     */
    public static ConcentrationThresholds defaults() {
        return new ConcentrationThresholds(25.0, 100.0, 300.0, 700.0);
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
        if (concentration <= boundary(baseline, this.slightlyElevatedPercent)) {
            return Optional.of(ConcentrationClassification.SLIGHTLY_ELEVATED);
        }
        if (concentration <= boundary(baseline, this.moderatelyElevatedPercent)) {
            return Optional.of(ConcentrationClassification.MODERATELY_ELEVATED);
        }
        if (concentration <= boundary(baseline, this.highlyElevatedPercent)) {
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
