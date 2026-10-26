package org.bitwisemadness.tensuraxaerointegration.util;

/**
 * Which chunks count as "around the player": a circle, not a square.
 *
 * <p>The distinction is not cosmetic. The corners of a square of radius
 * {@code r} sit {@code r * sqrt(2)} chunks from the player - 14.1 chunks for a
 * radius of 10 - and a server only holds chunks loaded out to roughly its view
 * distance. A square request therefore asks about chunks the server has no chunk
 * data for at all, and every one of them can only ever be answered with "no
 * value", which the client then has to present as last known.</p>
 *
 * <p>Used on both sides so the client's request and the server's cap mean exactly
 * the same thing: a chunk is in range when its centre-to-centre distance is at
 * most {@code radius}, measured in chunks, inclusive.</p>
 */
public final class ChunkRadius {

    private ChunkRadius() {
    }

    /**
     * Whether a chunk lies within a circular radius of another chunk.
     *
     * @param chunkX  the chunk to test
     * @param chunkZ  the chunk to test
     * @param centerX the player's chunk
     * @param centerZ the player's chunk
     * @param radius  the radius in chunks, inclusive; not negative
     * @return true when the chunks are at most {@code radius} chunks apart
     */
    public static boolean within(int chunkX, int chunkZ, int centerX, int centerZ, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("a radius cannot be negative, was " + radius);
        }
        // Widened before squaring: a map looking at far-away coordinates must not
        // overflow the comparison into claiming a chunk is close.
        long dx = (long) chunkX - centerX;
        long dz = (long) chunkZ - centerZ;
        long limit = (long) radius * radius;
        return dx * dx + dz * dz <= limit;
    }
}
