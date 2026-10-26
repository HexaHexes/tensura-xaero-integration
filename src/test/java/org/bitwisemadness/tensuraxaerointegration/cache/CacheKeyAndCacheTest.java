package org.bitwisemadness.tensuraxaerointegration.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CacheKeyAndCacheTest {

    private static final ChunkCacheKey OVERWORLD_SAVE_A =
            new ChunkCacheKey("save-a", "minecraft:overworld", 3, -7);

    @Test
    void identical_keys_are_equal() {
        assertEquals(OVERWORLD_SAVE_A, new ChunkCacheKey("save-a", "minecraft:overworld", 3, -7));
        assertEquals(OVERWORLD_SAVE_A.hashCode(),
                new ChunkCacheKey("save-a", "minecraft:overworld", 3, -7).hashCode());
    }

    @Test
    void dimensions_do_not_collide() {
        ChunkCacheKey nether = new ChunkCacheKey("save-a", "minecraft:the_nether", 3, -7);
        assertNotEquals(OVERWORLD_SAVE_A, nether);
        ConcentrationCache cache = new ConcentrationCache(8);
        cache.put(OVERWORLD_SAVE_A, 900.0, 1);
        cache.put(nether, 1200.0, 1);
        assertEquals(900.0, cache.value(OVERWORLD_SAVE_A).getAsDouble());
        assertEquals(1200.0, cache.value(nether).getAsDouble());
    }

    @Test
    void worlds_do_not_collide() {
        ChunkCacheKey otherSave = OVERWORLD_SAVE_A.withWorld("save-b");
        assertNotEquals(OVERWORLD_SAVE_A, otherSave);
        ConcentrationCache cache = new ConcentrationCache(8);
        cache.put(OVERWORLD_SAVE_A, 600.0, 1);
        cache.put(otherSave, 700.0, 1);
        assertEquals(600.0, cache.value(OVERWORLD_SAVE_A).getAsDouble());
        assertEquals(700.0, cache.value(otherSave).getAsDouble());
    }

    @Test
    void blank_world_or_dimension_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkCacheKey("", "minecraft:overworld", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkCacheKey("save", " ", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ChunkCacheKey(null, "minecraft:overworld", 0, 0));
    }

    @Test
    void regions_use_xaero_32_chunk_geometry_including_negatives() {
        assertEquals(new RegionKey("minecraft:overworld", 0, 0),
                new ChunkCacheKey("s", "minecraft:overworld", 31, 0).region());
        assertEquals(new RegionKey("minecraft:overworld", 1, 0),
                new ChunkCacheKey("s", "minecraft:overworld", 32, 0).region());
        assertEquals(new RegionKey("minecraft:overworld", -1, -1),
                new ChunkCacheKey("s", "minecraft:overworld", -1, -1).region());
        assertEquals(new RegionKey("minecraft:overworld", -1, -1),
                new ChunkCacheKey("s", "minecraft:overworld", -32, -32).region());
    }

    @Test
    void a_changed_value_is_reported_and_an_unchanged_one_is_not() {
        ConcentrationCache cache = new ConcentrationCache(8);
        assertTrue(cache.put(OVERWORLD_SAVE_A, 600.0, 1));
        assertFalse(cache.put(OVERWORLD_SAVE_A, 600.0, 2), "same value must not count as a change");
        assertTrue(cache.put(OVERWORLD_SAVE_A, 601.0, 3));
        assertFalse(cache.put(OVERWORLD_SAVE_A, 601.0, 4));
    }

    @Test
    void an_unchanged_refresh_still_updates_freshness() {
        ConcentrationCache cache = new ConcentrationCache(8);
        cache.put(OVERWORLD_SAVE_A, 600.0, 10);
        assertTrue(cache.freshValue(OVERWORLD_SAVE_A, 20, 15).isPresent());
        // Re-observed unchanged at tick 30: value stays, freshness moves.
        assertFalse(cache.put(OVERWORLD_SAVE_A, 600.0, 30));
        assertTrue(cache.freshValue(OVERWORLD_SAVE_A, 40, 15).isPresent());
        assertEquals(600.0, cache.value(OVERWORLD_SAVE_A).getAsDouble());
    }

    @Test
    void stale_values_are_not_offered_as_fresh() {
        ConcentrationCache cache = new ConcentrationCache(8);
        cache.put(OVERWORLD_SAVE_A, 600.0, 10);
        assertTrue(cache.freshValue(OVERWORLD_SAVE_A, 30, 15).isEmpty());
        assertTrue(cache.value(OVERWORLD_SAVE_A).isPresent(), "the value itself is still cached");
    }

    @Test
    void going_unavailable_is_a_change_and_clears_the_value() {
        ConcentrationCache cache = new ConcentrationCache(8);
        cache.put(OVERWORLD_SAVE_A, 600.0, 1);
        assertTrue(cache.markUnavailable(OVERWORLD_SAVE_A, 2));
        assertTrue(cache.value(OVERWORLD_SAVE_A).isEmpty());
        assertFalse(cache.markUnavailable(OVERWORLD_SAVE_A, 3), "already unavailable");
        assertTrue(cache.entry(OVERWORLD_SAVE_A).orElseThrow().available() == false);
    }

    @Test
    void a_valid_zero_is_distinct_from_missing_data() {
        ConcentrationCache cache = new ConcentrationCache(8);
        cache.put(OVERWORLD_SAVE_A, 0.0, 1);
        assertTrue(cache.value(OVERWORLD_SAVE_A).isPresent());
        assertEquals(0.0, cache.value(OVERWORLD_SAVE_A).getAsDouble());
    }

    @Test
    void the_cache_is_bounded_and_evicts_the_least_recently_used() {
        ConcentrationCache cache = new ConcentrationCache(2);
        ChunkCacheKey a = new ChunkCacheKey("s", "d", 1, 1);
        ChunkCacheKey b = new ChunkCacheKey("s", "d", 2, 2);
        ChunkCacheKey c = new ChunkCacheKey("s", "d", 3, 3);
        cache.put(a, 1.0, 1);
        cache.put(b, 2.0, 1);
        cache.value(a); // touch a, so b is the eviction candidate
        cache.put(c, 3.0, 1);
        assertEquals(2, cache.size());
        assertTrue(cache.value(a).isPresent());
        assertTrue(cache.value(b).isEmpty());
        assertTrue(cache.value(c).isPresent());
    }

    @Test
    void clearing_removes_everything() {
        ConcentrationCache cache = new ConcentrationCache(8);
        cache.put(OVERWORLD_SAVE_A, 600.0, 1);
        cache.clear();
        assertEquals(0, cache.size());
        assertTrue(cache.value(OVERWORLD_SAVE_A).isEmpty());
    }

    @Test
    void an_invalid_capacity_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new ConcentrationCache(0));
    }

    @Test
    void entries_reject_inconsistent_availability() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationEntry(Double.NaN, 1, true, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationEntry(5.0, 1, false, false));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationEntry(Double.NaN, 1, false, true));
    }

    @Test
    void a_last_known_entry_is_never_fresh() {
        ConcentrationEntry entry = ConcentrationEntry.lastKnown(600.0);
        assertEquals(600.0, entry.value());
        assertTrue(entry.historical());
        assertFalse(entry.fresh(0, 10_000), "a remembered value is not a current one");
    }

    @Test
    void demoting_keeps_the_value_and_counts_as_a_change() {
        ConcentrationCache cache = new ConcentrationCache(8);
        cache.put(OVERWORLD_SAVE_A, 600.0, 5);
        assertTrue(cache.demote(OVERWORLD_SAVE_A));
        assertFalse(cache.demote(OVERWORLD_SAVE_A), "already last known");
        assertEquals(600.0, cache.value(OVERWORLD_SAVE_A).getAsDouble());
        assertTrue(cache.entry(OVERWORLD_SAVE_A).orElseThrow().historical());
        assertTrue(cache.freshValue(OVERWORLD_SAVE_A, 5, 100).isEmpty());
    }

    @Test
    void a_replayed_value_never_overwrites_a_live_one() {
        ConcentrationCache cache = new ConcentrationCache(8);
        cache.put(OVERWORLD_SAVE_A, 600.0, 5);
        assertFalse(cache.putLastKnown(OVERWORLD_SAVE_A, 900.0));
        assertEquals(600.0, cache.value(OVERWORLD_SAVE_A).getAsDouble());

        ChunkCacheKey untouched = new ChunkCacheKey("sp:save-a", "minecraft:overworld", 41, 41);
        assertTrue(cache.putLastKnown(untouched, 900.0));
        assertTrue(cache.entry(untouched).orElseThrow().historical());
    }
}
