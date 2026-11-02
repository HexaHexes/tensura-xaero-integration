package org.bitwisemadness.tensuraxaerointegration.datagen;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;

/**
 * Writes a pre-built map of datapack files, keyed by their path inside the jar.
 *
 * <p>The content itself lives in {@link MagiculeTierContent}; this class only
 * handles the plumbing, so every file goes through the same vanilla writer
 * ({@link DataProvider#saveStable}) and comes out in the canonical format the
 * other packs use.</p>
 */
public final class JsonFilesProvider implements DataProvider {
    private final String name;
    private final PackOutput output;
    private final Map<String, JsonObject> files;

    public JsonFilesProvider(String name, PackOutput output, Map<String, JsonObject> files) {
        this.name = name;
        this.output = output;
        this.files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
    }

    @Override
    public CompletableFuture<?> run(CachedOutput cache) {
        Path root = output.getOutputFolder();
        List<CompletableFuture<?>> writes = new ArrayList<>(files.size());
        files.forEach((path, json) -> writes.add(DataProvider.saveStable(cache, json, root.resolve(path))));
        return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
    }

    @Override
    public String getName() {
        return name;
    }
}
