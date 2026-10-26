package org.bitwisemadness.tensuraxaerointegration.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bitwisemadness.tensuraxaerointegration.net.PackedChunkPos;
import xaero.map.MapProcessor;
import xaero.map.WorldMapSession;
import xaero.map.region.MapLayer;
import xaero.map.region.MapRegion;
import xaero.map.region.MapTileChunk;
import xaero.map.world.MapDimension;
import xaero.map.world.MapWorld;

/**
 * The only class that imports Xaero's World Map.
 *
 * <p>Reached solely through {@link WorldMapBridge} after a {@code ModList} check.
 * Everything here reads the map's own state; it never writes map data and never
 * causes a region to be created ({@code getLeaf} is the non-creating lookup).</p>
 *
 * <p>Mapped status comes from the map's own tiles: a chunk is mapped exactly when
 * its {@code MapTile} exists, which is the same signal Xaero uses to decide
 * whether terrain is discovered. Chunk-loaded, chunk-generated and
 * server-visited checks are deliberately not used - none of them mean "mapped".</p>
 */
final class XaeroWorldMapAccess {

    /**
     * Reused by {@link #isMapped} so a single-chunk test allocates nothing.
     *
     * <p>The layer walk only offers a callback, so the answer has to be written
     * somewhere; one instance is reused instead of building a result set. The map
     * state it reads is client-thread-only, which makes the probe too.</p>
     */
    private static final AnyMappedProbe ANY_MAPPED = new AnyMappedProbe();

    private XaeroWorldMapAccess() {
    }

    /**
     * Chunks in the square that any loaded layer holds a tile for.
     *
     * <p>A primitive set: the result is keyed by packed position, so a boxed
     * {@code Long} per hit would be the walk's whole allocation cost.</p>
     */
    static LongSet mappedChunks(ResourceKey<Level> dimension, int centerChunkX, int centerChunkZ, int radius) {
        MapProcessor processor = processor();
        if (processor == null) {
            return LongSets.emptySet();
        }
        MapWorld world = processor.getMapWorld();
        if (world == null) {
            return LongSets.emptySet();
        }
        MapDimension mapDimension = world.getDimension(dimension);
        if (mapDimension == null) {
            return LongSets.emptySet();
        }
        LongSet mapped = new LongOpenHashSet();
        // Every cave layer the map currently holds, so a chunk explored only in
        // cave mode still counts as mapped.
        mapDimension.getLayeredMapRegions().applyToEachLoadedLayer((layer, mapLayer) -> {
            for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius; chunkX++) {
                for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius; chunkZ++) {
                    if (isMapped(mapLayer, chunkX, chunkZ)) {
                        mapped.add(PackedChunkPos.pack(chunkX, chunkZ));
                    }
                }
            }
        });
        return mapped;
    }

    /**
     * Whether any loaded layer holds a tile for this chunk.
     *
     * <p>The single-chunk form of {@link #mappedChunks}: it stops at the first hit
     * and builds no set, so answering it costs no allocation.</p>
     */
    static boolean isMapped(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        MapProcessor processor = processor();
        if (processor == null) {
            return false;
        }
        MapWorld world = processor.getMapWorld();
        if (world == null) {
            return false;
        }
        MapDimension mapDimension = world.getDimension(dimension);
        if (mapDimension == null) {
            return false;
        }
        return ANY_MAPPED.test(mapDimension, chunkX, chunkZ);
    }

    private static boolean isMapped(MapLayer layer, int chunkX, int chunkZ) {
        MapRegion region = layer.getMapRegions().getLeaf(chunkX >> 5, chunkZ >> 5);
        if (region == null) {
            return false;
        }
        MapTileChunk tileChunk = region.getChunk(chunkX >> 2 & 7, chunkZ >> 2 & 7);
        return tileChunk != null && tileChunk.getTile(chunkX & 3, chunkZ & 3) != null;
    }

    /** Writes what one chunk's any-layer test found; see {@link #ANY_MAPPED}. */
    private static final class AnyMappedProbe implements BiConsumer<Integer, MapLayer> {

        private int chunkX;
        private int chunkZ;
        private boolean found;

        boolean test(MapDimension dimension, int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.found = false;
            dimension.getLayeredMapRegions().applyToEachLoadedLayer(this);
            return this.found;
        }

        @Override
        public void accept(Integer layerIndex, MapLayer layer) {
            if (!this.found) {
                this.found = isMapped(layer, this.chunkX, this.chunkZ);
            }
        }
    }

    static void refreshRegion(ResourceKey<Level> dimension, int regionX, int regionZ) {
        MapProcessor processor = processor();
        if (processor == null) {
            return;
        }
        MapWorld world = processor.getMapWorld();
        if (world == null) {
            return;
        }
        MapDimension mapDimension = world.getDimension(dimension);
        if (mapDimension == null) {
            return;
        }
        // Drops the cached highlight hash for the region; the next GUI frame sees
        // the mismatch against our new calculateRegionHash and repaints it.
        mapDimension.getHighlightHandler().clearCachedHash(regionX, regionZ);
    }

    private static MapProcessor processor() {
        WorldMapSession session = WorldMapSession.getCurrentSession();
        return session == null ? null : session.getMapProcessor();
    }
}
