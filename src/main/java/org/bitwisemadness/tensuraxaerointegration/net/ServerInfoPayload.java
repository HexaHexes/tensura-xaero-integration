package org.bitwisemadness.tensuraxaerointegration.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;
import org.bitwisemadness.tensuraxaerointegration.concentration.OverlayPalette;

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
 *   <li><b>refreshIntervalTicks</b> and <b>refreshIntervalMillis</b> — how often
 *       the server re-confirms a subscribed chunk whose value has not changed,
 *       in its own ticks and in the real time those ticks actually take. The
 *       second number is the one the client's freshness window is built from: a
 *       server that cannot keep up stretches its ticks, and comparing a client
 *       clock with a server tick counter made values flicker between live and
 *       last known on exactly those servers.</li>
 *   <li><b>palette</b> — the datapack's default colours. A client config colour
 *       overrides the band it names; the rest fall back to these values.</li>
 * </ul>
 *
 * <p>Sent on join and refreshed periodically, so a server config change reaches
 * connected clients.</p>
 */
public record ServerInfoPayload(double baseline, int refreshIntervalTicks, long refreshIntervalMillis,
        OverlayPalette palette) implements CustomPacketPayload {

    public static final Type<ServerInfoPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TensuraXaeroIntegration.MOD_ID, "server_info"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerInfoPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeDouble(payload.baseline);
                        buffer.writeVarInt(payload.refreshIntervalTicks);
                        buffer.writeVarLong(payload.refreshIntervalMillis);
                        for (OverlayPalette.TierStyle style : styles(payload.palette)) {
                            buffer.writeVarInt(style.rgb());
                            buffer.writeDouble(style.opacity());
                        }
                    },
                    buffer -> {
                        double baseline = buffer.readDouble();
                        int interval = buffer.readVarInt();
                        long intervalMillis = buffer.readVarLong();
                        OverlayPalette.TierStyle[] styles = new OverlayPalette.TierStyle[4];
                        for (int i = 0; i < styles.length; i++) {
                            styles[i] = new OverlayPalette.TierStyle(buffer.readVarInt(), buffer.readDouble());
                        }
                        return new ServerInfoPayload(baseline, interval, intervalMillis,
                                new OverlayPalette(styles[0], styles[1], styles[2], styles[3]));
                    });

    public ServerInfoPayload {
        if (palette == null) {
            throw new IllegalArgumentException("the palette is required, if only to say 'use the defaults'");
        }
        if (refreshIntervalMillis <= 0) {
            throw new IllegalArgumentException("the refresh promise must be positive, was " + refreshIntervalMillis);
        }
    }

    /** The four band styles, lowest band first. */
    private static OverlayPalette.TierStyle[] styles(OverlayPalette palette) {
        return new OverlayPalette.TierStyle[] {palette.slightlyElevated(), palette.moderatelyElevated(),
                palette.highlyElevated(), palette.exceptionallyElevated()};
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Stores the server's parameters on the client thread. */
    public static void handle(ServerInfoPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> org.bitwisemadness.tensuraxaerointegration.client.ClientPayloadHandler
                .onServerInfo(payload.baseline(), payload.refreshIntervalTicks(),
                        payload.refreshIntervalMillis(), payload.palette()));
    }
}
