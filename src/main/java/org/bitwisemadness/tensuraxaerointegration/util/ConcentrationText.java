package org.bitwisemadness.tensuraxaerointegration.util;

import java.util.Locale;

/**
 * Formats a concentration for display, without inventing a unit.
 *
 * <p>Tensura's values are {@code double}s in its own "magicule" config units and
 * its baseline is a whole number (500 by default). Values are shown with a
 * thousands separator and at most one decimal, and a whole number is shown
 * without a trailing {@code .0}.</p>
 */
public final class ConcentrationText {

    private ConcentrationText() {
    }

    /**
     * Formats a concentration value.
     *
     * @throws IllegalArgumentException when the value is not finite
     */
    public static String format(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("cannot format a non-finite concentration");
        }
        long tenths = Math.round(value * 10.0);
        double rounded = tenths / 10.0;
        if (tenths % 10 == 0) {
            return String.format(Locale.ROOT, "%,.0f", rounded);
        }
        return String.format(Locale.ROOT, "%,.1f", rounded);
    }
}
