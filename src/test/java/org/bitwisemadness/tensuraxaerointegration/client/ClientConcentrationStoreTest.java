package org.bitwisemadness.tensuraxaerointegration.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** Wall clock the store measures freshness against, in ms. */
    private long clock = 1_000_000L;

    private ClientConcentrationStore newStore() {
        return new ClientConcentrationStore(64, () -> this.clock);
    }

    private void advance(long millis) {
        this.clock += millis;
    }

    private static Optional<Observation> observe(ClientConcentrationStore store, ChunkCacheKey key, long window) {
        return store.observe(key, window);
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
        advance(50);
        assertFalse(store.offer(CHUNK, true, 600.0, SLIGHTLY));
        assertTrue(store.drainDirtyRegions().isEmpty());
    }

    @Test
    void a_value_that_stops_being_refreshed_becomes_last_known_and_repaints_once() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        store.drainDirtyRegions();

        advance(5_000);
        store.expire(1_000);
        Observation observation = observe(store, CHUNK, 1_000).orElseThrow();
        assertEquals(600.0, observation.value(), "the observation itself is kept");
        assertFalse(observation.live(), "but it is no longer current");
        assertEquals(Set.of(REGION), store.drainDirtyRegions(), "the dim colour must be painted");

        // Expiring again is a no-op: one repaint, not one per sweep.
        store.expire(1_000);
        assertTrue(store.drainDirtyRegions().isEmpty());
    }

    @Test
    void expiry_names_the_chunk_it_demoted_and_the_real_gap() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        assertTrue(store.expire(1_000).isEmpty(), "nothing has expired yet");

        advance(5_000);
        ClientConcentrationStore.Expiry expiry = store.expire(1_000).orElseThrow();
        assertEquals(1, expiry.count(), "one value dimmed");
        assertEquals(CHUNK, expiry.oldest(), "the caller needs the chunk to report why it dimmed");
        // The gap is measured before the entry is demoted; a demoted entry keeps
        // its value but no stamp, so reading it afterwards reports the epoch.
        assertEquals(5_000, expiry.oldestAgeMillis(), "the reported gap is the real one, not the clock's value");
        assertTrue(store.expire(1_000).isEmpty(), "it is already last known");
    }

    @Test
    void a_sweep_reports_how_many_values_went_at_once() {
        ClientConcentrationStore store = newStore();
        ChunkCacheKey second = new ChunkCacheKey("sp:world", "minecraft:overworld", 35, -3);
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        advance(2_000);
        store.offer(second, true, 600.0, SLIGHTLY);
        store.drainDirtyRegions();

        advance(10_000);
        ClientConcentrationStore.Expiry expiry = store.expire(1_000).orElseThrow();
        assertEquals(2, expiry.count(), "a sweep that demotes a crowd is reported as such");
        assertEquals(CHUNK, expiry.oldest(), "the value that had gone longest is the one named");
        assertEquals(12_000, expiry.oldestAgeMillis());
    }

    @Test
    void time_spent_paused_does_not_age_a_value() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        long window = store.staleAfterMillis();

        // A five-minute pause menu: the wall clock ran, the server did not.
        advance(300_000);
        store.addPausedMillis(300_000);
        assertTrue(observe(store, CHUNK, window).orElseThrow().live(),
                "a paused server cannot confirm anything, so nothing expired");
        assertTrue(store.expire(window).isEmpty());
        assertEquals(300_000, store.pausedMillis());

        // Unpaused time still counts.
        advance(ClientConcentrationStore.MIN_STALE_MILLIS + window);
        assertFalse(observe(store, CHUNK, window).orElseThrow().live(),
                "real silence after the pause is still honest staleness");
        assertEquals(1, store.expire(window).orElseThrow().count());
    }

    @Test
    void a_negative_pause_duration_is_ignored() {
        ClientConcentrationStore store = newStore();
        store.addPausedMillis(-5_000);
        assertEquals(0, store.pausedMillis(), "a clock going backwards must not credit the future");
    }

    @Test
    void clearing_server_info_forgets_paused_time() {
        ClientConcentrationStore store = newStore();
        store.addPausedMillis(4_000);
        assertEquals(4_000, store.pausedMillis());
        store.clearServerInfo();
        assertEquals(0, store.pausedMillis(), "paused time belongs to the session that ended");
    }

    @Test
    void a_refresh_after_demotion_makes_the_value_live_again() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        advance(5_000);
        store.expire(1_000);
        store.drainDirtyRegions();

        assertTrue(store.offer(CHUNK, true, 600.0, SLIGHTLY), "going live again is a visible change");
        assertTrue(observe(store, CHUNK, 1_000).orElseThrow().live());
        assertEquals(Set.of(REGION), store.drainDirtyRegions());
    }

    @Test
    void a_heartbeat_of_unchanged_values_keeps_a_settled_chunk_live() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        long window = store.staleAfterMillis();
        // The chunk sits at its ceiling and never changes; the server re-confirms it
        // once per interval. Without those confirms every value would dim.
        for (int cycle = 0; cycle < 5; cycle++) {
            advance(store.serverRefreshMillis());
            assertFalse(store.offer(CHUNK, true, 600.0, SLIGHTLY), "an unchanged value needs no repaint");
            store.expire(window);
            assertTrue(observe(store, CHUNK, window).orElseThrow().live(),
                    "a confirmed, unchanged value is still current after " + (cycle + 1) + " intervals");
        }
    }

    @Test
    void the_freshness_window_is_three_server_confirmations() {
        ClientConcentrationStore store = newStore();
        assertEquals(ClientConcentrationStore.DEFAULT_SERVER_REFRESH_MILLIS * 3, store.staleAfterMillis(),
                "the shipped 200-tick cadence is assumed until the server announces its own");

        store.setServerRefreshInterval(200, 30_000);
        assertEquals(90_000, store.staleAfterMillis(), "a server that needs 30 s per beat gets a 90 s window");
        assertEquals(30_000, store.serverRefreshMillis());
        assertEquals(200, store.serverRefreshIntervalTicks());

        store.setServerRefreshInterval(20, 1_000);
        assertEquals(ClientConcentrationStore.MIN_STALE_MILLIS, store.staleAfterMillis(),
                "a fast server must not make values flicker between live and last known");
    }

    @Test
    void a_stalled_server_no_longer_dims_a_value_it_is_still_confirming() {
        ClientConcentrationStore store = newStore();
        store.setServerRefreshInterval(200, 10_000); // nominal cadence
        store.offer(CHUNK, true, 600.0, SLIGHTLY);
        long window = store.staleAfterMillis(); // 30 s

        advance(25_000); // a 25 s stall between two confirms
        assertTrue(observe(store, CHUNK, window).orElseThrow().live(),
                "three beats of slack absorb a multi-second stall");

        advance(10_000); // 35 s total: two beats missed
        store.expire(window);
        assertFalse(observe(store, CHUNK, window).orElseThrow().live(),
                "past the window the value is honestly last known");
    }

    @Test
    void an_invalid_refresh_interval_is_rejected() {
        ClientConcentrationStore store = newStore();
        for (int invalid : new int[] {0, -1}) {
            try {
                store.setServerRefreshInterval(invalid, 10_000);
                throw new AssertionError("a non-positive tick interval must be rejected, was " + invalid);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
        try {
            store.setServerRefreshInterval(200, 0);
            throw new AssertionError("a non-positive promise must be rejected");
        } catch (IllegalArgumentException expected) {
            // expected
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
    }

    @Test
    void clearing_the_server_info_resets_the_cadence_and_palette() {
        ClientConcentrationStore store = newStore();
        store.setServerRefreshInterval(1200, 60_000);
        store.setServerPalette(new OverlayPalette(
                new OverlayPalette.TierStyle(0x111111, 0.1),
                new OverlayPalette.TierStyle(0x222222, 0.2),
                new OverlayPalette.TierStyle(0x333333, 0.3),
                new OverlayPalette.TierStyle(0x444444, 0.4)));

        store.clearServerInfo();

        assertEquals(ClientConcentrationStore.DEFAULT_SERVER_REFRESH_MILLIS, store.serverRefreshMillis(),
                "another world must not inherit this server's cadence");
        assertEquals(OverlayPalette.defaults(), store.serverPalette());
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

    /**
     * The paint read is what the highlighters use per visible chunk, so it has to
     * agree with {@link ClientConcentrationStore#observe} about everything it
     * reports - band and liveness - while carrying no number.
     */
    @Test
    void paint_state_agrees_with_the_observation_it_replaces() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, SLIGHTLY);

        int state = store.paintState(CHUNK, 100);
        Observation observation = observe(store, CHUNK, 100).orElseThrow();

        assertEquals(ConcentrationClassification.SLIGHTLY_ELEVATED,
                ClientConcentrationStore.bandOf(state));
        assertEquals(observation.classification().orElse(null), ClientConcentrationStore.bandOf(state));
        assertEquals(observation.live(), ClientConcentrationStore.liveOf(state));
    }

    @Test
    void a_chunk_with_nothing_stored_has_no_paint_state() {
        ClientConcentrationStore store = newStore();
        assertEquals(ClientConcentrationStore.NO_PAINT, store.paintState(CHUNK, 100));
    }

    @Test
    void an_unavailable_chunk_has_no_paint_state() {
        ClientConcentrationStore store = newStore();
        store.markUnavailable(CHUNK, true);
        assertEquals(ClientConcentrationStore.NO_PAINT, store.paintState(CHUNK, 100),
                "the pack said \"no value\" here, so there is nothing to paint");
    }

    @Test
    void a_stale_value_paints_as_last_known_with_its_band_intact() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, HIGHLY);
        advance(500);

        int state = store.paintState(CHUNK, 100);
        assertFalse(ClientConcentrationStore.liveOf(state),
                "a value past the window must not paint as current");
        assertEquals(ConcentrationClassification.HIGHLY_ELEVATED,
                ClientConcentrationStore.bandOf(state), "the dimmed colour still needs its band");
    }

    @Test
    void an_unclassified_value_still_has_a_paint_state() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, UNCLASSIFIED);

        int state = store.paintState(CHUNK, 100);
        assertNotEquals(ClientConcentrationStore.NO_PAINT, state, "there is still a line to hover");
        assertTrue(ClientConcentrationStore.liveOf(state));
        assertNull(ClientConcentrationStore.bandOf(state));
    }

    @Test
    void a_band_this_client_does_not_know_paints_uncoloured() {
        ClientConcentrationStore store = newStore();
        store.offer(CHUNK, true, 600.0, 99);

        int state = store.paintState(CHUNK, 100);
        assertNotEquals(ClientConcentrationStore.NO_PAINT, state);
        assertNull(ClientConcentrationStore.bandOf(state),
                "an unknown band is shown uncoloured, never guessed");
    }
}
