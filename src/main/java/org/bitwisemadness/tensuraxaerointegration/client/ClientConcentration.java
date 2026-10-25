package org.bitwisemadness.tensuraxaerointegration.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.bitwisemadness.tensuraxaerointegration.cache.ChunkCacheKey;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKey;
import org.bitwisemadness.tensuraxaerointegration.config.ClientConfig;
import org.bitwisemadness.tensuraxaerointegration.net.ConcentrationPayload;
import org.bitwisemadness.tensuraxaerointegration.net.PackedChunkPos;
import org.bitwisemadness.tensuraxaerointegration.net.SubscriptionPayload;

/**
 * Client-side session: owns the display store, decides which chunks to ask for,
 * and invalidates map regions when values change.
 *
 * <p>The subscription set is derived from the maps' own mapped state, never from
 * chunk-loaded or visited state. Only mapped chunks within the configured radius
 * are ever requested, so the client part of the mapped-chunk rule is enforced
 * before a single byte leaves the client.</p>
 *
 * <p>Every method here runs on the client thread (client tick, or payload
 * handlers via {@code enqueueWork}).</p>
 */
public final class ClientConcentration {

    private static final ClientConcentration INSTANCE = new ClientConcentration();

    /** Maintenance (staleness sweep, purge) cadence. */
    private static final int MAINTENANCE_INTERVAL_TICKS = 20;

    /**
     * How often the whole subscription set is re-declared.
     *
     * <p>Self-healing: the server prunes out-of-range subscriptions, and a client
     * radius larger than the server's cap would otherwise leave chunks
     * permanently unrequested. Re-sending is idempotent and small.</p>
     */
    private static final int RESYNC_INTERVAL_TICKS = 200;

    private int lastResyncTick;

    private final ClientConcentrationStore store = new ClientConcentrationStore(4096);
    private final Set<ChunkCacheKey> subscriptions = new LinkedHashSet<>();

    private String worldToken = "";
    private String dimensionId = "";
    private int ticks;
    private int lastSubscribeTick;
    private boolean subscribeRefreshDue = true;

    private ClientConcentration() {
    }

    public static ClientConcentration get() {
        return INSTANCE;
    }

    public ClientConcentrationStore store() {
        return this.store;
    }

    /** The current world token, used to build cache keys. */
    public String worldToken() {
        return this.worldToken;
    }

    /** The current dimension id, used to build cache keys. */
    public String dimensionId() {
        return this.dimensionId;
    }

    /** Builds the cache key for a chunk in a dimension, scoped to this session's world. */
    public ChunkCacheKey key(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        String token = this.worldToken.isBlank() ? "unknown" : this.worldToken;
        return new ChunkCacheKey(token, dimension.location().toString(), chunkX, chunkZ);
    }

    /** Drives staleness, subscription diffing and map invalidation. */
    public void onClientTick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            if (!this.subscriptions.isEmpty() || this.store.size() > 0) {
                resetSession();
            }
            return;
        }
        String token = worldToken(minecraft);
        String dimension = minecraft.level.dimension().location().toString();
        if (!token.equals(this.worldToken) || !dimension.equals(this.dimensionId)) {
            beginSession(token, dimension);
        }
        this.store.tick();
        this.ticks++;

        if (this.ticks % MAINTENANCE_INTERVAL_TICKS == 0) {
            this.store.expire(ClientConfig.staleAfterTicks());
            this.store.purge(ClientConfig.purgeAfterTicks());
        }
        if (this.subscribeRefreshDue || this.ticks - this.lastSubscribeTick >= ClientConfig.subscribeIntervalTicks()) {
            this.lastSubscribeTick = this.ticks;
            this.subscribeRefreshDue = false;
            updateSubscriptions(minecraft);
        }
        refreshDirtyRegions();
    }

    /** Handles a batch of values from the server. */
    public void accept(ConcentrationPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        String dimension = payload.dimension().toString();
        boolean isCurrentDimension = minecraft.level.dimension().location().toString().equals(dimension);
        if (!isCurrentDimension && !dimension.equals(this.dimensionId)) {
            // A late packet from a dimension the player already left.
            return;
        }
        if (this.dimensionId.isEmpty()) {
            // Packet arrived before the first tick after login; adopt the level's
            // dimension for keying instead of dropping data the client just asked for.
            this.dimensionId = dimension;
        }
        long[] chunks = payload.chunks();
        double[] values = payload.values();
        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, payload.dimension());
        String token = this.worldToken.isBlank() ? "unknown" : this.worldToken;
        for (int i = 0; i < chunks.length; i++) {
            int chunkX = PackedChunkPos.unpackX(chunks[i]);
            int chunkZ = PackedChunkPos.unpackZ(chunks[i]);
            ChunkCacheKey key = new ChunkCacheKey(token, dimension, chunkX, chunkZ);
            // Defence in depth: the store refuses unmapped chunks anyway, but the
            // mapped test is repeated here so a value can never be remembered for
            // a chunk that stopped being mapped between request and reply.
            boolean mapped = isMapped(dimensionKey, chunkX, chunkZ);
            double value = values[i];
            if (Double.isNaN(value)) {
                this.store.markUnavailable(key, mapped);
            } else {
                this.store.offer(key, mapped, value);
            }
        }
    }

    /** Full reset: used on disconnect and world change. */
    public void resetSession() {
        this.subscriptions.clear();
        this.store.clear();
        this.store.clearBaseline();
        this.worldToken = "";
        this.dimensionId = "";
        this.lastSubscribeTick = 0;
        this.subscribeRefreshDue = true;
    }

    // --- subscription driving ----------------------------------------------

    private void beginSession(String token, String dimension) {
        boolean newWorld = !token.equals(this.worldToken);
        if (!this.dimensionId.isEmpty() && !this.subscriptions.isEmpty() && !this.dimensionId.equals(dimension)) {
            // Release the old dimension's subscriptions instead of leaving the
            // server holding chunks the client no longer shows.
            sendSubscriptions(false, this.dimensionId, this.subscriptions);
        }
        this.subscriptions.clear();
        this.store.clear();
        if (newWorld) {
            // The baseline is per world; a dimension change keeps it.
            this.store.clearBaseline();
        }
        this.worldToken = token;
        this.dimensionId = dimension;
        this.lastSubscribeTick = 0;
        this.lastResyncTick = 0;
        this.subscribeRefreshDue = true;
        this.ticks = 0;
    }

    private void updateSubscriptions(Minecraft minecraft) {
        if (!displayEnabled()) {
            if (!this.subscriptions.isEmpty()) {
                sendSubscriptions(false, this.dimensionId, this.subscriptions);
                this.subscriptions.clear();
                this.store.clear();
            }
            return;
        }
        if (minecraft.getConnection() == null) {
            return;
        }
        Set<Long> mapped = mappedChunksNearby(minecraft);
        String token = this.worldToken.isBlank() ? "unknown" : this.worldToken;
        Set<ChunkCacheKey> wanted = new LinkedHashSet<>();
        for (long packed : mapped) {
            wanted.add(new ChunkCacheKey(token, this.dimensionId,
                    PackedChunkPos.unpackX(packed), PackedChunkPos.unpackZ(packed)));
        }

        List<ChunkCacheKey> added = new ArrayList<>();
        for (ChunkCacheKey key : wanted) {
            if (!this.subscriptions.contains(key)) {
                added.add(key);
            }
        }
        List<ChunkCacheKey> removed = new ArrayList<>();
        for (ChunkCacheKey key : this.subscriptions) {
            if (!wanted.contains(key)) {
                removed.add(key);
            }
        }

        boolean resync = this.ticks - this.lastResyncTick >= RESYNC_INTERVAL_TICKS;
        if (resync) {
            this.lastResyncTick = this.ticks;
        }
        if (resync && !wanted.isEmpty()) {
            sendSubscriptions(true, this.dimensionId, new ArrayList<>(wanted));
        } else if (!added.isEmpty()) {
            sendSubscriptions(true, this.dimensionId, added);
        }
        if (!removed.isEmpty()) {
            sendSubscriptions(false, this.dimensionId, removed);
            for (ChunkCacheKey key : removed) {
                this.store.forget(key);
            }
        }
        this.subscriptions.clear();
        this.subscriptions.addAll(wanted);
    }

    private Set<Long> mappedChunksNearby(Minecraft minecraft) {
        int centerX = minecraft.player.chunkPosition().x;
        int centerZ = minecraft.player.chunkPosition().z;
        int radius = ClientConfig.subscribeRadiusChunks();
        Set<Long> mapped = new HashSet<>();
        if (XaeroMods.worldMapPresent()) {
            mapped.addAll(WorldMapBridge.mappedChunks(minecraft.level.dimension(), centerX, centerZ, radius));
        }
        if (XaeroMods.minimapPresent()) {
            mapped.addAll(MinimapBridge.mappedChunks(minecraft.level.dimension(), centerX, centerZ, radius));
        }
        return mapped;
    }

    private boolean isMapped(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (XaeroMods.worldMapPresent() && WorldMapBridge.isChunkMapped(dimension, chunkX, chunkZ)) {
            return true;
        }
        return XaeroMods.minimapPresent() && MinimapBridge.isChunkMapped(dimension, chunkX, chunkZ);
    }

    private void sendSubscriptions(boolean subscribe, String dimension, Set<ChunkCacheKey> keys) {
        sendSubscriptions(subscribe, dimension, new ArrayList<>(keys));
    }

    private void sendSubscriptions(boolean subscribe, String dimension, List<ChunkCacheKey> keys) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null || keys.isEmpty()) {
            return;
        }
        int batchSize = ClientConfig.subscribeBatchSize();
        for (int start = 0; start < keys.size(); start += batchSize) {
            int end = Math.min(keys.size(), start + batchSize);
            long[] packed = new long[end - start];
            for (int i = start; i < end; i++) {
                ChunkCacheKey key = keys.get(i);
                packed[i - start] = PackedChunkPos.pack(key.chunkX(), key.chunkZ());
            }
            PacketDistributor.sendToServer(
                    new SubscriptionPayload(ResourceLocation.parse(dimension), packed, subscribe));
        }
    }

    // --- map invalidation ---------------------------------------------------

    private void refreshDirtyRegions() {
        Set<RegionKey> dirty = this.store.drainDirtyRegions();
        if (dirty.isEmpty()) {
            return;
        }
        for (RegionKey region : dirty) {
            WorldMapBridge.refreshRegion(region);
            MinimapBridge.refreshRegion(region);
        }
    }

    private static boolean displayEnabled() {
        return ClientConfig.minimapOverlayEnabled()
                || ClientConfig.worldMapOverlayEnabled()
                || ClientConfig.worldMapHoverEnabled();
    }

    private static String worldToken(Minecraft minecraft) {
        if (minecraft.getSingleplayerServer() != null) {
            String levelName = minecraft.getSingleplayerServer().getWorldData().getLevelName();
            return "sp:" + (levelName == null ? "" : levelName);
        }
        ServerData server = minecraft.getCurrentServer();
        if (server != null) {
            return "mp:" + server.ip;
        }
        return "unknown";
    }
}
