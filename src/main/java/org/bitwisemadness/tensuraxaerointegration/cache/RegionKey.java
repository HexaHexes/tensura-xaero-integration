package org.bitwisemadness.tensuraxaerointegration.cache;

/**
 * A dimension plus a 32x32-chunk region coordinate.
 *
 * <p>Both Xaero maps invalidate whole regions, so this is the unit of redraw.
 * The region size matches the maps' own {@code MapRegion} / minimap region
 * geometry (chunk >> 5).</p>
 */
public record RegionKey(String dimension, int regionX, int regionZ) {

    public RegionKey {
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("dimension id must not be blank");
        }
    }

    /** The region containing a chunk. */
    public static RegionKey of(String dimension, int chunkX, int chunkZ) {
        return new RegionKey(dimension, chunkX >> 5, chunkZ >> 5);
    }
}
