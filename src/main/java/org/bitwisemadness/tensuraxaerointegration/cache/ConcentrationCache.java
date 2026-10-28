package org.bitwisemadness.tensuraxaerointegration.cache;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.bitwisemadness.tensuraxaerointegration.net.PackedChunkPos;

/**
 * A bounded, timestamped map of chunk concentration observations.
 *
 * <p>Two rules make this safe to use as the client's display source:</p>
 * <ul>
 *   <li>Writes report whether the <em>value</em> changed, not merely that a
 *       write happened. A refresh that re-reads the same number updates the
 *       timestamp but returns {@code false}, so callers do not invalidate a map
 *       region for nothing.</li>
 *   <li>Eviction is least-recently-<em>written</em> at a fixed cap, so a long
 *       play session cannot grow the cache without bound.</li>
 * </ul>
 *
 * <p>Reads are read-only. Two Xaero highlighters read this class per visible chunk
 * whenever they (re)build a map tile, from the map's render/writer threads, so a
 * lookup that touched the eviction order - as an access-ordered map does - would
 * turn every repaint into a mutation under the same lock the tick thread writes
 * with, and would let what is on screen decide what survives. Recency therefore
 * follows writes: the chunk the server is still confirming is the one that keeps
 * its slot.</p>
 *
 * <p>Reads are also keyed by primitives. The authoritative map is written through
 * with a {@link ChunkCacheKey}, which is fine on the write path, but a map tile
 * rebuild asks about hundreds of chunks and must not allocate a key for each one,
 * so every write also lands in a long-keyed index of packed chunk positions, one
 * index per world-and-dimension scope. Reads through {@link #entryAt} touch only
 * that index and allocate nothing.</p>
 *
 * <p>This class never decides <em>whether</em> a chunk may be cached; that
 * decision belongs to the caller (mapped-only ingress on the client, the
 * subscription check on the server).</p>
 */
public final class ConcentrationCache {

    /** A snapshot row, used to build the on-disk history. */
    public record Snapshot(ChunkCacheKey key, ConcentrationEntry entry) {
    }

    /**
     * One value demoted by {@link #expire}, with the gap measured before the
     * demotion dropped its stamp.
     */
    public record Expired(ChunkCacheKey key, long ageMillis) {
    }

    /** The world and dimension a packed chunk position is only meaningful within. */
    private record Scope(String world, String dimension) {
    }

    private final int maxEntries;
    private final LinkedHashMap<ChunkCacheKey, ConcentrationEntry> entries;

    /**
     * The allocation-free read index: packed chunk positions, per scope.
     *
     * <p>Held in step with {@link #entries} by {@link #write}, {@link #remove},
     * {@link #clear} and the eviction hook, so every read answers exactly what the
     * authoritative map holds.</p>
     */
    private final Map<Scope, Long2ObjectOpenHashMap<ConcentrationEntry>> byPosition = new LinkedHashMap<>();

    /** Last scope read, so a repaint frame does not build a {@link Scope} per chunk. */
    private Scope activeScope;
    private Long2ObjectOpenHashMap<ConcentrationEntry> activePositions;

    /**
     * Keys whose entry is available and current: the only entries that can ever
     * expire, and normally a small fraction of a cache that also carries the
     * world's remembered history.
     */
    private final Set<ChunkCacheKey> live = new LinkedHashSet<>();

    public ConcentrationCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, was " + maxEntries);
        }
        this.maxEntries = maxEntries;
        final int cap = maxEntries;
        this.entries = new LinkedHashMap<>(Math.min(maxEntries, 256), 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkCacheKey, ConcentrationEntry> eldest) {
                if (size() <= cap) {
                    return false;
                }
                // An evicted value must leave the live set too, or the set would
                // keep growing with keys the map no longer holds.
                live.remove(eldest.getKey());
                // And the read index, or a lookup would answer with a value the
                // authoritative map has already dropped.
                positionsFor(eldest.getKey().world(), eldest.getKey().dimension())
                        .remove(PackedChunkPos.pack(eldest.getKey().chunkX(), eldest.getKey().chunkZ()));
                return true;
            }
        };
    }

    /**
     * Records an authoritative value.
     *
     * @return true when the stored value or its availability actually changed
     */
    public synchronized boolean put(ChunkCacheKey key, double value, int band, long stampMillis) {
        return write(key, ConcentrationEntry.of(value, band, stampMillis));
    }

    /**
     * Records that the chunk currently has no readable value.
     *
     * @return true when the stored entry changed
     */
    public synchronized boolean markUnavailable(ChunkCacheKey key, long stampMillis) {
        return write(key, ConcentrationEntry.unavailable(stampMillis));
    }

    /**
     * Demotes a live value to a remembered one, keeping the number but marking
     * it as no longer current. Used when a chunk leaves the subscription range,
     * unloads, or is replayed from disk.
     *
     * @return true when the entry changed, i.e. it was live and is now historical
     */
    public synchronized boolean demote(ChunkCacheKey key) {
        ConcentrationEntry current = this.entries.get(key);
        if (current == null || !current.available() || current.historical()) {
            return false;
        }
        return write(key, ConcentrationEntry.lastKnown(current.value(), current.band()));
    }

    /**
     * Inserts a remembered value from disk without touching current data.
     *
     * <p>A value the server has already sent this session always wins, so a
     * replayed file can never overwrite fresher information.</p>
     */
    public synchronized boolean putLastKnown(ChunkCacheKey key, double value, int band) {
        ConcentrationEntry current = this.entries.get(key);
        if (current != null && !current.historical()) {
            return false;
        }
        return write(key, ConcentrationEntry.lastKnown(value, band));
    }

    private boolean write(ChunkCacheKey key, ConcentrationEntry next) {
        ConcentrationEntry current = this.entries.get(key);
        // Reinsertion moves the key to the tail, which is what makes recency a
        // property of writes while leaving every read a plain lookup.
        this.entries.remove(key);
        this.entries.put(key, next);
        positionsFor(key.world(), key.dimension())
                .put(PackedChunkPos.pack(key.chunkX(), key.chunkZ()), next);
        if (next.available() && !next.historical()) {
            this.live.add(key);
        } else {
            this.live.remove(key);
        }
        return current == null || !sameObservation(current, next);
    }

    /** The packed-position index for a scope, reusing the last one asked for. */
    private Long2ObjectOpenHashMap<ConcentrationEntry> positionsFor(String world, String dimension) {
        Scope scope = this.activeScope;
        if (scope != null && scope.world().equals(world) && scope.dimension().equals(dimension)) {
            return this.activePositions;
        }
        Scope fresh = new Scope(world, dimension);
        Long2ObjectOpenHashMap<ConcentrationEntry> positions = this.byPosition.get(fresh);
        if (positions == null) {
            positions = new Long2ObjectOpenHashMap<>();
            this.byPosition.put(fresh, positions);
        }
        this.activeScope = fresh;
        this.activePositions = positions;
        return positions;
    }

    /**
     * Demotes every current value that has not been confirmed within the window.
     *
     * <p>Only current values are visited - a value that is already last known
     * cannot expire again - so the work is proportional to what the server is
     * confirming rather than to the whole map, most of which is remembered
     * history. No snapshot is taken: the sweep never allocates unless something
     * actually expires.</p>
     *
     * @param nowMillis    the caller's clock, in ms
     * @param windowMillis how old a confirmation may be and still count as current
     * @return one row per value demoted, with the gap measured before demotion
     */
    public synchronized List<Expired> expire(long nowMillis, long windowMillis) {
        if (this.live.isEmpty()) {
            return List.of();
        }
        List<Expired> stale = null;
        for (ChunkCacheKey key : this.live) {
            ConcentrationEntry entry = this.entries.get(key);
            if (entry == null || !entry.available() || entry.historical()
                    || entry.fresh(nowMillis, windowMillis)) {
                continue;
            }
            if (stale == null) {
                stale = new ArrayList<>();
            }
            // A clock that has gone backwards - a system time change, or more
            // commonly a pause credit that outran the wall clock - leaves the stamp
            // in the future. The value is stale and is demoted, but its gap cannot
            // be measured: report a zero gap rather than a negative age, which
            // callers would read as "no time has passed".
            stale.add(new Expired(key, Math.max(0L, nowMillis - entry.stampMillis())));
        }
        if (stale == null) {
            return List.of();
        }
        for (Expired expired : stale) {
            // Demotion happens after the scan: it removes from `live`, which is
            // what the loop above is iterating.
            demote(expired.key());
        }
        return stale;
    }

    private static boolean sameObservation(ConcentrationEntry a, ConcentrationEntry b) {
        // `historical` is part of the observation: promoting or demoting a value
        // changes how it is drawn, so it has to count as a change.
        return a.available() == b.available()
                && a.historical() == b.historical()
                && a.band() == b.band()
                && Double.compare(a.value(), b.value()) == 0;
    }

    public synchronized Optional<ConcentrationEntry> entry(ChunkCacheKey key) {
        return Optional.ofNullable(this.entries.get(key));
    }

    /**
     * The stored entry for a chunk, or {@code null} when there is none.
     *
     * <p>The allocation-free form of {@link #entry}: a map tile rebuild asks about
     * every visible chunk and needs only the band and the liveness, so it must not
     * pay for an {@code Optional} to find out that there is nothing there. Never
     * hand this entry out for mutation.</p>
     */
    public synchronized ConcentrationEntry entryOrNull(ChunkCacheKey key) {
        return this.entries.get(key);
    }

    /**
     * The stored entry for a chunk addressed by primitives, or {@code null}.
     *
     * <p>What the paint path reads: same answer as
     * {@link #entryOrNull(ChunkCacheKey)}, without building a key for every chunk
     * of every tile rebuild.</p>
     */
    public synchronized ConcentrationEntry entryAt(String world, String dimension, int chunkX, int chunkZ) {
        Long2ObjectMap<ConcentrationEntry> positions = positionsFor(world, dimension);
        return positions.get(PackedChunkPos.pack(chunkX, chunkZ));
    }

    /**
     * Whether anything displayable is held for the chunk, current or remembered.
     *
     * <p>The version a highlighter needs when it only has to answer "paint or
     * not": it allocates nothing, unlike {@link #entry}.</p>
     */
    public synchronized boolean hasAvailable(ChunkCacheKey key) {
        ConcentrationEntry entry = this.entries.get(key);
        return entry != null && entry.available();
    }

    /**
     * Whether a chunk addressed by primitives holds anything displayable.
     *
     * <p>The allocation-free form of {@link #hasAvailable(ChunkCacheKey)}.</p>
     */
    public synchronized boolean hasAvailableAt(String world, String dimension, int chunkX, int chunkZ) {
        ConcentrationEntry entry = entryAt(world, dimension, chunkX, chunkZ);
        return entry != null && entry.available();
    }

    /** The stored value, empty when absent or unavailable. */
    public synchronized OptionalDouble value(ChunkCacheKey key) {
        ConcentrationEntry entry = this.entries.get(key);
        if (entry == null || !entry.available()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(entry.value());
    }

    /** The stored value if it is available and no older than {@code windowMillis}. */
    public synchronized OptionalDouble freshValue(ChunkCacheKey key, long nowMillis, long windowMillis) {
        ConcentrationEntry entry = this.entries.get(key);
        if (entry == null || !entry.available() || !entry.fresh(nowMillis, windowMillis)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(entry.value());
    }

    public synchronized boolean remove(ChunkCacheKey key) {
        this.live.remove(key);
        positionsFor(key.world(), key.dimension())
                .remove(PackedChunkPos.pack(key.chunkX(), key.chunkZ()));
        return this.entries.remove(key) != null;
    }

    /** Drops everything. Called on world change, disconnect and reload. */
    public synchronized void clear() {
        this.entries.clear();
        this.live.clear();
        this.byPosition.clear();
        this.activeScope = null;
        this.activePositions = null;
    }

    public synchronized int size() {
        return this.entries.size();
    }

    public int capacity() {
        return this.maxEntries;
    }

    /** A copy of the current rows, safe to iterate while writing. */
    public synchronized List<Snapshot> snapshot() {
        List<Snapshot> out = new ArrayList<>(this.entries.size());
        this.entries.forEach((key, entry) -> out.add(new Snapshot(key, entry)));
        return out;
    }

    /** Every key currently held, newest-written last. */
    public synchronized List<ChunkCacheKey> keys() {
        return new ArrayList<>(this.entries.keySet());
    }

    /** How many held values are current; the work an expiry sweep actually does. */
    public synchronized int liveCount() {
        return this.live.size();
    }
}
