package org.bitwisemadness.tensuraxaerointegration.gametest;

import java.util.OptionalDouble;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;
import org.bitwisemadness.tensuraxaerointegration.config.ServerConfig;
import org.bitwisemadness.tensuraxaerointegration.server.ConcentrationServer;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationClassification;
import org.bitwisemadness.tensuraxaerointegration.tensura.TensuraBridge;
import org.bitwisemadness.tensuraxaerointegration.tiers.DimensionTier;
import org.bitwisemadness.tensuraxaerointegration.tiers.MagiculeTiers;
import org.bitwisemadness.tensuraxaerointegration.tiers.TierTable;

/**
 * Server-side checks against the real Tensura jar.
 *
 * <p>These run in a headless GameTest server with Tensura, ManasCore,
 * Architectury, GeckoLib, SmartBrainLib and TerraBlender on the run classpath,
 * so they exercise the actual {@code ChunkStorage} API rather than a stub. What
 * they deliberately do <b>not</b> cover is client rendering: no Xaero mod is
 * present in this run and none can be, because they are client-only.</p>
 *
 * <p>Keep {@code expectedTests} in {@code build.gradle} in sync with the number
 * of {@code @GameTest} methods here.</p>
 */
@GameTestHolder(TensuraXaeroIntegration.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ConcentrationGameTests {

    private ConcentrationGameTests() {
    }

    /** Tensura's configured baseline is reachable through the bridge. */
    @GameTest(template = "empty")
    public static void baseline_is_readable(GameTestHelper helper) {
        OptionalDouble baseline = TensuraBridge.baseline();
        if (baseline.isEmpty()) {
            helper.fail("Tensura's baseline could not be read");
            return;
        }
        if (!(baseline.getAsDouble() > 0.0)) {
            helper.fail("Baseline must be positive, was " + baseline.getAsDouble());
            return;
        }
        helper.succeed();
    }

    /** A loaded chunk answers with a finite, non-negative authoritative value. */
    @GameTest(template = "empty")
    public static void loaded_chunk_has_a_concentration(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos worldPos = helper.absolutePos(new BlockPos(1, 1, 1));
        int chunkX = SectionPos.blockToSectionCoord(worldPos.getX());
        int chunkZ = SectionPos.blockToSectionCoord(worldPos.getZ());
        if (!level.hasChunk(chunkX, chunkZ)) {
            helper.fail("The test chunk should already be loaded");
            return;
        }
        OptionalDouble value = TensuraBridge.read(level, chunkX, chunkZ);
        if (value.isEmpty()) {
            helper.fail("A loaded chunk should report a concentration");
            return;
        }
        if (!Double.isFinite(value.getAsDouble()) || value.getAsDouble() < 0.0) {
            helper.fail("Concentration was not a finite non-negative number: " + value.getAsDouble());
            return;
        }
        helper.succeed();
    }

    /** Reading an unloaded chunk reports unavailable and does not load it. */
    @GameTest(template = "empty")
    public static void unloaded_chunk_is_unavailable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos worldPos = helper.absolutePos(new BlockPos(1, 1, 1));
        int chunkX = SectionPos.blockToSectionCoord(worldPos.getX()) + 100_000;
        int chunkZ = SectionPos.blockToSectionCoord(worldPos.getZ()) + 100_000;
        if (level.hasChunk(chunkX, chunkZ)) {
            helper.fail("A far chunk was unexpectedly loaded; the test cannot prove the guard");
            return;
        }
        if (TensuraBridge.read(level, chunkX, chunkZ).isPresent()) {
            helper.fail("An unloaded chunk must report unavailable, not a value");
            return;
        }
        if (level.hasChunk(chunkX, chunkZ)) {
            helper.fail("Reading a concentration must never load a chunk");
            return;
        }
        helper.succeed();
    }

    /** The coordinator is started by the server lifecycle and ticks safely. */
    @GameTest(template = "empty")
    public static void coordinator_ticks_without_players(GameTestHelper helper) {
        if (ConcentrationServer.get().trackedPlayers() != 0) {
            helper.fail("A headless GameTest server must not have tracked players");
            return;
        }
        // Exercises the "no players, no work" early return; it must not throw.
        ConcentrationServer.get().onServerTick(helper.getLevel().getServer());
        if (ConcentrationServer.get().hasSubscriptions()) {
            helper.fail("No subscriptions should exist without players");
            return;
        }
        helper.succeed();
    }

    /**
     * The datapack reaches the server: it loads, describes the shipped dimensions,
     * and gives the hell dimension its own ladder instead of the default one.
     */
    @GameTest(template = "empty")
    public static void tier_datapack_is_loaded(GameTestHelper helper) {
        TierTable table = MagiculeTiers.get();
        if (table.describedDimensions().isEmpty()) {
            helper.fail("no dimension tiers were loaded; the datapack did not reach the server");
            return;
        }
        if (!table.describedDimensions().contains("minecraft:overworld")) {
            helper.fail("the overworld entry is missing from the loaded table: "
                    + table.describedDimensions());
            return;
        }
        DimensionTier overworld = table.forDimension("minecraft:overworld", 1.0).orElseThrow();
        if (overworld.baseline() != 500.0) {
            helper.fail("the overworld baseline should be 500.0, was " + overworld.baseline());
            return;
        }
        if (!overworld.classify(600.0).filter(band -> band != ConcentrationClassification.NORMAL).isPresent()) {
            helper.fail("600 should be an elevated band in the overworld, got " + overworld.classify(600.0));
            return;
        }
        DimensionTier hell = table.forDimension("tensura:hell", 1.0).orElseThrow();
        if (!(hell.baseline() > 1000.0)) {
            helper.fail("the hell entry should use its own high baseline, was " + hell.baseline());
            return;
        }
        helper.succeed();
    }

    /** The server config is attached and its bounds are usable. */
    @GameTest(template = "empty")
    public static void server_config_is_usable(GameTestHelper helper) {
        if (ServerConfig.reconcileBudgetChunks() <= 0) {
            helper.fail("reconcileBudgetChunksPerPlayer must be positive");
            return;
        }
        if (ServerConfig.maxSubscribedChunks() <= 0) {
            helper.fail("maxSubscribedChunksPerPlayer must be positive");
            return;
        }
        if (ServerConfig.maxSubscribeRadiusChunks() <= 0) {
            helper.fail("maxSubscribeRadiusChunks must be positive");
            return;
        }
        helper.succeed();
    }
}
