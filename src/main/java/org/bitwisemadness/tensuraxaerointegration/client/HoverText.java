package org.bitwisemadness.tensuraxaerointegration.client;

import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.network.chat.Component;
import org.bitwisemadness.tensuraxaerointegration.cache.ChunkCacheKey;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationClassification;
import org.bitwisemadness.tensuraxaerointegration.config.ClientConfig;
import org.bitwisemadness.tensuraxaerointegration.util.ConcentrationText;

/**
 * Builds the world-map hover line for one chunk.
 *
 * <p>Reads only the client store, so it never blocks the map on a server round
 * trip. A missing, unavailable or stale value produces {@code null}, which tells
 * Xaero to draw nothing - the verified hover API has no "pending" state, so
 * omitting the line is more honest than showing a stale number.</p>
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

        OptionalDouble value = store.displayValue(key, ClientConfig.staleAfterTicks());
        if (value.isEmpty()) {
            return null;
        }
        String formatted = ConcentrationText.format(value.getAsDouble());
        Optional<ConcentrationClassification> classification =
                store.classification(key, ClientConfig.staleAfterTicks(), ClientConfig.thresholds());
        if (classification.isPresent()) {
            return Component.translatable("tensura_xaero_integration.hover.line",
                    formatted, Component.translatable(classification.get().translationKey()));
        }
        // No usable baseline: report the number, never invent a band for it.
        return Component.translatable("tensura_xaero_integration.hover.line_no_threshold", formatted);
    }
}
