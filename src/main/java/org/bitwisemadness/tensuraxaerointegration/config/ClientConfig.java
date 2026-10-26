package org.bitwisemadness.tensuraxaerointegration.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.bitwisemadness.tensuraxaerointegration.concentration.OverlayPalette;

/**
 * Client-side settings: what is drawn, how it looks, and how much data the client
 * asks for.
 *
 * <p>Everything visual lives here because it only affects this client's screen.
 * The thresholds are multipliers of the authoritative baseline rather than
 * absolute numbers, so a server that changes {@code baseMagicule} keeps the
 * bands meaningful without every player editing their config.</p>
 */
public final class ClientConfig {

    private static final ModConfigSpec SPEC;

    private static final ModConfigSpec.BooleanValue MINIMAP_OVERLAY;
    private static final ModConfigSpec.BooleanValue WORLD_MAP_OVERLAY;
    private static final ModConfigSpec.BooleanValue WORLD_MAP_HOVER;

    private static final ModConfigSpec.ConfigValue<String> SLIGHTLY_COLOR;
    private static final ModConfigSpec.DoubleValue SLIGHTLY_OPACITY;
    private static final ModConfigSpec.ConfigValue<String> MODERATELY_COLOR;
    private static final ModConfigSpec.DoubleValue MODERATELY_OPACITY;
    private static final ModConfigSpec.ConfigValue<String> HIGHLY_COLOR;
    private static final ModConfigSpec.DoubleValue HIGHLY_OPACITY;
    private static final ModConfigSpec.ConfigValue<String> EXCEPTIONALLY_COLOR;
    private static final ModConfigSpec.DoubleValue EXCEPTIONALLY_OPACITY;

    private static final ModConfigSpec.DoubleValue HISTORICAL_OPACITY_SCALE;
    private static final ModConfigSpec.BooleanValue PERSISTENCE_ENABLED;
    private static final ModConfigSpec.IntValue PERSISTED_ENTRIES_MAX;

    private static final ModConfigSpec.IntValue SUBSCRIBE_RADIUS_CHUNKS;
    private static final ModConfigSpec.IntValue SUBSCRIBE_INTERVAL_TICKS;
    private static final ModConfigSpec.IntValue SUBSCRIBE_BATCH_SIZE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("overlays");
        MINIMAP_OVERLAY = builder
                .comment("Draw the concentration overlay on Xaero's Minimap.")
                .define("minimap", true);
        WORLD_MAP_OVERLAY = builder
                .comment("Draw the concentration overlay on Xaero's World Map.")
                .define("worldMap", true);
        WORLD_MAP_HOVER = builder
                .comment("Show the hovered chunk's concentration as a tooltip on Xaero's World Map.")
                .define("worldMapHover", true);
        builder.pop();

        builder.push("palette");
        builder.comment("Leave a colour empty to use the server's datapack palette, which is what")
                .comment("the pack intends for its dimensions. Set one to override that band")
                .comment("locally, e.g. for contrast or colour-blindness.")
                .comment("Opacity is 0 (invisible) to 1 (opaque); -1 means 'use the datapack value'.")
                .comment("A chunk at its dimension's normal concentration is never painted.");
        SLIGHTLY_COLOR = colour(builder, "slightlyElevatedColor", "");
        SLIGHTLY_OPACITY = opacity(builder, "slightlyElevatedOpacity", -1.0);
        MODERATELY_COLOR = colour(builder, "moderatelyElevatedColor", "");
        MODERATELY_OPACITY = opacity(builder, "moderatelyElevatedOpacity", -1.0);
        HIGHLY_COLOR = colour(builder, "highlyElevatedColor", "");
        HIGHLY_OPACITY = opacity(builder, "highlyElevatedOpacity", -1.0);
        EXCEPTIONALLY_COLOR = colour(builder, "exceptionallyElevatedColor", "");
        EXCEPTIONALLY_OPACITY = opacity(builder, "exceptionallyElevatedOpacity", -1.0);
        builder.pop();

        builder.push("freshness");
        builder.comment("How long a value counts as current is NOT set here: it is derived from")
                .comment("the server's own refresh interval (twice that interval), so the two")
                .comment("cannot disagree. A value past that window keeps its number, is drawn")
                .comment("dimmer and is labelled last known. The server's interval is logged on")
                .comment("join.");
        HISTORICAL_OPACITY_SCALE = builder
                .comment("Opacity multiplier for a last known value, relative to its band.")
                .comment("0 hides remembered values on the map (they still answer the hover")
                .comment("line), 1 draws them exactly as bright as a current value.")
                .defineInRange("historicalOpacityScale", 0.45, 0.0, 1.0);
        builder.pop();

        builder.push("persistence");
        PERSISTENCE_ENABLED = builder
                .comment("Remember concentrations between sessions, per world, so chunks that")
                .comment("are no longer loaded still show what was last observed there.")
                .comment("Only values for chunks your maps have mapped are ever stored.")
                .define("enabled", true);
        PERSISTED_ENTRIES_MAX = builder
                .comment("Most chunks remembered per world. Bounds both the file and memory;")
                .comment("the least recently seen chunks are dropped first.")
                .defineInRange("maxEntriesPerWorld", 20000, 0, 250000);
        builder.pop();

        builder.push("subscription");
        SUBSCRIBE_RADIUS_CHUNKS = builder
                .comment("Radius in chunks around the player for which mapped chunks are")
                .comment("requested. The server caps this independently and only answers for")
                .comment("chunks it currently has loaded.")
                .defineInRange("radiusChunks", 10, 1, 32);
        SUBSCRIBE_INTERVAL_TICKS = builder
                .comment("How often the mapped-chunk set is recomputed and sent.")
                .defineInRange("intervalTicks", 20, 1, 600);
        SUBSCRIBE_BATCH_SIZE = builder
                .comment("Chunk entries per subscription packet.")
                .defineInRange("batchSize", 256, 1, 512);
        builder.pop();

        SPEC = builder.build();
    }

    private ClientConfig() {
    }

    /** An empty string means "inherit this band's colour from the server". */
    private static ModConfigSpec.ConfigValue<String> colour(ModConfigSpec.Builder builder, String key, String fallback) {
        return builder.define(key, fallback, value -> value instanceof String text
                && (text.isBlank() || OverlayPalette.parseRgbSafe(text).isPresent()));
    }

    /** {@code -1} means "inherit this band's opacity from the server". */
    private static ModConfigSpec.DoubleValue opacity(ModConfigSpec.Builder builder, String key, double fallback) {
        return builder.defineInRange(key, fallback, -1.0, 1.0);
    }

    /** Registers the {@code client} config file. */
    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, SPEC);
    }

    public static boolean minimapOverlayEnabled() {
        return MINIMAP_OVERLAY.get();
    }

    public static boolean worldMapOverlayEnabled() {
        return WORLD_MAP_OVERLAY.get();
    }

    public static boolean worldMapHoverEnabled() {
        return WORLD_MAP_HOVER.get();
    }

    /** How bright a last-known value is drawn, as a fraction of its band's opacity. */
    public static double historicalOpacityScale() {
        return HISTORICAL_OPACITY_SCALE.get();
    }

    public static boolean persistenceEnabled() {
        return PERSISTENCE_ENABLED.get();
    }

    /** Most chunks remembered per world, and therefore the in-memory capacity too. */
    public static int persistedEntriesMax() {
        return PERSISTED_ENTRIES_MAX.get();
    }

    public static int subscribeRadiusChunks() {
        return SUBSCRIBE_RADIUS_CHUNKS.get();
    }

    public static int subscribeIntervalTicks() {
        return SUBSCRIBE_INTERVAL_TICKS.get();
    }

    public static int subscribeBatchSize() {
        return Math.min(512, Math.max(1, SUBSCRIBE_BATCH_SIZE.get()));
    }

    /**
     * The palette to paint with: the server's (datapack) palette, with any band
     * this client's config names overriding it.
     *
     * <p>An empty colour or an opacity of {@code -1} leaves that band as the
     * server sent it, which is why the shipped config overrides nothing.</p>
     */
    public static OverlayPalette palette(OverlayPalette serverPalette) {
        return OverlayPalette.fromConfig(
                index -> switch (index) {
                    case 0 -> SLIGHTLY_COLOR.get();
                    case 1 -> MODERATELY_COLOR.get();
                    case 2 -> HIGHLY_COLOR.get();
                    default -> EXCEPTIONALLY_COLOR.get();
                },
                index -> switch (index) {
                    case 0 -> SLIGHTLY_OPACITY.get();
                    case 1 -> MODERATELY_OPACITY.get();
                    case 2 -> HIGHLY_OPACITY.get();
                    default -> EXCEPTIONALLY_OPACITY.get();
                },
                serverPalette == null ? OverlayPalette.defaults() : serverPalette);
    }
}
