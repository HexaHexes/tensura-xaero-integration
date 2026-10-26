package org.bitwisemadness.tensuraxaerointegration.client;

import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;
import org.bitwisemadness.tensuraxaerointegration.concentration.OverlayPalette;
import org.bitwisemadness.tensuraxaerointegration.net.ConcentrationPayload;

/**
 * Entry point for server-to-client payloads.
 *
 * <p>Both methods are only ever called from {@code IPayloadContext.enqueueWork},
 * i.e. on the client thread, so they may touch Xaero state and the display store
 * directly. The class is never loaded on a dedicated server because nothing
 * there invokes it.</p>
 */
public final class ClientPayloadHandler {

    private ClientPayloadHandler() {
    }

    public static void onConcentrations(ConcentrationPayload payload) {
        ClientConcentration.get().accept(payload);
    }

    /**
     * Stores the server's presentation parameters: the confirmation cadence the
     * freshness window derives from, and the datapack palette client config can
     * override band by band.
     */
    public static void onServerInfo(double baseline, int refreshIntervalTicks, OverlayPalette palette) {
        ClientConcentrationStore store = ClientConcentration.get().store();
        // Applied before anything is classified so the window is already the
        // server's when a value arrives.
        if (refreshIntervalTicks > 0) {
            store.setServerRefreshInterval(refreshIntervalTicks);
        }
        store.setServerPalette(palette);
        // Logged once per session on purpose: a baseline the server could not
        // read and a cadence mismatch are both invisible in game, and both are
        // answerable from a log rather than from guesswork.
        if (baselineLogged != (Double.isNaN(baseline) ? "nan" : "value")) {
            baselineLogged = Double.isNaN(baseline) ? "nan" : "value";
            if (Double.isNaN(baseline)) {
                TensuraXaeroIntegration.LOGGER.warn(
                        "Server could not report Tensura's baseline; chunks in dimensions without a "
                                + "datapack tier entry will be shown without a band");
            } else {
                TensuraXaeroIntegration.LOGGER.info(
                        "Tensura baseline for this world: {} (server confirms chunks every {} ticks, "
                                + "so values count as current for {} ticks)",
                        baseline, store.serverRefreshIntervalTicks(), store.staleAfterTicks());
            }
        }
    }

    /** Last baseline state logged, so the message appears once per session. */
    private static String baselineLogged = "";

    /** Forgets what was logged, so a new session logs its own baseline. */
    public static void resetBaselineLog() {
        baselineLogged = "";
    }
}
