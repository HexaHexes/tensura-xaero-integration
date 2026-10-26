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
 * <p>Deliberately tiny and dependency-free: a magic number, a version byte, the
 * baseline, and one row per chunk. Everything here is pure {@code java.io}, so
 * the round trip is covered by the JUnit suite without a game client.</p>
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

    /** Bump when the layout changes; older files are then rejected, not misread. */
    public static final int VERSION = 1;

    /** Hard ceiling on rows read from one file, independent of any config. */
    public static final int MAX_ROWS = 250_000;

    private ConcentrationFileFormat() {
    }

    /** One chunk's remembered concentration. */
    public record Row(String dimension, int chunkX, int chunkZ, double value) {

        public Row {
            if (dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("a row needs a dimension id");
            }
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException("a row needs a finite non-negative value, was " + value);
            }
        }
    }

    /**
     * A whole file: the baseline the values were classified against, plus rows.
     *
     * @param baseline the server-reported baseline, or NaN when it was not known
     */
    public record Contents(double baseline, List<Row> rows) {

        public Contents {
            if (!Double.isNaN(baseline) && (!Double.isFinite(baseline) || baseline <= 0.0)) {
                throw new IllegalArgumentException("baseline must be positive or NaN, was " + baseline);
            }
            rows = List.copyOf(rows);
        }
    }

    /** Writes a file body. Rows are written in iteration order. */
    public static void write(OutputStream out, Contents contents) throws IOException {
        DataOutputStream data = out instanceof DataOutputStream stream ? stream : new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeByte(VERSION);
        data.writeDouble(contents.baseline());
        data.writeInt(contents.rows().size());
        for (Row row : contents.rows()) {
            data.writeUTF(row.dimension());
            data.writeInt(row.chunkX());
            data.writeInt(row.chunkZ());
            data.writeDouble(row.value());
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
        double baseline = data.readDouble();
        if (!Double.isNaN(baseline) && (!Double.isFinite(baseline) || baseline <= 0.0)) {
            throw new IOException("invalid baseline in concentration file: " + baseline);
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
            if (dimension.isBlank() || !Double.isFinite(value) || value < 0.0) {
                throw new IOException("invalid row " + i + " in concentration file");
            }
            rows.add(new Row(dimension, chunkX, chunkZ, value));
        }
        return new Contents(baseline, rows);
    }
}
