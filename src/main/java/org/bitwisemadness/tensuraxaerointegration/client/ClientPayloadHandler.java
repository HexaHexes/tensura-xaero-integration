package org.bitwisemadness.tensuraxaerointegration.client;

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
    }
}
