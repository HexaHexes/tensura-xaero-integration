package org.bitwisemadness.tensuraxaerointegration.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PackedChunkPosTest {

    @Test
    void positive_coordinates_round_trip() {
        long packed = PackedChunkPos.pack(1234, 5678);
        assertEquals(1234, PackedChunkPos.unpackX(packed));
        assertEquals(5678, PackedChunkPos.unpackZ(packed));
    }

    @Test
    void negative_coordinates_round_trip() {
        long packed = PackedChunkPos.pack(-1234, -5678);
        assertEquals(-1234, PackedChunkPos.unpackX(packed));
        assertEquals(-5678, PackedChunkPos.unpackZ(packed));
    }

    @Test
    void mixed_signs_round_trip() {
        long packed = PackedChunkPos.pack(-1, 0);
        assertEquals(-1, PackedChunkPos.unpackX(packed));
        assertEquals(0, PackedChunkPos.unpackZ(packed));

        long other = PackedChunkPos.pack(0, -1);
        assertEquals(0, PackedChunkPos.unpackX(other));
        assertEquals(-1, PackedChunkPos.unpackZ(other));
    }

    @Test
    void extreme_coordinates_round_trip() {
        long packed = PackedChunkPos.pack(Integer.MIN_VALUE, Integer.MAX_VALUE);
        assertEquals(Integer.MIN_VALUE, PackedChunkPos.unpackX(packed));
        assertEquals(Integer.MAX_VALUE, PackedChunkPos.unpackZ(packed));
    }

    @Test
    void distinct_coordinates_pack_distinctly() {
        assertEquals(false, PackedChunkPos.pack(1, 0) == PackedChunkPos.pack(0, 1));
    }
}
