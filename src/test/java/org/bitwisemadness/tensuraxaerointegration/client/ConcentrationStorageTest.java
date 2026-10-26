package org.bitwisemadness.tensuraxaerointegration.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bitwisemadness.tensuraxaerointegration.cache.ConcentrationFileFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConcentrationStorageTest {

    @TempDir
    Path gameDirectory;

    private static ConcentrationFileFormat.Contents contents() {
        return new ConcentrationFileFormat.Contents(List.of(
                new ConcentrationFileFormat.Row("minecraft:overworld", 34, -3, 600.0, 1),
                new ConcentrationFileFormat.Row("minecraft:the_nether", -7, 900, 12_500.5, -1)));
    }

    @Test
    void a_saved_world_reads_back_unchanged() {
        ConcentrationStorage storage = new ConcentrationStorage(this.gameDirectory);
        storage.saveNow("sp:My World", contents());

        assertEquals(contents(), storage.load("sp:My World", 100).orElseThrow());
        assertTrue(Files.isDirectory(this.gameDirectory.resolve(ConcentrationStorage.FOLDER)));
    }

    @Test
    void a_queued_save_lands_after_a_flush() {
        ConcentrationStorage storage = new ConcentrationStorage(this.gameDirectory);
        storage.saveAsync("mp:example.org:25565", contents());
        storage.flush();

        assertEquals(contents(), storage.load("mp:example.org:25565", 100).orElseThrow());
    }

    @Test
    void missing_and_corrupt_files_read_as_no_history() throws IOException {
        ConcentrationStorage storage = new ConcentrationStorage(this.gameDirectory);
        assertTrue(storage.load("sp:never-played", 100).isEmpty());

        Path file = storage.fileFor("sp:broken");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "this is not a concentration file");
        assertTrue(storage.load("sp:broken", 100).isEmpty(), "a corrupt file must not throw");
    }

    @Test
    void worlds_never_share_a_file() {
        ConcentrationStorage storage = new ConcentrationStorage(this.gameDirectory);
        assertNotEquals(storage.fileFor("sp:World"), storage.fileFor("sp:World2"));
        assertNotEquals(storage.fileFor("sp:a b"), storage.fileFor("sp:a_b"),
                "sanitising alone would collide, so a hash disambiguates");
    }

    @Test
    void encoded_names_stay_inside_the_storage_folder() {
        ConcentrationStorage storage = new ConcentrationStorage(this.gameDirectory);
        Path file = storage.fileFor("../../escape/attempt");
        assertEquals(this.gameDirectory.resolve(ConcentrationStorage.FOLDER), file.getParent());
        assertFalse(ConcentrationStorage.encode("../../escape/attempt").contains("/"));
        assertFalse(ConcentrationStorage.encode("sp:a b").contains(" "));
    }

    @Test
    void a_blank_world_token_is_still_usable() {
        ConcentrationStorage storage = new ConcentrationStorage(this.gameDirectory);
        storage.saveNow("", contents());
        assertEquals(contents(), storage.load("", 100).orElseThrow());
    }
}
