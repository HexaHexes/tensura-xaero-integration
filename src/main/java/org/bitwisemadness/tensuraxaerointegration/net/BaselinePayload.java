package org.bitwisemadness.tensuraxaerointegration.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;

/**
 * Server to client: Tensura's authoritative normal baseline for this world.
 *
 * <p>Sent on join and refreshed occasionally, so the client's band thresholds
 * track {@code area_magicule_config.baseMagicule} instead of a client guess.
 * {@link Double#NaN} means the server could not read a baseline; the client then
 * uses its configured fallback, and never labels a value it cannot classify.</p>
 */
public record BaselinePayload(double baseline) implements CustomPacketPayload {

    public static final Type<BaselinePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TensuraXaeroIntegration.MOD_ID, "baseline"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BaselinePayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> buffer.writeDouble(payload.baseline),
                    buffer -> new BaselinePayload(buffer.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Stores the baseline on the client thread. */
    public static void handle(BaselinePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> org.bitwisemadness.tensuraxaerointegration.client.ClientPayloadHandler
                .onBaseline(payload.baseline()));
    }
}
