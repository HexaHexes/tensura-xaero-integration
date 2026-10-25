package org.bitwisemadness.tensuraxaerointegration.cache;

/**
 * One cached concentration observation.
 *
 * <p>{@code available} distinguishes "Tensura says the value is X" from "the
 * value could not be read"; a valid zero is available, a missing chunk is not.
 * {@code value} is {@link Double#NaN} exactly when {@code available} is false,
 * so it must never be interpreted as a concentration.</p>
 *
 * @param value     the authoritative concentration, or NaN when unavailable
 * @param tick      the client/server tick the observation was taken at
 * @param available whether {@code value} is real
 */
public record ConcentrationEntry(double value, long tick, boolean available) {

    public ConcentrationEntry {
        if (available && !Double.isFinite(value)) {
            throw new IllegalArgumentException("an available entry needs a finite value");
        }
        if (!available && !Double.isNaN(value)) {
            throw new IllegalArgumentException("an unavailable entry must carry NaN");
        }
    }

    static ConcentrationEntry of(double value, long tick) {
        return new ConcentrationEntry(value, tick, true);
    }

    static ConcentrationEntry unavailable(long tick) {
        return new ConcentrationEntry(Double.NaN, tick, false);
    }

    /** Whether the observation is still within {@code staleAfterTicks} of {@code now}. */
    public boolean fresh(long now, long staleAfterTicks) {
        if (now < this.tick) {
            // Clock went backwards (world switch resets tick counters): treat as
            // stale rather than trusting a timestamp from another session.
            return false;
        }
        return now - this.tick <= staleAfterTicks;
    }
}
