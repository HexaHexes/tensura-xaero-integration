package org.bitwisemadness.tensuraxaerointegration.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;

/**
 * Server to client: the server's own parameters that the client's presentation
 * has to follow.
 *
 * <p>Two values, both owned by the server and both needed by the client:</p>
 * <ul>
 *   <li><b>baseline</b> — Tensura's authoritative normal for this world, so the
 *       client's band thresholds track {@code area_magicule_config.baseMagicule}
 *       instead of a guess. {@link Double#NaN} means the server could not read
 *       one; the client then uses its configured fallback.</li>
 *   <li><b>refreshIntervalTicks</b> — how often the server re-confirms a
 *       subscribed chunk whose value has not changed. The client derives its
 *       freshness window from this instead of keeping a second, independent
 *       number that could disagree with the server and either dim values that
 *       are still being confirmed or trust ones that are not.</li>
 * </ul>
 *
 * <p>Sent on join and refreshed periodically, so a server config change reaches
 * connected clients.</p>
 */
public record ServerInfoPayload(double baseline, int refreshIntervalTicks) implements CustomPacketPayload {

    public static final Type<ServerInfoPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TensuraXaeroIntegration.MOD_ID, "server_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerInfoPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeDouble(payload.baseline);
                        buffer.writeVarInt(payload.refreshIntervalTicks);
                    },
                    buffer -> new ServerInfoPayload(buffer.readDouble(), buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Stores the server's parameters on the client thread. */
    public static void handle(ServerInfoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> org.bitwisemadness.tensuraxaerointegration.client.ClientPayloadHandler
                .onServerInfo(payload.baseline(), payload.refreshIntervalTicks()));
    }
}
