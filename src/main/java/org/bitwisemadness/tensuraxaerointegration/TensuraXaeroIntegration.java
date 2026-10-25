package org.bitwisemadness.tensuraxaerointegration;

import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.bitwisemadness.tensuraxaerointegration.client.ClientSetup;
import org.bitwisemadness.tensuraxaerointegration.config.ClientConfig;
import org.bitwisemadness.tensuraxaerointegration.config.ServerConfig;
import org.bitwisemadness.tensuraxaerointegration.net.Network;
import org.bitwisemadness.tensuraxaerointegration.server.ConcentrationServer;
import org.bitwisemadness.tensuraxaerointegration.tensura.TensuraBridge;
import org.slf4j.Logger;

/**
 * Entry point.
 *
 * <p>Layers, from the outside in:</p>
 * <ol>
 *   <li><b>Tensura</b> (optional, server-side) is the only source of
 *       concentration values.</li>
 *   <li><b>{@link ConcentrationServer}</b> validates subscriptions and runs the
 *       hybrid refresh (chunk-tick events plus a bounded reconciliation sweep).</li>
 *   <li><b>Payloads</b> carry only chunks a player subscribed to.</li>
 *   <li><b>Xaero's maps</b> (both optional, client-side) draw from a store that
 *       refuses unmapped chunks.</li>
 * </ol>
 *
 * <p>Nothing here touches a Tensura or Xaero class: each integration sits behind
 * its own bridge, which checks {@code ModList} before loading the accessor. The
 * mod therefore loads and no-ops cleanly with any subset of the three optional
 * mods installed.</p>
 */
@Mod(TensuraXaeroIntegration.MOD_ID)
public final class TensuraXaeroIntegration {

    public static final String MOD_ID = "tensura_xaero_integration";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TensuraXaeroIntegration(IEventBus modBus, ModContainer container) {
        ServerConfig.register(container);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientConfig.register(container);
            ClientSetup.register();
        }

        modBus.addListener(Network::register);
        modBus.addListener(TensuraXaeroIntegration::onConfigReloaded);

        NeoForge.EVENT_BUS.addListener(TensuraXaeroIntegration::onServerStarted);
        NeoForge.EVENT_BUS.addListener(TensuraXaeroIntegration::onServerStopping);
        NeoForge.EVENT_BUS.addListener(TensuraXaeroIntegration::onServerTick);
        NeoForge.EVENT_BUS.addListener(TensuraXaeroIntegration::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(TensuraXaeroIntegration::onPlayerLoggedOut);

        LOGGER.info("Tensura Xaero Integration initialized (Tensura: {})", TensuraBridge.isAvailable());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        ConcentrationServer.get().start(event.getServer());
        // Only attaches when Tensura is installed; the accessor class is never
        // loaded otherwise, so the listener cannot reference a missing type.
        TensuraBridge.registerTickObserver();
    }

    private static void onServerStopping(ServerStoppingEvent event) {
        TensuraBridge.unregisterTickObserver();
        ConcentrationServer.get().stop();
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        ConcentrationServer.get().onServerTick(event.getServer());
    }

    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            ConcentrationServer.get().onLogin(player);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            ConcentrationServer.get().onLogout(player);
        }
    }

    /**
     * Repaints the client when the visual config changes: threshold or colour
     * edits must not wait for the next concentration change to appear.
     */
    private static void onConfigReloaded(ModConfigEvent.Reloading event) {
        if (FMLEnvironment.dist == Dist.CLIENT && event.getConfig().getType() == ModConfig.Type.CLIENT) {
            ClientSetup.onClientConfigReloaded();
        }
    }
}
