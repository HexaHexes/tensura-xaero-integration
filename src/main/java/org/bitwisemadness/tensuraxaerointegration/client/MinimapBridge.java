package org.bitwisemadness.tensuraxaerointegration.client;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKey;

/**
 * Gate for everything that touches Xaero's Minimap.
 *
 * <p>Separate from {@link WorldMapBridge} on purpose: the two maps have
 * independent sessions, independent mapped state and independent mixin configs,
 * so a minimap-only install works without any world-map class being loaded.</p>
 */
public final class MinimapBridge {

    private static boolean failureLogged;

    private MinimapBridge() {
    }

    public static boolean available() {
        return XaeroMods.minimapPresent();
    }

    /** Packed chunk positions the minimap has written in its current window. */
    public static LongSet mappedChunks(ResourceKey<Level> dimension, int centerChunkX, int centerChunkZ, int radius) {
        if (!available() || dimension == null) {
            return LongSets.emptySet();
        }
        try {
            return XaeroMinimapAccess.mappedChunks(dimension, centerChunkX, centerChunkZ, radius);
        } catch (Throwable failure) {
            logFailure(failure);
            return LongSets.emptySet();
        }
    }

    public static boolean isChunkMapped(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (!available() || dimension == null) {
            return false;
        }
        try {
            return XaeroMinimapAccess.isMapped(dimension, chunkX, chunkZ);
        } catch (Throwable failure) {
            logFailure(failure);
            return false;
        }
    }

    /** Bumps the minimap writer's highlight version for one region. */
    public static void refreshRegion(RegionKey region) {
        if (!available() || region == null) {
            return;
        }
        try {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(region.dimension()));
            XaeroMinimapAccess.refreshRegion(dimension, region.regionX(), region.regionZ());
        } catch (Throwable failure) {
            logFailure(failure);
        }
    }

    private static void logFailure(Throwable failure) {
        if (!failureLogged) {
            failureLogged = true;
            TensuraXaeroIntegration.LOGGER.warn(
                    "Xaero's Minimap integration failed; the overlay is disabled for this session", failure);
        }
    }
}
