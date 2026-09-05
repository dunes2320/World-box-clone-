package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldGenTest {

    @Test
    void sameSeedProducesIdenticalWorld() {
        World a = WorldGen.generate(4242L);
        World b = WorldGen.generate(4242L);
        assertArrayEquals(a.tileType, b.tileType, "tile types must be reproducible from the seed");
        assertArrayEquals(a.height, b.height, 0.0f, "heights must be reproducible from the seed");
        assertArrayEquals(a.fertility, b.fertility, 0.0f, "fertility must be reproducible from the seed");
    }

    @Test
    void differentSeedsProduceDifferentWorlds() {
        World a = WorldGen.generate(1L);
        World b = WorldGen.generate(2L);
        int differing = 0;
        for (int i = 0; i < a.tileCount; i++) {
            if (a.height[i] != b.height[i]) {
                differing++;
            }
        }
        assertTrue(differing > a.tileCount / 2,
            "two seeds should produce visibly different terrain, only " + differing + " tiles differed");
    }

    @Test
    void worldEdgesAreDeepWater() {
        // The island falloff exists so the map has a coastline instead of
        // ending at a cliff; if it regresses, the border stops being water.
        World world = WorldGen.generate(77L);
        int last = world.size - 1;
        for (int i = 0; i < world.size; i++) {
            assertEquals(TileType.DEEP_WATER, world.typeAt(i, 0), "top edge should be deep water at x=" + i);
            assertEquals(TileType.DEEP_WATER, world.typeAt(i, last), "bottom edge should be deep water at x=" + i);
            assertEquals(TileType.DEEP_WATER, world.typeAt(0, i), "left edge should be deep water at z=" + i);
            assertEquals(TileType.DEEP_WATER, world.typeAt(last, i), "right edge should be deep water at z=" + i);
        }
    }

    @Test
    void generatesVariedTerrainWithRealLand() {
        World world = WorldGen.generate(2024L);
        int[] counts = new int[TileType.COUNT];
        for (int i = 0; i < world.tileCount; i++) {
            counts[world.tileType[i]]++;
        }

        int distinct = 0;
        for (int count : counts) {
            if (count > 0) {
                distinct++;
            }
        }
        assertTrue(distinct >= 5, "expected a varied world, got only " + distinct + " tile types");

        int land = world.tileCount - counts[TileType.DEEP_WATER] - counts[TileType.SHALLOW_WATER];
        double landFraction = land / (double) world.tileCount;
        // A world that is nearly all ocean or nearly all continent is a
        // generation failure, not a rare seed - the falloff and bias should
        // keep this in a sane band for any seed.
        assertTrue(landFraction > 0.15 && landFraction < 0.75,
            "land fraction should be a plausible island, got " + landFraction);
    }

    @Test
    void heightsStayWithinConfiguredBounds() {
        World world = WorldGen.generate(31337L);
        for (int i = 0; i < world.tileCount; i++) {
            float h = world.height[i];
            assertTrue(h >= SimConfig.MIN_HEIGHT && h <= SimConfig.MAX_HEIGHT,
                "height " + h + " escaped configured bounds at index " + i);
        }
    }

    @Test
    void tileTypeAlwaysMatchesItsOwnHeight() {
        // Terraforming reclassifies via the same function, so any drift between
        // stored type and stored height would be a real bug.
        World world = WorldGen.generate(555L);
        for (int i = 0; i < world.tileCount; i++) {
            byte expected = TileType.fromTerrain(world.height[i], world.fertility[i]);
            assertEquals(expected, world.tileType[i], "tile type disagrees with its height at index " + i);
        }
    }

    @Test
    void severalSeedsAllProduceUsableWorlds() {
        for (long seed = 0; seed < 12; seed++) {
            World world = WorldGen.generate(seed);
            int land = 0;
            for (int i = 0; i < world.tileCount; i++) {
                if (!TileType.isWater(world.tileType[i])) {
                    land++;
                }
            }
            double fraction = land / (double) world.tileCount;
            assertTrue(fraction > 0.1, "seed " + seed + " generated an almost empty ocean (" + fraction + ")");
        }
    }
}
