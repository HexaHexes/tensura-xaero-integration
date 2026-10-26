package org.bitwisemadness.tensuraxaerointegration.client;

import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;
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

    public static void onBaseline(double baseline) {
        ClientConcentration.get().store().setBaseline(baseline);
        // Logged once per session on purpose: a baseline the server could not
        // read silently removes every band, and with them the whole overlay, so
        // it has to be visible in a bug report rather than inferred.
        if (baselineLogged != (Double.isNaN(baseline) ? "nan" : "value")) {
            baselineLogged = Double.isNaN(baseline) ? "nan" : "value";
            if (Double.isNaN(baseline)) {
                TensuraXaeroIntegration.LOGGER.warn(
                        "Server could not report Tensura's baseline; classifying against the configured fallback ({})",
                        ClientConcentration.get().store().effectiveBaseline());
            } else {
                TensuraXaeroIntegration.LOGGER.info("Tensura baseline for this world: {}", baseline);
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
