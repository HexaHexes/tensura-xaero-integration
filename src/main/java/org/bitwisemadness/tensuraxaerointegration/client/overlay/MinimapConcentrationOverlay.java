package org.bitwisemadness.tensuraxaerointegration.client.overlay;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKey;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentration;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentrationStore;
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

    /**
     * The five colours one chunk needs, reused per thread.
     *
     * <p>Xaero's {@code getChunkHighlitColor} reads all five entries into locals
     * and copies them into its own result store before returning, so this array
     * never outlives the call. Per thread because the minimap's render and writer
     * threads both ask, and one array shared between them would race.</p>
     */
    private static final ThreadLocal<int[]> COLORS = ThreadLocal.withInitial(() -> new int[5]);

    private MinimapConcentrationOverlay() {
        super(false);
    }

    @Override
    public boolean regionHasHighlights(ResourceKey<Level> dimension, int regionX, int regionZ) {
        if (!ClientConfig.minimapOverlayEnabled()) {
            return false;
        }
        return ClientConcentration.get().store().hasRegionData(new RegionKey(
                ClientConcentration.get().dimensionIdOf(dimension), regionX, regionZ));
    }

    @Override
    public boolean chunkIsHighlit(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!ClientConfig.minimapOverlayEnabled()) {
            return false;
        }
        // Answers without building an observation; the minimap asks this and
        // getColors for the same chunk every time it writes a tile.
        return ClientConcentration.get().store()
                .hasValue(ClientConcentration.get().key(dimension, chunkX, chunkZ));
    }

    @Override
    protected int[] getColors(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!ClientConfig.minimapOverlayEnabled()) {
            return null;
        }
        // One packed read: painting needs the band and the liveness, not the number.
        ClientConcentrationStore store = ClientConcentration.get().store();
        int state = store.paintState(
                ClientConcentration.get().key(dimension, chunkX, chunkZ), store.staleAfterMillis());
        if (state == ClientConcentrationStore.NO_PAINT) {
            return null;
        }
        double scale = ClientConcentrationStore.liveOf(state) ? 1.0 : ClientConfig.historicalOpacityScale();
        int colour = ClientConfig.palette(store.serverPalette())
                .xaeroColorFor(ClientConcentrationStore.bandOf(state), scale);
        int[] colours = COLORS.get();
        colours[0] = colour;
        colours[1] = colour;
        colours[2] = colour;
        colours[3] = colour;
        colours[4] = colour;
        return colours;
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

}
