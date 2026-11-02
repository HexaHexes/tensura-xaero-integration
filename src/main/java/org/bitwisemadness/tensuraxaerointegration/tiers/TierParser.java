package org.bitwisemadness.tensuraxaerointegration.tiers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationClassification;
import org.bitwisemadness.tensuraxaerointegration.concentration.ConcentrationThresholds;
import org.bitwisemadness.tensuraxaerointegration.concentration.OverlayPalette;

/**
 * Turns the datapack's JSON into a {@link TierTable}, without touching the game.
 *
 * <p>Two shapes live in the {@code magicule_tiers} directory:</p>
 * <pre>
 * { "dimensions": ["minecraft:the_nether"], "baseline": 600.0, "bands": { ... } }
 * { "palette": { "slightly_elevated": { "color": "#29B6F6", "opacity": 0.10 } } }
 * </pre>
 *
 * <p>A file is applied if it parses; a broken file is reported and skipped, so
 * one typo cannot empty the whole table and silently flatten every dimension to
 * the fallback. Problems are returned rather than logged, which is what makes
 * the schema testable in plain JUnit.</p>
 */
public final class TierParser {

    /** Datapack directory these files live in. */
    public static final String DIRECTORY = "magicule_tiers";

    /** The band percentages, all four required when the object is present. */
    private static final List<ConcentrationClassification> ELEVATED_BANDS = List.of(
            ConcentrationClassification.SLIGHTLY_ELEVATED,
            ConcentrationClassification.MODERATELY_ELEVATED,
            ConcentrationClassification.HIGHLY_ELEVATED,
            ConcentrationClassification.EXCEPTIONALLY_ELEVATED);

    private static final Pattern DIMENSION_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private TierParser() {
    }

    /** A parsed directory: the table to install and everything that went wrong. */
    public record Result(TierTable table, List<String> problems) {

        public Result {
            problems = List.copyOf(problems);
        }

        public boolean clean() {
            return this.problems.isEmpty();
        }
    }

    /**
     * Parses every file of the directory.
     *
     * @param files file id (for messages) → its parsed JSON, in any order
     */
    public static Result parse(Map<String, JsonElement> files) {
        List<String> problems = new ArrayList<>();
        Map<String, DimensionTier> dimensions = new LinkedHashMap<>();
        OverlayPalette palette = OverlayPalette.defaults();

        for (Map.Entry<String, JsonElement> file : new TreeMap<>(files).entrySet()) {
            String name = file.getKey();
            JsonElement root = file.getValue();
            if (root == null || !root.isJsonObject()) {
                problems.add(name + ": the file must contain a JSON object");
                continue;
            }
            JsonObject object = root.getAsJsonObject();
            palette = parsePalette(name, object, palette, problems);
            parseDimensions(name, object, dimensions, problems);
        }

        return new Result(new TierTable(dimensions, ConcentrationThresholds.defaults(), palette), problems);
    }

    // --- dimensions ---------------------------------------------------------

    private static void parseDimensions(String name, JsonObject object,
            Map<String, DimensionTier> dimensions, List<String> problems) {
        if (!object.has("dimensions")) {
            return;
        }
        JsonElement element = object.get("dimensions");
        if (element == null || !element.isJsonArray()) {
            problems.add(name + ": \"dimensions\" must be an array of dimension ids");
            return;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.isEmpty()) {
            problems.add(name + ": \"dimensions\" is empty, so the file describes nothing");
            return;
        }
        Double baseline = number(object, "baseline");
        if (baseline == null) {
            problems.add(name + ": a file with \"dimensions\" needs a numeric \"baseline\"");
            return;
        }
        if (!(baseline > 0.0) || !Double.isFinite(baseline)) {
            problems.add(name + ": \"baseline\" must be finite and positive, was " + baseline);
            return;
        }
        ConcentrationThresholds bands = parseBands(name, object, problems);
        DimensionTier tier;
        try {
            tier = new DimensionTier(baseline, bands);
        } catch (IllegalArgumentException invalid) {
            problems.add(name + ": " + invalid.getMessage());
            return;
        }
        for (JsonElement id : array) {
            String dimension = id == null || !id.isJsonPrimitive() ? null : id.getAsString();
            if (dimension == null || !DIMENSION_ID.matcher(dimension).matches()) {
                problems.add(name + ": not a dimension id: " + id);
                continue;
            }
            if (dimensions.put(dimension, tier) != null) {
                problems.add(name + ": " + dimension + " was already declared by another file; "
                        + "the alphabetically last file wins");
            }
        }
    }

    private static ConcentrationThresholds parseBands(String name, JsonObject object, List<String> problems) {
        JsonElement element = object.get("bands");
        if (element == null) {
            return ConcentrationThresholds.defaults();
        }
        if (!element.isJsonObject()) {
            problems.add(name + ": \"bands\" must be an object of band percentages");
            return ConcentrationThresholds.defaults();
        }
        JsonObject bands = element.getAsJsonObject();
        double[] percents = new double[ELEVATED_BANDS.size()];
        boolean complete = true;
        for (int i = 0; i < ELEVATED_BANDS.size(); i++) {
            String key = ELEVATED_BANDS.get(i).key();
            Double value = number(bands, key);
            if (value == null) {
                problems.add(name + ": \"bands\" is missing \"" + key + "\"; a partial ladder is ignored");
                complete = false;
                break;
            }
            percents[i] = value;
        }
        if (!complete) {
            return ConcentrationThresholds.defaults();
        }
        try {
            return new ConcentrationThresholds(percents[0], percents[1], percents[2], percents[3]);
        } catch (IllegalArgumentException invalid) {
            problems.add(name + ": " + invalid.getMessage());
            return ConcentrationThresholds.defaults();
        }
    }

    // --- palette ------------------------------------------------------------

    private static OverlayPalette parsePalette(String name, JsonObject object,
            OverlayPalette current, List<String> problems) {
        JsonElement element = object.get("palette");
        if (element == null) {
            return current;
        }
        if (!element.isJsonObject()) {
            problems.add(name + ": \"palette\" must be an object keyed by band");
            return current;
        }
        OverlayPalette palette = current;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            Optional<ConcentrationClassification> band = ConcentrationClassification.fromKey(entry.getKey());
            if (band.isEmpty() || band.get() == ConcentrationClassification.NORMAL) {
                problems.add(name + ": \"" + entry.getKey() + "\" is not a painted band; valid keys are "
                        + bandKeys());
                continue;
            }
            JsonElement style = entry.getValue();
            if (style == null || !style.isJsonObject()) {
                problems.add(name + ": band \"" + entry.getKey() + "\" needs a { color, opacity } object");
                continue;
            }
            JsonObject styleObject = style.getAsJsonObject();
            String colour = string(styleObject, "color");
            OptionalInt rgb = OverlayPalette.parseRgbSafe(colour);
            if (rgb.isEmpty()) {
                problems.add(name + ": band \"" + entry.getKey() + "\" needs a #RRGGBB \"color\", was " + colour);
                continue;
            }
            Double opacity = number(styleObject, "opacity");
            if (opacity == null || opacity < 0.0 || opacity > 1.0) {
                problems.add(name + ": band \"" + entry.getKey() + "\" needs \"opacity\" within 0..1");
                continue;
            }
            palette = withTier(palette, band.get(), new OverlayPalette.TierStyle(rgb.getAsInt(), opacity));
        }
        return palette;
    }

    private static OverlayPalette withTier(OverlayPalette palette, ConcentrationClassification band,
            OverlayPalette.TierStyle style) {
        return switch (band) {
            case SLIGHTLY_ELEVATED -> new OverlayPalette(style, palette.moderatelyElevated(),
                    palette.highlyElevated(), palette.exceptionallyElevated());
            case MODERATELY_ELEVATED -> new OverlayPalette(palette.slightlyElevated(), style,
                    palette.highlyElevated(), palette.exceptionallyElevated());
            case HIGHLY_ELEVATED -> new OverlayPalette(palette.slightlyElevated(), palette.moderatelyElevated(),
                    style, palette.exceptionallyElevated());
            default -> new OverlayPalette(palette.slightlyElevated(), palette.moderatelyElevated(),
                    palette.highlyElevated(), style);
        };
    }

    // --- helpers ------------------------------------------------------------

    private static String bandKeys() {
        StringBuilder builder = new StringBuilder();
        for (ConcentrationClassification band : ELEVATED_BANDS) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(band.key());
        }
        return builder.toString();
    }

    private static Double number(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return element.getAsDouble();
    }

    private static String string(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || !element.isJsonPrimitive() ? null : element.getAsString();
    }
}
