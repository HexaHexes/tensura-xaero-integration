package org.bitwisemadness.tensuraxaerointegration.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChunkRadiusTest {

    @Test
    void the_players_own_chunk_is_always_in_range() {
        assertTrue(ChunkRadius.within(0, 0, 0, 0, 0), "radius 0 is the chunk itself");
        assertTrue(ChunkRadius.within(-7, 42, -7, 42, 10));
    }

    @Test
    void a_chunk_exactly_on_the_radius_is_in_range() {
        assertTrue(ChunkRadius.within(10, 0, 0, 0, 10));
        assertTrue(ChunkRadius.within(0, -10, 0, 0, 10));
        assertTrue(ChunkRadius.within(6, 8, 0, 0, 10), "6-8-10 is exactly ten chunks away");
    }

    @Test
    void one_chunk_past_the_radius_is_out_of_range() {
        assertFalse(ChunkRadius.within(11, 0, 0, 0, 10));
        assertFalse(ChunkRadius.within(7, 8, 0, 0, 10), "10.63 chunks away");
    }

    @Test
    void the_corners_of_a_square_radius_are_not_in_range() {
        // The whole reason this is circular: a square of radius 10 would ask about
        // (10,10), which is 14.1 chunks away and lie outside any loaded area.
        assertFalse(ChunkRadius.within(10, 10, 0, 0, 10));
        assertFalse(ChunkRadius.within(-10, -10, 0, 0, 10));
        assertFalse(ChunkRadius.within(16, 16, 0, 0, 16), "the server cap is a circle too");
    }

    @Test
    void the_shape_is_centred_on_the_player_not_the_origin() {
        assertTrue(ChunkRadius.within(103, -96, 100, -100, 5), "3-4-5 is exactly five chunks away");
        assertFalse(ChunkRadius.within(105, -95, 100, -100, 5), "5-5 is 7.07 chunks away");
    }

    @Test
    void far_away_coordinates_do_not_overflow_into_a_close_answer() {
        int far = 1_000_000;
        assertFalse(ChunkRadius.within(far, far, -far, -far, 16));
        assertFalse(ChunkRadius.within(far, 0, 0, 0, 32));
    }

    @Test
    void a_negative_radius_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> ChunkRadius.within(0, 0, 0, 0, -1));
    }
}
