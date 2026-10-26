package org.bitwisemadness.tensuraxaerointegration.tiers;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationThresholds;
import org.bitwisemadness.tensuraxaerointegration.concentration.OverlayPalette;

/**
 * The loaded datapack: which dimension has which normal, the ladder to use for a
 * dimension that is not listed, and the default palette.
 *
 * <p>Immutable and swapped whole on reload, so a running server never observes a
 * half-applied table.</p>
 *
 * @param dimensions   dimension id → its tier
 * @param defaultBands ladder for a dimension the datapack does not list
 * @param palette      default band colours, overridable by the client's config
 */
public record TierTable(Map<String, DimensionTier> dimensions,
        ConcentrationThresholds defaultBands,
        OverlayPalette palette) {

    public TierTable {
        if (dimensions == null || defaultBands == null || palette == null) {
            throw new IllegalArgumentException("a tier table needs dimensions, bands and a palette");
        }
        dimensions = Map.copyOf(new TreeMap<>(dimensions));
    }

    /** The empty table: no dimension is described, so every one uses the fallback. */
    public static TierTable defaults() {
        return new TierTable(Map.of(), ConcentrationThresholds.defaults(), OverlayPalette.defaults());
    }

    /**
     * The tier to classify a dimension with.
     *
     * <p>A dimension the datapack does not mention falls back to Tensura's own
     * baseline with the default ladder, which is exactly the old single-baseline
     * behaviour - an unlisted dimension is not silently unclassified.</p>
     *
     * @param fallbackBaseline Tensura's configured baseline, used only when this
     *                         dimension has no datapack entry
     * @return empty only when nothing can classify the dimension, i.e. it is
     *         unlisted and the fallback baseline is unusable
     */
    public Optional<DimensionTier> forDimension(String dimension, double fallbackBaseline) {
        DimensionTier tier = this.dimensions.get(dimension);
        if (tier != null) {
            return Optional.of(tier);
        }
        if (!Double.isFinite(fallbackBaseline) || fallbackBaseline <= 0.0) {
            return Optional.empty();
        }
        return Optional.of(new DimensionTier(fallbackBaseline, this.defaultBands));
    }

    /** Every dimension the datapack describes. */
    public Set<String> describedDimensions() {
        return this.dimensions.keySet();
    }
}
