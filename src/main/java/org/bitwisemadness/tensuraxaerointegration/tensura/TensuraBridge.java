package org.bitwisemadness.tensuraxaerointegration.tensura;

import java.util.OptionalDouble;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;

/**
 * Tensura integration facade.
 *
 * <p>This class deliberately contains <b>no</b> references to any Tensura type.
 * Every method checks {@link #isAvailable()} first and only then delegates to
 * {@link TensuraAccess}. Because the JVM resolves class references lazily,
 * {@code TensuraAccess} is never loaded — and so never tries to resolve missing
 * Tensura classes — when Tensura is absent. That is what lets this mod ship as a
 * hard-independent jar while still using Tensura's real API when it is installed.</p>
 *
 * <p>Failures degrade to "no data" rather than to a crash: a Tensura build that
 * moved a method must not take the game down, it should simply stop drawing.</p>
 */
public final class TensuraBridge {

    private TensuraBridge() {
    }

    /** Whether Tensura: Reincarnated is installed. */
    public static boolean isAvailable() {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded("tensura");
    }

    /** Authoritative concentration for a loaded chunk, or empty when unavailable. */
    public static OptionalDouble read(ServerLevel level, int chunkX, int chunkZ) {
        if (!isAvailable() || level == null) {
            return OptionalDouble.empty();
        }
        try {
            return TensuraAccess.read(level, chunkX, chunkZ);
        } catch (Throwable failure) {
            return degrade("read chunk concentration", failure);
        }
    }

    /** Authoritative concentration for a chunk the caller already holds. */
    public static OptionalDouble read(LevelChunk chunk) {
        if (!isAvailable() || chunk == null) {
            return OptionalDouble.empty();
        }
        try {
            return TensuraAccess.read(chunk);
        } catch (Throwable failure) {
            return degrade("read chunk concentration", failure);
        }
    }

    /** Tensura's configured normal baseline, or empty when it cannot be read. */
    public static OptionalDouble baseline() {
        if (!isAvailable()) {
            return OptionalDouble.empty();
        }
        try {
            return TensuraAccess.baseline();
        } catch (Throwable failure) {
            return degrade("read the magicule baseline", failure);
        }
    }

    /**
     * Attaches the chunk-tick observer that supplies the event-driven half of
     * the refresh. Safe to call more than once per process.
     */
    public static void registerTickObserver() {
        if (!isAvailable()) {
            return;
        }
        try {
            TensuraTickObserver.register();
        } catch (Throwable failure) {
            TensuraXaeroIntegration.LOGGER.warn(
                    "Could not attach the Tensura chunk-tick observer; periodic reconciliation still runs",
                    failure);
        }
    }

    /** Detaches the chunk-tick observer on server stop. */
    public static void unregisterTickObserver() {
        if (!isAvailable()) {
            return;
        }
        try {
            TensuraTickObserver.unregister();
        } catch (Throwable failure) {
            TensuraXaeroIntegration.LOGGER.debug("Could not detach the Tensura chunk-tick observer", failure);
        }
    }

    private static OptionalDouble degrade(String action, Throwable failure) {
        TensuraXaeroIntegration.LOGGER.debug("Tensura integration could not {}", action, failure);
        return OptionalDouble.empty();
    }
}
