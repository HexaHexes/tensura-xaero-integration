package org.bitwisemadness.tensuraxaerointegration.concentration;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntFunction;

/**
 * The colour and opacity used for each elevated band, plus the encoding both
 * Xaero maps expect.
 *
 * <p>Xaero stores an overlay colour as {@code blue << 24 | green << 16 | red << 8 | alpha},
 * i.e. alpha in the lowest byte (verified in
 * {@code DimensionHighlighterHandler.applyChunkHighlightColors}). Everything
 * here is pure so the shared palette can be tested without a game client.</p>
 *
 * <p>Default palette: a single cool-to-warm ramp (cyan, indigo, violet, magenta)
 * whose alpha rises with intensity, so terrain stays readable underneath.
 * Every value is configurable; a chunk at
 * {@link ConcentrationClassification#NORMAL} is encoded as fully transparent
 * but still reports a colour, which is what lets the world map show its hover
 * line for normal chunks without painting anything.</p>
 */
public record OverlayPalette(TierStyle slightlyElevated,
        TierStyle moderatelyElevated,
        TierStyle highlyElevated,
        TierStyle exceptionallyElevated) {

    public OverlayPalette {
        if (slightlyElevated == null || moderatelyElevated == null
                || highlyElevated == null || exceptionallyElevated == null) {
            throw new IllegalArgumentException("every elevated band needs a style");
        }
    }

    /** A single band's solid colour (0xRRGGBB) and draw opacity (0..1). */
    public record TierStyle(int rgb, double opacity) {

        public TierStyle {
            if (rgb < 0 || rgb > 0xFFFFFF) {
                throw new IllegalArgumentException("rgb must be 0xRRGGBB, was " + Integer.toHexString(rgb));
            }
            if (!Double.isFinite(opacity) || opacity < 0.0 || opacity > 1.0) {
                throw new IllegalArgumentException("opacity must be within 0..1, was " + opacity);
            }
        }

        /** This style encoded the way Xaero's highlighter consumes it. */
        public int xaeroColor() {
            return OverlayPalette.xaeroColor(rgb, opacity);
        }
    }

    /** The shipped palette. Presentation defaults, not gameplay values. */
    public static OverlayPalette defaults() {
        return new OverlayPalette(
                new TierStyle(0x29B6F6, 0.10),
                new TierStyle(0x5C6BC0, 0.20),
                new TierStyle(0xAB47BC, 0.32),
                new TierStyle(0xEC407A, 0.45));
    }

    /** The style for a band; {@code NORMAL} (and {@code null}) has no style. */
    public Optional<TierStyle> tierFor(ConcentrationClassification classification) {
        return Optional.ofNullable(tierOrNull(classification));
    }

    /**
     * The style for a band, or {@code null} when it is not painted.
     *
     * <p>The allocation-free form of {@link #tierFor}, for the map highlighters:
     * they ask for a colour per visible chunk on every tile rebuild, and an
     * {@code Optional} per chunk is the whole cost of a lookup against four
     * constants.</p>
     */
    private TierStyle tierOrNull(ConcentrationClassification classification) {
        if (classification == null) {
            return null;
        }
        return switch (classification) {
            case NORMAL -> null;
            case SLIGHTLY_ELEVATED -> this.slightlyElevated;
            case MODERATELY_ELEVATED -> this.moderatelyElevated;
            case HIGHLY_ELEVATED -> this.highlyElevated;
            case EXCEPTIONALLY_ELEVATED -> this.exceptionallyElevated;
        };
    }

    /** The packed colour for a band, empty when the band is not painted. */
    public OptionalInt styleFor(ConcentrationClassification classification) {
        Optional<TierStyle> tier = tierFor(classification);
        return tier.isPresent() ? OptionalInt.of(tier.get().xaeroColor()) : OptionalInt.empty();
    }

    /**
     * The colour an Xaero highlighter returns for a band: the band's colour for
     * elevated chunks, fully transparent for {@code NORMAL}.
     *
     * <p>Returning a zero-alpha colour (rather than {@code null}) keeps a normal
     * mapped chunk "highlit" in Xaero's sense, which is what allows its hover
     * line to be shown while nothing is painted.</p>
     */
    public int xaeroColorFor(ConcentrationClassification classification) {
        return xaeroColorFor(classification, 1.0);
    }

    /**
     * The colour for a band with its opacity scaled, used to draw a last-known
     * value dimmer than a live one.
     *
     * <p>A band that is not painted stays fully transparent at every scale, and
     * the scale is clamped to {@code 0..1} so this can never brighten a band.</p>
     */
    public int xaeroColorFor(ConcentrationClassification classification, double opacityScale) {
        // No Optional and no boxing: this runs per visible chunk on every tile
        // rebuild, so a lookup against four styles must stay allocation-free.
        TierStyle tier = tierOrNull(classification);
        return tier == null ? 0 : xaeroColor(tier.rgb(), tier.opacity() * clamp(opacityScale));
    }

    /** Packs 0xRRGGBB + opacity into Xaero's {@code BBGGRRAA} int layout. */
    public static int xaeroColor(int rgb, double opacity) {
        int alpha = (int) Math.round(clamp(opacity) * 255.0);
        int red = rgb >> 16 & 0xFF;
        int green = rgb >> 8 & 0xFF;
        int blue = rgb & 0xFF;
        return blue << 24 | green << 16 | red << 8 | alpha;
    }

    /**
     * Parses {@code #RRGGBB} or {@code RRGGBB} (case-insensitive).
     *
     * @throws IllegalArgumentException when the text is not a six-digit hex colour
     */
    public static int parseRgb(String text) {
        return parseRgbSafe(text).orElseThrow(
                () -> new IllegalArgumentException("not a #RRGGBB colour: " + text));
    }

    /** Non-throwing {@link #parseRgb}, for config validation. */
    public static OptionalInt parseRgbSafe(String text) {
        if (text == null) {
            return OptionalInt.empty();
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.length() != 6) {
            return OptionalInt.empty();
        }
        for (int i = 0; i < 6; i++) {
            if (Character.digit(trimmed.charAt(i), 16) < 0) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.of(Integer.parseInt(trimmed, 16));
    }

    private static double clamp(double opacity) {
        if (!Double.isFinite(opacity)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, opacity));
    }

    /** Convenience for building a palette from unvalidated config strings. */
    public static OverlayPalette fromConfig(IntFunction<String> colours,
            IntFunction<Double> opacities, OverlayPalette fallback) {
        OverlayPalette defaults = fallback == null ? defaults() : fallback;
        return new OverlayPalette(
                configTier(colours, opacities, 0, defaults.slightlyElevated),
                configTier(colours, opacities, 1, defaults.moderatelyElevated),
                configTier(colours, opacities, 2, defaults.highlyElevated),
                configTier(colours, opacities, 3, defaults.exceptionallyElevated));
    }

    private static TierStyle configTier(IntFunction<String> colours, IntFunction<Double> opacities,
            int index, TierStyle fallback) {
        int rgb = parseRgbSafe(colours.apply(index)).orElse(fallback.rgb());
        Double opacity = opacities.apply(index);
        double safeOpacity = opacity == null || !Double.isFinite(opacity) || opacity < 0.0 || opacity > 1.0
                ? fallback.opacity()
                : opacity;
        return new TierStyle(rgb, safeOpacity);
    }
}
