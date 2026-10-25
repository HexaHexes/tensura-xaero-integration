package org.bitwisemadness.tensuraxaerointegration.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Wires the client-only listeners.
 *
 * <p>Only called from the mod constructor behind a client-distribution check, so
 * this class is never loaded on a dedicated server. Keeping the registration in
 * one place also makes the client surface obvious.</p>
 */
public final class ClientSetup {

    private ClientSetup() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(ClientSetup::onClientTick);
        NeoForge.EVENT_BUS.addListener(ClientSetup::onLoggingOut);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        ClientConcentration.get().onClientTick(Minecraft.getInstance());
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientConcentration.get().resetSession();
    }

    /** Repaints everything after a client config edit that changes the overlay. */
    public static void onClientConfigReloaded() {
        ClientConcentration.get().store().markAllRegionsDirty();
    }
}
