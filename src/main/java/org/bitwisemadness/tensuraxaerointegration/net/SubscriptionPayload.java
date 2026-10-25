package org.bitwisemadness.tensuraxaerointegration.net;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;
import org.bitwisemadness.tensuraxaerointegration.server.ConcentrationServer;

/**
 * Client to server: "these chunks are mapped for me", or "I no longer show
 * these chunks".
 *
 * <p>The client only ever lists chunks its Xaero maps have written, which is the
 * primary mapped-chunk rule. The server independently validates the request
 * against its own limits (dimension, distance, loaded state, per-player cap), so
 * the payload is a declaration, not an authority.</p>
 *
 * @param dimension the dimension the chunk list belongs to
 * @param chunks    packed chunk positions ({@link PackedChunkPos})
 * @param subscribe true to add, false to remove
 */
public record SubscriptionPayload(ResourceLocation dimension, long[] chunks, boolean subscribe)
        implements CustomPacketPayload {

    public static final Type<SubscriptionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TensuraXaeroIntegration.MOD_ID, "subscriptions"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SubscriptionPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeVarInt(payload.chunks.length);
                for (long chunk : payload.chunks) {
                    buffer.writeLong(chunk);
                }
                buffer.writeBoolean(payload.subscribe);
            },
            buffer -> {
                ResourceLocation dimension = buffer.readResourceLocation();
                int count = buffer.readVarInt();
                if (count < 0 || count > NetworkLimits.MAX_SUBSCRIPTION_ENTRIES) {
                    throw new io.netty.handler.codec.DecoderException(
                            "subscription payload carries " + count + " chunks");
                }
                long[] chunks = new long[count];
                for (int i = 0; i < count; i++) {
                    chunks[i] = buffer.readLong();
                }
                return new SubscriptionPayload(dimension, chunks, buffer.readBoolean());
            });

    public SubscriptionPayload {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(chunks, "chunks");
        if (chunks.length > NetworkLimits.MAX_SUBSCRIPTION_ENTRIES) {
            throw new IllegalArgumentException("too many chunks in one subscription payload: " + chunks.length);
        }
        chunks = chunks.clone();
    }

    @Override
    public long[] chunks() {
        return this.chunks.clone();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Handles the payload on the server thread.
     *
     * <p>{@code enqueueWork} is what makes the world access in
     * {@link ConcentrationServer} legal: the network thread never touches a level
     * or a chunk.</p>
     */
    public static void handle(SubscriptionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof net.minecraft.server.level.ServerPlayer player) {
                ConcentrationServer.get().onSubscribe(player, payload);
            }
        });
    }
}
