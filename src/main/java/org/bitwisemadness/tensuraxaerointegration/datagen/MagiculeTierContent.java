package org.bitwisemadness.tensuraxaerointegration.datagen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationClassification;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationThresholds;
import org.bitwisemadness.tensuraxaerointegration.concentration.OverlayPalette;
import org.bitwisemadness.tensuraxaerointegration.tiers.DimensionTier;
import org.bitwisemadness.tensuraxaerointegration.tiers.TierParser;
import org.bitwisemadness.tensuraxaerointegration.tiers.TierTable;

/**
 * The per-dimension tier datapack this mod ships (issue #27), as tables.
 *
 * <p>This class exists only so that {@link JsonFilesProvider} can write the JSON
 * the game actually loads. The runtime never reads it: {@code TierReloadListener}
 * takes whatever the datapack contributes, which means the generated files in
 * {@code src/generated/resources} <em>are</em> the built-in tiers rather than a
 * copy of one held in Java. A datapack that omits a dimension therefore removes
 * it, instead of a Java fallback quietly restoring it.</p>
 *
 * <p>That also makes drift a real risk - edit a number here, forget
 * {@code runData}, and the jar ships last week's tiers. {@link #build()} closes
 * the loop by round-tripping every generated file through {@link TierParser},
 * the same parser the server loads with, so a typo fails the datagen run
 * instead of silently flattening a dimension onto the fallback ladder at
 * runtime. The headless GameTest asserts the same contract again in-game.</p>
 */
public final class MagiculeTierContent {

    /** Where the tiers ship, inside the jar. */
    private static final String DIRECTORY = "data/tensura_xaero_integration/magicule_tiers";

    /**
     * The ladder hell and the elemental realm ship: a tight ramp over a huge
     * normal, so the bands stay inside the range players actually see there.
     * The other dimensions omit {@code bands} and take the default ladder.
     */
    private static final ConcentrationThresholds HOT_LADDER =
            new ConcentrationThresholds(0.5, 1.0, 2.0, 3.0);

    private static final List<TierEntry> ENTRIES = List.of(
            new TierEntry("boss_area", "tensura:boss_area", 500.0, null),
            new TierEntry("dread_land", "iceandfire:dread_land", 50500.0, null),
            new TierEntry("elemental_realm", "mysticism:elemental_realm", 100500.0, HOT_LADDER),
            new TierEntry("hell", "tensura:hell", 100500.0, HOT_LADDER),
            new TierEntry("kamui_dimension", "mysticism:kamui_dimension", 500.0, null),
            new TierEntry("labyrinth", "tensura:labyrinth", 30000.0, null),
            new TierEntry("overworld", "minecraft:overworld", 500.0, null),
            new TierEntry("pocket_dimension", "irons_spellbooks:pocket_dimension", 500.0, null),
            new TierEntry("reincarnation_trial",
                    "tensura_all_the_adventure:reincarnation_trial", 500.0, null),
            new TierEntry("the_end", "minecraft:the_end", 750.0, null),
            new TierEntry("the_nether", "minecraft:the_nether", 600.0, null));

    private MagiculeTierContent() {
    }

    /** Every file of the shipped pack, keyed by its path inside the jar. */
    public static Map<String, JsonObject> build() {
        Map<String, JsonObject> files = new LinkedHashMap<>();
        for (TierEntry entry : ENTRIES) {
            files.put(DIRECTORY + "/" + entry.file() + ".json", dimensionJson(entry));
        }
        files.put(DIRECTORY + "/palette.json", paletteJson());
        validate(files);
        return files;
    }

    /**
     * One shipped dimension file: its name, the dimension it describes, the
     * concentration that counts as normal there, and the band ladder -
     * {@code null} ships no {@code bands} key so the parser's default ladder
     * applies.
     */
    private record TierEntry(String file, String dimension, double baseline, ConcentrationThresholds bands) {
    }

    /** Field order matches what {@link TierParser} reads, so the diff stays honest. */
    private static JsonObject dimensionJson(TierEntry entry) {
        JsonObject json = new JsonObject();
        JsonArray dimensions = new JsonArray();
        dimensions.add(entry.dimension());
        json.add("dimensions", dimensions);
        json.addProperty("baseline", entry.baseline());
        if (entry.bands() != null) {
            json.add("bands", bandsJson(entry.bands()));
        }
        return json;
    }

    private static JsonObject bandsJson(ConcentrationThresholds bands) {
        JsonObject json = new JsonObject();
        json.addProperty(ConcentrationClassification.SLIGHTLY_ELEVATED.key(),
                bands.slightlyElevatedPercent());
        json.addProperty(ConcentrationClassification.MODERATELY_ELEVATED.key(),
                bands.moderatelyElevatedPercent());
        json.addProperty(ConcentrationClassification.HIGHLY_ELEVATED.key(),
                bands.highlyElevatedPercent());
        json.addProperty(ConcentrationClassification.EXCEPTIONALLY_ELEVATED.key(),
                bands.exceptionallyElevatedPercent());
        return json;
    }

    /**
     * The shipped palette restates {@link OverlayPalette#defaults()} in datapack
     * form, so pack authors can copy it and the two can never disagree: the
     * file is generated from the same constants the parser falls back to.
     */
    private static JsonObject paletteJson() {
        OverlayPalette defaults = OverlayPalette.defaults();
        JsonObject palette = new JsonObject();
        palette.add(ConcentrationClassification.SLIGHTLY_ELEVATED.key(),
                styleJson(defaults.slightlyElevated()));
        palette.add(ConcentrationClassification.MODERATELY_ELEVATED.key(),
                styleJson(defaults.moderatelyElevated()));
        palette.add(ConcentrationClassification.HIGHLY_ELEVATED.key(),
                styleJson(defaults.highlyElevated()));
        palette.add(ConcentrationClassification.EXCEPTIONALLY_ELEVATED.key(),
                styleJson(defaults.exceptionallyElevated()));
        JsonObject json = new JsonObject();
        json.add("palette", palette);
        return json;
    }

    private static JsonObject styleJson(OverlayPalette.TierStyle style) {
        JsonObject json = new JsonObject();
        json.addProperty("color", String.format(Locale.ROOT, "#%06X", style.rgb()));
        json.addProperty("opacity", style.opacity());
        return json;
    }

    /** Fails the datagen run when the built pack would not load as intended. */
    private static void validate(Map<String, JsonObject> files) {
        List<String> errors = new ArrayList<>();
        if (files.size() != ENTRIES.size() + 1) {
            errors.add("expected " + (ENTRIES.size() + 1) + " files (one per dimension plus the palette), "
                    + "built " + files.size());
        }
        // The round trip is the real gate: the parser the server loads with must
        // accept every generated file with zero problems, or a typo would be
        // reported-and-skipped at runtime and flatten a dimension to the fallback.
        Map<String, JsonElement> elements = new LinkedHashMap<>(files);
        TierParser.Result result = TierParser.parse(elements);
        if (!result.clean()) {
            errors.add("the parser rejects the generated files: " + result.problems());
        }
        TierTable table = result.table();
        for (TierEntry entry : ENTRIES) {
            DimensionTier tier = table.forDimension(entry.dimension(), -1.0).orElse(null);
            if (tier == null) {
                errors.add(entry.dimension() + ": missing from the parsed table");
                continue;
            }
            if (tier.baseline() != entry.baseline()) {
                errors.add(entry.dimension() + ": baseline round-trips as " + tier.baseline()
                        + ", expected " + entry.baseline());
            }
            ConcentrationThresholds expectedBands = entry.bands() == null
                    ? ConcentrationThresholds.defaults() : entry.bands();
            if (!tier.bands().equals(expectedBands)) {
                errors.add(entry.dimension() + ": bands round-trip as " + tier.bands());
            }
        }
        // The contract ConcentrationGameTests.tier_datapack_is_loaded asserts in
        // the headless server, checked here so a table edit fails in runData
        // with a clear message instead of three CI jobs later.
        DimensionTier overworld = table.forDimension("minecraft:overworld", -1.0).orElse(null);
        if (overworld == null || overworld.baseline() != 500.0) {
            errors.add("the overworld must keep the 500.0 baseline the GameTest asserts");
        } else if (overworld.classify(600.0)
                .filter(band -> band != ConcentrationClassification.NORMAL).isEmpty()) {
            errors.add("600 must classify as an elevated band in the overworld");
        }
        DimensionTier hell = table.forDimension("tensura:hell", -1.0).orElse(null);
        if (hell == null || !(hell.baseline() > 1000.0)) {
            errors.add("the hell entry must keep its own high baseline");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("the shipped tier datapack is broken: "
                    + String.join("; ", errors));
        }
    }
}
