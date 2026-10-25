package org.bitwisemadness.tensuraxaerointegration.client;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.bitwisemadness.tensuraxaerointegration.net.PackedChunkPos;
import xaero.common.minimap.MinimapProcessor;
import xaero.common.minimap.region.MinimapChunk;
import xaero.common.minimap.write.MinimapWriter;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;

/**
 * The only class that imports Xaero's Minimap.
 *
 * <p>Reached solely through {@link MinimapBridge} after a {@code ModList} check.
 * The minimap only holds the chunks inside its current write window, so mapped
 * state here is necessarily local: a chunk far outside the window is not
 * "unmapped", it is simply not readable from the minimap. The world map is the
 * authority for distant chunks.</p>
 */
final class XaeroMinimapAccess {

    private XaeroMinimapAccess() {
    }

    static Set<Long> mappedChunks(ResourceKey<Level> dimension, int centerChunkX, int centerChunkZ, int radius) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().equals(dimension)) {
            // The minimap writer is bound to the dimension the player is in.
            return Set.of();
        }
        MinimapWriter writer = writer();
        if (writer == null) {
            return Set.of();
        }
        MinimapChunk[][] blocks = writer.getLoadedBlocks();
        if (blocks == null) {
            return Set.of();
        }
        Set<Long> mapped = new HashSet<>();
        for (MinimapChunk[] column : blocks) {
            if (column == null) {
                continue;
            }
            for (MinimapChunk chunk : column) {
                if (chunk == null) {
                    continue;
                }
                // A MinimapChunk covers 4x4 chunks; a non-null tile means written.
                for (int tileX = 0; tileX < 4; tileX++) {
                    for (int tileZ = 0; tileZ < 4; tileZ++) {
                        if (chunk.getTile(tileX, tileZ) == null) {
                            continue;
                        }
                        int chunkX = chunk.getX() * 4 + tileX;
                        int chunkZ = chunk.getZ() * 4 + tileZ;
                        if (Math.abs(chunkX - centerChunkX) > radius || Math.abs(chunkZ - centerChunkZ) > radius) {
                            continue;
                        }
                        mapped.add(PackedChunkPos.pack(chunkX, chunkZ));
                    }
                }
            }
        }
        return mapped;
    }

    /**
     * Single-chunk mapped lookup for the payload ingress path.
     *
     * <p>Indexes the loaded blocks directly instead of scanning every tile, so a
     * 256-entry update payload does not cost 256 full window scans.</p>
     */
    static boolean isMapped(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().equals(dimension)) {
            return false;
        }
        MinimapWriter writer = writer();
        if (writer == null) {
            return false;
        }
        MinimapChunk[][] blocks = writer.getLoadedBlocks();
        if (blocks == null) {
            return false;
        }
        int blockX = chunkX >> 2;
        int blockZ = chunkZ >> 2;
        int indexX = blockX - writer.getLoadedMapChunkX();
        int indexZ = blockZ - writer.getLoadedMapChunkZ();
        if (indexX < 0 || indexZ < 0 || indexX >= blocks.length || indexZ >= blocks.length) {
            return false;
        }
        MinimapChunk[] column = blocks[indexX];
        if (column == null || indexZ >= column.length) {
            return false;
        }
        MinimapChunk chunk = column[indexZ];
        return chunk != null && chunk.getTile(chunkX & 3, chunkZ & 3) != null;
    }

    static void refreshRegion(ResourceKey<Level> dimension, int regionX, int regionZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().equals(dimension)) {
            return;
        }
        MinimapWriter writer = writer();
        if (writer == null) {
            return;
        }
        var handler = writer.getDimensionHighlightHandler();
        if (handler != null) {
            // Bumps the writer's highlight version for the region if it is inside
            // the loading canvas, which is what makes the minimap re-write it.
            handler.requestRefresh(regionX, regionZ);
        }
    }

    private static MinimapWriter writer() {
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null) {
            return null;
        }
        MinimapProcessor processor = session.getProcessor();
        return processor == null ? null : processor.getMinimapWriter();
    }
}
