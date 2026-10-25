package org.bitwisemadness.tensuraxaerointegration.client;

import java.util.HashSet;
import java.util.Set;
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

    private XaeroWorldMapAccess() {
    }

    static Set<Long> mappedChunks(ResourceKey<Level> dimension, int centerChunkX, int centerChunkZ, int radius) {
        MapProcessor processor = processor();
        if (processor == null) {
            return Set.of();
        }
        MapWorld world = processor.getMapWorld();
        if (world == null) {
            return Set.of();
        }
        MapDimension mapDimension = world.getDimension(dimension);
        if (mapDimension == null) {
            return Set.of();
        }
        Set<Long> mapped = new HashSet<>();
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

    private static boolean isMapped(MapLayer layer, int chunkX, int chunkZ) {
        MapRegion region = layer.getMapRegions().getLeaf(chunkX >> 5, chunkZ >> 5);
        if (region == null) {
            return false;
        }
        MapTileChunk tileChunk = region.getChunk(chunkX >> 2 & 7, chunkZ >> 2 & 7);
        return tileChunk != null && tileChunk.getTile(chunkX & 3, chunkZ & 3) != null;
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
