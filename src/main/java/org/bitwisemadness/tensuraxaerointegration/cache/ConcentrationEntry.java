package org.bitwisemadness.tensuraxaerointegration.cache;

/**
 * One cached concentration observation, either live or last-known.
 *
 * <p>{@code available} distinguishes "Tensura says the value is X" from "the
 * value could not be read"; a valid zero is available, a missing chunk is not.
 * {@code value} is {@link Double#NaN} exactly when {@code available} is false,
 * so it must never be interpreted as a concentration.</p>
 *
 * <p>{@code historical} marks a value that was authoritative when it was
 * observed but is no longer being refreshed - the chunk unloaded, went out of
 * subscription range, or the entry was replayed from disk. It is still a real
 * observation of that chunk, never an interpolation or a guess, so it may be
 * shown, but it must be shown as <em>last known</em> rather than as a current
 * value. Only a fresh observation clears the flag.</p>
 *
 * <p>{@code band} is the server's closed-vocabulary band code for the value (see
 * {@code ConcentrationClassification#code()}), or {@code -1} when the server
 * could not classify it. It is stored, not recomputed: the tiers are the
 * server's, so a client-side second opinion is exactly what must not exist.</p>
 *
 * <p>Timestamps are wall-clock milliseconds, not ticks. The server confirms a
 * chunk on its own tick counter, and a server that cannot keep up stretches those
 * ticks in real time; measuring the gap in client ticks compared two unsynchronised
 * clocks and made values flicker between live and last known on exactly the
 * servers where the feature matters.</p>
 *
 * @param value      the observed concentration, or NaN when unavailable
 * @param band       the server's band code, or -1 when unclassified
 * @param stampMillis when the observation was taken, in wall-clock ms (ignored when
 *                   historical)
 * @param available  whether {@code value} is real
 * @param historical whether the value is a remembered observation, not a current one
 */
public record ConcentrationEntry(double value, int band, long stampMillis, boolean available,
        boolean historical) {

    /** No band: the value could not be classified, so nothing is painted for it. */
    public static final int UNCLASSIFIED = -1;

    public ConcentrationEntry {
        if (available && !Double.isFinite(value)) {
            throw new IllegalArgumentException("an available entry needs a finite value");
        }
        if (!available && !Double.isNaN(value)) {
            throw new IllegalArgumentException("an unavailable entry must carry NaN");
        }
        if (!available && historical) {
            throw new IllegalArgumentException("an unavailable entry cannot be historical");
        }
        if (band < UNCLASSIFIED) {
            throw new IllegalArgumentException("a band code cannot be below -1, was " + band);
        }
    }

    static ConcentrationEntry of(double value, int band, long stampMillis) {
        return new ConcentrationEntry(value, band, stampMillis, true, false);
    }

    static ConcentrationEntry unavailable(long stampMillis) {
        return new ConcentrationEntry(Double.NaN, UNCLASSIFIED, stampMillis, false, false);
    }

    /** A remembered observation: shown dimmer and labelled last-known. */
    static ConcentrationEntry lastKnown(double value, int band) {
        return new ConcentrationEntry(value, band, 0L, true, true);
    }

    /** Whether the observation is current: available, not historical, within the window. */
    public boolean fresh(long nowMillis, long windowMillis) {
        if (!this.available || this.historical) {
            return false;
        }
        if (nowMillis < this.stampMillis) {
            // Clock went backwards (system time change): treat as stale rather than
            // trusting a timestamp from the future.
            return false;
        }
        return nowMillis - this.stampMillis <= windowMillis;
    }
}
