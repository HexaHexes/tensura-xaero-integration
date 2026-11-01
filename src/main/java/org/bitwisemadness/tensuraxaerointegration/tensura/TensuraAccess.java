package org.bitwisemadness.tensuraxaerointegration.tensura;

import io.github.manasmods.tensura.config.AreaMagiculeConfig;
import io.github.manasmods.tensura.storage.TensuraStorages;
import io.github.manasmods.tensura.storage.chunk.ChunkStorage;
import java.util.OptionalDouble;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The only class in this mod that imports Tensura: Reincarnated types.
 *
 * <p>Never reference this class directly — always go through
 * {@link TensuraBridge}, which checks {@code ModList} first. Loading this class
 * without Tensura installed would throw {@link NoClassDefFoundError}.</p>
 *
 * <p>Every method here is server-side. Tensura only stores authoritative chunk
 * magicule in the server's {@code LevelChunk} storage; a client-side
 * {@code ChunkStorage} reports the base value and is never synchronised, so
 * reading it there would silently invent data.</p>
 */
final class TensuraAccess {

    private TensuraAccess() {
    }

    /**
     * Reads a loaded chunk's authoritative concentration.
     *
     * <p>The {@code hasChunk} guard is what guarantees no chunk is loaded or
     * generated to answer a map query; an unloaded chunk is reported as
     * unavailable rather than as Tensura's baseline fallback.</p>
     */
    static OptionalDouble read(ServerLevel level, int chunkX, int chunkZ) {
        if (!level.hasChunk(chunkX, chunkZ)) {
            return OptionalDouble.empty();
        }
        return read(level.getChunk(chunkX, chunkZ));
    }

    /** Reads a chunk the caller already holds (e.g. from a chunk-tick event). */
    static OptionalDouble read(LevelChunk chunk) {
        if (chunk == null || chunk.getLevel().isClientSide()) {
            return OptionalDouble.empty();
        }
        ChunkStorage storage = TensuraStorages.getChunkFrom(chunk);
        if (storage == null) {
            return OptionalDouble.empty();
        }
        double value = storage.getMagicule();
        if (!Double.isFinite(value) || value < 0.0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(value);
    }

    /**
     * Tensura's configured normal baseline ({@code baseMagicule}), which the
     * band percentages are relative to.
     *
     * <p>Read from {@code ChunkStorage.CONFIG}, the same object Tensura's own
     * storage uses. Empty when the config has not been registered (very early
     * loading) or holds a non-positive value — this mod does not fabricate a
     * baseline it cannot verify.</p>
     */
    static OptionalDouble baseline() {
        AreaMagiculeConfig config = ChunkStorage.CONFIG;
        if (config == null) {
            return OptionalDouble.empty();
        }
        double base = config.baseMagicule;
        if (!Double.isFinite(base) || base <= 0.0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(base);
    }

    /** The per-chunk configured maximum, for diagnostics; never used for display. */
    static OptionalDouble maxOf(LevelChunk chunk) {
        if (chunk == null || chunk.getLevel().isClientSide()) {
            return OptionalDouble.empty();
        }
        ChunkStorage storage = TensuraStorages.getChunkFrom(chunk);
        if (storage == null) {
            return OptionalDouble.empty();
        }
        double value = storage.getMaxMagicule();
        return Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
    }
}
