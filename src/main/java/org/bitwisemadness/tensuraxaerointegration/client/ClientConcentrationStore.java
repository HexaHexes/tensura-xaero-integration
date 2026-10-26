package org.bitwisemadness.tensuraxaerointegration.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.bitwisemadness.tensuraxaerointegration.cache.ChunkCacheKey;
import org.bitwisemadness.tensuraxaerointegration.cache.ConcentrationCache;
import org.bitwisemadness.tensuraxaerointegration.cache.ConcentrationEntry;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKey;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationClassification;
import org.bitwisemadness.tensuraxaerointegration.concentration.OverlayPalette;

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
 * <p>The band a value is painted with is whatever the server classified it as;
 * this class stores it and never derives one. Only the display palette and the
 * freshness window are client-side, and both are seeded from the server (the
 * datapack palette in {@link #setServerPalette}, the refresh cadence in
 * {@link #setServerRefreshInterval}).</p>
 *
 * <p>Time is wall-clock milliseconds from an injectable clock, minus the time the
 * integrated server spent paused ({@link #addPausedMillis}). A paused server
 * confirms nothing, so paused time must not count towards staleness: otherwise
 * every value in the world expires while the pause menu is open and comes back
 * when it closes, which looks exactly like a value that was never in doubt.</p>
 */
public final class ClientConcentrationStore {

    /** Fixed region geometry, matching Xaero's 32x32-chunk map regions. */
    public static final int REGION_SHIFT = 5;

    /**
     * How long the server says one confirmation takes, assumed until it says
     * otherwise: the shipped 200-tick interval at a healthy 50 ms per tick.
     */
    public static final long DEFAULT_SERVER_REFRESH_MILLIS = 10_000L;

    /**
     * Floor for the derived freshness window, so a server that confirms very
     * often cannot make values flicker between live and last known.
     */
    public static final long MIN_STALE_MILLIS = 3_000L;

    /**
     * How many confirmations a value stays current for.
     *
     * <p>Three, not two: one beat of slack is not a margin on a server that
     * stutters. A single "can't keep up" stall of a few seconds would otherwise
     * push the confirmation past the window and dim a value that was never in
     * doubt.</p>
     */
    private static final long STALE_INTERVALS = 3L;

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

    /**
     * One remembered value, used to build the on-disk snapshot and to replay it.
     *
     * @param band the server's band code when the value was observed, or -1
     */
    public record StoredValue(ChunkCacheKey key, double value, int band) {

        public StoredValue {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("a stored value must be finite and non-negative");
            }
            if (band < ConcentrationEntry.UNCLASSIFIED) {
                throw new IllegalArgumentException("a band code cannot be below -1, was " + band);
            }
        }
    }

    private final ConcentrationCache cache;
    private final Map<RegionKey, Integer> regionVersions = new ConcurrentHashMap<>();
    private final Set<RegionKey> dirtyRegions = new LinkedHashSet<>();

    private final LongSupplier clock;

    /** Wall-clock time this session during which the server could not confirm anything. */
    private long pausedMillis;

    private int serverRefreshIntervalTicks = 200;
    private long serverRefreshMillis = DEFAULT_SERVER_REFRESH_MILLIS;
    private OverlayPalette serverPalette = OverlayPalette.defaults();

    public ClientConcentrationStore(int maxEntries) {
        this(maxEntries, System::currentTimeMillis);
    }

    /**
     * @param clock wall-clock source in milliseconds; injectable so the JUnit suite
     *              can describe a stalled server without sleeping
     */
    ClientConcentrationStore(int maxEntries, LongSupplier clock) {
        this.cache = new ConcentrationCache(maxEntries);
        this.clock = clock;
    }

    /**
     * The clock this store measures freshness against: wall-clock milliseconds
     * with paused time excluded.
     *
     * <p>Stamps are taken with the same offset, so excluding paused time never
     * makes an age negative: during a pause the offset grows by exactly as much
     * as the wall clock does, which freezes every age where it was.</p>
     */
    public long now() {
        return this.clock.getAsLong() - this.pausedMillis;
    }

    /**
     * Records wall-clock time in which the server was paused and could not
     * confirm any chunk.
     *
     * <p>A singleplayer pause freezes the integrated server but not the client's
     * tick loop, so the client would otherwise age every value during a pause and
     * dim the whole map on unpause.</p>
     */
    public void addPausedMillis(long millis) {
        if (millis > 0L) {
            this.pausedMillis += millis;
        }
    }

    /** How much wall-clock time this session has spent paused. */
    public long pausedMillis() {
        return this.pausedMillis;
    }

    /**
     * Sets the palette the datapack supplies, used for any band the client's
     * config does not override.
     *
     * @param palette the server's default palette, never null
     */
    public void setServerPalette(OverlayPalette palette) {
        if (palette == null) {
            throw new IllegalArgumentException("the server palette cannot be null");
        }
        this.serverPalette = palette;
    }

    /** The palette the server last supplied; the built-in one until it does. */
    public OverlayPalette serverPalette() {
        return this.serverPalette;
    }

    /**
     * Records how long the server needs, in real time, to confirm an unchanged
     * chunk again.
     *
     * <p>This is what the freshness window is derived from, so there is no second
     * client-side number that could disagree with the server: values are trusted
     * for exactly as long as the server's own cadence justifies. It is a duration
     * rather than a tick count precisely because the server's ticks are not the
     * client's ticks.</p>
     *
     * @param ticks        the server's confirmation interval in its own ticks, positive
     * @param promiseMillis how long that interval really takes, positive
     */
    public void setServerRefreshInterval(int ticks, long promiseMillis) {
        if (ticks <= 0) {
            throw new IllegalArgumentException("server refresh interval must be positive, was " + ticks);
        }
        if (promiseMillis <= 0) {
            throw new IllegalArgumentException("the refresh promise must be positive, was " + promiseMillis);
        }
        this.serverRefreshIntervalTicks = ticks;
        this.serverRefreshMillis = promiseMillis;
    }

    /** The confirmation cadence currently in force, in the server's ticks. */
    public int serverRefreshIntervalTicks() {
        return this.serverRefreshIntervalTicks;
    }

    /** How long the server's next confirmation is expected to take, in ms. */
    public long serverRefreshMillis() {
        return this.serverRefreshMillis;
    }

    /**
     * How long a value counts as current: three server confirmations, floored at
     * {@link #MIN_STALE_MILLIS}.
     */
    public long staleAfterMillis() {
        return Math.max(MIN_STALE_MILLIS, STALE_INTERVALS * this.serverRefreshMillis);
    }

    /**
     * Offers a current concentration for a chunk.
     *
     * @param mapped whether every enabled map considers the chunk mapped
     * @return true when the stored value changed (and the map needs a repaint)
     */
    public boolean offer(ChunkCacheKey key, boolean mapped, double value, int band) {
        if (!mapped) {
            // Mapped-only rule: an unmapped chunk is never cached, even when the
            // server offered it. Structurally invisible to every consumer.
            return false;
        }
        boolean changed = this.cache.put(key, value, band, now());
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
        boolean changed = this.cache.markUnavailable(key, now());
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
    public Optional<Observation> observe(ChunkCacheKey key, long windowMillis) {
        Optional<ConcentrationEntry> entry = this.cache.entry(key);
        if (entry.isEmpty() || !entry.get().available()) {
            return Optional.empty();
        }
        ConcentrationEntry value = entry.get();
        boolean live = value.fresh(now(), windowMillis);
        // The band is the server's, never recomputed here: it owns the tier table.
        Optional<ConcentrationClassification> classification =
                ConcentrationClassification.fromCode(value.band());
        return Optional.of(new Observation(value.value(), classification, live));
    }

    /**
     * A paint state that means "nothing to draw for this chunk".
     *
     * @see #paintState(ChunkCacheKey, long)
     */
    public static final int NO_PAINT = -1;

    /**
     * The band and liveness a map needs to colour a chunk, without building an
     * {@link Observation}.
     *
     * <p>This is the read the two Xaero highlighters make for every visible chunk
     * each time they rebuild a map tile, and it does not need the number: the
     * colour comes from the band and the opacity from the liveness. Going through
     * {@link #observe} cost an {@code Optional} for the entry, a record and an
     * {@code Optional} for the band per chunk. The two small pieces are packed
     * into one {@code int} so the whole read is one map lookup and one lock, and
     * the answer is decoded with {@link #bandOf} / {@link #liveOf}.</p>
     *
     * @return {@link #NO_PAINT}, or {@code (bandCode + 1) << 1 | (live ? 1 : 0)};
     *         the offset keeps a chunk with no band ({@code -1}) distinct from
     *         {@link #NO_PAINT}
     */
    public int paintState(ChunkCacheKey key, long windowMillis) {
        ConcentrationEntry entry = this.cache.entryOrNull(key);
        if (entry == null || !entry.available()) {
            return NO_PAINT;
        }
        // A band code this client does not know is drawn uncoloured, as in observe().
        int bandCode = ConcentrationClassification.byCode(entry.band()) == null ? -1 : entry.band();
        return (bandCode + 1) << 1 | (entry.fresh(now(), windowMillis) ? 1 : 0);
    }

    /** The band in a paint state, or {@code null} when the chunk has none. */
    public static ConcentrationClassification bandOf(int paintState) {
        return ConcentrationClassification.byCode((paintState >> 1) - 1);
    }

    /** Whether a paint state is live rather than last known. */
    public static boolean liveOf(int paintState) {
        return (paintState & 1) != 0;
    }

    /** Every available value held, live or last-known; the persistence snapshot. */
    public List<StoredValue> storedValues() {
        List<StoredValue> values = new ArrayList<>();
        for (ConcentrationCache.Snapshot snapshot : this.cache.snapshot()) {
            if (snapshot.entry().available()) {
                values.add(new StoredValue(snapshot.key(), snapshot.entry().value(), snapshot.entry().band()));
            }
        }
        return values;
    }

    /**
     * Replays remembered values, band included.
     *
     * <p>Restored values are always last-known, and a value the server has
     * already sent this session always wins. Rows come from a file this mod
     * wrote through the mapped-only ingress, so they are already mapped chunks.
     * A row written before bands were recorded carries {@code -1} and is shown
     * uncoloured rather than reclassified against today's tiers.</p>
     *
     * @return true when anything changed and the maps need a repaint
     */
    public boolean restoreHistory(List<StoredValue> values) {
        boolean changed = false;
        for (StoredValue value : values) {
            if (this.cache.putLastKnown(value.key(), value.value(), value.band())) {
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
     * What one expiry sweep demoted, so a caller can report why values dimmed.
     *
     * <p>The number matters as much as the oldest chunk: one value going quiet is
     * a missed confirmation, a sweep that demotes dozens is the server having been
     * unable to tick at all (a pause, a dimension change, a stall).</p>
     *
     * @param count           how many values became last known
     * @param oldest          the chunk that had gone longest without a confirmation
     * @param oldestAgeMillis that chunk's real gap, measured before it was demoted
     */
    public record Expiry(int count, ChunkCacheKey oldest, long oldestAgeMillis) {

        public Expiry {
            if (count <= 0) {
                throw new IllegalArgumentException("an expiry sweep demoted at least one chunk");
            }
            if (oldest == null) {
                throw new IllegalArgumentException("an expiry sweep has an oldest chunk");
            }
            if (oldestAgeMillis < 0L) {
                throw new IllegalArgumentException("an age cannot be negative, was " + oldestAgeMillis);
            }
        }
    }

    /**
     * Demotes live values that have not been refreshed within
     * {@code windowMillis} and dirties their regions once.
     *
     * <p>Demotion is a visible change (bright to dim), so it has to trigger a
     * repaint; the cache reports each entry only once, so a long-idle value does
     * not repaint its region every sweep.</p>
     *
     * <p>The gap is measured before demoting, because a demoted entry no longer
     * carries a stamp - reading it afterwards reports the epoch as the age.</p>
     *
     * @return what this sweep demoted, so a caller can report why; empty when
     *         nothing expired
     */
    public Optional<Expiry> expire(long windowMillis) {
        List<ConcentrationCache.Expired> expired = this.cache.expire(now(), windowMillis);
        if (expired.isEmpty()) {
            return Optional.empty();
        }
        ChunkCacheKey oldest = null;
        long oldestAge = -1L;
        for (ConcentrationCache.Expired value : expired) {
            bumpRegion(value.key());
            if (value.ageMillis() > oldestAge) {
                oldestAge = value.ageMillis();
                oldest = value.key();
            }
        }
        return Optional.of(new Expiry(expired.size(), oldest, oldestAge));
    }

    /**
     * Whether anything displayable is held for a chunk, current or remembered.
     *
     * <p>What a map's "is this chunk highlit" hook needs, and it allocates
     * nothing - unlike {@link #observe}, which both highlighters call once per
     * chunk each time they rebuild a tile.</p>
     */
    public boolean hasValue(ChunkCacheKey key) {
        return this.cache.hasAvailable(key);
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
        // The server-supplied palette and cadence belong to the world, not to the
        // dimension, so they survive a dimension change; clearServerInfo() is for
        // world changes and disconnects.
    }

    /**
     * Forgets what this server told us, e.g. on disconnect or world change.
     *
     * <p>The confirmation cadence and the datapack palette are that server's, and
     * a different world must not inherit them. The next join announces both again.</p>
     */
    public void clearServerInfo() {
        this.serverRefreshIntervalTicks = 200;
        this.serverRefreshMillis = DEFAULT_SERVER_REFRESH_MILLIS;
        this.serverPalette = OverlayPalette.defaults();
        // Paused time belongs to the session that is ending; stamps are cleared too.
        this.pausedMillis = 0L;
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
