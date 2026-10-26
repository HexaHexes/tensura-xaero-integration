package org.bitwisemadness.tensuraxaerointegration.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bitwisemadness.tensuraxaerointegration.cache.ChunkCacheKey;
import org.bitwisemadness.tensuraxaerointegration.cache.RegionKey;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentrationStore.Observation;
import org.bitwisemadness.tensuraxaerointegration.client.ClientConcentrationStore.StoredValue;
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

    private static Optional<Observation> observe(ClientConcentrationStore store, ChunkCacheKey key, long stale) {
        return store.observe(key, stale, ConcentrationThresholds.defaults());
    }

    @Test
    void unmapped_values_are_never_stored() {
        ClientConcentrationStore store = newStore();
        assertFalse(store.offer(CHUNK, false, 4000.0));
        assertEquals(0, store.size());
        assertTrue(observe(store, CHUNK, 100).isEmpty());
        assertTrue(store.drainDirtyRegions().isEmpty(), "nothing to repaint");
    }

    @Test
    void mapped_values_are_stored_and_classified_against_the_baseline() {
        ClientConcentrationStore store = newStore();
        assertTrue(store.offer(CHUNK, true, 600.0));
        Observation observation = observe(store, CHUNK, 100).orElseThrow();
        assertEquals(600.0, observation.value());
        assertTrue(observation.live(), "a fresh value is live");
        assertEquals(Optional.of(ConcentrationClassification.SLIGHTLY_ELEVATED), observation.classification());
        assertTrue(observation.elevated());
    }

    @Test
    void a_baseline_value_is_classified_normal() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 500.0);
        Observation observation = observe(store, CHUNK, 100).orElseThrow();
        assertEquals(Optional.of(ConcentrationClassification.NORMAL), observation.classification());
        assertFalse(observation.elevated());
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
                observe(store, CHUNK, 100).orElseThrow().classification());
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
    void a_value_that_stops_being_refreshed_becomes_last_known_and_repaints_once() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        store.drainDirtyRegions();

        for (int i = 0; i < 30; i++) {
            store.tick();
        }
        store.expire(10);
        Observation observation = observe(store, CHUNK, 10).orElseThrow();
        assertEquals(600.0, observation.value(), "the observation itself is kept");
        assertFalse(observation.live(), "but it is no longer current");
        assertEquals(Set.of(REGION), store.drainDirtyRegions(), "the dim colour must be painted");

        // Expiring again is a no-op: one repaint, not one per sweep.
        store.expire(10);
        assertTrue(store.drainDirtyRegions().isEmpty());
    }

    @Test
    void a_refresh_after_demotion_makes_the_value_live_again() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        for (int i = 0; i < 30; i++) {
            store.tick();
        }
        store.expire(10);
        store.drainDirtyRegions();

        assertTrue(store.offer(CHUNK, true, 600.0), "going live again is a visible change");
        assertTrue(observe(store, CHUNK, 10).orElseThrow().live());
        assertEquals(Set.of(REGION), store.drainDirtyRegions());
    }

    @Test
    void the_freshness_window_follows_the_server_refresh_interval() {
        ClientConcentrationStore store = newStore();
        assertEquals(2L * ClientConcentrationStore.DEFAULT_SERVER_REFRESH_INTERVAL_TICKS, store.staleAfterTicks(),
                "the shipped cadence is assumed until the server announces its own");

        store.setServerRefreshInterval(1200);
        assertEquals(2400L, store.staleAfterTicks(), "a slow server buys its values a longer window");

        store.setServerRefreshInterval(20);
        assertEquals(ClientConcentrationStore.MIN_STALE_TICKS, store.staleAfterTicks(),
                "a fast server must not make values flicker between live and last known");
    }

    @Test
    void a_slow_server_no_longer_dims_a_value_the_old_fixed_window_would_have_expired() {
        ClientConcentrationStore store = newStore();
        store.setServerRefreshInterval(1200);
        store.offer(CHUNK, true, 600.0);

        for (int i = 0; i < 1000; i++) {
            store.tick();
        }
        store.expire(store.staleAfterTicks());
        assertTrue(observe(store, CHUNK, store.staleAfterTicks()).orElseThrow().live(),
                "1000 ticks is inside this server's 2400-tick window");

        for (int i = 0; i < 1500; i++) {
            store.tick();
        }
        store.expire(store.staleAfterTicks());
        assertFalse(observe(store, CHUNK, store.staleAfterTicks()).orElseThrow().live(),
                "past two confirmations the value is honestly last known");
    }

    @Test
    void an_invalid_refresh_interval_is_rejected() {
        ClientConcentrationStore store = newStore();
        for (int invalid : new int[] {0, -1}) {
            try {
                store.setServerRefreshInterval(invalid);
                throw new AssertionError("a non-positive interval must be rejected, was " + invalid);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    void clearing_the_baseline_forgets_the_server_cadence_too() {
        ClientConcentrationStore store = newStore();
        store.setServerRefreshInterval(1200);
        store.clearBaseline();
        assertEquals(ClientConcentrationStore.DEFAULT_SERVER_REFRESH_INTERVAL_TICKS,
                store.serverRefreshIntervalTicks(), "another world must not inherit this server's policy");
    }

    @Test
    void a_heartbeat_of_unchanged_values_keeps_a_settled_chunk_live() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        long staleAfter = 600;
        // The chunk sits at its ceiling and never changes; the server re-confirms
        // it every 200 ticks. Without those confirms every value would dim.
        for (int cycle = 0; cycle < 5; cycle++) {
            for (int i = 0; i < 200; i++) {
                store.tick();
            }
            assertFalse(store.offer(CHUNK, true, 600.0), "an unchanged value needs no repaint");
            store.expire(staleAfter);
            assertTrue(observe(store, CHUNK, staleAfter).orElseThrow().live(),
                    "a confirmed, unchanged value is still current after " + (cycle + 1) * 200 + " ticks");
        }
    }

    @Test
    void an_unavailable_answer_keeps_the_last_known_value() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        store.drainDirtyRegions();

        assertTrue(store.markUnavailable(CHUNK, true));
        Observation observation = observe(store, CHUNK, 100).orElseThrow();
        assertEquals(600.0, observation.value());
        assertFalse(observation.live(), "the server no longer vouches for it");
        assertEquals(Set.of(REGION), store.drainDirtyRegions());
    }

    @Test
    void an_unavailable_answer_with_nothing_held_records_unavailable() {
        ClientConcentrationStore store = newStore();
        store.markUnavailable(CHUNK, true);
        assertTrue(observe(store, CHUNK, 100).isEmpty());
        assertTrue(store.storedValues().isEmpty(), "nothing available is worth persisting");
    }

    @Test
    void unmapped_unavailable_answers_are_ignored() {
        ClientConcentrationStore store = newStore();
        assertFalse(store.markUnavailable(CHUNK, false));
        assertEquals(0, store.size());
    }

    @Test
    void demotion_keeps_the_value_without_a_server_answer() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 900.0);
        assertTrue(store.demote(CHUNK));
        assertFalse(observe(store, CHUNK, 100).orElseThrow().live());
        assertFalse(store.demote(CHUNK), "already last known: no further change");
    }

    @Test
    void without_a_baseline_the_value_has_no_classification() {
        ClientConcentrationStore store = new ClientConcentrationStore(8);
        store.offer(CHUNK, true, 600.0);
        Observation observation = observe(store, CHUNK, 100).orElseThrow();
        assertEquals(600.0, observation.value());
        assertTrue(observation.classification().isEmpty());
        assertFalse(observation.elevated());
    }

    @Test
    void the_configured_fallback_classifies_while_the_server_has_not_answered() {
        ClientConcentrationStore store = new ClientConcentrationStore(8);
        store.setFallbackBaseline(500.0);
        store.offer(CHUNK, true, 1500.0);

        Observation observation = observe(store, CHUNK, 100).orElseThrow();
        assertEquals(Optional.of(ConcentrationClassification.HIGHLY_ELEVATED), observation.classification(),
                "without a fallback every band would be missing and the overlay empty");
        assertTrue(observation.elevated());
    }

    @Test
    void a_server_baseline_always_wins_over_the_fallback() {
        ClientConcentrationStore store = new ClientConcentrationStore(8);
        store.setFallbackBaseline(500.0);
        store.setBaseline(2000.0);
        store.offer(CHUNK, true, 1500.0);

        assertEquals(2000.0, store.effectiveBaseline());
        assertEquals(Optional.of(ConcentrationClassification.NORMAL),
                observe(store, CHUNK, 100).orElseThrow().classification());
    }

    @Test
    void an_invalid_fallback_is_rejected() {
        ClientConcentrationStore store = new ClientConcentrationStore(8);
        store.setFallbackBaseline(Double.NaN); // accepted: fallback disabled
        assertTrue(Double.isNaN(store.effectiveBaseline()));
        try {
            store.setFallbackBaseline(0.0);
            throw new AssertionError("a zero fallback must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    void restored_history_is_last_known_and_never_overwrites_live_data() {
        ClientConcentrationStore store = newStore();
        ChunkCacheKey remembered = new ChunkCacheKey("sp:world", "minecraft:overworld", 40, 40);
        store.offer(CHUNK, true, 800.0);

        boolean changed = store.restoreHistory(
                List.of(new StoredValue(CHUNK, 600.0), new StoredValue(remembered, 900.0)), 500.0);

        assertTrue(changed);
        Observation live = observe(store, CHUNK, 100).orElseThrow();
        assertEquals(800.0, live.value(), "the server's value wins over the file");
        assertTrue(live.live());

        Observation replayed = observe(store, remembered, 100).orElseThrow();
        assertEquals(900.0, replayed.value());
        assertFalse(replayed.live(), "a replayed value is last known");
    }

    @Test
    void restored_baseline_only_fills_a_gap() {
        ClientConcentrationStore empty = new ClientConcentrationStore(8);
        empty.restoreHistory(List.of(), 500.0);
        assertEquals(500.0, empty.baseline());

        ClientConcentrationStore known = newStore();
        known.restoreHistory(List.of(), 1200.0);
        assertEquals(500.0, known.baseline(), "a known baseline is not replaced by the file");
    }

    @Test
    void stored_values_cover_available_entries_only() {
        ClientConcentrationStore store = newStore();
        ChunkCacheKey missing = new ChunkCacheKey("sp:world", "minecraft:overworld", 41, 41);
        store.offer(CHUNK, true, 600.0);
        store.markUnavailable(missing, true);
        assertEquals(List.of(new StoredValue(CHUNK, 600.0)), store.storedValues());
    }

    @Test
    void clearing_forgets_values_and_dirties_known_regions() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0);
        store.drainDirtyRegions();
        store.clear();
        assertEquals(0, store.size());
        assertTrue(observe(store, CHUNK, 100).isEmpty());
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
        assertEquals(600.0, observe(store, CHUNK, 100).orElseThrow().value());
        assertEquals(2000.0, observe(store, nether, 100).orElseThrow().value());
        assertEquals(900.0, observe(store, otherWorld, 100).orElseThrow().value());
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
