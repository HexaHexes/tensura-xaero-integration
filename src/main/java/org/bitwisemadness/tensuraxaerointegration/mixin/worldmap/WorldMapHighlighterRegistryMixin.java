package org.bitwisemadness.tensuraxaerointegration.mixin.worldmap;

import org.bitwisemadness.tensuraxaerointegration.client.overlay.WorldMapConcentrationOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.highlight.AbstractHighlighter;
import xaero.map.highlight.HighlighterRegistry;

/**
 * Registers the world-map overlay into Xaero's highlighter registry.
 *
 * <p>Why a mixin: the registry is created inside {@code WorldMapSession.init},
 * handed to {@code MapProcessor}, and immediately frozen with
 * {@code end()} (the list becomes unmodifiable). Xaero calls
 * {@code SupportMods.xaeroPac.registerHighlighters(...)} for its own claims
 * integration in exactly that window, but exposes no equivalent call for other
 * mods — the public {@code getHighlighterRegistry()} only ever appears after
 * {@code end()}, where {@code register} throws. Injecting at the head of
 * {@code end()} is the narrowest point that runs while the list is still mutable
 * and guarantees the overlay is present for every session.</p>
 *
 * <p>The config is client-only and declares {@code requiredMods=["xaeroworldmap"]},
 * so this class can never load without the target present.</p>
 */
@Mixin(HighlighterRegistry.class)
public abstract class WorldMapHighlighterRegistryMixin {

    @Shadow
    public abstract void register(AbstractHighlighter highlighter);

    @Inject(method = "end", at = @At("HEAD"))
    private void tensuraXaero$registerConcentrationOverlay(CallbackInfo callback) {
        this.register(WorldMapConcentrationOverlay.INSTANCE);
    }
}
