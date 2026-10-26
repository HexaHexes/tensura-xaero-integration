package org.bitwisemadness.tensuraxaerointegration.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.network.PacketDistributor;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;
import org.bitwisemadness.tensuraxaerointegration.cache.ChunkCacheKey;
import org.bitwisemadness.tensuraxaerointegration.config.ServerConfig;
import org.bitwisemadness.tensuraxaerointegration.net.BaselinePayload;
import org.bitwisemadness.tensuraxaerointegration.net.ConcentrationPayload;
import org.bitwisemadness.tensuraxaerointegration.net.PackedChunkPos;
import org.bitwisemadness.tensuraxaerointegration.net.SubscriptionPayload;
import org.bitwisemadness.tensuraxaerointegration.refresh.ReconcileCursor;
import org.bitwisemadness.tensuraxaerointegration.refresh.UpdateQueue;
import org.bitwisemadness.tensuraxaerointegration.tensura.TensuraBridge;

/**
 * Server-side owner of the concentration pipeline.
 *
 * <p>Responsibilities, in order of importance:</p>
 * <ol>
 *   <li><b>Disclosure control.</b> A value is only ever sent to a player who has
 *       subscribed that chunk, only for the dimension the player is in, only for
 *       chunks currently loaded on the server, and only within the configured
 *       distance of the player. Nothing is broadcast.</li>
 *   <li><b>Event-driven updates.</b> {@link #onChunkTicked} is called from
 *       Tensura's chunk-tick event for chunks that are already loaded; the value
 *       is compared with what was last sent and queued only if it changed.</li>
 *   <li><b>Periodic reconciliation.</b> {@link #onServerTick} re-reads a bounded,
 *       rotating slice of each player's subscriptions every cycle, so a change
 *       missed by the tick observer (or a chunk that unloaded) is corrected.</li>
 * </ol>
 *
 * <p>Only the server thread touches this class, except {@link #get()}.</p>
 */
public final class ConcentrationServer {

    private static final ConcentrationServer INSTANCE = new ConcentrationServer();

    /** How often the authoritative baseline is re-sent to every player. */
    private static final int BASELINE_REFRESH_INTERVAL_TICKS = 600;

    /** Retry delay while Tensura's baseline cannot be read at all. */
    private static final int BASELINE_RETRY_TICKS = 100;

    private final Map<UUID, PlayerState> players = new LinkedHashMap<>();

    private MinecraftServer server;
    private String worldToken = "server";
    private int lastBaselineTick = Integer.MIN_VALUE;

    private ConcentrationServer() {
    }

    public static ConcentrationServer get() {
        return INSTANCE;
    }

    /** Called once per server start. */
    public void start(MinecraftServer server) {
        this.server = server;
        String name = server.getWorldData().getLevelName();
        this.worldToken = name == null || name.isBlank() ? "server" : name;
        this.players.clear();
        this.lastBaselineTick = Integer.MIN_VALUE;
    }

    /** Called on server stop: drops every subscription and pending update. */
    public void stop() {
        this.players.clear();
        this.server = null;
    }

    public void onLogin(ServerPlayer player) {
        PlayerState state = new PlayerState(player.getUUID());
        this.players.put(player.getUUID(), state);
        state.baselinePending = true;
    }

    public void onLogout(ServerPlayer player) {
        this.players.remove(player.getUUID());
    }

    // --- subscriptions ------------------------------------------------------

    /**
     * Handles one subscription payload.
     *
     * <p>Every entry is validated here; the client's declaration is never
     * trusted beyond the list of chunks it says it has mapped.</p>
     */
    public void onSubscribe(ServerPlayer player, SubscriptionPayload payload) {
        if (this.server == null || !ServerConfig.syncEnabled()) {
            return;
        }
        PlayerState state = this.players.get(player.getUUID());
        if (state == null) {
            return;
        }
        ServerLevel level = this.server.getLevel(
                ResourceKey.create(Registries.DIMENSION, payload.dimension()));
        if (level == null || !level.dimension().equals(player.serverLevel().dimension())) {
            // Only the dimension the player is standing in is answerable: chunk
            // storage for any other dimension is not loaded for them, and offering
            // it would disclose data the client is not displaying anyway.
            return;
        }
        int now = this.server.getTickCount();
        // The first packet of a session must not be rate-limited; comparing against
        // an "unset" sentinel would overflow and reject every packet for good.
        if (state.receivedSubscribe
                && now - state.lastSubscribeTick < ServerConfig.minSubscribePacketIntervalTicks()) {
            return;
        }
        state.receivedSubscribe = true;
        state.lastSubscribeTick = now;

        int radius = ServerConfig.maxSubscribeRadiusChunks();
        int playerChunkX = player.chunkPosition().x;
        int playerChunkZ = player.chunkPosition().z;
        long[] chunks = payload.chunks();

        if (payload.subscribe()) {
            int cap = ServerConfig.maxSubscribedChunks();
            for (long packed : chunks) {
                int chunkX = PackedChunkPos.unpackX(packed);
                int chunkZ = PackedChunkPos.unpackZ(packed);
                if (!withinRadius(chunkX, chunkZ, playerChunkX, playerChunkZ, radius)) {
                    continue;
                }
                ChunkCacheKey key = key(level, chunkX, chunkZ);
                if (state.subscriptions.contains(key)) {
                    continue;
                }
                if (state.subscriptions.size() >= cap) {
                    break;
                }
                if (state.subscriptions.add(key)) {
                    offerCurrent(level, key, now, state);
                }
            }
        } else {
            for (long packed : chunks) {
                state.subscriptions.remove(key(level, PackedChunkPos.unpackX(packed), PackedChunkPos.unpackZ(packed)));
            }
        }
        state.queue.retainOnly(state.subscriptions);
        if (this.players.size() == 1) {
            TensuraXaeroIntegration.LOGGER.debug("Subscriptions for {}: {} chunk(s)",
                    player.getGameProfile().getName(), state.subscriptions.size());
        }
    }

    // --- event-driven half --------------------------------------------------

    /**
     * Called from Tensura's {@code CHUNK_TICK_POST} for an already-loaded chunk.
     *
     * <p>The chunk is in memory by definition here, so the read is a field access
     * on its storage; no loading, no generation, no map work.</p>
     */
    public void onChunkTicked(ServerLevel level, LevelChunk chunk) {
        if (this.players.isEmpty() || !ServerConfig.syncEnabled()) {
            return;
        }
        String dimension = level.dimension().location().toString();
        int chunkX = chunk.getPos().x;
        int chunkZ = chunk.getPos().z;
        ChunkCacheKey key = new ChunkCacheKey(this.worldToken, dimension, chunkX, chunkZ);
        if (!anyPlayerSubscribed(key, dimension)) {
            return;
        }
        double value = readOrNaN(TensuraBridge.read(chunk));
        int now = this.server == null ? 0 : this.server.getTickCount();
        for (PlayerState state : this.players.values()) {
            if (!dimension.equals(state.dimension) || !state.subscriptions.contains(key)) {
                continue;
            }
            state.queue.offerIfChanged(key, value, now);
        }
    }

    private boolean anyPlayerSubscribed(ChunkCacheKey key, String dimension) {
        for (PlayerState state : this.players.values()) {
            if (dimension.equals(state.dimension) && state.subscriptions.contains(key)) {
                return true;
            }
        }
        return false;
    }

    // --- periodic half ------------------------------------------------------

    /** Called at the end of every server tick. */
    public void onServerTick(MinecraftServer server) {
        if (this.players.isEmpty() || this.server == null) {
            return;
        }
        int now = server.getTickCount();
        boolean enabled = ServerConfig.syncEnabled();
        boolean sendBaseline = now - this.lastBaselineTick >= BASELINE_REFRESH_INTERVAL_TICKS;
        if (sendBaseline) {
            this.lastBaselineTick = now;
        }
        double baseline = readOrNaN(TensuraBridge.baseline());
        if (Double.isNaN(baseline)) {
            // Tensura's config can be unreadable for a moment; retry soon rather
            // than leaving clients without a baseline for the full interval.
            this.lastBaselineTick = now - BASELINE_REFRESH_INTERVAL_TICKS + BASELINE_RETRY_TICKS;
        }

        for (PlayerState state : this.players.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(state.id);
            if (player == null) {
                continue;
            }
            state.dimension = player.serverLevel().dimension().location().toString();
            if (!enabled) {
                state.queue.clear();
                continue;
            }
            if (state.baselinePending || sendBaseline) {
                PacketDistributor.sendToPlayer(player, new BaselinePayload(baseline));
                state.baselinePending = false;
            }
            pruneIneligible(player, state);
            if (now % ServerConfig.reconcileIntervalTicks() == 0) {
                reconcile(player, state, now);
            }
            flush(player, state, now);
        }
    }

    /**
     * Re-reads a bounded slice of the player's subscriptions.
     *
     * <p>The slice rotates, so the whole set is covered over several cycles while
     * any single tick stays cheap. Chunks that are no longer loaded are reported
     * unavailable instead of keeping their last value alive.</p>
     */
    private void reconcile(ServerPlayer player, PlayerState state, int now) {
        List<ChunkCacheKey> snapshot = new ArrayList<>(state.subscriptions);
        List<ChunkCacheKey> slice = state.cursor.next(snapshot, ServerConfig.reconcileBudgetChunks());
        if (slice.isEmpty()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        for (ChunkCacheKey key : slice) {
            if (!state.subscriptions.contains(key)) {
                continue;
            }
            offerCurrent(level, key, now, state);
        }
    }

    /** Reads a chunk's current value and queues it when it differs from what was sent. */
    private void offerCurrent(ServerLevel level, ChunkCacheKey key, int now, PlayerState state) {
        OptionalDouble value = TensuraBridge.read(level, key.chunkX(), key.chunkZ());
        state.queue.offerIfChanged(key, readOrNaN(value), now);
    }

    /** Sends everything the queue allows right now, batched into one payload. */
    private void flush(ServerPlayer player, PlayerState state, int now) {
        List<UpdateQueue.Update> ready = state.queue.takeReady(
                now, ServerConfig.minUpdateIntervalTicks(), ServerConfig.maxUpdatesPerPacket());
        if (ready.isEmpty()) {
            return;
        }
        if (state.dimension.isEmpty()) {
            return;
        }
        long[] chunks = new long[ready.size()];
        double[] values = new double[ready.size()];
        for (int i = 0; i < ready.size(); i++) {
            UpdateQueue.Update update = ready.get(i);
            chunks[i] = PackedChunkPos.pack(update.key().chunkX(), update.key().chunkZ());
            values[i] = update.value();
        }
        ResourceLocation dimension = ResourceLocation.parse(state.dimension);
        PacketDistributor.sendToPlayer(player, new ConcentrationPayload(dimension, chunks, values));
    }

    /**
     * Drops subscriptions that are no longer answerable: another dimension, or
     * further than the server-side radius.
     *
     * <p>This is what keeps a client from pinning a distant chunk and having the
     * server keep reading it forever.</p>
     */
    private void pruneIneligible(ServerPlayer player, PlayerState state) {
        int radius = ServerConfig.maxSubscribeRadiusChunks();
        int playerChunkX = player.chunkPosition().x;
        int playerChunkZ = player.chunkPosition().z;
        Iterator<ChunkCacheKey> iterator = state.subscriptions.iterator();
        while (iterator.hasNext()) {
            ChunkCacheKey key = iterator.next();
            boolean otherDimension = !key.dimension().equals(state.dimension);
            boolean tooFar = !withinRadius(key.chunkX(), key.chunkZ(), playerChunkX, playerChunkZ, radius);
            if (otherDimension || tooFar) {
                iterator.remove();
            }
        }
        state.queue.retainOnly(state.subscriptions);
    }

    // --- helpers ------------------------------------------------------------

    private ChunkCacheKey key(ServerLevel level, int chunkX, int chunkZ) {
        return new ChunkCacheKey(this.worldToken, level.dimension().location().toString(), chunkX, chunkZ);
    }

    private static boolean withinRadius(int chunkX, int chunkZ, int centerX, int centerZ, int radius) {
        return Math.abs(chunkX - centerX) <= radius && Math.abs(chunkZ - centerZ) <= radius;
    }

    private static double readOrNaN(OptionalDouble value) {
        return value.isPresent() ? value.getAsDouble() : Double.NaN;
    }

    /** Per-player state: what they asked for, and what has been sent. */
    private static final class PlayerState {
        private final UUID id;
        private final Set<ChunkCacheKey> subscriptions = new LinkedHashSet<>();
        private final UpdateQueue queue = new UpdateQueue();
        private final ReconcileCursor<ChunkCacheKey> cursor = new ReconcileCursor<>();
        private String dimension = "";
        private int lastSubscribeTick;
        private boolean receivedSubscribe;
        private boolean baselinePending;

        private PlayerState(UUID id) {
            this.id = id;
        }
    }

    /** Visible for diagnostics and GameTests. */
    public int trackedPlayers() {
        return this.players.size();
    }

    /** Visible for diagnostics and GameTests: the number of subscriptions of a player. */
    public int subscriptionCount(UUID playerId) {
        PlayerState state = this.players.get(playerId);
        return state == null ? 0 : state.subscriptions.size();
    }

    /** Visible for diagnostics and GameTests: whether any subscription exists. */
    public boolean hasSubscriptions() {
        return this.players.values().stream().anyMatch(state -> !state.subscriptions.isEmpty());
    }

    /** The dimension key currently recorded for a player, or empty. */
    public java.util.Optional<ResourceKey<Level>> dimensionOf(UUID playerId) {
        PlayerState state = this.players.get(playerId);
        if (state == null || state.dimension.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(state.dimension)));
    }
}
