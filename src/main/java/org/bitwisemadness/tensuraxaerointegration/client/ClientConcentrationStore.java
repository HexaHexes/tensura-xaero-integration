package org.bitwisemadness.tensuraxaerointegration.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>A value has two display states. <b>Live</b> means the server reported it
 * recently. <b>Last known</b> means it is a real observation that is no longer
 * being refreshed - the chunk left the subscription range, unloaded, or was
 * replayed from disk. A last-known value is never presented as current: the
 * overlay dims it and the hover line labels it. Values are never interpolated,
 * extrapolated or invented, and an unavailable chunk stays unavailable.</p>
 *
 * <p>Every stored change bumps the {@link RegionKey} version of the chunk's
 * 32x32-chunk region and marks that region dirty. Map invalidation therefore
 * only happens when a value or its state really changed, and the world-map
 * highlighter can answer {@code calculateRegionHash} in constant time from the
 * version.</p>
 *
 * <p>Time is the client tick counter, supplied by {@link #tick()}.</p>
 */
public final class ClientConcentrationStore {

    /** Fixed region geometry, matching Xaero's 32x32-chunk map regions. */
    public static final int REGION_SHIFT = 5;

    /**
     * Confirmation cadence assumed until the server reports its own.
     *
     * <p>Matches the shipped {@code refresh.refreshIntervalTicks}, so a server
     * that never announces its cadence (or a client that joined before the packet
     * arrived) still treats values the way that server does.</p>
     */
    public static final int DEFAULT_SERVER_REFRESH_INTERVAL_TICKS = 200;

    /**
     * Floor for the derived freshness window, so a server that confirms very
     * often cannot make values flicker between live and last known.
     */
    public static final long MIN_STALE_TICKS = 100;

    /**
     * How many confirmation intervals a value stays current for. Two tolerates one
     * missed heartbeat before the value is demoted.
     */
    private static final long STALE_INTERVALS = 2L;

    /** A value as the renderers and hover line should see it. */
    public record Observation(double value, Optional<ConcentrationClassification> classification, boolean live) {

        public Observation {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("an observation needs a finite value");
            }
        }

        /** Whether this observation should be painted at all. */
        public boolean elevated() {
            return this.classification.isPresent()
                    && this.classification.get() != ConcentrationClassification.NORMAL;
        }
    }

    /** One remembered value, used to build the on-disk snapshot and to replay it. */
    public record StoredValue(ChunkCacheKey key, double value) {

        public StoredValue {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("a stored value must be finite and non-negative");
            }
        }
    }

    private final ConcentrationCache cache;
    private final Map<RegionKey, Integer> regionVersions = new ConcurrentHashMap<>();
    private final Set<RegionKey> dirtyRegions = new LinkedHashSet<>();

    private long tick;
    private double baseline = Double.NaN;
    private double fallbackBaseline = Double.NaN;
    private int serverRefreshIntervalTicks = DEFAULT_SERVER_REFRESH_INTERVAL_TICKS;

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
     * @param baseline a finite positive value, or NaN when unknown
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
     * Sets the baseline to classify against while the server has not supplied
     * one, so a server that cannot report its configured value does not silently
     * remove every band (and with them the whole overlay).
     *
     * @param fallback a finite positive value, or NaN to disable the fallback
     */
    public void setFallbackBaseline(double fallback) {
        if (!Double.isNaN(fallback) && (!Double.isFinite(fallback) || fallback <= 0.0)) {
            throw new IllegalArgumentException("fallback baseline must be positive or NaN, was " + fallback);
        }
        this.fallbackBaseline = fallback;
    }

    /** The baseline actually used for classification: the server's, else the fallback. */
    public double effectiveBaseline() {
        if (Double.isFinite(this.baseline) && this.baseline > 0.0) {
            return this.baseline;
        }
        return this.fallbackBaseline;
    }

    /**
     * Records how often the server re-confirms an unchanged chunk.
     *
     * <p>This is what the freshness window is derived from, so there is no second
     * client-side number that could disagree with the server: values are trusted
     * for exactly as long as the server's own cadence justifies.</p>
     *
     * @param ticks the server's confirmation interval, positive
     */
    public void setServerRefreshInterval(int ticks) {
        if (ticks <= 0) {
            throw new IllegalArgumentException("server refresh interval must be positive, was " + ticks);
        }
        this.serverRefreshIntervalTicks = ticks;
    }

    /** The confirmation cadence currently in force, in client ticks. */
    public int serverRefreshIntervalTicks() {
        return this.serverRefreshIntervalTicks;
    }

    /**
     * How long a value counts as current, derived from the server's confirmation
     * cadence: {@code 2 * refreshIntervalTicks}, floored at {@link #MIN_STALE_TICKS}.
     */
    public long staleAfterTicks() {
        return Math.max(MIN_STALE_TICKS, STALE_INTERVALS * this.serverRefreshIntervalTicks);
    }

    /**
     * Offers a current concentration for a chunk.
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
        boolean changed = this.cache.put(key, value, this.tick);
        if (changed) {
            bumpRegion(key);
        }
        return changed;
    }

    /**
     * Records that the server cannot report this chunk right now.
     *
     * <p>A value this client already holds becomes <em>last known</em> instead of
     * being thrown away, which is what keeps an exploded area coloured after the
     * chunk unloads. With nothing held, the chunk is recorded as unavailable.</p>
     *
     * @return true when the visible state changed
     */
    public boolean markUnavailable(ChunkCacheKey key, boolean mapped) {
        if (!mapped) {
            return false;
        }
        if (this.cache.demote(key)) {
            bumpRegion(key);
            return true;
        }
        boolean changed = this.cache.markUnavailable(key, this.tick);
        if (changed) {
            bumpRegion(key);
        }
        return changed;
    }

    /**
     * Keeps a chunk's value but stops presenting it as current, e.g. when it
     * leaves the subscription radius.
     *
     * @return true when the visible state changed
     */
    public boolean demote(ChunkCacheKey key) {
        boolean changed = this.cache.demote(key);
        if (changed) {
            bumpRegion(key);
        }
        return changed;
    }

    /** Drops a chunk entirely, e.g. when it stops being mapped. */
    public void forget(ChunkCacheKey key) {
        if (this.cache.remove(key)) {
            bumpRegion(key);
        }
    }

    /**
     * The observation to display for a chunk: live when the server refreshed it
     * recently, otherwise the last known value. Empty when nothing is held.
     */
    public Optional<Observation> observe(ChunkCacheKey key, long staleAfterTicks,
            ConcentrationThresholds thresholds) {
        Optional<ConcentrationEntry> entry = this.cache.entry(key);
        if (entry.isEmpty() || !entry.get().available()) {
            return Optional.empty();
        }
        ConcentrationEntry value = entry.get();
        boolean live = value.fresh(this.tick, staleAfterTicks);
        Optional<ConcentrationClassification> classification = Optional.empty();
        double baseline = effectiveBaseline();
        if (Double.isFinite(baseline) && baseline > 0.0) {
            classification = thresholds.classifyIfValid(value.value(), baseline);
        }
        return Optional.of(new Observation(value.value(), classification, live));
    }

    /** Every available value held, live or last-known; the persistence snapshot. */
    public List<StoredValue> storedValues() {
        List<StoredValue> values = new ArrayList<>();
        for (ConcentrationCache.Snapshot snapshot : this.cache.snapshot()) {
            if (snapshot.entry().available()) {
                values.add(new StoredValue(snapshot.key(), snapshot.entry().value()));
            }
        }
        return values;
    }

    /**
     * Replays remembered values and their baseline.
     *
     * <p>Restored values are always last-known, and a value the server has
     * already sent this session always wins. Rows come from a file this mod
     * wrote through the mapped-only ingress, so they are already mapped chunks.</p>
     *
     * @return true when anything changed and the maps need a repaint
     */
    public boolean restoreHistory(List<StoredValue> values, double baseline) {
        if (Double.isFinite(baseline) && baseline > 0.0 && Double.isNaN(this.baseline)) {
            setBaseline(baseline);
        }
        boolean changed = false;
        for (StoredValue value : values) {
            if (this.cache.putLastKnown(value.key(), value.value())) {
                bumpRegion(value.key());
                changed = true;
            }
        }
        return changed;
    }

    public Optional<ConcentrationEntry> rawEntry(ChunkCacheKey key) {
        return this.cache.entry(key);
    }

    /**
     * Demotes live values that have not been refreshed within
     * {@code staleAfterTicks} and dirties their regions once.
     *
     * <p>Demotion is a visible change (bright to dim), so it has to trigger a
     * repaint; the cache reports each entry only once, so a long-idle value does
     * not repaint its region every sweep.</p>
     */
    public void expire(long staleAfterTicks) {
        for (ConcentrationCache.Snapshot snapshot : this.cache.snapshot()) {
            ConcentrationEntry entry = snapshot.entry();
            if (entry.available() && !entry.historical() && !entry.fresh(this.tick, staleAfterTicks)) {
                if (this.cache.demote(snapshot.key())) {
                    bumpRegion(snapshot.key());
                }
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

    /** Whether a highlighter should consider this region as having data. */
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
        // The baseline belongs to the world, not to the dimension, so it survives
        // a dimension change; clearBaseline() is for world changes and disconnects.
    }

    /**
     * Forgets the server-supplied baseline, e.g. on disconnect or world change.
     *
     * <p>The confirmation cadence is reset with it: it is that server's policy,
     * and a different world must not inherit it. The next join announces it again.</p>
     */
    public void clearBaseline() {
        this.baseline = Double.NaN;
        this.serverRefreshIntervalTicks = DEFAULT_SERVER_REFRESH_INTERVAL_TICKS;
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
