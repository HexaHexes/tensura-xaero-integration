package org.bitwisemadness.tensuraxaerointegration.refresh;

/**
 * The cadence promise a server makes to its clients: how long one confirmation
 * really takes, in milliseconds.
 *
 * <p>{@code intervalTicks * 50 ms} is only true at 20 ticks per second. A pack
 * heavy enough to log {@code Can't keep up! ... 126 ticks behind} stretches a
 * 200-tick confirmation into tens of seconds, and a client that trusted the
 * nominal figure would dim values the server was still confirming. So the server
 * measures itself: every sweep of {@code sweepTicks} ticks is timed, extrapolated
 * to one confirmation interval, and the promise becomes the worst sweep it has
 * seen recently.</p>
 *
 * <p>Two bounds keep that honest in the other direction. The promise never drops
 * below the nominal interval - a fast sweep is not licence to expect confirmation
 * faster than the configured cadence - and it never exceeds {@link #MAX_MULTIPLE}
 * intervals, so a resumed pause or a pathological stall cannot talk a client into
 * trusting a value for an hour. The worst sample is forgotten after
 * {@link #SAMPLES} sweeps, so a server that recovers narrows its promise again
 * instead of carrying one stall forever.</p>
 *
 * <p>Not thread-safe: the server thread is the only sampler and reader.</p>
 */
public final class CadenceEstimate {

    /** Sweeps remembered; the worst of them is what the promise claims. */
    public static final int SAMPLES = 15;

    /** Ceiling on the promise, as a multiple of the nominal interval. */
    public static final long MAX_MULTIPLE = 4L;

    private final int intervalTicks;
    private final int sweepTicks;
    private final long nominalMillis;
    private final long capMillis;
    private final long[] samples = new long[SAMPLES];

    private int next;
    private int filled;

    /**
     * @param intervalTicks how often a chunk is confirmed, in server ticks; positive
     * @param sweepTicks    how many ticks one timing sample covers; positive
     */
    public CadenceEstimate(int intervalTicks, int sweepTicks) {
        if (intervalTicks <= 0) {
            throw new IllegalArgumentException("the confirmation interval must be positive, was " + intervalTicks);
        }
        if (sweepTicks <= 0) {
            throw new IllegalArgumentException("a sweep must cover at least one tick, was " + sweepTicks);
        }
        this.intervalTicks = intervalTicks;
        this.sweepTicks = sweepTicks;
        this.nominalMillis = intervalTicks * 50L;
        this.capMillis = this.nominalMillis * MAX_MULTIPLE;
    }

    /**
     * Records how long one sweep really took, stalls included.
     *
     * <p>A stall is the sample that matters: it is exactly the case the nominal
     * figure misdescribes. Non-positive durations describe a broken clock rather
     * than a fast server and are ignored.</p>
     *
     * @param sweepMillis wall-clock time the last sweep took, in ms
     */
    public void record(long sweepMillis) {
        if (sweepMillis <= 0L) {
            return;
        }
        this.samples[this.next] = sweepMillis;
        this.next = (this.next + 1) % SAMPLES;
        if (this.filled < SAMPLES) {
            this.filled++;
        }
    }

    /**
     * How long the client should expect one confirmation to take: the worst
     * recently measured sweep, extrapolated to the full interval and bounded on
     * both sides.
     */
    public long promiseMillis() {
        long worst = 0L;
        for (int i = 0; i < this.filled; i++) {
            worst = Math.max(worst, this.samples[i]);
        }
        if (worst <= 0L) {
            return this.nominalMillis;
        }
        long extrapolated = Math.round(worst * (double) this.intervalTicks / this.sweepTicks);
        return Math.max(this.nominalMillis, Math.min(this.capMillis, extrapolated));
    }

    /** The configured confirmation interval this estimate is expressed in. */
    public int intervalTicks() {
        return this.intervalTicks;
    }

    /** The nominal promise: the interval at a healthy 20 ticks per second. */
    public long nominalMillis() {
        return this.nominalMillis;
    }

    /** The widest promise a stall can produce. */
    public long capMillis() {
        return this.capMillis;
    }

    /** How many samples the current promise is drawn from. */
    public int sampleCount() {
        return this.filled;
    }

    public void clear() {
        this.next = 0;
        this.filled = 0;
        java.util.Arrays.fill(this.samples, 0L);
    }
}
