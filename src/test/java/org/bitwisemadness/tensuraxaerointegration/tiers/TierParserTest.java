package org.bitwisemadness.tensuraxaerointegration.tiers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationClassification;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationThresholds;
import org.bitwisemadness.tensuraxaerointegration.concentration.OverlayPalette;
import org.junit.jupiter.api.Test;

class TierParserTest {

    private static TierParser.Result parse(String... nameThenJson) {
        Map<String, JsonElement> files = new LinkedHashMap<>();
        for (int i = 0; i < nameThenJson.length; i += 2) {
            files.put(nameThenJson[i], JsonParser.parseString(nameThenJson[i + 1]));
        }
        return TierParser.parse(files);
    }

    @Test
    void a_dimension_file_uses_the_default_ladder() {
        TierParser.Result result = parse("overworld",
                "{\"dimensions\":[\"minecraft:overworld\"],\"baseline\":500.0}");

        assertTrue(result.clean(), () -> "unexpected problems: " + result.problems());
        DimensionTier tier = result.table().forDimension("minecraft:overworld", 1.0).orElseThrow();
        assertEquals(500.0, tier.baseline());
        assertEquals(ConcentrationThresholds.defaults(), tier.bands());
        assertEquals(Optional.of(ConcentrationClassification.SLIGHTLY_ELEVATED), tier.classify(600.0));
        assertEquals(Optional.of(ConcentrationClassification.NORMAL), tier.classify(500.0));
    }

    @Test
    void a_per_dimension_ladder_replaces_the_defaults() {
        TierParser.Result result = parse("hell", """
                {"dimensions":["tensura:hell"],"baseline":100500.0,
                 "bands":{"slightly_elevated":1.005,"moderately_elevated":1.01,
                          "highly_elevated":1.02,"exceptionally_elevated":1.03}}
                """);

        assertTrue(result.clean(), () -> "unexpected problems: " + result.problems());
        DimensionTier tier = result.table().forDimension("tensura:hell", 1.0).orElseThrow();
        assertEquals(100500.0, tier.baseline());
        assertEquals(Optional.of(ConcentrationClassification.NORMAL), tier.classify(100500.0));
        assertEquals(Optional.of(ConcentrationClassification.SLIGHTLY_ELEVATED), tier.classify(100700.0));
        assertEquals(Optional.of(ConcentrationClassification.EXCEPTIONALLY_ELEVATED), tier.classify(104000.0));
    }

    @Test
    void one_file_can_describe_several_dimensions() {
        TierParser.Result result = parse("end",
                "{\"dimensions\":[\"minecraft:the_end\",\"minecraft:small_end_islands\"],\"baseline\":750.0}");

        assertTrue(result.clean());
        assertEquals(2, result.table().describedDimensions().size());
        assertTrue(result.table().forDimension("minecraft:small_end_islands", 1.0).isPresent());
    }

    @Test
    void a_palette_file_sets_the_default_colours() {
        TierParser.Result result = parse("palette", """
                {"palette":{"slightly_elevated":{"color":"#010203","opacity":0.5}}}
                """);

        assertTrue(result.clean(), () -> "unexpected problems: " + result.problems());
        OverlayPalette palette = result.table().palette();
        assertEquals(new OverlayPalette.TierStyle(0x010203, 0.5), palette.slightlyElevated());
        assertEquals(OverlayPalette.defaults().moderatelyElevated(), palette.moderatelyElevated(),
                "bands the file does not mention keep their defaults");
    }

    @Test
    void a_partial_ladder_is_reported_and_ignored() {
        TierParser.Result result = parse("broken", """
                {"dimensions":["minecraft:overworld"],"baseline":500.0,
                 "bands":{"slightly_elevated":1.25}}
                """);

        assertFalse(result.clean());
        DimensionTier tier = result.table().forDimension("minecraft:overworld", 1.0).orElseThrow();
        assertEquals(ConcentrationThresholds.defaults(), tier.bands(),
                "the dimension keeps a usable ladder rather than losing its colours");
    }

    @Test
    void a_non_increasing_ladder_is_rejected() {
        TierParser.Result result = parse("broken", """
                {"dimensions":["minecraft:overworld"],"baseline":500.0,
                 "bands":{"slightly_elevated":2.0,"moderately_elevated":2.0,
                          "highly_elevated":4.0,"exceptionally_elevated":8.0}}
                """);

        assertFalse(result.clean());
        assertEquals(ConcentrationThresholds.defaults(),
                result.table().forDimension("minecraft:overworld", 1.0).orElseThrow().bands());
    }

    @Test
    void a_missing_or_impossible_baseline_skips_the_file() {
        TierParser.Result missing = parse("a", "{\"dimensions\":[\"minecraft:overworld\"]}");
        assertFalse(missing.clean());
        assertTrue(missing.table().describedDimensions().isEmpty());

        TierParser.Result zero = parse("b", "{\"dimensions\":[\"minecraft:overworld\"],\"baseline\":0.0}");
        assertFalse(zero.clean());
        assertTrue(zero.table().describedDimensions().isEmpty());
    }

    @Test
    void an_invalid_dimension_id_is_reported_without_losing_the_valid_ones() {
        TierParser.Result result = parse("mixed",
                "{\"dimensions\":[\"minecraft:overworld\",\"Not A Dimension\"],\"baseline\":500.0}");

        assertFalse(result.clean());
        assertTrue(result.table().forDimension("minecraft:overworld", 1.0).isPresent());
        assertTrue(result.table().describedDimensions().stream().noneMatch(id -> id.contains(" ")));
    }

    @Test
    void one_broken_file_does_not_empty_the_table() {
        TierParser.Result result = parse(
                "good", "{\"dimensions\":[\"minecraft:overworld\"],\"baseline\":500.0}",
                "bad", "{\"dimensions\":\"nope\",\"baseline\":500.0}");

        assertFalse(result.clean());
        assertTrue(result.table().forDimension("minecraft:overworld", 1.0).isPresent());
    }

    @Test
    void a_duplicate_dimension_is_reported_and_the_last_file_wins() {
        TierParser.Result result = parse(
                "a_nether", "{\"dimensions\":[\"minecraft:the_nether\"],\"baseline\":600.0}",
                "b_nether", "{\"dimensions\":[\"minecraft:the_nether\"],\"baseline\":700.0}");

        assertFalse(result.clean());
        assertEquals(700.0, result.table().forDimension("minecraft:the_nether", 1.0).orElseThrow().baseline());
    }

    @Test
    void palette_rejects_bands_that_are_never_painted() {
        TierParser.Result result = parse("palette", """
                {"palette":{"normal":{"color":"#010203","opacity":0.5},
                            "not_a_band":{"color":"#010203","opacity":0.5}}}
                """);

        assertEquals(2, result.problems().size());
        assertEquals(OverlayPalette.defaults(), result.table().palette());
    }

    @Test
    void an_unlisted_dimension_uses_the_fallback_baseline() {
        TierParser.Result result = parse("overworld",
                "{\"dimensions\":[\"minecraft:overworld\"],\"baseline\":500.0}");
        TierTable table = result.table();

        DimensionTier fallback = table.forDimension("some:pack_dimension", 500.0).orElseThrow();
        assertEquals(500.0, fallback.baseline());
        assertEquals(ConcentrationThresholds.defaults(), fallback.bands());
    }

    @Test
    void an_unlisted_dimension_is_unclassified_when_nothing_can_band_it() {
        TierTable table = parse("overworld",
                "{\"dimensions\":[\"minecraft:overworld\"],\"baseline\":500.0}").table();

        assertTrue(table.forDimension("some:pack_dimension", Double.NaN).isEmpty(),
                "an unusable fallback must leave the chunk unbanded, not guess a tier");
    }

    @Test
    void an_empty_directory_is_a_valid_empty_table() {
        TierParser.Result result = TierParser.parse(Map.of());
        assertTrue(result.clean());
        assertEquals(TierTable.defaults(), result.table());
    }
}
