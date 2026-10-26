package org.bitwisemadness.tensuraxaerointegration.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.bitwisemadness.tensuraxaerointegration.cache.ConcentrationFileFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-world storage for the client's last-known concentrations.
 *
 * <p>One file per world, all dimensions in it, under
 * {@code <game directory>/tensura_xaero_integration/}. A file only ever holds
 * chunks that passed the mapped-only ingress, so replaying it cannot reveal a
 * chunk this client had not mapped.</p>
 *
 * <p>Writes happen on a single daemon thread and land atomically (temp file plus
 * a move), so a crash mid-save cannot leave a half-written file - the previous
 * one simply stays. A corrupt or unreadable file is treated as "no history":
 * the caller gets an empty result and the game keeps running.</p>
 *
 * <p>Reading is synchronous and happens once per session start; it is a single
 * bounded file, not per-chunk I/O.</p>
 */
public final class ConcentrationStorage {

    /** Folder name under the game directory. */
    public static final String FOLDER = "tensura_xaero_integration";

    /**
     * How long the client thread will wait for the writer, at most.
     *
     * <p>A save is one bounded file written by a daemon thread, so this is a
     * ceiling for the two places that need a write to be visible - a session
     * change that immediately reloads the world, and logout - not a typical wait.
     * It used to be five seconds, which froze the client behind a disk that had
     * fallen behind. A write that exceeds it still completes in the background.</p>
     */
    public static final long WRITE_TIMEOUT_MILLIS = 250L;

    private static final Logger LOGGER = LoggerFactory.getLogger("tensura_xaero_integration/storage");

    private final Path directory;
    private final ExecutorService writer;

    public ConcentrationStorage(Path gameDirectory) {
        this.directory = gameDirectory.resolve(FOLDER);
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "tensura-xaero-concentration-save");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** The file backing a world token. */
    public Path fileFor(String worldToken) {
        return this.directory.resolve(encode(worldToken) + ".bin");
    }

    /**
     * Turns a world token into a filesystem-safe name.
     *
     * <p>World tokens are {@code sp:<level name>} or {@code mp:<address>}, which
     * can contain spaces, colons and slashes. The readable part is sanitised and
     * a hash of the exact token is appended, so two different worlds can never
     * map onto the same file.</p>
     */
    public static String encode(String worldToken) {
        String token = worldToken == null || worldToken.isBlank() ? "unknown" : worldToken;
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < token.length() && safe.length() < 48; i++) {
            char c = token.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_';
            safe.append(allowed ? c : '_');
        }
        return safe + "-" + String.format(Locale.ROOT, "%08x", token.hashCode());
    }

    /**
     * Queues a save. The contents must already be a snapshot; the caller builds
     * it on the client thread so the store is not read from the writer thread.
     */
    public void saveAsync(String worldToken, ConcentrationFileFormat.Contents contents) {
        Path target = fileFor(worldToken);
        this.writer.execute(() -> write(target, contents));
    }

    /**
     * Queues a save and waits for it, up to {@code timeoutMillis}.
     *
     * <p>For the session change that saves the world it is leaving and immediately
     * loads it again for the new dimension: the file has to be current when the
     * load runs. A write that misses the deadline keeps going on the writer thread,
     * and the caller loads whatever is on disk at that moment.</p>
     *
     * @return true when the write landed within the deadline
     */
    public boolean saveAndWait(String worldToken, ConcentrationFileFormat.Contents contents,
            long timeoutMillis) {
        return await(this.writer.submit(() -> write(fileFor(worldToken), contents)), timeoutMillis);
    }

    /** Reads a world's history, or empty when there is none or it cannot be read. */
    public Optional<ConcentrationFileFormat.Contents> load(String worldToken, int maxRows) {
        Path file = fileFor(worldToken);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            return Optional.of(ConcentrationFileFormat.read(in, maxRows));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Ignoring unreadable concentration history at {}: {}", file, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Waits for queued writes to finish without stopping the writer, so the next
     * session can keep saving. Called after the session's final save.
     */
    public void flush() {
        flush(WRITE_TIMEOUT_MILLIS);
    }

    /**
     * Waits up to {@code timeoutMillis} for everything already queued to land.
     *
     * <p>The single writer thread means a barrier task stands for every write
     * queued before it, so one wait covers them all.</p>
     */
    public void flush(long timeoutMillis) {
        await(this.writer.submit(() -> { }), timeoutMillis);
    }

    /** Waits for a writer task, reporting rather than throwing when it is late. */
    private static boolean await(Future<?> task, long timeoutMillis) {
        try {
            task.get(Math.max(0L, timeoutMillis), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException late) {
            LOGGER.debug("Concentration history write is still running after {} ms; it will finish on the writer thread",
                    timeoutMillis);
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException failure) {
            // write() has already logged what went wrong.
            return false;
        }
    }

    private void write(Path target, ConcentrationFileFormat.Contents contents) {
        Path temporary = null;
        try {
            Files.createDirectories(this.directory);
            temporary = Files.createTempFile(this.directory, "concentration-", ".tmp");
            try (OutputStream out = Files.newOutputStream(temporary)) {
                ConcentrationFileFormat.write(out, contents);
            }
            move(temporary, target);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not save concentration history to {}: {}", target, e.toString());
            deleteQuietly(temporary);
        }
    }

    private static void move(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover temp file is harmless; the next save overwrites it.
        }
    }
}
