package org.bitwisemadness.tensuraxaerointegration.client;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.bitwisemadness.tensuraxaerointegration.config.ClientConfig;

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
        // Saves the session's last-known values, then waits for any queued write.
        ClientConcentration.get().resetSession();
        ClientConcentration.get().flushPersistence();
    }

    /** Repaints everything after a client config edit that changes the overlay. */
    public static void onClientConfigReloaded() {
        // The resolved palette is memoised per paint; a colour edit must not keep
        // painting the previous one.
        ClientConfig.invalidatePalette();
        ClientConcentration.get().store().markAllRegionsDirty();
    }
}
