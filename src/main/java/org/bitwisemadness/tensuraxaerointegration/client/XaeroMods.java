package org.bitwisemadness.tensuraxaerointegration.client;

import net.neoforged.fml.ModList;

/**
 * Presence checks for the two optional display targets.
 *
 * <p>Contains no references to any Xaero class, so it is safe to call from
 * anywhere. The actual Xaero-touching code sits behind these checks in
 * {@link WorldMapBridge} and {@link MinimapBridge}.</p>
 */
public final class XaeroMods {

    private XaeroMods() {
    }

    public static boolean worldMapPresent() {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded("xaeroworldmap");
    }

    public static boolean minimapPresent() {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded("xaerominimap");
    }

    public static boolean anyPresent() {
        return worldMapPresent() || minimapPresent();
    }
}
