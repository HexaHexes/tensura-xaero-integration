package org.bitwisemadness.tensuraxaerointegration.client.overlay;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKey;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentration;
import org.bitwisemadness.tensuraxaerointegration.client.HoverText;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationClassification;
import org.bitwisemadness.tensuraxaerointegration.config.ClientConfig;
import xaero.map.highlight.ChunkHighlighter;

/**
 * The world-map overlay.
 *
 * <p>Xaero's contract, as verified in the 1.47.0 jar:</p>
 * <ul>
 *   <li>{@code coveringOutsideDiscovered} is {@code false}, so Xaero itself never
 *       asks about, colours or tooltips an undiscovered chunk.</li>
 *   <li>{@code chunkIsHighlit} gates both the colour and the hover line, so it
 *       returns true exactly when this client has a fresh value for the chunk.</li>
 *   <li>{@code calculateRegionHash} is folded into Xaero's cached per-region
 *       highlight hash; returning the region's data version is what turns a
 *       concentration change into a repaint without touching rendering code.</li>
 *   <li>A {@code NORMAL} chunk returns a zero-alpha colour rather than
 *       {@code null}: nothing is painted, but the hover line can still be shown.</li>
 * </ul>
 *
 * <p>All reads come from the client store, which only ever holds mapped chunks,
 * so no lookup here can reveal an unmapped one. Runs on the map's render/writer
 * threads, hence the store's thread-safe reads.</p>
 */
public final class WorldMapConcentrationOverlay extends ChunkHighlighter {

    public static final WorldMapConcentrationOverlay INSTANCE = new WorldMapConcentrationOverlay();

    private WorldMapConcentrationOverlay() {
        super(false);
    }

    private static boolean enabled() {
        return ClientConfig.worldMapOverlayEnabled() || ClientConfig.worldMapHoverEnabled();
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> dimension, int regionX, int regionZ) {
        if (!enabled()) {
            return false;
        }
        return ClientConcentration.get().store().hasRegionData(regionKey(dimension, regionX, regionZ));
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return enabled() && classification(dimension, chunkX, chunkZ).isPresent();
    }

    @Override
    protected int[] getColors(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!ClientConfig.worldMapOverlayEnabled()) {
            return null;
        }
        Optional<ConcentrationClassification> classification = classification(dimension, chunkX, chunkZ);
        if (classification.isEmpty()) {
            return null;
        }
        int colour = ClientConfig.palette().xaeroColorFor(classification.get());
        return new int[] {colour, colour, colour, colour, colour};
    }

    @Override
    public int calculateRegionHash(ResourceKey<Level> dimension, int regionX, int regionZ) {
        // A per-region data version: constant while the data is unchanged, so the
        // map does not repaint for nothing, and different the moment it changes.
        return ClientConcentration.get().store().regionVersion(regionKey(dimension, regionX, regionZ));
    }

    @Override
    public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        // The blunt (cursor) tooltip carries the line; drawing it twice reads badly.
        return null;
    }

    @Override
    public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!ClientConfig.worldMapHoverEnabled()) {
            return null;
        }
        return HoverText.describe(dimension.location().toString(), chunkX, chunkZ);
    }

    @Override
    public void addMinimapBlockHighlightTooltips(List<Component> tooltips, ResourceKey<Level> dimension,
            int blockX, int blockZ, int width) {
        // The minimap has its own highlighter; nothing to add here.
    }

    private static Optional<ConcentrationClassification> classification(ResourceKey<Level> dimension,
            int chunkX, int chunkZ) {
        return ClientConcentration.get().store().classification(
                ClientConcentration.get().key(dimension, chunkX, chunkZ),
                ClientConfig.staleAfterTicks(),
                ClientConfig.thresholds());
    }

    private static RegionKey regionKey(ResourceKey<Level> dimension, int regionX, int regionZ) {
        return new RegionKey(dimension.location().toString(), regionX, regionZ);
    }
}
