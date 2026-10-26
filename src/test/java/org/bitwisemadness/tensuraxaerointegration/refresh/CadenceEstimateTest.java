package org.bitwisemadness.tensuraxaerointegration.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CadenceEstimateTest {

    /** The shipped cadence: 200-tick confirmations timed over 20-tick sweeps. */
    private static CadenceEstimate estimate() {
        return new CadenceEstimate(200, 20);
    }

    @Test
    void a_server_that_has_not_measured_itself_promises_the_nominal_interval() {
        CadenceEstimate cadence = estimate();
        assertEquals(10_000, cadence.promiseMillis(), "200 ticks at a healthy 50 ms each");
        assertEquals(10_000, cadence.nominalMillis());
        assertEquals(40_000, cadence.capMillis(), "a stall may claim four intervals, no more");
        assertEquals(0, cadence.sampleCount());
    }

    @Test
    void a_stall_widens_the_promise_instead_of_being_discarded() {
        CadenceEstimate cadence = estimate();
        // One 20-tick sweep took 1.5 s: the server is running at roughly 13 tps.
        cadence.record(1_500);
        assertEquals(15_000, cadence.promiseMillis(),
                "200 ticks at that rate really do take 15 s, so the client must be told");
    }

    @Test
    void a_fast_sweep_never_promises_faster_than_the_configured_cadence() {
        CadenceEstimate cadence = estimate();
        cadence.record(100); // 5 ms per tick
        assertEquals(10_000, cadence.promiseMillis(),
                "a healthy server still confirms every 200 ticks, not sooner");
    }

    @Test
    void the_worst_recent_sweep_is_what_the_promise_claims() {
        CadenceEstimate cadence = estimate();
        for (int i = 0; i < CadenceEstimate.SAMPLES; i++) {
            cadence.record(1_200); // 60 ms per tick: 12 s per confirmation
        }
        assertEquals(12_000, cadence.promiseMillis());
        cadence.record(2_500);
        assertEquals(25_000, cadence.promiseMillis(), "one worse stall raises the promise for the window");
        assertEquals(CadenceEstimate.SAMPLES, cadence.sampleCount());
    }

    @Test
    void a_resumed_pause_cannot_stretch_the_promise_beyond_the_cap() {
        CadenceEstimate cadence = estimate();
        cadence.record(300_000); // a five-minute pause, seen as one enormous sweep
        assertEquals(cadence.capMillis(), cadence.promiseMillis(),
                "a pause must not talk a client into trusting a value for minutes");
    }

    @Test
    void a_recovered_server_narrows_its_promise_again() {
        CadenceEstimate cadence = estimate();
        cadence.record(4_000); // 40 s per confirmation
        assertEquals(40_000, cadence.promiseMillis());

        for (int i = 0; i < CadenceEstimate.SAMPLES; i++) {
            cadence.record(200); // back to a healthy 50 ms per tick
        }
        assertEquals(10_000, cadence.promiseMillis(),
                "the stall is forgotten once it is no longer one of the recent sweeps");
    }

    @Test
    void a_sample_of_zero_length_is_ignored() {
        CadenceEstimate cadence = estimate();
        cadence.record(0);
        cadence.record(-5);
        assertEquals(0, cadence.sampleCount(), "a stopped clock is not a fast server");
        assertEquals(10_000, cadence.promiseMillis());
    }

    @Test
    void clearing_forgets_every_sample() {
        CadenceEstimate cadence = estimate();
        cadence.record(3_000);
        cadence.clear();
        assertEquals(0, cadence.sampleCount());
        assertEquals(10_000, cadence.promiseMillis());
    }

    @Test
    void an_impossible_interval_or_sweep_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new CadenceEstimate(0, 20));
        assertThrows(IllegalArgumentException.class, () -> new CadenceEstimate(-200, 20));
        assertThrows(IllegalArgumentException.class, () -> new CadenceEstimate(200, 0));
        assertThrows(IllegalArgumentException.class, () -> new CadenceEstimate(200, -20));
    }
}
