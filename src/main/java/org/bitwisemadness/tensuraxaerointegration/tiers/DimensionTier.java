package org.bitwisemadness.tensuraxaerointegration.tiers;

import java.util.Optional;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationClassification;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationThresholds;

/**
 * One dimension's ambient normal and the bands that scale off it.
 *
 * <p>Dimensions do not share a magicule scale: with Tensura's shipped data the
 * overworld normal is the bare baseline, the nether multiplies it and adds up to
 * +4500 in crimson forests, and the hell dimension adds 100000 before any biome.
 * A single percentage ladder therefore cannot describe more than one of them, so
 * the baseline - and optionally the ladder itself - is per dimension.</p>
 *
 * <p>The bands stay the same four presentation bands; this only decides where
 * they sit in a given dimension. Nothing here is a Tensura gameplay rule.</p>
 *
 * @param baseline the concentration that counts as normal in this dimension
 * @param bands    percentage increases over {@code baseline} for the four elevated bands
 */
public record DimensionTier(double baseline, ConcentrationThresholds bands) {

    public DimensionTier {
        if (!Double.isFinite(baseline) || baseline <= 0.0) {
            throw new IllegalArgumentException("a tier baseline must be finite and positive, was " + baseline);
        }
        if (bands == null) {
            throw new IllegalArgumentException("a tier needs band percentages");
        }
    }

    /** Classifies a concentration in this dimension, empty when it cannot be. */
    public Optional<ConcentrationClassification> classify(double concentration) {
        return this.bands.classifyIfValid(concentration, this.baseline);
    }
}
