package org.bitwisemadness.tensuraxaerointegration.net;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Registers the three payloads on the mod event bus.
 *
 * <p>Both directions are registered on both physical sides, which is how a
 * dedicated server can send updates to clients while a client can send
 * subscriptions to a server. The handlers themselves decide at runtime whether
 * they have anything to do.</p>
 */
public final class Network {

    /** Protocol version; bump alongside any payload shape change. */
    private static final String PROTOCOL_VERSION = "4";

    private Network() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToServer(
                SubscriptionPayload.TYPE,
                SubscriptionPayload.STREAM_CODEC,
                SubscriptionPayload::handle);
        registrar.playToClient(
                ConcentrationPayload.TYPE,
                ConcentrationPayload.STREAM_CODEC,
                ConcentrationPayload::handle);
        registrar.playToClient(
                ServerInfoPayload.TYPE,
                ServerInfoPayload.STREAM_CODEC,
                ServerInfoPayload::handle);
    }
}
