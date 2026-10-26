package org.bitwisemadness.tensuraxaerointegration.client;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import org.bitwisemadness.tensuraxaerointegration.cache.ChunkCacheKey;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentrationStore.Observation;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationClassification;
import org.bitwisemadness.tensuraxaerointegration.util.ConcentrationText;

/**
 * Builds the world-map hover line for one chunk.
 *
 * <p>Reads only the client store, so it never blocks the map on a server round
 * trip. A value that is no longer being refreshed is still shown - it is a real
 * observation - but the line says so, so a remembered number is never mistaken
 * for a current one. A chunk with no observation at all produces {@code null},
 * which tells Xaero to draw nothing.</p>
 *
 * <p>Xaero asks for this line every frame for the chunk under the cursor, and the
 * answer only changes when the observation does - a new value, the value going
 * stale, or the pointer moving to another chunk. The line is therefore remembered
 * and reused while nothing changed, so a still frame costs no number formatting
 * and no component graph.</p>
 */
public final class HoverText {

    /** The last line built; render-thread only, like every caller. */
    private static final Memo LAST_LINE = new Memo();

    private HoverText() {
    }

    /**
     * The hover line for a chunk, or {@code null} when nothing should be shown.
     *
     * @param dimensionId the dimension id the chunk belongs to
     */
    public static Component describe(String dimensionId, int chunkX, int chunkZ) {
        ClientConcentrationStore store = ClientConcentration.get().store();
        String token = ClientConcentration.get().worldToken();
        ChunkCacheKey key = LAST_LINE.keyFor(token.isBlank() ? "unknown" : token, dimensionId, chunkX, chunkZ);

        Optional<Observation> observation = store.observe(key, store.staleAfterMillis());
        if (observation.isEmpty()) {
            return null;
        }
        return LAST_LINE.line(observation.get());
    }

    private static Component build(Observation value, ConcentrationClassification band) {
        String formatted = ConcentrationText.format(value.value());
        if (band != null) {
            return Component.translatable(
                    value.live()
                            ? "tensura_xaero_integration.hover.line"
                            : "tensura_xaero_integration.hover.line_last_known",
                    formatted, Component.translatable(band.translationKey()));
        }
        // No usable baseline: report the number, never invent a band for it.
        return Component.translatable(
                value.live()
                        ? "tensura_xaero_integration.hover.line_no_threshold"
                        : "tensura_xaero_integration.hover.line_no_threshold_last_known",
                formatted);
    }

    /**
     * One remembered line, plus the chunk key so a still hover does not allocate
     * one per frame.
     *
     * <p>What the line says depends only on the value, whether it is live and its
     * band - never on which chunk it belongs to - so matching on those three is
     * enough to reuse it. The key is held separately and only refreshed when the
     * hovered chunk actually changes.</p>
     */
    private static final class Memo {

        private ChunkCacheKey key;
        private double value = Double.NaN;
        private boolean live;
        private ConcentrationClassification band;
        private Component line;

        /** The key for a chunk, reusing the last one when the hover has not moved. */
        ChunkCacheKey keyFor(String world, String dimension, int chunkX, int chunkZ) {
            ChunkCacheKey current = this.key;
            if (current != null && current.chunkX() == chunkX && current.chunkZ() == chunkZ
                    && current.dimension().equals(dimension) && current.world().equals(world)) {
                return current;
            }
            this.key = new ChunkCacheKey(world, dimension, chunkX, chunkZ);
            return this.key;
        }

        /** The line for an observation, formatted only when it differs from the last. */
        Component line(Observation observation) {
            double observed = observation.value();
            boolean isLive = observation.live();
            ConcentrationClassification observedBand = observation.classification().orElse(null);
            if (this.line != null && Double.compare(this.value, observed) == 0
                    && this.live == isLive && this.band == observedBand) {
                return this.line;
            }
            Component built = build(observation, observedBand);
            // NaN can never equal a real observation, so the first call always builds.
            this.value = observed;
            this.live = isLive;
            this.band = observedBand;
            this.line = built;
            return built;
        }
    }
}
