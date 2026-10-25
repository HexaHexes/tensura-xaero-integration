package org.bitwisemadness.tensuraxaerointegration.concentration;

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
}
