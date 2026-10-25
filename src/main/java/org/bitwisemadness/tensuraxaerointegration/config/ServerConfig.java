package org.bitwisemadness.tensuraxaerointegration.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side settings: what may be sent, how much work reconciliation may do,
 * and how fast a single chunk may be re-sent.
 *
 * <p>These are SERVER config values because they govern server work and server
 * disclosure. They live in the world's server config, so a pack can tune them
 * per world without touching clients.</p>
 *
 * <p>The defaults are deliberately conservative: a small subscription cap, a
 * radius that only covers chunks around the player, and a per-chunk minimum
 * interval so a fast-regenerating chunk cannot turn into a packet flood.</p>
 */
public final class ServerConfig {

    private static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue SYNC_ENABLED;
    private static final ModConfigSpec.IntValue RECONCILE_INTERVAL_TICKS;
    private static final ModConfigSpec.IntValue RECONCILE_BUDGET_CHUNKS;
    private static final ModConfigSpec.IntValue MAX_SUBSCRIBED_CHUNKS;
    private static final ModConfigSpec.IntValue MAX_SUBSCRIBE_RADIUS_CHUNKS;
    private static final ModConfigSpec.IntValue MIN_UPDATE_INTERVAL_TICKS;
    private static final ModConfigSpec.IntValue MAX_UPDATES_PER_PACKET;
    private static final ModConfigSpec.IntValue MIN_SUBSCRIBE_PACKET_INTERVAL_TICKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("sync");
        SYNC_ENABLED = builder
                .comment("Master switch for concentration synchronisation. When off, the server")
                .comment("sends no concentration data at all and the maps stay uncoloured.")
                .define("enabled", true);
        builder.pop();

        builder.push("subscriptions");
        MAX_SUBSCRIBED_CHUNKS = builder
                .comment("Most chunks one player may have subscribed at once. Bounds both the")
                .comment("server's bookkeeping and the data a client can hold.")
                .defineInRange("maxSubscribedChunksPerPlayer", 2048, 1, 65536);
        MAX_SUBSCRIBE_RADIUS_CHUNKS = builder
                .comment("Largest distance, in chunks, between the player and a chunk the server")
                .comment("will answer for. This is the hard limit that keeps a client from probing")
                .comment("chunks it is not standing among; it must be at least the client's")
                .comment("subscription radius or nothing is ever sent.")
                .defineInRange("maxSubscribeRadiusChunks", 16, 1, 64);
        MIN_SUBSCRIBE_PACKET_INTERVAL_TICKS = builder
                .comment("Minimum ticks between two subscription packets from one player.")
                .defineInRange("minSubscribePacketIntervalTicks", 5, 0, 200);
        builder.pop();

        builder.push("refresh");
        RECONCILE_INTERVAL_TICKS = builder
                .comment("Periodic reconciliation interval in ticks. Event-driven updates arrive")
                .comment("on Tensura's chunk tick; this sweep re-reads every subscription over")
                .comment("successive cycles to catch missed changes and unloaded chunks.")
                .defineInRange("reconcileIntervalTicks", 100, 1, 24000);
        RECONCILE_BUDGET_CHUNKS = builder
                .comment("Most subscribed chunks re-read per player per reconciliation cycle.")
                .comment("The cursor rotates, so every subscription is visited eventually.")
                .defineInRange("reconcileBudgetChunksPerPlayer", 256, 1, 8192);
        MIN_UPDATE_INTERVAL_TICKS = builder
                .comment("Minimum ticks between two sends for the same chunk. A change inside")
                .comment("the window is kept pending, not dropped.")
                .defineInRange("minUpdateIntervalTicks", 20, 0, 2400);
        MAX_UPDATES_PER_PACKET = builder
                .comment("Chunk updates batched into one packet.")
                .defineInRange("maxUpdatesPerPacket", 256, 1, 512);
        builder.pop();

        SPEC = builder.build();
    }

    private ServerConfig() {
    }

    /** Registers the {@code server} config file. */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
    }

    public static boolean syncEnabled() {
        return SYNC_ENABLED.get();
    }

    public static int reconcileIntervalTicks() {
        return RECONCILE_INTERVAL_TICKS.get();
    }

    public static int reconcileBudgetChunks() {
        return RECONCILE_BUDGET_CHUNKS.get();
    }

    public static int maxSubscribedChunks() {
        return MAX_SUBSCRIBED_CHUNKS.get();
    }

    public static int maxSubscribeRadiusChunks() {
        return MAX_SUBSCRIBE_RADIUS_CHUNKS.get();
    }

    public static int minUpdateIntervalTicks() {
        return MIN_UPDATE_INTERVAL_TICKS.get();
    }

    public static int maxUpdatesPerPacket() {
        return MAX_UPDATES_PER_PACKET.get();
    }

    public static int minSubscribePacketIntervalTicks() {
        return MIN_SUBSCRIBE_PACKET_INTERVAL_TICKS.get();
    }
}
