package org.bitwisemadness.tensuraxaerointegration.cache;

/**
 * Identity of one cached concentration: a world token, a dimension id and the
 * chunk coordinates.
 *
 * <p>The world token is what keeps two saves, two servers and a single-player
 * world apart; the dimension id keeps the overworld, the nether and any modded
 * dimension apart even when they share chunk coordinates. Both are required:
 * chunk coordinates alone collide across every one of those.</p>
 *
 * <p>Pure by design, so key isolation is unit-tested without a game.</p>
 */
public record ChunkCacheKey(String world, String dimension, int chunkX, int chunkZ) {

    public ChunkCacheKey {
        if (world == null || world.isBlank()) {
            throw new IllegalArgumentException("world token must not be blank");
        }
        if (dimension == null || dimension.isBlank()) {
            throw new IllegalArgumentException("dimension id must not be blank");
        }
    }

    /** The chunk's region coordinate (32x32 chunks), matching Xaero's regions. */
    public RegionKey region() {
        return new RegionKey(this.dimension, this.chunkX >> 5, this.chunkZ >> 5);
    }

    /** A key in a different world: never equal to this one, by construction. */
    public ChunkCacheKey withWorld(String otherWorld) {
        return new ChunkCacheKey(otherWorld, this.dimension, this.chunkX, this.chunkZ);
    }
}
