package org.bitwisemadness.tensuraxaerointegration.client;

import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKey;

/**
 * Gate for everything that touches Xaero's World Map.
 *
 * <p>No Xaero type appears in this class, so it can be loaded and called when the
 * World Map is absent. {@link XaeroWorldMapAccess} is only reached after the
 * {@code ModList} check, which is what keeps a world-map-less install from
 * throwing {@link NoClassDefFoundError}.</p>
 */
public final class WorldMapBridge {

    private static boolean failureLogged;

    private WorldMapBridge() {
    }

    public static boolean available() {
        return XaeroMods.worldMapPresent();
    }

    /**
     * Packed chunk positions that the World Map has written, within the square
     * around the given chunk.
     *
     * <p>Empty when the mod or its session is absent, or when the integration
     * cannot read the map (never fatal).</p>
     */
    public static Set<Long> mappedChunks(ResourceKey<Level> dimension, int centerChunkX, int centerChunkZ, int radius) {
        if (!available() || dimension == null) {
            return Set.of();
        }
        try {
            return XaeroWorldMapAccess.mappedChunks(dimension, centerChunkX, centerChunkZ, radius);
        } catch (Throwable failure) {
            logFailure(failure);
            return Set.of();
        }
    }

    public static boolean isChunkMapped(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return !mappedChunks(dimension, chunkX, chunkZ, 0).isEmpty();
    }

    /** Clears one region's cached highlight hash so the map repaints it. */
    public static void refreshRegion(RegionKey region) {
        if (!available() || region == null) {
            return;
        }
        try {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(region.dimension()));
            XaeroWorldMapAccess.refreshRegion(dimension, region.regionX(), region.regionZ());
        } catch (Throwable failure) {
            logFailure(failure);
        }
    }

    private static void logFailure(Throwable failure) {
        if (!failureLogged) {
            failureLogged = true;
            TensuraXaeroIntegration.LOGGER.warn(
                    "Xaero's World Map integration failed; the overlay is disabled for this session", failure);
        }
    }
}
