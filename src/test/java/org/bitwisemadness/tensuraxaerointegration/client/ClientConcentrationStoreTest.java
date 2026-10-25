package org.bitwisemadness.tensuraxaerointegration.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import org.bitwisemadness.tensuraxaerointegration.cache.ChunkCacheKey;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKey;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationClassification;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationThresholds;
import org.junit.jupiter.api.Test;

class ClientConcentrationStoreTest {

    private static final ChunkCacheKey CHUNK =
            new ChunkCacheKey("sp:world", "minecraft:overworld", 34, -3);
    private static final RegionKey REGION = CHUNK.region();

    private ClientConcentrationStore newStore() {
        ClientConcentrationStore store = new ClientConcentrationStore(64);
        store.setBaseline(500.0);
        return store;
    }

    @Test
    void unmapped_values_are_never_stored() {
        ClientConcentrationStore store = newStore();
        assertFalse(store.offer(CHUNK, false, 4000.0));
        assertEquals(0, store.size());
        assertTrue(store.displayValue(CHUNK, 100).isEmpty());
        assertTrue(store.drainDirtyRegions().isEmpty(), "nothing to repaint");
    }

    @Test
    void mapped_values_are_stored_and_classified_against_the_baseline() {
        ClientConcentrationStore store = newStore();
        assertTrue(store.offer(CHUNK, true, 600.0));
        assertEquals(600.0, store.displayValue(CHUNK, 100).getAsDouble());
        Optional<ConcentrationClassification> classification =
                store.classification(CHUNK, 100, ConcentrationThresholds.defaults());
        assertEquals(Optional.of(ConcentrationClassification.SLIGHTLY_ELEVATED), classification);
    }

    @Test
    void a_baseline_value_is_classified_normal() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 500.0);
        assertEquals(Optional.of(ConcentrationClassification.NORMAL),
                store.classification(CHUNK, 100, ConcentrationThresholds.defaults()));
    }

    @Test
    void elevation_transitions_bump_the_region_version_each_time() {
        ClientConcentrationStore store = newStore();
        assertEquals(0, store.regionVersion(REGION));

        store.offer(CHUNK, true, 600.0); // normal -> slightly elevated
        assertEquals(1, store.regionVersion(REGION));

        store.offer(CHUNK, true, 650.0); // still slightly elevated, different value
        assertEquals(2, store.regionVersion(REGION));

        store.offer(CHUNK, true, 650.0); // unchanged: no repaint
        assertEquals(2, store.regionVersion(REGION));

        store.offer(CHUNK, true, 500.0); // back to normal
        assertEquals(3, store.regionVersion(REGION));
        assertEquals(Optional.of(ConcentrationClassification.NORMAL),
                store.classification(CHUNK, 100, ConcentrationThresholds.defaults()));
    }

    @Test
    void dirty_regions_are_reported_once() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        assertEquals(Set.of(REGION), store.drainDirtyRegions());
        assertTrue(store.drainDirtyRegions().isEmpty());
    }

    @Test
    void a_refresh_that_does_not_change_the_value_does_not_repaint() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        store.drainDirtyRegions();
        store.tick();
        assertFalse(store.offer(CHUNK, true, 600.0));
        assertTrue(store.drainDirtyRegions().isEmpty());
    }

    @Test
    void stale_values_stop_being_displayed_and_repaint_once() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        store.drainDirtyRegions();

        for (int i = 0; i < 30; i++) {
            store.tick();
        }
        store.expire(10);
        assertTrue(store.displayValue(CHUNK, 10).isEmpty(), "stale values are not displayed");
        assertEquals(Set.of(REGION), store.drainDirtyRegions(), "the stale colour must be removed");
        assertEquals(0, store.classification(CHUNK, 10, ConcentrationThresholds.defaults()).stream().count());

        // Expiring again is a no-op: one repaint, not one per sweep.
        store.expire(10);
        assertTrue(store.drainDirtyRegions().isEmpty());
    }

    @Test
    void a_refresh_after_expiry_makes_the_value_displayable_again() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        for (int i = 0; i < 30; i++) {
            store.tick();
        }
        store.expire(10);
        store.offer(CHUNK, true, 600.0);
        assertTrue(store.displayValue(CHUNK, 10).isPresent());
    }

    @Test
    void unavailable_entries_are_not_displayed() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        store.markUnavailable(CHUNK, true);
        assertTrue(store.displayValue(CHUNK, 100).isEmpty());
        assertTrue(store.classification(CHUNK, 100, ConcentrationThresholds.defaults()).isEmpty());
    }

    @Test
    void without_a_baseline_the_value_has_no_classification() {
        ClientConcentrationStore store = new ClientConcentrationStore(8);
        store.offer(CHUNK, true, 600.0);
        assertTrue(store.displayValue(CHUNK, 100).isPresent());
        assertTrue(store.classification(CHUNK, 100, ConcentrationThresholds.defaults()).isEmpty());
    }

    @Test
    void clearing_forgets_values_and_dirties_known_regions() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        store.drainDirtyRegions();
        store.clear();
        assertEquals(0, store.size());
        assertTrue(store.displayValue(CHUNK, 100).isEmpty());
        assertEquals(Set.of(REGION), store.drainDirtyRegions());
        assertEquals(0, store.regionVersion(REGION));
        assertEquals(500.0, store.baseline(), "a dimension change keeps the world's baseline");
        store.clearBaseline();
        assertTrue(Double.isNaN(store.baseline()));
    }

    @Test
    void dimension_and_world_isolation_hold_in_the_store() {
        ClientConcentrationStore store = newStore();
        ChunkCacheKey nether = new ChunkCacheKey("sp:world", "minecraft:the_nether", 34, -3);
        ChunkCacheKey otherWorld = new ChunkCacheKey("mp:server", "minecraft:overworld", 34, -3);
        store.offer(CHUNK, true, 600.0);
        store.offer(nether, true, 2000.0);
        store.offer(otherWorld, true, 900.0);
        assertEquals(600.0, store.displayValue(CHUNK, 100).getAsDouble());
        assertEquals(2000.0, store.displayValue(nether, 100).getAsDouble());
        assertEquals(900.0, store.displayValue(otherWorld, 100).getAsDouble());
    }

    @Test
    void purging_removes_only_old_entries() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        ChunkCacheKey recent = new ChunkCacheKey("sp:world", "minecraft:overworld", 40, 40);
        for (int i = 0; i < 100; i++) {
            store.tick();
        }
        store.offer(recent, true, 700.0);
        store.purge(50);
        assertEquals(1, store.size());
        assertTrue(store.displayValue(recent, 100).isPresent());
    }

    @Test
    void forgetting_a_chunk_dirties_its_region() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        store.drainDirtyRegions();
        store.forget(CHUNK);
        assertEquals(0, store.size());
        assertEquals(Set.of(REGION), store.drainDirtyRegions());
    }

    @Test
    void mark_all_regions_dirty_repaints_without_data_changes() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        store.drainDirtyRegions();
        store.markAllRegionsDirty();
        assertEquals(Set.of(REGION), store.drainDirtyRegions());
    }

    @Test
    void an_invalid_baseline_is_rejected() {
        ClientConcentrationStore store = new ClientConcentrationStore(8);
        store.setBaseline(Double.NaN); // accepted: "not supplied yet"
        try {
            store.setBaseline(-5.0);
            throw new AssertionError("negative baselines must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
