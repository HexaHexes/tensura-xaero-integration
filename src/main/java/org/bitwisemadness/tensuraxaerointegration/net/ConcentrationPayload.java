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
 * <p>The band travels with the value because the server owns the tier table: it
 * is the only side that knows a dimension's datapack baseline, and deciding the
 * band per client would let two players see different tiers for one chunk.</p>
 *
 * @param dimension the dimension the entries belong to
 * @param chunks    packed chunk positions
 * @param values    one value per chunk, NaN for unavailable
 * @param bands     one band code per chunk ({@code -1} = not classified)
 */
public record ConcentrationPayload(ResourceLocation dimension, long[] chunks, double[] values, byte[] bands)
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
                for (byte band : payload.bands) {
                    buffer.writeByte(band);
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
                byte[] bands = new byte[count];
                for (int i = 0; i < count; i++) {
                    bands[i] = buffer.readByte();
                }
                return new ConcentrationPayload(dimension, chunks, values, bands);
            });

    public ConcentrationPayload {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(bands, "bands");
        if (chunks.length != values.length || chunks.length != bands.length) {
            throw new IllegalArgumentException("chunks, values and bands must be equal length");
        }
        if (chunks.length > NetworkLimits.MAX_UPDATE_ENTRIES) {
            throw new IllegalArgumentException("too many chunks in one update payload: " + chunks.length);
        }
        chunks = chunks.clone();
        values = values.clone();
        bands = bands.clone();
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
    public byte[] bands() {
        return this.bands.clone();
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
