package org.bitwisemadness.tensuraxaerointegration.client;

/**
 * How much of a client tick gap counts as time the integrated server was paused.
 *
 * <p>Paused time has to be kept out of the freshness clock: a paused server
 * confirms nothing, so ageing values by it would dim the whole map on unpausing.
 * The client cannot see when a pause began, only whether the server is paused now
 * and whether it was paused at the previous tick, so a gap is credited from that
 * pair:</p>
 *
 * <ul>
 *   <li>paused at both ends - the whole gap was pause time;</li>
 *   <li>paused only now - the pause began somewhere inside the gap and the rest of
 *       it was ordinary time that may include a stall, so at most one tick is
 *       credited; the remainder arrives on the following paused ticks, or in full
 *       on the tick that resumes;</li>
 *   <li>paused at neither end - nothing.</li>
 * </ul>
 *
 * <p>Crediting the whole gap in the middle case is what rewound the clock: a
 * multi-second stall immediately before the pause menu opened was counted as
 * paused time, which put the store's clock behind stamps it had already taken and
 * demoted values whose confirmation the clock then dated in the future.</p>
 *
 * <p>Minecraft-free so the arithmetic is testable without a client.</p>
 */
public final class PauseCredit {

    /** One client tick at 20 tps: the most a pause starting inside a gap can hide. */
    private static final long ONE_TICK_MILLIS = 50L;

    private PauseCredit() {
    }

    /**
     * @param pausedAtLastTick whether the server was paused at the previous tick
     * @param pausedNow        whether it is paused at this one
     * @param gapMillis        wall-clock time since the previous tick
     * @return the milliseconds of that gap to credit as paused time
     */
    public static long creditMillis(boolean pausedAtLastTick, boolean pausedNow, long gapMillis) {
        if (gapMillis <= 0L) {
            return 0L;
        }
        if (pausedAtLastTick) {
            return gapMillis;
        }
        if (pausedNow) {
            return Math.min(gapMillis, ONE_TICK_MILLIS);
        }
        return 0L;
    }
}
