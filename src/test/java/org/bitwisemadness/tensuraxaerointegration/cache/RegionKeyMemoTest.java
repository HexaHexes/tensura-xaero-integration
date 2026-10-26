package org.bitwisemadness.tensuraxaerointegration.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class RegionKeyMemoTest {

    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void the_same_region_is_one_key() {
        RegionKeyMemo memo = new RegionKeyMemo();
        RegionKey first = memo.keyFor(OVERWORLD, 3, -2);

        assertSame(first, memo.keyFor(OVERWORLD, 3, -2), "a region gate and its hash are one key");
        assertEquals(new RegionKey(OVERWORLD, 3, -2), first);
    }

    @Test
    void a_different_region_rebuilds() {
        RegionKeyMemo memo = new RegionKeyMemo();
        RegionKey first = memo.keyFor(OVERWORLD, 3, -2);

        RegionKey next = memo.keyFor(OVERWORLD, 4, -2);
        assertNotSame(first, next);
        assertEquals(new RegionKey(OVERWORLD, 4, -2), next);
        assertSame(next, memo.keyFor(OVERWORLD, 4, -2));
    }

    @Test
    void a_different_dimension_rebuilds() {
        RegionKeyMemo memo = new RegionKeyMemo();
        RegionKey overworld = memo.keyFor(OVERWORLD, 3, -2);

        RegionKey nether = memo.keyFor("minecraft:the_nether", 3, -2);
        assertNotSame(overworld, nether, "the same coordinates in another dimension are another region");
        assertEquals(new RegionKey("minecraft:the_nether", 3, -2), nether);
    }

    @Test
    void interleaved_regions_keep_one_entry() {
        RegionKeyMemo memo = new RegionKeyMemo();
        memo.keyFor(OVERWORLD, 1, 1);
        memo.keyFor(OVERWORLD, 2, 2);

        // One entry, so coming back to the first region builds again - correct, just
        // not cached. The caller asks about one region at a time.
        RegionKey back = memo.keyFor(OVERWORLD, 1, 1);
        assertEquals(new RegionKey(OVERWORLD, 1, 1), back);
        assertSame(back, memo.keyFor(OVERWORLD, 1, 1));
    }

    @Test
    void negative_coordinates_are_not_confused_with_positive_ones() {
        RegionKeyMemo memo = new RegionKeyMemo();
        RegionKey negative = memo.keyFor(OVERWORLD, -1, -1);

        RegionKey positive = memo.keyFor(OVERWORLD, 1, 1);
        assertNotSame(negative, positive);
        assertEquals(new RegionKey(OVERWORLD, -1, -1), negative);
    }
}
