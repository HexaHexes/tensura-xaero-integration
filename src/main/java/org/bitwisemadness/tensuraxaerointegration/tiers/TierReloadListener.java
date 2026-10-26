package org.bitwisemadness.tensuraxaerointegration.tiers;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.bitwisemadness.tensuraxaerointegration.TensuraXaeroIntegration;

/**
 * Loads {@code data/<namespace>/magicule_tiers/*.json} into {@link MagiculeTiers}.
 *
 * <p>Registered on {@code AddReloadListenerEvent}, so it runs on world load and on
 * {@code /reload}: a pack can retune a dimension's normal without restarting the
 * server. All the schema work happens in {@link TierParser}; this class only
 * supplies the JSON and the logging, which keeps the parsing testable without a
 * game.</p>
 */
public final class TierReloadListener extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new Gson();

    public TierReloadListener() {
        super(GSON, TierParser.DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager resourceManager,
            ProfilerFiller profiler) {
        Map<String, JsonElement> byName = new LinkedHashMap<>();
        files.forEach((id, json) -> byName.put(id.toString(), json));

        TierParser.Result result = TierParser.parse(byName);
        for (String problem : result.problems()) {
            TensuraXaeroIntegration.LOGGER.warn("Magicule tier data problem: {}", problem);
        }
        MagiculeTiers.replace(result.table());
        TensuraXaeroIntegration.LOGGER.info(
                "Loaded magicule tiers for {} dimension(s): {}",
                result.table().describedDimensions().size(),
                String.join(", ", result.table().describedDimensions()));
    }
}
