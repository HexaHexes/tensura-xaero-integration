package org.bitwisemadness.tensuraxaerointegration.cache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * A bounded, timestamped map of chunk concentration observations.
 *
 * <p>Two rules make this safe to use as the client's display source:</p>
 * <ul>
 *   <li>Writes report whether the <em>value</em> changed, not merely that a
 *       write happened. A refresh that re-reads the same number updates the
 *       timestamp but returns {@code false}, so callers do not invalidate a map
 *       region for nothing.</li>
 *   <li>Eviction is least-recently-used at a fixed cap, so a long play session
 *       cannot grow the cache without bound.</li>
 * </ul>
 *
 * <p>This class never decides <em>whether</em> a chunk may be cached; that
 * decision belongs to the caller (mapped-only ingress on the client, the
 * subscription check on the server).</p>
 */
public final class ConcentrationCache {

    /** A snapshot row, used for expiry sweeps. */
    public record Snapshot(ChunkCacheKey key, ConcentrationEntry entry) {
    }

    private final int maxEntries;
    private final LinkedHashMap<ChunkCacheKey, ConcentrationEntry> entries;

    public ConcentrationCache(int maxEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive, was " + maxEntries);
        }
        this.maxEntries = maxEntries;
        final int cap = maxEntries;
        this.entries = new LinkedHashMap<>(Math.min(maxEntries, 256), 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkCacheKey, ConcentrationEntry> eldest) {
                return size() > cap;
            }
        };
    }

    /**
     * Records an authoritative value.
     *
     * @return true when the stored value or its availability actually changed
     */
    public synchronized boolean put(ChunkCacheKey key, double value, long tick) {
        return write(key, ConcentrationEntry.of(value, tick));
    }

    /**
     * Records that the chunk currently has no readable value.
     *
     * @return true when the stored entry changed
     */
    public synchronized boolean markUnavailable(ChunkCacheKey key, long tick) {
        return write(key, ConcentrationEntry.unavailable(tick));
    }

    private boolean write(ChunkCacheKey key, ConcentrationEntry next) {
        ConcentrationEntry current = this.entries.get(key);
        this.entries.put(key, next);
        return current == null || !sameObservation(current, next);
    }

    private static boolean sameObservation(ConcentrationEntry a, ConcentrationEntry b) {
        return a.available() == b.available()
                && Double.compare(a.value(), b.value()) == 0;
    }

    public synchronized Optional<ConcentrationEntry> entry(ChunkCacheKey key) {
        return Optional.ofNullable(this.entries.get(key));
    }

    /** The stored value, empty when absent or unavailable. */
    public synchronized OptionalDouble value(ChunkCacheKey key) {
        ConcentrationEntry entry = this.entries.get(key);
        if (entry == null || !entry.available()) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(entry.value());
    }

    /** The stored value if it is available and no older than {@code staleAfterTicks}. */
    public synchronized OptionalDouble freshValue(ChunkCacheKey key, long now, long staleAfterTicks) {
        ConcentrationEntry entry = this.entries.get(key);
        if (entry == null || !entry.available() || !entry.fresh(now, staleAfterTicks)) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(entry.value());
    }

    public synchronized boolean remove(ChunkCacheKey key) {
        return this.entries.remove(key) != null;
    }

    /** Drops everything. Called on world change, disconnect and reload. */
    public synchronized void clear() {
        this.entries.clear();
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

    /** Every key currently held, newest-used last. */
    public synchronized List<ChunkCacheKey> keys() {
        return new ArrayList<>(this.entries.keySet());
    }
}
