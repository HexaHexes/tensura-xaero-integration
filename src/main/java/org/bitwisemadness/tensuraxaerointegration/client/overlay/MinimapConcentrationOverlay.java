package org.bitwisemadness.tensuraxaerointegration.client.overlay;

import java.util.Optional;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKey;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentration;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentrationStore;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentrationStore.Observation;
import org.bitwisemadness.tensuraxaerointegration.config.ClientConfig;
import xaero.common.minimap.highlight.ChunkHighlighter;
import xaero.hud.minimap.info.render.compile.InfoDisplayCompiler;

/**
 * The minimap overlay.
 *
 * <p>Shares the palette and the client store with the world map, so the same
 * chunk is the same colour on both maps. The minimap's highlighter interface has
 * no hover hook of its own that this mod needs, so the tooltip method is a no-op
 * and the numerical readout stays on the world map.</p>
 *
 * <p>{@code coveringOutsideDiscovered} is {@code false} for the same reason as on
 * the world map: the minimap then only applies the overlay to chunks it has
 * actually written.</p>
 */
public final class MinimapConcentrationOverlay extends ChunkHighlighter {

    public static final MinimapConcentrationOverlay INSTANCE = new MinimapConcentrationOverlay();

    private MinimapConcentrationOverlay() {
        super(false);
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> dimension, int regionX, int regionZ) {
        if (!ClientConfig.minimapOverlayEnabled()) {
            return false;
        }
        return ClientConcentration.get().store().hasRegionData(
                new RegionKey(dimension.location().toString(), regionX, regionZ));
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return observation(dimension, chunkX, chunkZ).isPresent();
    }

    @Override
    protected int[] getColors(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        Optional<Observation> observation = observation(dimension, chunkX, chunkZ);
        if (observation.isEmpty()) {
            return null;
        }
        Observation value = observation.get();
        double scale = value.live() ? 1.0 : ClientConfig.historicalOpacityScale();
        int colour = ClientConfig.palette(ClientConcentration.get().store().serverPalette())
                .xaeroColorFor(value.classification().orElse(null), scale);
        return new int[] {colour, colour, colour, colour, colour};
    }

    @Override
    public void addChunkHighlightTooltips(InfoDisplayCompiler compiler, ResourceKey<Level> dimension,
            int chunkX, int chunkZ, int width) {
        // No minimap readout is part of this mod's scope.
    }

    @Override
    public void addBlockHighlightTooltips(InfoDisplayCompiler compiler, ResourceKey<Level> dimension,
            int blockX, int blockZ, int width) {
        // No minimap readout is part of this mod's scope.
    }

    private static Optional<Observation> observation(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!ClientConfig.minimapOverlayEnabled()) {
            return Optional.empty();
        }
        ClientConcentrationStore store = ClientConcentration.get().store();
        return store.observe(
                ClientConcentration.get().key(dimension, chunkX, chunkZ),
                store.staleAfterTicks());
    }
}
