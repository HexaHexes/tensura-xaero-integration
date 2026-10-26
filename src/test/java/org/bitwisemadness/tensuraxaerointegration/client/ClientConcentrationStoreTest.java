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
import org.bitwisemadness.tensuraxaerointegration.concentration.OverlayPalette;
import org.junit.jupiter.api.Test;

class ClientConcentrationStoreTest {

    private static final ChunkCacheKey CHUNK =
            new ChunkCacheKey("sp:world", "minecraft:overworld", 34, -3);
    private static final RegionKey REGION = CHUNK.region();

    /** Band codes as the server sends them; see ConcentrationClassification. */
    private static final int NORMAL = ConcentrationClassification.NORMAL.code();
    private static final int SLIGHTLY = ConcentrationClassification.SLIGHTLY_ELEVATED.code();
    private static final int HIGHLY = ConcentrationClassification.HIGHLY_ELEVATED.code();
    private static final int UNCLASSIFIED = -1;

    private ClientConcentrationStore newStore() {
        return new ClientConcentrationStore(64);
    }

    private static Optional<Observation> observe(ClientConcentrationStore store, ChunkCacheKey key, long stale) {
        return store.observe(key, stale);
    }

    @Test
    void unmapped_values_are_never_stored() {
        ClientConcentrationStore store = newStore();
        assertFalse(store.offer(CHUNK, false, 4000.0, HIGHLY));
        assertEquals(0, store.size());
        assertTrue(observe(store, CHUNK, 100).isEmpty());
        assertTrue(store.drainDirtyRegions().isEmpty(), "nothing to repaint");
    }

    @Test
    void mapped_values_are_stored_with_the_server_band() {
        ClientConcentrationStore store = newStore();
        assertTrue(store.offer(CHUNK, true, 600.0, SLIGHTLY));
        Observation observation = observe(store, CHUNK, 100).orElseThrow();
        assertEquals(600.0, observation.value());
        assertTrue(observation.live(), "a fresh value is live");
        assertEquals(Optional.of(ConcentrationClassification.SLIGHTLY_ELEVATED), observation.classification(),
                "the band is the server's, not recomputed from a local baseline");
        assertTrue(observation.elevated());
    }

    @Test
    void an_unclassified_value_is_shown_without_a_band() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, UNCLASSIFIED);
        Observation observation = observe(store, CHUNK, 100).orElseThrow();
        assertEquals(600.0, observation.value(), "the number is still real and may be hovered");
        assertTrue(observation.classification().isEmpty(), "but no band is invented for it");
        assertFalse(observation.elevated());
    }

    @Test
    void a_normal_band_is_not_elevated() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 500.0, NORMAL);
        Observation observation = observe(store, CHUNK, 100).orElseThrow();
        assertEquals(Optional.of(ConcentrationClassification.NORMAL), observation.classification());
        assertFalse(observation.elevated());
    }

    @Test
    void a_rebanded_chunk_bumps_the_region_version_without_a_value_change() {
        ClientConcentrationStore store = newStore();
        assertEquals(0, store.regionVersion(REGION));

        store.offer(CHUNK, true, 600.0, NORMAL);
        assertEquals(1, store.regionVersion(REGION));

        // Same concentration, different tier after a datapack reload: the map has
        // to repaint even though the number did not move.
        assertTrue(store.offer(CHUNK, true, 600.0, SLIGHTLY));
        assertEquals(2, store.regionVersion(REGION));

        assertFalse(store.offer(CHUNK, true, 600.0, SLIGHTLY), "identical offer is not a change");
        assertEquals(2, store.regionVersion(REGION));
    }

    @Test
    void dirty_regions_are_reported_once() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        assertEquals(Set.of(REGION), store.drainDirtyRegions());
        assertTrue(store.drainDirtyRegions().isEmpty());
    }

    @Test
    void a_refresh_that_does_not_change_the_value_does_not_repaint() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        store.drainDirtyRegions();
        store.tick();
        assertFalse(store.offer(CHUNK, true, 600.0, SLIGHTLY));
        assertTrue(store.drainDirtyRegions().isEmpty());
    }

    @Test
    void a_value_that_stops_being_refreshed_becomes_last_known_and_repaints_once() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
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
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        for (int i = 0; i < 30; i++) {
            store.tick();
        }
        store.expire(10);
        store.drainDirtyRegions();

        assertTrue(store.offer(CHUNK, true, 600.0, SLIGHTLY), "going live again is a visible change");
        assertTrue(observe(store, CHUNK, 10).orElseThrow().live());
        assertEquals(Set.of(REGION), store.drainDirtyRegions());
    }

    @Test
    void a_heartbeat_of_unchanged_values_keeps_a_settled_chunk_live() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        long staleAfter = store.staleAfterTicks();
        // The chunk sits at its ceiling and never changes; the server re-confirms
        // it every interval. Without those confirms every value would dim.
        for (int cycle = 0; cycle < 5; cycle++) {
            for (int i = 0; i < store.serverRefreshIntervalTicks(); i++) {
                store.tick();
            }
            assertFalse(store.offer(CHUNK, true, 600.0, SLIGHTLY), "an unchanged value needs no repaint");
            store.expire(staleAfter);
            assertTrue(observe(store, CHUNK, staleAfter).orElseThrow().live(),
                    "a confirmed, unchanged value is still current after " + (cycle + 1) + " intervals");
        }
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
        store.offer(CHUNK, true, 600.0, SLIGHTLY);

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
    void the_datapack_palette_is_kept_until_the_client_overrides_it() {
        ClientConcentrationStore store = newStore();
        assertEquals(OverlayPalette.defaults(), store.serverPalette(), "the built-in palette is the floor");

        OverlayPalette fromServer = new OverlayPalette(
                new OverlayPalette.TierStyle(0x112233, 0.5),
                new OverlayPalette.TierStyle(0x445566, 0.6),
                new OverlayPalette.TierStyle(0x778899, 0.7),
                new OverlayPalette.TierStyle(0xAABBCC, 0.8));
        store.setServerPalette(fromServer);
        assertEquals(fromServer, store.serverPalette());
    }

    @Test
    void an_unavailable_answer_keeps_the_last_known_value() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
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
    void demotion_keeps_the_value_and_band_without_a_server_answer() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 900.0, SLIGHTLY);
        assertTrue(store.demote(CHUNK));
        Observation observation = observe(store, CHUNK, 100).orElseThrow();
        assertFalse(observation.live());
        assertEquals(Optional.of(ConcentrationClassification.SLIGHTLY_ELEVATED), observation.classification(),
                "a remembered value keeps the band it was observed with");
        assertFalse(store.demote(CHUNK), "already last known: no further change");
    }

    @Test
    void restored_history_is_last_known_and_never_overwrites_live_data() {
        ClientConcentrationStore store = newStore();
        ChunkCacheKey remembered = new ChunkCacheKey("sp:world", "minecraft:overworld", 40, 40);
        store.offer(CHUNK, true, 800.0, SLIGHTLY);

        boolean changed = store.restoreHistory(
                List.of(new StoredValue(CHUNK, 600.0, SLIGHTLY), new StoredValue(remembered, 900.0, HIGHLY)));

        assertTrue(changed);
        Observation live = observe(store, CHUNK, 100).orElseThrow();
        assertEquals(800.0, live.value(), "the server's value wins over the file");
        assertTrue(live.live());

        Observation replayed = observe(store, remembered, 100).orElseThrow();
        assertEquals(900.0, replayed.value());
        assertFalse(replayed.live(), "a replayed value is last known");
        assertEquals(Optional.of(ConcentrationClassification.HIGHLY_ELEVATED), replayed.classification(),
                "the stored band travels with the value");
    }

    @Test
    void a_restored_row_without_a_band_is_not_reclassified() {
        ClientConcentrationStore store = newStore();
        ChunkCacheKey remembered = new ChunkCacheKey("sp:world", "minecraft:overworld", 40, 40);
        store.restoreHistory(List.of(new StoredValue(remembered, 900.0, UNCLASSIFIED)));

        Observation replayed = observe(store, remembered, 100).orElseThrow();
        assertEquals(900.0, replayed.value());
        assertTrue(replayed.classification().isEmpty(), "an old row is shown uncoloured, not re-banded");
    }

    @Test
    void stored_values_cover_available_entries_only() {
        ClientConcentrationStore store = newStore();
        ChunkCacheKey missing = new ChunkCacheKey("sp:world", "minecraft:overworld", 41, 41);
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        store.markUnavailable(missing, true);
        assertEquals(List.of(new StoredValue(CHUNK, 600.0, SLIGHTLY)), store.storedValues());
    }

    @Test
    void clearing_forgets_values_and_dirties_known_regions() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        store.drainDirtyRegions();
        store.clear();
        assertEquals(0, store.size());
        assertTrue(observe(store, CHUNK, 100).isEmpty());
        assertEquals(Set.of(REGION), store.drainDirtyRegions());
        assertEquals(0, store.regionVersion(REGION));
        assertEquals(OverlayPalette.defaults(), store.serverPalette(), "a dimension change keeps the world's palette");
        store.clearServerInfo();
        assertEquals(ClientConcentrationStore.DEFAULT_SERVER_REFRESH_INTERVAL_TICKS,
                store.serverRefreshIntervalTicks());
    }

    @Test
    void dimension_and_world_isolation_hold_in_the_store() {
        ClientConcentrationStore store = newStore();
        ChunkCacheKey nether = new ChunkCacheKey("sp:world", "minecraft:the_nether", 34, -3);
        ChunkCacheKey otherWorld = new ChunkCacheKey("mp:server", "minecraft:overworld", 34, -3);
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        store.offer(nether, true, 2000.0, HIGHLY);
        store.offer(otherWorld, true, 900.0, NORMAL);
        assertEquals(600.0, observe(store, CHUNK, 100).orElseThrow().value());
        assertEquals(2000.0, observe(store, nether, 100).orElseThrow().value());
        assertEquals(900.0, observe(store, otherWorld, 100).orElseThrow().value());
    }

    @Test
    void forgetting_a_chunk_dirties_its_region() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        store.drainDirtyRegions();
        store.forget(CHUNK);
        assertEquals(0, store.size());
        assertEquals(Set.of(REGION), store.drainDirtyRegions());
    }

    @Test
    void mark_all_regions_dirty_repaints_without_data_changes() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        store.drainDirtyRegions();
        store.markAllRegionsDirty();
        assertEquals(Set.of(REGION), store.drainDirtyRegions());
    }
}
