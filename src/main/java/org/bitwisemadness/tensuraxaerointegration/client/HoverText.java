package org.bitwisemadness.tensuraxaerointegration.client;

import java.util.Optional;
import net.minecraft.network.chat.Component;
import org.bitwisemadness.tensuraxaerointegration.cache.ChunkCacheKey;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentrationStore.Observation;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationClassification;
import org.bitwisemadness.tensuraxaerointegration.config.ClientConfig;
import org.bitwisemadness.tensuraxaerointegration.util.ConcentrationText;

/**
 * Builds the world-map hover line for one chunk.
 *
 * <p>Reads only the client store, so it never blocks the map on a server round
 * trip. A value that is no longer being refreshed is still shown - it is a real
 * observation - but the line says so, so a remembered number is never mistaken
 * for a current one. A chunk with no observation at all produces {@code null},
 * which tells Xaero to draw nothing.</p>
 */
public final class HoverText {

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
        ChunkCacheKey key = new ChunkCacheKey(token.isBlank() ? "unknown" : token, dimensionId, chunkX, chunkZ);

        Optional<Observation> observation = store.observe(key, store.staleAfterMillis());
        if (observation.isEmpty()) {
            return null;
        }
        Observation value = observation.get();
        String formatted = ConcentrationText.format(value.value());
        Optional<ConcentrationClassification> classification = value.classification();
        if (classification.isPresent()) {
            return Component.translatable(
                    value.live()
                            ? "tensura_xaero_integration.hover.line"
                            : "tensura_xaero_integration.hover.line_last_known",
                    formatted, Component.translatable(classification.get().translationKey()));
        }
        // No usable baseline: report the number, never invent a band for it.
        return Component.translatable(
                value.live()
                        ? "tensura_xaero_integration.hover.line_no_threshold"
                        : "tensura_xaero_integration.hover.line_no_threshold_last_known",
                formatted);
    }
}
