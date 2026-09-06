package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FeaturesTest {

    @Test
    void rejectsAnUnusableCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new Features(0, 128 * 128));
    }

    @Test
    void placeAndRemoveReuseSlots() {
        Features features = new Features(4, 128 * 128);
        int t = 1024;
        int a = features.place(t, Features.KIND_HOUSE, 10, 20, (short) 1, 0);
        int b = features.place(t, Features.KIND_FARM, 30, 40, (short) 1, 0);
        assertEquals(2, features.getLiveCount());
        assertTrue(features.isAlive(a));
        assertTrue(features.isAlive(b));

        features.remove(a);
        assertFalse(features.isAlive(a));
        assertEquals(1, features.getLiveCount());

        int c = features.place(t, Features.KIND_MARKET, 5, 5, (short) 1, 0);
        assertEquals(a, c, "an emptied slot must be reused");
        assertNotEquals(b, c);
    }

    @Test
    void multipleFeaturesOnOneTileFormAChain() {
        Features features = new Features(8, 128 * 128);
        int tile = 500;
        features.place(tile, Features.KIND_HOUSE, 0, 0, (short) 0, 0);
        features.place(tile, Features.KIND_ROAD, 128, 128, (short) 0, 0);
        features.place(tile, Features.KIND_TEMPLE, 200, 200, (short) 0, 0);

        assertEquals(3, features.countOnTile(tile));

        // Walk the chain manually and check every hop lives on this tile.
        int walked = 0;
        for (int i = features.headAt(tile); i != Features.NONE; i = features.next[i]) {
            assertEquals(tile, features.tile[i]);
            walked++;
        }
        assertEquals(3, walked);
    }

    @Test
    void removingFromTheMiddleOfAChainKeepsTheRestValid() {
        Features features = new Features(8, 128 * 128);
        int tile = 42;
        int a = features.place(tile, Features.KIND_HOUSE, 0, 0, (short) 0, 0);
        int b = features.place(tile, Features.KIND_HOUSE, 0, 0, (short) 0, 0);
        int c = features.place(tile, Features.KIND_HOUSE, 0, 0, (short) 0, 0);

        features.remove(b);
        assertEquals(2, features.countOnTile(tile));
        // a and c must still be reachable via the chain, in any order.
        boolean sawA = false, sawC = false;
        for (int i = features.headAt(tile); i != Features.NONE; i = features.next[i]) {
            if (i == a) sawA = true;
            if (i == c) sawC = true;
            assertNotEquals(b, i, "the unlinked feature must not appear in the chain");
        }
        assertTrue(sawA);
        assertTrue(sawC);
    }

    @Test
    void placeReturnsMinusOneWhenTheFeaturePoolIsFull() {
        Features features = new Features(2, 128 * 128);
        assertTrue(features.place(1, Features.KIND_HOUSE, 0, 0, (short) 0, 0) >= 0);
        assertTrue(features.place(1, Features.KIND_HOUSE, 0, 0, (short) 0, 0) >= 0);
        assertEquals(-1, features.place(1, Features.KIND_HOUSE, 0, 0, (short) 0, 0));
        assertTrue(features.isFull());
    }

    @Test
    void removingUnrelatedFeaturesOnOtherTilesLeavesTheirChainsAlone() {
        Features features = new Features(6, 128 * 128);
        int here = 100;
        int there = 200;
        int a = features.place(here, Features.KIND_HOUSE, 0, 0, (short) 0, 0);
        features.place(there, Features.KIND_HOUSE, 0, 0, (short) 0, 0);
        features.place(there, Features.KIND_HOUSE, 0, 0, (short) 0, 0);

        features.remove(a);
        assertEquals(0, features.countOnTile(here));
        assertEquals(2, features.countOnTile(there));
    }
}
