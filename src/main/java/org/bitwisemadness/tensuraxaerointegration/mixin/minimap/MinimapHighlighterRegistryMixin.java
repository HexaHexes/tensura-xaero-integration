package org.bitwisemadness.tensuraxaerointegration.mixin.minimap;

import org.bitwisemadness.tensuraxaerointegration.client.overlay.MinimapConcentrationOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.minimap.highlight.AbstractHighlighter;
import xaero.common.minimap.highlight.HighlighterRegistry;

/**
 * Registers the minimap overlay into Xaero's minimap highlighter registry.
 *
 * <p>Why a mixin: {@code MinimapSession} creates the registry privately, freezes
 * it with {@code end()}, and passes it to a {@code MinimapWriter} whose field has
 * no accessor. Xaero only offers registration to its own bundled integrations
 * ({@code SupportXaeroWorldmap} and the claims mod), and the world map's
 * {@code registerMinimapHighlighters} forwarding hook is an empty method in the
 * pinned 1.21.1 builds (verified in the jar). Injecting at the head of
 * {@code end()} is the smallest hook that runs before the list is frozen.</p>
 *
 * <p>Independent config, client-only, {@code requiredMods=["xaerominimap"]}: a
 * world-map-only install never loads this class, and a minimap-only install never
 * needs the world map.</p>
 */
@Mixin(HighlighterRegistry.class)
public abstract class MinimapHighlighterRegistryMixin {

    @Shadow
    public abstract void register(AbstractHighlighter highlighter);

    @Inject(method = "end", at = @At("HEAD"))
    private void tensuraXaero$registerConcentrationOverlay(CallbackInfo callback) {
        this.register(MinimapConcentrationOverlay.INSTANCE);
    }
}
