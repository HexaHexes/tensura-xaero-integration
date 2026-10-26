package org.bitwisemadness.tensuraxaerointegration.tiers;

/**
 * The tier table currently in force.
 *
 * <p>Written by the datapack reload listener and read by the server when it
 * classifies a chunk. A {@code volatile} swap rather than mutation, so a reader
 * on the server thread can never see a partially built table and a reload needs
 * no lock.</p>
 */
public final class MagiculeTiers {

    private static volatile TierTable table = TierTable.defaults();

    private MagiculeTiers() {
    }

    /** The table in force; never null. */
    public static TierTable get() {
        return table;
    }

    /** Replaces the table; a null is treated as "no datapack", not as "no tiers". */
    public static void replace(TierTable next) {
        table = next == null ? TierTable.defaults() : next;
    }
}
