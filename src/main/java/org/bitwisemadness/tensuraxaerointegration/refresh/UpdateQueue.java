package org.bitwisemadness.tensuraxaerointegration.refresh;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bitwisemadness.tensuraxaerointegration.cache.ChunkCacheKey;

/**
 * Per-player outgoing update state: which chunks have a newer value than what
 * was last sent, and when each chunk was last sent.
 *
 * <p>This is the deduplication and throttling half of the hybrid refresh. Tensura
 * reports a chunk tick, the coordinator diffs the value, and this queue keeps at
 * most one pending value per chunk (last write wins) and enforces a minimum
 * interval between two transmissions for the same chunk. A change that arrives
 * inside the interval is not lost, it simply waits for the next drain.</p>
 *
 * <p>{@link Double#NaN} is the "no readable value" payload. Two NaNs count as
 * equal, so an already-sent unavailability is not resent every tick.</p>
 *
 * <p>Deduplication and liveness pull in opposite directions: most chunks sit at
 * their ceiling and never change, so sending only changes would let every value
 * go stale on the client within a minute. {@link #offerRefresh} re-queues an
 * unchanged value after {@link #dueForRefresh} reports it is due, which is what
 * keeps a real observation counting as current without inventing a value.</p>
 *
 * <p>Not thread-safe: the coordinator owns one instance per player and only
 * touches it from the server thread.</p>
 */
public final class UpdateQueue {

    /**
     * One ready-to-send update.
     *
     * @param band the server's band code for the value, or {@code -1} when it
     *             could not be classified
     */
    public record Update(ChunkCacheKey key, double value, int band) {
    }

    private static final class Slot {
        private double pendingValue;
        private int pendingBand = -1;
        private boolean pending;
        private boolean everSent;
        private long lastSentTick;
        private double lastSentValue;
        private int lastSentBand = -1;
    }

    private final Map<ChunkCacheKey, Slot> slots = new LinkedHashMap<>();

    /**
     * Offers a freshly read value.
     *
     * @return true when this differs from the last value sent for the chunk (or
     *         nothing was sent yet), i.e. the value is worth sending
     */
    public boolean offerIfChanged(ChunkCacheKey key, double value, int band, long tick) {
        Slot slot = this.slots.computeIfAbsent(key, ignored -> new Slot());
        if (slot.everSent && sameDouble(slot.lastSentValue, value) && slot.lastSentBand == band) {
            // Nothing to send. A still-pending, now-redundant offer is dropped.
            slot.pending = false;
            return false;
        }
        slot.pendingValue = value;
        slot.pendingBand = band;
        slot.pending = true;
        return true;
    }

    /**
     * Queues a value even though it matches the last one sent.
     *
     * <p>Deduplication alone would leave an unchanged chunk silent forever, so
     * the client could never tell a still-current value from one that stopped
     * being refreshed. This is the heartbeat that keeps a genuine observation
     * counting as live; the throttle in {@link #takeReady} still applies.</p>
     */
    public void offerRefresh(ChunkCacheKey key, double value, int band) {
        Slot slot = this.slots.computeIfAbsent(key, ignored -> new Slot());
        slot.pendingValue = value;
        slot.pendingBand = band;
        slot.pending = true;
    }

    /**
     * Chunks whose last send is at least {@code intervalTicks} old, i.e. the ones
     * a heartbeat has to confirm again.
     *
     * <p>A chunk with a value already pending is skipped: it will be sent anyway.</p>
     */
    public List<ChunkCacheKey> dueForRefresh(long now, long intervalTicks) {
        if (intervalTicks <= 0L || this.slots.isEmpty()) {
            return List.of();
        }
        List<ChunkCacheKey> due = new ArrayList<>();
        for (Map.Entry<ChunkCacheKey, Slot> entry : this.slots.entrySet()) {
            Slot slot = entry.getValue();
            if (!slot.everSent || slot.pending) {
                continue;
            }
            if (now - slot.lastSentTick >= intervalTicks) {
                due.add(entry.getKey());
            }
        }
        return due;
    }

    /** Whether the chunk has a value waiting to be sent. */
    public boolean hasPending(ChunkCacheKey key) {
        Slot slot = this.slots.get(key);
        return slot != null && slot.pending;
    }

    /** The value last accepted for transmission, or empty when nothing was sent. */
    public java.util.OptionalDouble lastSentValue(ChunkCacheKey key) {
        Slot slot = this.slots.get(key);
        if (slot == null || !slot.everSent) {
            return java.util.OptionalDouble.empty();
        }
        return java.util.OptionalDouble.of(slot.lastSentValue);
    }

    /**
     * Takes up to {@code max} pending updates that are allowed to go out now.
     *
     * <p>An update whose chunk was sent less than {@code minIntervalTicks} ago is
     * left pending for a later drain. Taking an update records it as sent at
     * {@code now}, so two drains in the same tick cannot resend it.</p>
     */
    public List<Update> takeReady(long now, long minIntervalTicks, int max) {
        if (max <= 0 || this.slots.isEmpty()) {
            return List.of();
        }
        long interval = Math.max(0L, minIntervalTicks);
        List<Update> ready = new ArrayList<>(Math.min(max, this.slots.size()));
        for (Map.Entry<ChunkCacheKey, Slot> entry : this.slots.entrySet()) {
            if (ready.size() >= max) {
                break;
            }
            Slot slot = entry.getValue();
            if (!slot.pending) {
                continue;
            }
            if (slot.everSent && now - slot.lastSentTick < interval) {
                continue;
            }
            slot.pending = false;
            slot.everSent = true;
            slot.lastSentTick = now;
            slot.lastSentValue = slot.pendingValue;
            slot.lastSentBand = slot.pendingBand;
            ready.add(new Update(entry.getKey(), slot.pendingValue, slot.pendingBand));
        }
        return ready;
    }

    /** Forgets chunks that are no longer subscribed, bounding per-player memory. */
    public void retainOnly(Set<ChunkCacheKey> keep) {
        this.slots.keySet().removeIf(key -> !keep.contains(key));
    }

    public void clear() {
        this.slots.clear();
    }

    public int trackedChunks() {
        return this.slots.size();
    }

    /** Number of chunks waiting for the next drain. */
    public int pendingCount() {
        int pending = 0;
        for (Slot slot : this.slots.values()) {
            if (slot.pending) {
                pending++;
            }
        }
        return pending;
    }

    private static boolean sameDouble(double a, double b) {
        return Double.compare(a, b) == 0;
    }
}
