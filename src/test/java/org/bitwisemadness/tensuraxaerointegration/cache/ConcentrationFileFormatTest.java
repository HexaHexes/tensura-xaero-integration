package org.bitwisemadness.tensuraxaerointegration.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConcentrationFileFormatTest {

    private static ConcentrationFileFormat.Contents contents() {
        return new ConcentrationFileFormat.Contents(500.0, List.of(
                new ConcentrationFileFormat.Row("minecraft:overworld", 34, -3, 600.0),
                new ConcentrationFileFormat.Row("minecraft:the_nether", -7, 900, 12_500.5)));
    }

    private static byte[] body(ConcentrationFileFormat.Contents contents) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ConcentrationFileFormat.write(buffer, contents);
        return buffer.toByteArray();
    }

    private static ConcentrationFileFormat.Contents read(byte[] bytes, int maxRows) throws IOException {
        return ConcentrationFileFormat.read(new ByteArrayInputStream(bytes), maxRows);
    }

    @Test
    void a_written_file_reads_back_unchanged() throws IOException {
        ConcentrationFileFormat.Contents written = contents();
        assertEquals(written, read(body(written), 100));
    }

    @Test
    void an_empty_file_round_trips_including_an_unknown_baseline() throws IOException {
        ConcentrationFileFormat.Contents empty =
                new ConcentrationFileFormat.Contents(Double.NaN, List.of());
        ConcentrationFileFormat.Contents read = read(body(empty), 100);
        assertEquals(0, read.rows().size());
        assertTrue(Double.isNaN(read.baseline()));
    }

    @Test
    void a_foreign_file_is_rejected() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(0x12345678);
        assertThrows(IOException.class, () -> read(buffer.toByteArray(), 100));
    }

    @Test
    void an_unknown_version_is_rejected_rather_than_misread() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(ConcentrationFileFormat.MAGIC);
        out.writeByte(ConcentrationFileFormat.VERSION + 1);
        out.writeDouble(500.0);
        out.writeInt(0);
        out.flush();
        assertThrows(IOException.class, () -> read(buffer.toByteArray(), 100));
    }

    @Test
    void a_row_count_above_the_limit_is_rejected() throws IOException {
        byte[] bytes = body(contents());
        assertThrows(IOException.class, () -> read(bytes, 1));
    }

    @Test
    void a_negative_row_count_is_rejected() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(ConcentrationFileFormat.MAGIC);
        out.writeByte(ConcentrationFileFormat.VERSION);
        out.writeDouble(500.0);
        out.writeInt(-1);
        out.flush();
        assertThrows(IOException.class, () -> read(buffer.toByteArray(), 100));
    }

    @Test
    void a_non_finite_value_in_the_file_is_rejected() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(ConcentrationFileFormat.MAGIC);
        out.writeByte(ConcentrationFileFormat.VERSION);
        out.writeDouble(500.0);
        out.writeInt(1);
        out.writeUTF("minecraft:overworld");
        out.writeInt(0);
        out.writeInt(0);
        out.writeDouble(Double.NaN);
        out.flush();
        assertThrows(IOException.class, () -> read(buffer.toByteArray(), 100));
    }

    @Test
    void a_truncated_file_is_rejected() throws IOException {
        byte[] bytes = body(contents());
        byte[] truncated = new byte[bytes.length - 4];
        System.arraycopy(bytes, 0, truncated, 0, truncated.length);
        assertThrows(IOException.class, () -> read(truncated, 100));
    }

    @Test
    void rows_reject_values_that_are_not_observations() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationFileFormat.Row("minecraft:overworld", 0, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationFileFormat.Row("minecraft:overworld", 0, 0, -1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationFileFormat.Row(" ", 0, 0, 1.0));
    }

    @Test
    void contents_reject_an_impossible_baseline() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationFileFormat.Contents(0.0, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ConcentrationFileFormat.Contents(-5.0, List.of()));
    }
}
