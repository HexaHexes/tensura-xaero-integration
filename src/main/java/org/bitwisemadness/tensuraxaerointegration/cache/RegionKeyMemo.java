package org.bitwisemadness.tensuraxaerointegration.cache;

/**
 * Remembers one {@link RegionKey}, for a caller that asks about the same region
 * several times in a row.
 *
 * <p>Xaero rebuilds one region at a time and asks its highlighter for that region's
 * gate and then its hash, so a single remembered entry covers both without building
 * a key per call. One entry is enough by the same reasoning as
 * {@code ClientConcentration.dimensionIdOf}: the asker is working one region.</p>
 *
 * <p>A key is a record used as a map key, so the memo hands back the same instance
 * rather than mutating one; entries are published as a single immutable value, which
 * keeps this safe for a map's reader and writer threads.</p>
 */
public final class RegionKeyMemo {

    private record Entry(String dimension, int regionX, int regionZ, RegionKey key) {
    }

    private volatile Entry entry;

    /** The key for a region, reusing the remembered one when it is the same region. */
    public RegionKey keyFor(String dimension, int regionX, int regionZ) {
        Entry current = this.entry;
        if (current != null && current.regionX() == regionX && current.regionZ() == regionZ
                && current.dimension().equals(dimension)) {
            return current.key();
        }
        RegionKey key = new RegionKey(dimension, regionX, regionZ);
        this.entry = new Entry(dimension, regionX, regionZ, key);
        return key;
    }
}
