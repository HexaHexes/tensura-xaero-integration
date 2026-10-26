package org.bitwisemadness.tensuraxaerointegration.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;
import org.bitwisemadness.tensuraxaerointegration.cache.ChunkCacheKey;
import org.bitwisemadness.tensuraxaerointegration.cache.ConcentrationFileFormat;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKey;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentrationStore.StoredValue;
import org.bitwisemadness.tensuraxaerointegration.config.ClientConfig;
import org.bitwisemadness.tensuraxaerointegration.net.ConcentrationPayload;
import org.bitwisemadness.tensuraxaerointegration.net.PackedChunkPos;
import org.bitwisemadness.tensuraxaerointegration.net.SubscriptionPayload;
import org.bitwisemadness.tensuraxaerointegration.util.ChunkRadius;

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

    /** How often the remembered values are written to disk. */
    private static final int SAVE_INTERVAL_TICKS = 600;

    /**
     * How long a chunk stays subscribed after the maps stop reporting it as mapped.
     *
     * <p>Xaero's mapped query walks the map's <em>currently loaded</em> layers and
     * regions, so a region eviction or a cave-layer change makes chunks that are
     * still mapped on disk vanish from the set for a while. Treating that as "no
     * longer mapped" unsubscribed them, demoted their values, and re-subscribed
     * them twenty ticks later - a visible dim and re-brighten on chunks that were
     * never in question. Xaero does not un-map a chunk, so the set remembers what
     * it saw for a minute before believing the disappearance.</p>
     */
    private static final int MAPPED_MEMORY_TICKS = 1200;

    /** A sweep that demotes this many at once is an event, not a missed beat. */
    private static final int MASS_EXPIRY_CHUNKS = 8;

    private int lastResyncTick;
    private int lastSaveTick;

    // One-shot diagnostics: "why did that value dim?" is otherwise guesswork.
    private boolean expiryReported;
    private boolean massExpiryReported;
    private boolean unavailableReported;
    private boolean removalReported;

    // Pause accounting: the wall clock runs while the pause menu is open, but the
    // integrated server does not, so that time must not age a value.
    private long lastTickMillis;
    private boolean pausedAtLastTick;

    /** Created on first use, so no config value is read during class initialisation. */
    private ClientConcentrationStore store;
    private final ConcentrationStorage storage = new ConcentrationStorage(FMLPaths.GAMEDIR.get());
    private final Set<ChunkCacheKey> subscriptions = new LinkedHashSet<>();

    /** Chunks the maps reported as mapped, and the tick they last did. */
    private final Map<ChunkCacheKey, Integer> mappedTicks = new HashMap<>();

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
        ClientConcentrationStore current = this.store;
        if (current == null) {
            current = new ClientConcentrationStore(Math.max(1, ClientConfig.persistedEntriesMax()));
            this.store = current;
        }
        return current;
    }

    /** The current world token, used to build cache keys. */
    public String worldToken() {
        return this.worldToken;
    }

    /** The current dimension id, used to build cache keys. */
    public String dimensionId() {
        return this.dimensionId;
    }

    /**
     * The last dimension asked about, with its id. Published as one immutable value
     * so a render thread can never pair one dimension's key with another's id.
     */
    private record DimensionMemo(ResourceKey<Level> dimension, String id) {
    }

    /** One entry is enough: a map asks for thousands of chunks of one dimension. */
    private volatile DimensionMemo dimensionMemo;

    /** Builds the cache key for a chunk in a dimension, scoped to this session's world. */
    public ChunkCacheKey key(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        String token = this.worldToken.isBlank() ? "unknown" : this.worldToken;
        return new ChunkCacheKey(token, dimensionIdOf(dimension), chunkX, chunkZ);
    }

    /**
     * The dimension id this mod keys chunks by, memoised.
     *
     * <p>{@code location().toString()} builds a new String on every call, and the
     * map highlighters ask for a chunk key per visible chunk per frame. A map
     * draws one dimension at a time, so a single remembered entry covers it.</p>
     */
    public String dimensionIdOf(ResourceKey<Level> dimension) {
        DimensionMemo memo = this.dimensionMemo;
        if (memo != null && (memo.dimension() == dimension || memo.dimension().equals(dimension))) {
            return memo.id();
        }
        String id = dimension.location().toString();
        this.dimensionMemo = new DimensionMemo(dimension, id);
        return id;
    }

    /** Drives staleness, subscription diffing and map invalidation. */
    public void onClientTick(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.player == null) {
            if (!this.subscriptions.isEmpty() || store().size() > 0) {
                resetSession();
            }
            return;
        }
        String token = worldToken(minecraft);
        String dimension = minecraft.level.dimension().location().toString();
        if (!token.equals(this.worldToken) || !dimension.equals(this.dimensionId)) {
            beginSession(token, dimension);
        }
        this.ticks++;
        accountForPause(minecraft);

        if (this.ticks % MAINTENANCE_INTERVAL_TICKS == 0) {
            // The window follows the server's own confirmation cadence, so it can
            // never be shorter than the server's heartbeat or longer than the
            // server's promise to keep a chunk current.
            store().expire(store().staleAfterMillis()).ifPresent(this::reportExpiry);
        }
        if (this.ticks - this.lastSaveTick >= SAVE_INTERVAL_TICKS) {
            this.lastSaveTick = this.ticks;
            saveToDisk(this.worldToken, false);
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
        byte[] bands = payload.bands();
        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, payload.dimension());
        String token = this.worldToken.isBlank() ? "unknown" : this.worldToken;
        for (int i = 0; i < chunks.length; i++) {
            int chunkX = PackedChunkPos.unpackX(chunks[i]);
            int chunkZ = PackedChunkPos.unpackZ(chunks[i]);
            ChunkCacheKey key = new ChunkCacheKey(token, dimension, chunkX, chunkZ);
            // Defence in depth: the store refuses unmapped chunks anyway, but the
            // mapped test is repeated here so a value can never be remembered for
            // a chunk that stopped being mapped between request and reply. A chunk
            // this client still subscribes is accepted regardless: the map's
            // region cache evicting a chunk does not un-map it, and dropping the
            // confirmation would age a value that is being refreshed on schedule.
            boolean mapped = this.subscriptions.contains(key) || isMapped(dimensionKey, chunkX, chunkZ);
            double value = values[i];
            if (Double.isNaN(value)) {
                reportUnavailable(key);
                store().markUnavailable(key, mapped);
            } else {
                // The band is the server's decision; the client stores it as-is.
                store().offer(key, mapped, value, bands[i]);
            }
        }
    }

    /** Full reset: used on disconnect and world change. */
    public void resetSession() {
        // Capture what was learned before the store is dropped. Queued, not
        // written here: nothing loads this token afterwards, and a disconnect must
        // not wait on the disk.
        saveToDisk(this.worldToken, false);
        this.subscriptions.clear();
        store().clear();
        store().clearServerInfo();
        this.worldToken = "";
        this.dimensionId = "";
        this.mappedTicks.clear();
        this.lastSubscribeTick = 0;
        this.subscribeRefreshDue = true;
        this.lastTickMillis = 0L;
        this.pausedAtLastTick = false;
        this.expiryReported = false;
        this.massExpiryReported = false;
        this.unavailableReported = false;
        this.removalReported = false;
        ClientPayloadHandler.resetBaselineLog();
    }

    // --- persistence -------------------------------------------------------

    /**
     * Writes this world's remembered values.
     *
     * <p>Each row also carries the band the server gave it, so a replayed map is
     * coloured the way it was when the values were observed rather than against
     * whatever tiers the datapack holds today.</p>
     *
     * @param visibleToTheNextLoad true when a load of the same world follows
     *                             immediately and the file must be current for it
     */
    private void saveToDisk(String token, boolean visibleToTheNextLoad) {
        if (!ClientConfig.persistenceEnabled() || token == null || token.isBlank()) {
            return;
        }
        List<StoredValue> stored = store().storedValues();
        if (stored.isEmpty()) {
            return;
        }
        List<ConcentrationFileFormat.Row> rows = new ArrayList<>(stored.size());
        for (StoredValue value : stored) {
            ChunkCacheKey key = value.key();
            rows.add(new ConcentrationFileFormat.Row(
                    key.dimension(), key.chunkX(), key.chunkZ(), value.value(), value.band()));
        }
        ConcentrationFileFormat.Contents contents = new ConcentrationFileFormat.Contents(rows);
        if (visibleToTheNextLoad) {
            // Bounded wait: the load that follows needs this file current, but a
            // slow disk must not freeze the client while it catches up.
            if (!this.storage.saveAndWait(token, contents, ConcentrationStorage.WRITE_TIMEOUT_MILLIS)) {
                TensuraXaeroIntegration.LOGGER.debug(
                        "Concentration history for {} was still being written when the next session loaded it",
                        token);
            }
        } else {
            this.storage.saveAsync(token, contents);
        }
    }

    /**
     * Replays a world's remembered values.
     *
     * <p>Rows are trusted to be mapped chunks because only mapped values ever
     * reach the store that wrote them, and Xaero does not un-map a chunk. They
     * are loaded as last-known, so nothing here can masquerade as a live value.</p>
     */
    private void loadFromDisk(String token) {
        if (!ClientConfig.persistenceEnabled() || token == null || token.isBlank()) {
            return;
        }
        this.storage.load(token, ClientConfig.persistedEntriesMax()).ifPresent(contents -> {
            List<StoredValue> values = new ArrayList<>(contents.rows().size());
            for (ConcentrationFileFormat.Row row : contents.rows()) {
                values.add(new StoredValue(
                        new ChunkCacheKey(token, row.dimension(), row.chunkX(), row.chunkZ()),
                        row.value(), row.band()));
            }
            store().restoreHistory(values);
        });
    }

    /**
     * Waits briefly for queued saves; called after the logout save so the last
     * snapshot usually lands. A write that misses the deadline finishes on the
     * writer thread instead of holding the client.
     */
    public void flushPersistence() {
        this.storage.flush();
    }

    // --- subscription driving ----------------------------------------------

    private void beginSession(String token, String dimension) {
        boolean newWorld = !token.equals(this.worldToken);
        if (!this.dimensionId.isEmpty() && !this.subscriptions.isEmpty() && !this.dimensionId.equals(dimension)) {
            // Release the old dimension's subscriptions instead of leaving the
            // server holding chunks the client no longer shows.
            sendSubscriptions(false, this.dimensionId, this.subscriptions);
        }
        // Persist the session being left before its values are dropped, so a
        // dimension change does not discard what this world already taught us.
        saveToDisk(this.worldToken, true);
        this.subscriptions.clear();
        store().clear();
        if (newWorld) {
            // The palette and cadence are per world; a dimension change keeps them.
            store().clearServerInfo();
        }
        // Replay this world's remembered values, bands included. Live answers
        // replace them within a tick or two for whatever the server has loaded.
        loadFromDisk(token);
        this.worldToken = token;
        this.dimensionId = dimension;
        this.mappedTicks.clear();
        this.lastSubscribeTick = 0;
        this.lastResyncTick = 0;
        this.subscribeRefreshDue = true;
        this.ticks = 0;
        this.expiryReported = false;
        this.massExpiryReported = false;
        this.unavailableReported = false;
        this.removalReported = false;
    }

    private void updateSubscriptions(Minecraft minecraft) {
        if (!displayEnabled()) {
            if (!this.subscriptions.isEmpty()) {
                sendSubscriptions(false, this.dimensionId, this.subscriptions);
                this.subscriptions.clear();
                store().clear();
            }
            return;
        }
        if (minecraft.getConnection() == null) {
            return;
        }
        int radius = ClientConfig.subscribeRadiusChunks();
        int centerX = minecraft.player.chunkPosition().x;
        int centerZ = minecraft.player.chunkPosition().z;
        Set<Long> mapped = mappedChunksNearby(minecraft);
        String token = this.worldToken.isBlank() ? "unknown" : this.worldToken;
        Set<ChunkCacheKey> wanted = new LinkedHashSet<>();
        for (long packed : mapped) {
            int chunkX = PackedChunkPos.unpackX(packed);
            int chunkZ = PackedChunkPos.unpackZ(packed);
            // A straight-line radius, not the square the maps enumerate: a corner
            // of a square of radius 10 is 14.1 chunks away, past anything the
            // server has loaded, so asking for it only earns a "no value".
            if (!ChunkRadius.within(chunkX, chunkZ, centerX, centerZ, radius)) {
                continue;
            }
            ChunkCacheKey key = new ChunkCacheKey(token, this.dimensionId, chunkX, chunkZ);
            wanted.add(key);
            this.mappedTicks.put(key, this.ticks);
        }
        // Keep what the maps reported a moment ago: Xaero's mapped query reads the
        // regions it currently holds, and an eviction there is not an un-mapping.
        Iterator<Map.Entry<ChunkCacheKey, Integer>> remembered = this.mappedTicks.entrySet().iterator();
        while (remembered.hasNext()) {
            Map.Entry<ChunkCacheKey, Integer> entry = remembered.next();
            ChunkCacheKey key = entry.getKey();
            boolean expired = this.ticks - entry.getValue() > MAPPED_MEMORY_TICKS;
            boolean elsewhere = !key.dimension().equals(this.dimensionId) || !key.world().equals(token);
            boolean tooFar = !ChunkRadius.within(key.chunkX(), key.chunkZ(), centerX, centerZ, radius);
            if (expired || elsewhere || tooFar) {
                remembered.remove();
            } else {
                wanted.add(key);
            }
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
                // Out of request range is not out of memory: the value becomes
                // last-known rather than disappearing.
                this.mappedTicks.remove(key);
                store().demote(key);
            }
            reportRemoval(removed.size());
        }
        this.subscriptions.clear();
        this.subscriptions.addAll(wanted);
    }

    /**
     * Reports what expired: the gap, the window and how many values went at once,
     * so a flicker can be attributed rather than guessed at.
     *
     * <p>One line for the first sweep of the session, plus one for the first sweep
     * that demotes a crowd - a server that stopped ticking looks nothing like a
     * single missed beat, and that difference is the whole diagnosis.</p>
     */
    private void reportExpiry(ClientConcentrationStore.Expiry expiry) {
        boolean mass = expiry.count() >= MASS_EXPIRY_CHUNKS;
        if (this.expiryReported && (!mass || this.massExpiryReported)) {
            return;
        }
        this.expiryReported = true;
        if (mass) {
            this.massExpiryReported = true;
        }
        ClientConcentrationStore store = store();
        ChunkCacheKey oldest = expiry.oldest();
        TensuraXaeroIntegration.LOGGER.info(
                "{} chunk(s) went last known this sweep; the oldest, chunk {},{}: no confirmation "
                        + "for {} ms (window {} ms; the server confirms every {} ticks, measured at "
                        + "{} ms; {} ms of this session was paused)",
                expiry.count(), oldest.chunkX(), oldest.chunkZ(), expiry.oldestAgeMillis(),
                store.staleAfterMillis(), store.serverRefreshIntervalTicks(),
                store.serverRefreshMillis(), store.pausedMillis());
    }

    /**
     * Moves time spent with the integrated server paused out of the freshness
     * clock: a paused server confirms nothing, so that time cannot age a value.
     *
     * <p>The interval is credited on the first paused tick and again on the tick
     * that resumes, so it is counted whether or not client ticks keep firing while
     * the pause menu is open. Over-crediting is bounded by one tick per pause.</p>
     */
    private void accountForPause(Minecraft minecraft) {
        long nowMillis = System.currentTimeMillis();
        boolean paused = minecraft.isPaused();
        if (this.lastTickMillis > 0L && (paused || this.pausedAtLastTick)) {
            store().addPausedMillis(nowMillis - this.lastTickMillis);
        }
        this.lastTickMillis = nowMillis;
        this.pausedAtLastTick = paused;
    }

    /** One-shot report of a chunk the server could not answer for. */
    private void reportUnavailable(ChunkCacheKey key) {
        if (this.unavailableReported) {
            return;
        }
        this.unavailableReported = true;
        TensuraXaeroIntegration.LOGGER.info(
                "Chunk {},{} was reported unreadable by the server; its value is now last known",
                key.chunkX(), key.chunkZ());
    }

    /** One-shot report of chunks that left the request range and were demoted. */
    private void reportRemoval(int count) {
        if (this.removalReported) {
            return;
        }
        this.removalReported = true;
        TensuraXaeroIntegration.LOGGER.info(
                "{} chunk(s) left the request range; their values are now last known", count);
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
        Set<RegionKey> dirty = store().drainDirtyRegions();
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
