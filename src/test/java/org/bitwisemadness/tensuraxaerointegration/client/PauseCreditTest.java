package org.bitwisemadness.tensuraxaerointegration.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PauseCreditTest {

    @Test
    void a_gap_paused_at_both_ends_is_credited_in_full() {
        assertEquals(4_000, PauseCredit.creditMillis(true, true, 4_000),
                "the whole gap was pause time, however long the pause menu stayed open");
    }

    @Test
    void a_pause_that_begins_inside_a_stall_credits_at_most_one_tick() {
        // The client ticked, the server then ran, and ESC was pressed 12.7 s later:
        // crediting the stall as paused time is what rewound the freshness clock.
        assertEquals(50, PauseCredit.creditMillis(false, true, 12_742));
        assertEquals(20, PauseCredit.creditMillis(false, true, 20), "a short gap is not stretched");
    }

    @Test
    void a_gap_the_server_ran_through_credits_nothing() {
        assertEquals(0, PauseCredit.creditMillis(false, false, 12_742));
    }

    @Test
    void a_resume_after_a_frozen_pause_is_credited_in_full() {
        // The pause menu froze the client too, so the gap carries no tick and the
        // resume is where its duration is seen.
        assertEquals(300_000, PauseCredit.creditMillis(true, false, 300_000));
    }

    @Test
    void a_stopped_or_backwards_clock_credits_nothing() {
        assertEquals(0, PauseCredit.creditMillis(true, true, 0));
        assertEquals(0, PauseCredit.creditMillis(true, true, -5_000));
    }
}
