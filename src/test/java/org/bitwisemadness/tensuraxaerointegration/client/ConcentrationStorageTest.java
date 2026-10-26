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
        assertTrue(storage.saveAndWait("sp:My World", contents(), 5_000L), "the write landed in time");

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
    void a_save_that_has_to_be_visible_waits_for_itself_not_for_the_clock() {
        ConcentrationStorage storage = new ConcentrationStorage(this.gameDirectory);
        // A save can be read back the moment it reports success: that is the
        // contract the session-change path relies on when it loads immediately
        // afterwards, and it no longer costs a fixed five-second wait.
        assertTrue(storage.saveAndWait("sp:immediate", contents(), ConcentrationStorage.WRITE_TIMEOUT_MILLIS));
        assertEquals(contents(), storage.load("sp:immediate", 100).orElseThrow());
    }

    @Test
    void a_flush_with_no_deadline_left_returns_instead_of_throwing() {
        ConcentrationStorage storage = new ConcentrationStorage(this.gameDirectory);
        storage.saveAsync("sp:late", contents());
        storage.flush(0L); // may or may not catch the write; it must not block or throw
        storage.flush();    // and this one lets it land
        assertEquals(contents(), storage.load("sp:late", 100).orElseThrow());
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
        assertTrue(storage.saveAndWait("", contents(), 5_000L));
        assertEquals(contents(), storage.load("", 100).orElseThrow());
    }
}
