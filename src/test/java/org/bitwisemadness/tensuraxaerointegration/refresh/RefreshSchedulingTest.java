package org.bitwisemadness.tensuraxaerointegration.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.bitwisemadness.tensuraxaerointegration.cache.ChunkCacheKey;
import org.junit.jupiter.api.Test;

class RefreshSchedulingTest {

    private static final ChunkCacheKey A = new ChunkCacheKey("s", "minecraft:overworld", 0, 0);
    private static final ChunkCacheKey B = new ChunkCacheKey("s", "minecraft:overworld", 1, 0);

    // --- update queue -------------------------------------------------------

    @Test
    void an_unchanged_value_is_not_queued_twice() {
        UpdateQueue queue = new UpdateQueue();
        assertTrue(queue.offerIfChanged(A, 600.0, 0));
        assertEquals(1, queue.takeReady(0, 0, 10).size());
        assertFalse(queue.offerIfChanged(A, 600.0, 1), "same value as last sent");
        assertTrue(queue.takeReady(1, 0, 10).isEmpty());
    }

    @Test
    void a_changed_value_is_queued_again() {
        UpdateQueue queue = new UpdateQueue();
        queue.offerIfChanged(A, 600.0, 0);
        queue.takeReady(0, 0, 10);
        assertTrue(queue.offerIfChanged(A, 650.0, 1));
        List<UpdateQueue.Update> ready = queue.takeReady(1, 0, 10);
        assertEquals(1, ready.size());
        assertEquals(650.0, ready.get(0).value());
    }

    @Test
    void changes_inside_the_minimum_interval_wait_and_keep_the_latest_value() {
        UpdateQueue queue = new UpdateQueue();
        queue.offerIfChanged(A, 600.0, 0);
        queue.takeReady(0, 20, 10);

        queue.offerIfChanged(A, 610.0, 5);
        queue.offerIfChanged(A, 620.0, 10);
        assertTrue(queue.takeReady(10, 20, 10).isEmpty(), "still inside the interval");
        assertEquals(1, queue.pendingCount(), "one pending value per chunk, not three");

        List<UpdateQueue.Update> ready = queue.takeReady(20, 20, 10);
        assertEquals(1, ready.size());
        assertEquals(620.0, ready.get(0).value());
    }

    @Test
    void unavailability_deduplicates_like_a_value() {
        UpdateQueue queue = new UpdateQueue();
        assertTrue(queue.offerIfChanged(A, Double.NaN, 0));
        assertEquals(1, queue.takeReady(0, 0, 10).size());
        assertFalse(queue.offerIfChanged(A, Double.NaN, 1));
        assertTrue(queue.takeReady(1, 0, 10).isEmpty());
        // A real value after unavailability is a change again.
        assertTrue(queue.offerIfChanged(A, 500.0, 2));
    }

    @Test
    void a_drain_is_bounded_per_call() {
        UpdateQueue queue = new UpdateQueue();
        for (int i = 0; i < 10; i++) {
            queue.offerIfChanged(new ChunkCacheKey("s", "d", i, 0), i, 0);
        }
        assertEquals(3, queue.takeReady(0, 0, 3).size());
        assertEquals(3, queue.takeReady(0, 0, 3).size());
        assertEquals(3, queue.takeReady(0, 0, 3).size());
        assertEquals(1, queue.takeReady(0, 0, 3).size());
    }

    @Test
    void pruning_forgets_unsubscribed_chunks() {
        UpdateQueue queue = new UpdateQueue();
        queue.offerIfChanged(A, 1.0, 0);
        queue.offerIfChanged(B, 2.0, 0);
        Set<ChunkCacheKey> keep = new LinkedHashSet<>();
        keep.add(A);
        queue.retainOnly(keep);
        assertEquals(1, queue.trackedChunks());
        queue.clear();
        assertEquals(0, queue.trackedChunks());
    }

    @Test
    void last_sent_value_is_visible_for_reconciliation() {
        UpdateQueue queue = new UpdateQueue();
        assertTrue(queue.lastSentValue(A).isEmpty());
        queue.offerIfChanged(A, 42.0, 0);
        queue.takeReady(0, 0, 1);
        assertEquals(42.0, queue.lastSentValue(A).getAsDouble());
    }

    // --- reconciliation cursor ---------------------------------------------

    @Test
    void a_budget_larger_than_the_list_visits_it_once() {
        ReconcileCursor<String> cursor = new ReconcileCursor<>();
        List<String> values = List.of("a", "b", "c");
        assertEquals(values, cursor.next(values, 10));
        assertEquals(0, cursor.index());
    }

    @Test
    void successive_slices_cover_the_whole_list_and_wrap() {
        ReconcileCursor<String> cursor = new ReconcileCursor<>();
        List<String> values = List.of("a", "b", "c", "d", "e");
        assertEquals(List.of("a", "b"), cursor.next(values, 2));
        assertEquals(List.of("c", "d"), cursor.next(values, 2));
        assertEquals(List.of("e", "a"), cursor.next(values, 2));
        assertEquals(List.of("b", "c"), cursor.next(values, 2));
    }

    @Test
    void an_empty_list_is_safe() {
        ReconcileCursor<String> cursor = new ReconcileCursor<>();
        assertTrue(cursor.next(List.of(), 5).isEmpty());
        assertTrue(cursor.next(List.of(), 0).isEmpty());
        assertEquals(0, cursor.index());
    }

    @Test
    void a_shrinking_list_cannot_leave_the_cursor_out_of_range() {
        ReconcileCursor<String> cursor = new ReconcileCursor<>();
        cursor.next(List.of("a", "b", "c", "d", "e", "f"), 5);
        List<String> shrunk = List.of("a", "b");
        assertEquals(2, cursor.next(shrunk, 5).size());
        assertTrue(cursor.index() >= 0 && cursor.index() < shrunk.size());
    }

    @Test
    void a_zero_budget_does_nothing() {
        ReconcileCursor<String> cursor = new ReconcileCursor<>();
        assertTrue(cursor.next(List.of("a", "b"), 0).isEmpty());
        assertEquals(0, cursor.index());
    }

    @Test
    void the_cursor_can_be_reset() {
        ReconcileCursor<String> cursor = new ReconcileCursor<>();
        cursor.next(List.of("a", "b", "c"), 2);
        cursor.reset();
        assertEquals(List.of("a", "b"), cursor.next(List.of("a", "b", "c"), 2));
    }

    // --- sanity on the test fixtures ---------------------------------------

    @Test
    void fixtures_are_distinct() {
        assertFalse(A.equals(B));
        List<ChunkCacheKey> list = new ArrayList<>();
        list.add(A);
        list.add(B);
        assertEquals(2, list.size());
    }
}
