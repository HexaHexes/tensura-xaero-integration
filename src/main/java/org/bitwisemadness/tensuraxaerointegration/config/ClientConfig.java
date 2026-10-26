package org.bitwisemadness.tensuraxaerointegration.config;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationThresholds;
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

    private static final ModConfigSpec.DoubleValue SLIGHTLY_ELEVATED;
    private static final ModConfigSpec.DoubleValue MODERATELY_ELEVATED;
    private static final ModConfigSpec.DoubleValue HIGHLY_ELEVATED;
    private static final ModConfigSpec.DoubleValue EXCEPTIONALLY_ELEVATED;

    private static final ModConfigSpec.ConfigValue<String> SLIGHTLY_COLOR;
    private static final ModConfigSpec.DoubleValue SLIGHTLY_OPACITY;
    private static final ModConfigSpec.ConfigValue<String> MODERATELY_COLOR;
    private static final ModConfigSpec.DoubleValue MODERATELY_OPACITY;
    private static final ModConfigSpec.ConfigValue<String> HIGHLY_COLOR;
    private static final ModConfigSpec.DoubleValue HIGHLY_OPACITY;
    private static final ModConfigSpec.ConfigValue<String> EXCEPTIONALLY_COLOR;
    private static final ModConfigSpec.DoubleValue EXCEPTIONALLY_OPACITY;

    private static final ModConfigSpec.DoubleValue FALLBACK_BASELINE;

    private static final ModConfigSpec.IntValue STALE_AFTER_TICKS;
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

        builder.push("thresholds");
        builder.comment("Band boundaries as multiples of Tensura's configured baseline")
                .comment("(area_magicule_config: baseMagicule). Values must increase.")
                .comment("At or below 1.0x is 'normal' and draws nothing. These are")
                .comment("presentation bands, not Tensura gameplay rules.");
        SLIGHTLY_ELEVATED = builder
                .defineInRange("slightlyElevatedMultiplier", ConcentrationThresholds.defaults().slightlyElevatedMultiplier(),
                        1.0, 1000.0);
        MODERATELY_ELEVATED = builder
                .defineInRange("moderatelyElevatedMultiplier", ConcentrationThresholds.defaults().moderatelyElevatedMultiplier(),
                        1.0, 1000.0);
        HIGHLY_ELEVATED = builder
                .defineInRange("highlyElevatedMultiplier", ConcentrationThresholds.defaults().highlyElevatedMultiplier(),
                        1.0, 1000.0);
        EXCEPTIONALLY_ELEVATED = builder
                .defineInRange("exceptionallyElevatedMultiplier", ConcentrationThresholds.defaults().exceptionallyElevatedMultiplier(),
                        1.0, 1000.0);
        builder.pop();

        builder.push("palette");
        builder.comment("Colours are #RRGGBB strings; opacity is 0 (invisible) to 1 (opaque).")
                .comment("A chunk at baseline is never painted, whichever opacity is set here.");
        SLIGHTLY_COLOR = colour(builder, "slightlyElevatedColor", "#29B6F6");
        SLIGHTLY_OPACITY = opacity(builder, "slightlyElevatedOpacity", 0.10);
        MODERATELY_COLOR = colour(builder, "moderatelyElevatedColor", "#5C6BC0");
        MODERATELY_OPACITY = opacity(builder, "moderatelyElevatedOpacity", 0.20);
        HIGHLY_COLOR = colour(builder, "highlyElevatedColor", "#AB47BC");
        HIGHLY_OPACITY = opacity(builder, "highlyElevatedOpacity", 0.32);
        EXCEPTIONALLY_COLOR = colour(builder, "exceptionallyElevatedColor", "#EC407A");
        EXCEPTIONALLY_OPACITY = opacity(builder, "exceptionallyElevatedOpacity", 0.45);
        builder.pop();

        builder.push("baseline");
        FALLBACK_BASELINE = builder
                .comment("Baseline used only when the server cannot report Tensura's configured")
                .comment("value. 500 matches Tensura's shipped baseMagicule and is a fallback,")
                .comment("not a gameplay definition; it is never used while the server answers.")
                .defineInRange("fallback", 500.0, 1.0e-6, 1.0e12);
        builder.pop();

        builder.push("freshness");
        STALE_AFTER_TICKS = builder
                .comment("How long a value counts as current. After this many client ticks")
                .comment("without a refresh the chunk keeps its last known value, drawn dimmer")
                .comment("and labelled as last known, instead of pretending to be current.")
                .comment("600 ticks is 30 seconds at 20 tps.")
                .defineInRange("staleAfterTicks", 600, 20, 24000);
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

    private static ModConfigSpec.ConfigValue<String> colour(ModConfigSpec.Builder builder, String key, String fallback) {
        return builder.define(key, fallback, value -> value instanceof String text
                && OverlayPalette.parseRgbSafe(text).isPresent());
    }

    private static ModConfigSpec.DoubleValue opacity(ModConfigSpec.Builder builder, String key, double fallback) {
        return builder.defineInRange(key, fallback, 0.0, 1.0);
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

    public static double fallbackBaseline() {
        return FALLBACK_BASELINE.get();
    }

    public static int staleAfterTicks() {
        return STALE_AFTER_TICKS.get();
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

    /** Thresholds from config, falling back to the defaults on an invalid ordering. */
    public static ConcentrationThresholds thresholds() {
        try {
            return new ConcentrationThresholds(
                    SLIGHTLY_ELEVATED.get(), MODERATELY_ELEVATED.get(),
                    HIGHLY_ELEVATED.get(), EXCEPTIONALLY_ELEVATED.get());
        } catch (IllegalArgumentException invalid) {
            TensuraXaeroIntegration.LOGGER.warn(
                    "Client threshold multipliers must increase; using the defaults instead. {}", invalid.getMessage());
            return ConcentrationThresholds.defaults();
        }
    }

    /** Palette from config; an unparsable colour falls back to that band's default. */
    public static OverlayPalette palette() {
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
                OverlayPalette.defaults());
    }
}
