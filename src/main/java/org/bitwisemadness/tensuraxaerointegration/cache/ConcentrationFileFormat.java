package org.bitwisemadness.tensuraxaerointegration.cache;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The on-disk format for a world's last-known concentrations.
 *
 * <p>Deliberately tiny and dependency-free: a magic number, a version byte, and
 * one row per chunk carrying the value and the band the server gave it.
 * Everything here is pure {@code java.io}, so the round trip is covered by the
 * JUnit suite without a game client.</p>
 *
 * <p>The format is written only by this mod and only ever contains chunks that
 * passed the mapped-only ingress, so a row is a real observation of a mapped
 * chunk. Reading is strict: a wrong magic number, an unknown version, a
 * non-finite value or an impossible row count throws {@link IOException} rather
 * than loading half-understood data. Callers are expected to treat a corrupt
 * file as "no history" and keep playing.</p>
 */
public final class ConcentrationFileFormat {

    /** "TXI1" - identifies this mod's files and the generation of the format. */
    public static final int MAGIC = 0x54584931;

    /**
     * Bump when the layout changes; older files are then rejected, not misread.
     *
     * <p>Version 2 stores the server's band per row and drops the single global
     * baseline, which no longer describes anything now that tiers are per
     * dimension.</p>
     */
    public static final int VERSION = 2;

    /** Hard ceiling on rows read from one file, independent of any config. */
    public static final int MAX_ROWS = 250_000;

    private ConcentrationFileFormat() {
    }

    /**
     * One chunk's remembered concentration.
     *
     * @param band the server's band code when it was observed, or -1
     */
    public record Row(String dimension, int chunkX, int chunkZ, double value, int band) {

        public Row {
            if (dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("a row needs a dimension id");
            }
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("a row needs a finite non-negative value, was " + value);
            }
            if (band < -1) {
                throw new IllegalArgumentException("a band code cannot be below -1, was " + band);
            }
        }
    }

    /** A whole file: one row per remembered chunk. */
    public record Contents(List<Row> rows) {

        public Contents {
            rows = List.copyOf(rows);
        }
    }

    /** Writes a file body. Rows are written in iteration order. */
    public static void write(OutputStream out, Contents contents) throws IOException {
        DataOutputStream data = out instanceof DataOutputStream stream ? stream : new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeByte(VERSION);
        data.writeInt(contents.rows().size());
        for (Row row : contents.rows()) {
            data.writeUTF(row.dimension());
            data.writeInt(row.chunkX());
            data.writeInt(row.chunkZ());
            data.writeDouble(row.value());
            data.writeByte(row.band());
        }
        data.flush();
    }

    /**
     * Reads a file body.
     *
     * @param maxRows most rows to accept; clamped to {@link #MAX_ROWS}
     * @throws IOException when the stream is not a valid file of this format
     */
    public static Contents read(InputStream in, int maxRows) throws IOException {
        int limit = Math.max(0, Math.min(MAX_ROWS, maxRows));
        DataInputStream data = in instanceof DataInputStream stream ? stream : new DataInputStream(in);

        int magic = data.readInt();
        if (magic != MAGIC) {
            throw new IOException("not a concentration file (magic " + Integer.toHexString(magic) + ")");
        }
        int version = data.readUnsignedByte();
        if (version != VERSION) {
            throw new IOException("unsupported concentration file version " + version);
        }
        int count = data.readInt();
        if (count < 0 || count > limit) {
            throw new IOException("concentration file declares " + count + " rows, limit is " + limit);
        }
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String dimension = data.readUTF();
            int chunkX = data.readInt();
            int chunkZ = data.readInt();
            double value = data.readDouble();
            int band = data.readByte();
            if (dimension.isBlank() || !Double.isFinite(value) || value < 0.0 || band < -1) {
                throw new IOException("invalid row " + i + " in concentration file");
            }
            rows.add(new Row(dimension, chunkX, chunkZ, value, band));
        }
        return new Contents(rows);
    }
}
