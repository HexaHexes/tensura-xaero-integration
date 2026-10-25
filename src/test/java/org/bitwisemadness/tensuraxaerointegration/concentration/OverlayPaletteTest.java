package org.bitwisemadness.tensuraxaerointegration.concentration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OverlayPaletteTest {

    @Test
    void hex_colours_parse_with_or_without_a_hash() {
        assertEquals(0x29B6F6, OverlayPalette.parseRgb("#29B6F6"));
        assertEquals(0x29B6F6, OverlayPalette.parseRgb("29b6f6"));
        assertEquals(0xFFFFFF, OverlayPalette.parseRgb("FFFFFF"));
        assertEquals(0x000000, OverlayPalette.parseRgb("#000000"));
    }

    @Test
    void invalid_hex_colours_are_rejected_or_empty() {
        assertThrows(IllegalArgumentException.class, () -> OverlayPalette.parseRgb("#GGGGGG"));
        assertThrows(IllegalArgumentException.class, () -> OverlayPalette.parseRgb("#12345"));
        assertThrows(IllegalArgumentException.class, () -> OverlayPalette.parseRgb(null));
        assertTrue(OverlayPalette.parseRgbSafe("nope").isEmpty());
        assertTrue(OverlayPalette.parseRgbSafe(null).isEmpty());
    }

    @Test
    void xaero_encoding_puts_alpha_in_the_low_byte() {
        // Xaero: blue << 24 | green << 16 | red << 8 | alpha
        int packed = OverlayPalette.xaeroColor(0x112233, 1.0);
        assertEquals(0x332211FF, packed);
        assertEquals(0x33, packed >>> 24 & 0xFF);
        assertEquals(0x22, packed >> 16 & 0xFF);
        assertEquals(0x11, packed >> 8 & 0xFF);
        assertEquals(0xFF, packed & 0xFF);
    }

    @Test
    void opacity_is_clamped_and_rounded() {
        assertEquals(0, OverlayPalette.xaeroColor(0xFFFFFF, -1.0) & 0xFF);
        assertEquals(255, OverlayPalette.xaeroColor(0xFFFFFF, 2.0) & 0xFF);
        // 0.10 * 255 = 25.5 -> 26
        assertEquals(26, OverlayPalette.xaeroColor(0xFFFFFF, 0.10) & 0xFF);
        assertEquals(0, OverlayPalette.xaeroColor(0xFFFFFF, Double.NaN) & 0xFF);
    }

    @Test
    void normal_is_fully_transparent_but_still_encodes() {
        OverlayPalette palette = OverlayPalette.defaults();
        assertEquals(0, palette.xaeroColorFor(ConcentrationClassification.NORMAL));
        assertTrue(palette.styleFor(ConcentrationClassification.NORMAL).isEmpty());
    }

    @Test
    void every_elevated_band_has_a_distinct_colour() {
        OverlayPalette palette = OverlayPalette.defaults();
        int slight = palette.xaeroColorFor(ConcentrationClassification.SLIGHTLY_ELEVATED);
        int moderate = palette.xaeroColorFor(ConcentrationClassification.MODERATELY_ELEVATED);
        int high = palette.xaeroColorFor(ConcentrationClassification.HIGHLY_ELEVATED);
        int exceptional = palette.xaeroColorFor(ConcentrationClassification.EXCEPTIONALLY_ELEVATED);
        assertNotEquals(slight, moderate);
        assertNotEquals(moderate, high);
        assertNotEquals(high, exceptional);
    }

    @Test
    void opacity_increases_with_intensity_in_the_default_palette() {
        OverlayPalette palette = OverlayPalette.defaults();
        int slightAlpha = palette.xaeroColorFor(ConcentrationClassification.SLIGHTLY_ELEVATED) & 0xFF;
        int moderateAlpha = palette.xaeroColorFor(ConcentrationClassification.MODERATELY_ELEVATED) & 0xFF;
        int highAlpha = palette.xaeroColorFor(ConcentrationClassification.HIGHLY_ELEVATED) & 0xFF;
        int exceptionalAlpha = palette.xaeroColorFor(ConcentrationClassification.EXCEPTIONALLY_ELEVATED) & 0xFF;
        assertTrue(slightAlpha < moderateAlpha);
        assertTrue(moderateAlpha < highAlpha);
        assertTrue(highAlpha < exceptionalAlpha);
    }

    @Test
    void config_values_fall_back_per_field() {
        OverlayPalette defaults = OverlayPalette.defaults();
        OverlayPalette config = OverlayPalette.fromConfig(
                index -> "not-a-colour",
                index -> 5.0, // out of range
                defaults);
        assertEquals(defaults, config);
    }

    @Test
    void config_values_replace_defaults_when_valid() {
        OverlayPalette defaults = OverlayPalette.defaults();
        OverlayPalette config = OverlayPalette.fromConfig(
                index -> "#010203",
                index -> 0.5,
                defaults);
        assertEquals(OverlayPalette.xaeroColor(0x010203, 0.5),
                config.xaeroColorFor(ConcentrationClassification.SLIGHTLY_ELEVATED));
    }

    @Test
    void a_null_style_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new OverlayPalette(null, OverlayPalette.defaults().moderatelyElevated(),
                        OverlayPalette.defaults().highlyElevated(),
                        OverlayPalette.defaults().exceptionallyElevated()));
    }
}
