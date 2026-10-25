package org.bitwisemadness.tensuraxaerointegration.client;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bitwisemadness.tensuraxaerointegration.cache.ChunkCacheKey;
import org.bitwisemadness.tensuraxaerointegration.cache.ConcentrationCache;
import org.bitwisemadness.tensuraxaerointegration.cache.ConcentrationEntry;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKey;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationClassification;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationThresholds;

/**
 * The client's only view of concentration data, and the single place that
 * enforces "mapped chunks only".
 *
 * <p>Ingress takes an explicit {@code mapped} flag. An unmapped chunk is dropped
 * without being stored, so no renderer, tooltip or later network decision can
 * ever observe it - the invariant is structural rather than a check at each call
 * site. The client also passes {@code false} for chunks its Xaero maps have not
 * written, even if the server offered a value.</p>
 *
 * <p>Every stored change bumps the {@link RegionKey} version of the chunk's
 * 32x32-chunk region and marks that region dirty. Map invalidation therefore
 * only happens when a value really changed, and the world-map highlighter can
 * answer {@code calculateRegionHash} in constant time from the version.</p>
 *
 * <p>Time is the client tick counter, supplied by {@link #tick()}. An entry that
 * has not been refreshed within the configured staleness window stops being
 * displayed and is reported unavailable, never shown as if it were current.</p>
 */
public final class ClientConcentrationStore {

    /** Fixed region geometry, matching Xaero's 32x32-chunk map regions. */
    public static final int REGION_SHIFT = 5;

    private final ConcentrationCache cache;
    private final Map<RegionKey, Integer> regionVersions = new ConcurrentHashMap<>();
    private final Set<RegionKey> dirtyRegions = new LinkedHashSet<>();
    private final Set<ChunkCacheKey> expired = ConcurrentHashMap.newKeySet();

    private long tick;
    private double baseline = Double.NaN;

    public ClientConcentrationStore(int maxEntries) {
        this.cache = new ConcentrationCache(maxEntries);
    }

    /** Advances the client clock used for staleness. */
    public void tick() {
        this.tick++;
    }

    public long currentTick() {
        return this.tick;
    }

    /**
     * Sets the server-supplied authoritative baseline.
     *
     * @param baseline a finite positive value, or NaN when the server has not
     *                 supplied one
     */
    public void setBaseline(double baseline) {
        if (!Double.isNaN(baseline) && (!Double.isFinite(baseline) || baseline <= 0.0)) {
            throw new IllegalArgumentException("baseline must be positive or NaN, was " + baseline);
        }
        this.baseline = baseline;
    }

    /** The server-supplied baseline, or NaN when unknown. */
    public double baseline() {
        return this.baseline;
    }

    /**
     * Offers a concentration for a chunk.
     *
     * @param mapped whether every enabled map considers the chunk mapped
     * @return true when the stored value changed (and the map needs a repaint)
     */
    public boolean offer(ChunkCacheKey key, boolean mapped, double value) {
        if (!mapped) {
            // Mapped-only rule: an unmapped chunk is never cached, even when the
            // server offered it. Structurally invisible to every consumer.
            return false;
        }
        this.expired.remove(key);
        boolean changed = this.cache.put(key, value, this.tick);
        if (changed) {
            bumpRegion(key);
        }
        return changed;
    }

    /**
     * Marks a chunk's value as no longer readable.
     *
     * @return true when the visible state changed
     */
    public boolean markUnavailable(ChunkCacheKey key, boolean mapped) {
        if (!mapped) {
            return false;
        }
        this.expired.remove(key);
        boolean changed = this.cache.markUnavailable(key, this.tick);
        if (changed) {
            bumpRegion(key);
        }
        return changed;
    }

    /** Drops a chunk entirely, e.g. when it stops being subscribed. */
    public void forget(ChunkCacheKey key) {
        boolean had = this.cache.remove(key);
        this.expired.remove(key);
        if (had) {
            bumpRegion(key);
        }
    }

    /**
     * The value to display for a chunk: available, present and no older than
     * {@code staleAfterTicks}. Empty for missing, unavailable or stale values.
     */
    public OptionalDouble displayValue(ChunkCacheKey key, long staleAfterTicks) {
        if (this.expired.contains(key)) {
            return OptionalDouble.empty();
        }
        return this.cache.freshValue(key, this.tick, staleAfterTicks);
    }

    /** The classification for a chunk, when it has a displayable value. */
    public Optional<ConcentrationClassification> classification(ChunkCacheKey key, long staleAfterTicks,
            ConcentrationThresholds thresholds) {
        OptionalDouble value = displayValue(key, staleAfterTicks);
        if (value.isEmpty() || !Double.isFinite(this.baseline) || this.baseline <= 0.0) {
            return Optional.empty();
        }
        return thresholds.classifyIfValid(value.getAsDouble(), this.baseline);
    }

    public Optional<ConcentrationEntry> rawEntry(ChunkCacheKey key) {
        return this.cache.entry(key);
    }

    /**
     * Marks entries that have aged out as expired and dirties their regions once.
     *
     * <p>Without this a stale colour would stay painted for ever, because a region
     * is only repainted when its data changes.</p>
     */
    public void expire(long staleAfterTicks) {
        for (ConcentrationCache.Snapshot snapshot : this.cache.snapshot()) {
            ChunkCacheKey key = snapshot.key();
            ConcentrationEntry entry = snapshot.entry();
            if (this.expired.contains(key)) {
                continue;
            }
            if (entry.available() && !entry.fresh(this.tick, staleAfterTicks)) {
                this.expired.add(key);
                bumpRegion(key);
            }
        }
    }

    /**
     * Marks every region this session has seen as dirty, so a config change that
     * alters the overlay is reflected without waiting for the next value change.
     */
    public void markAllRegionsDirty() {
        this.dirtyRegions.addAll(this.regionVersions.keySet());
    }

    /**
     * Drops entries older than {@code purgeAfterTicks} entirely, bounding memory
     * in a long session. Entries are already hidden by {@link #expire} first, so
     * no repaint is needed here.
     */
    public void purge(long purgeAfterTicks) {
        for (ConcentrationCache.Snapshot snapshot : this.cache.snapshot()) {
            if (this.tick - snapshot.entry().tick() > purgeAfterTicks) {
                this.cache.remove(snapshot.key());
                this.expired.remove(snapshot.key());
            }
        }
    }

    /** The current version of a region; 0 means this client never had data there. */
    public int regionVersion(RegionKey region) {
        return this.regionVersions.getOrDefault(region, 0);
    }

    /**
     * Takes the regions that changed since the last call, so the refresh
     * coordinator can invalidate each of them exactly once.
     */
    public Set<RegionKey> drainDirtyRegions() {
        if (this.dirtyRegions.isEmpty()) {
            return Set.of();
        }
        Set<RegionKey> drained = Set.copyOf(this.dirtyRegions);
        this.dirtyRegions.clear();
        return drained;
    }

    /** Regions that a highlighter should consider visited (used for regionHasHighlights). */
    public boolean hasRegionData(RegionKey region) {
        return regionVersion(region) > 0;
    }

    /**
     * Clears every observation and marks the previously known regions dirty so
     * the maps repaint without them.
     *
     * <p>Called on world change, dimension change, disconnect and reload.</p>
     */
    public void clear() {
        this.dirtyRegions.addAll(this.regionVersions.keySet());
        this.regionVersions.clear();
        this.cache.clear();
        this.expired.clear();
        // The baseline belongs to the world, not to the dimension, so it survives
        // a dimension change; clearBaseline() is for world changes and disconnects.
    }

    /** Forgets the server-supplied baseline, e.g. on disconnect or world change. */
    public void clearBaseline() {
        this.baseline = Double.NaN;
    }

    public int size() {
        return this.cache.size();
    }

    public int capacity() {
        return this.cache.capacity();
    }

    private void bumpRegion(ChunkCacheKey key) {
        RegionKey region = key.region();
        this.regionVersions.merge(region, 1, Integer::sum);
        this.dirtyRegions.add(region);
    }
}
