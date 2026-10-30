package org.bitwisemadness.tensuraxaerointegration.client.overlay;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKey;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKeyMemo;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentration;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentrationStore;
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
 *       returns true exactly when this client holds a value for the chunk - live
 *       or last known.</li>
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

    /** One-shot diagnostics, so "nothing is drawn" can be located from the log. */
    private static final AtomicBoolean loggedHighlit = new AtomicBoolean();
    private static final AtomicBoolean loggedPainted = new AtomicBoolean();

    /**
     * The five colours one chunk needs, reused per thread.
     *
     * <p>Xaero's {@code getChunkHighlitColor} reads all five entries into locals
     * and copies them into its own result store before returning, so this array
     * never outlives the call. Per thread because the map's render and writer
     * threads both ask, and one array shared between them would race.</p>
     */
    private static final ThreadLocal<int[]> COLORS = ThreadLocal.withInitial(() -> new int[5]);

    /**
     * The region key last asked for: Xaero asks a region's gate and then its hash
     * while rebuilding it, and this one instance covers both.
     */
    private final RegionKeyMemo regionKeys = new RegionKeyMemo();

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
        // Xaero asks this *and* getColors for every chunk it draws, so it answers
        // without building an observation: no Optional, no record, no palette.
        return enabled() && ClientConcentration.get().hasValue(dimension, chunkX, chunkZ);
    }

    @Override
    protected int[] getColors(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!ClientConfig.worldMapOverlayEnabled()) {
            return null;
        }
        // One packed read: the number is not needed to paint, only the band and
        // whether the value is still current.
        ClientConcentrationStore store = ClientConcentration.get().store();
        int state = ClientConcentration.get().paintState(dimension, chunkX, chunkZ);
        if (state == ClientConcentrationStore.NO_PAINT) {
            return null;
        }
        ConcentrationClassification band = ClientConcentrationStore.bandOf(state);
        boolean live = ClientConcentrationStore.liveOf(state);
        if (loggedHighlit.compareAndSet(false, true)) {
            TensuraXaeroIntegration.LOGGER.info(
                    "World map is drawing concentration data (first chunk {},{}: live={} band={})",
                    chunkX, chunkZ, live, band);
        }
        // A value that is no longer refreshed is drawn dimmer, never as bright as
        // a current one; a normal chunk stays fully transparent either way.
        double scale = live ? 1.0 : ClientConfig.historicalOpacityScale();
        int colour = ClientConfig.palette(store.serverPalette()).xaeroColorFor(band, scale);
        if ((colour & 0xFF) > 0 && loggedPainted.compareAndSet(false, true)) {
            TensuraXaeroIntegration.LOGGER.info(
                    "World map overlay is painting (first painted chunk {},{}: band={} alpha={})",
                    chunkX, chunkZ, band, colour & 0xFF);
        }
        int[] colours = COLORS.get();
        colours[0] = colour;
        colours[1] = colour;
        colours[2] = colour;
        colours[3] = colour;
        colours[4] = colour;
        return colours;
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
        return HoverText.describe(ClientConcentration.get().dimensionIdOf(dimension), chunkX, chunkZ);
    }

    @Override
    public void addMinimapBlockHighlightTooltips(List<Component> tooltips, ResourceKey<Level> dimension,
            int blockX, int blockZ, int width) {
        // The minimap has its own highlighter; nothing to add here.
    }

    private RegionKey regionKey(ResourceKey<Level> dimension, int regionX, int regionZ) {
        return this.regionKeys.keyFor(ClientConcentration.get().dimensionIdOf(dimension), regionX, regionZ);
    }
}
