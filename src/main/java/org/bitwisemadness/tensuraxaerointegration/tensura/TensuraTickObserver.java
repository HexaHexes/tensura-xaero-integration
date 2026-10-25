package org.bitwisemadness.tensuraxaerointegration.tensura;

import io.github.manasmods.tensura.event.TensuraLevelEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;
import org.bitwisemadness.tensuraxaerointegration.server.ConcentrationServer;

/**
 * The event-driven half of the hybrid refresh.
 *
 * <p>Tensura fires {@link TensuraLevelEvents#CHUNK_TICK_POST} from
 * {@code ServerLevel.tickChunk} (verified against the 2.0.1.3 jar). It is a tick
 * notification, not a "value changed" notification, so the observer's job is
 * only to hand the already-loaded {@link LevelChunk} to the coordinator, which
 * diffs the authoritative value against what was last sent. No chunk is loaded,
 * no map is touched and no Tensura simulation is reimplemented here.</p>
 *
 * <p>Only reachable through {@link TensuraBridge}, which checks {@code ModList}
 * first, so this class is never loaded without Tensura installed.</p>
 */
final class TensuraTickObserver {

    private static final TensuraLevelEvents.ChunkTickEvent LISTENER = TensuraTickObserver::onChunkTick;

    private TensuraTickObserver() {
    }

    private static void onChunkTick(ServerLevel level, LevelChunk chunk) {
        ConcentrationServer.get().onChunkTicked(level, chunk);
    }

    static void register() {
        if (!TensuraLevelEvents.CHUNK_TICK_POST.isRegistered(LISTENER)) {
            TensuraLevelEvents.CHUNK_TICK_POST.register(LISTENER);
            TensuraXaeroIntegration.LOGGER.info("Attached Tensura's chunk-tick observer");
        }
    }

    static void unregister() {
        if (TensuraLevelEvents.CHUNK_TICK_POST.isRegistered(LISTENER)) {
            TensuraLevelEvents.CHUNK_TICK_POST.unregister(LISTENER);
        }
    }
}
