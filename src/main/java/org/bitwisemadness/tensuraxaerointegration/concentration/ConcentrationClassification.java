package org.bitwisemadness.tensuraxaerointegration.concentration;

import java.util.Optional;

/**
 * A presentation band for an ambient magicule concentration.
 *
 * <p>Tensura: Reincarnated exposes no named concentration tiers - only a raw
 * value and a configured baseline - so this ladder is a display concept, not a
 * game rule. It exists so both maps and the hover line agree on one vocabulary.
 * {@link #NORMAL} means "at or below the baseline" and is never drawn.</p>
 *
 * <p>Order matters: the enum is declared from the lowest band to the highest, so
 * {@code compareTo} sorts intensities correctly.</p>
 */
public enum ConcentrationClassification {

    /** At or below the authoritative baseline. No overlay. */
    NORMAL("normal"),
    /** The lowest drawn band. */
    SLIGHTLY_ELEVATED("slightly_elevated"),
    MODERATELY_ELEVATED("moderately_elevated"),
    HIGHLY_ELEVATED("highly_elevated"),
    /** The highest configured intensity. */
    EXCEPTIONALLY_ELEVATED("exceptionally_elevated");

    private final String key;

    ConcentrationClassification(String key) {
        this.key = key;
    }

    /** Whether this band draws an overlay at all. */
    public boolean elevated() {
        return this != NORMAL;
    }

    /** Translation key for the band's display name. */
    public String translationKey() {
        return "tensura_xaero_integration.classification." + this.key;
    }

    /** The datapack/JSON name of this band, e.g. {@code slightly_elevated}. */
    public String key() {
        return this.key;
    }

    /**
     * The stable wire/persistence code of this band: its ordinal.
     *
     * <p>Fixed by declaration order, so it is safe to send over the network and
     * to write to disk. Never reorder the enum; add new bands at the end.</p>
     */
    public int code() {
        return ordinal();
    }

    /**
     * The band for a wire/persistence code.
     *
     * @return empty for {@code -1} ("no band") or any unknown code, so data from
     *         a newer or corrupted source is shown unclassified instead of
     *         guessed
     */
    public static Optional<ConcentrationClassification> fromCode(int code) {
        ConcentrationClassification[] values = values();
        if (code < 0 || code >= values.length) {
            return Optional.empty();
        }
        return Optional.of(values[code]);
    }

    /** Looks a band up by its JSON/datapack key. */
    public static Optional<ConcentrationClassification> fromKey(String key) {
        for (ConcentrationClassification value : values()) {
            if (value.key.equals(key)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
