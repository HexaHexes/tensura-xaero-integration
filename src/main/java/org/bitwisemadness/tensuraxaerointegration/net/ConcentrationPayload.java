package org.bitwisemadness.tensuraxaerointegration.net;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;

/**
 * Server to client: concentration values for chunks the client is subscribed to.
 *
 * <p>A {@link Double#NaN} value means "no readable value right now" (typically an
 * unloaded chunk), which is distinct from a real zero. The server only ever
 * fills this payload from a player's own subscription set, so no chunk the client
 * did not declare mapped is ever named here.</p>
 *
 * @param dimension the dimension the entries belong to
 * @param chunks    packed chunk positions
 * @param values    one value per chunk, NaN for unavailable
 */
public record ConcentrationPayload(ResourceLocation dimension, long[] chunks, double[] values)
        implements CustomPacketPayload {

    public static final Type<ConcentrationPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TensuraXaeroIntegration.MOD_ID, "concentrations"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConcentrationPayload> STREAM_CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeResourceLocation(payload.dimension);
                buffer.writeVarInt(payload.chunks.length);
                for (long chunk : payload.chunks) {
                    buffer.writeLong(chunk);
                }
                for (double value : payload.values) {
                    buffer.writeDouble(value);
                }
            },
            buffer -> {
                ResourceLocation dimension = buffer.readResourceLocation();
                int count = buffer.readVarInt();
                if (count < 0 || count > NetworkLimits.MAX_UPDATE_ENTRIES) {
                    throw new io.netty.handler.codec.DecoderException(
                            "concentration payload carries " + count + " chunks");
                }
                long[] chunks = new long[count];
                for (int i = 0; i < count; i++) {
                    chunks[i] = buffer.readLong();
                }
                double[] values = new double[count];
                for (int i = 0; i < count; i++) {
                    values[i] = buffer.readDouble();
                }
                return new ConcentrationPayload(dimension, chunks, values);
            });

    public ConcentrationPayload {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(values, "values");
        if (chunks.length != values.length) {
            throw new IllegalArgumentException("chunks and values must be equal length");
        }
        if (chunks.length > NetworkLimits.MAX_UPDATE_ENTRIES) {
            throw new IllegalArgumentException("too many chunks in one update payload: " + chunks.length);
        }
        chunks = chunks.clone();
        values = values.clone();
    }

    @Override
    public long[] chunks() {
        return this.chunks.clone();
    }

    @Override
    public double[] values() {
        return this.values.clone();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Hands the payload to the client store on the client thread.
     *
     * <p>The receiver class is only touched when this method runs, which only
     * happens on a client — a dedicated server never loads a client class.</p>
     */
    public static void handle(ConcentrationPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> org.bitwisemadness.tensuraxaerointegration.client.ClientPayloadHandler
                .onConcentrations(payload));
    }
}
