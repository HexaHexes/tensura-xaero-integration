package org.bitwisemadness.tensuraxaerointegration.net;

/**
 * Packs a chunk coordinate pair into one {@code long} for the wire.
 *
 * <p>Layout: {@code (x << 32) | (z & 0xFFFFFFFFL)}, which round-trips negative
 * coordinates exactly. Both halves are signed 32-bit, matching chunk coordinate
 * range, and a payload carries a count so a decoder never trusts the buffer to
 * end cleanly.</p>
 */
public final class PackedChunkPos {

    private PackedChunkPos() {
    }

    public static long pack(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (long) chunkZ & 0xFFFFFFFFL;
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackZ(long packed) {
        return (int) packed;
    }
}
