package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WorldStatsTest {

    @Test
    void countsSumToTheWholeMap() {
        World world = WorldGen.generate(88L);
        int[] counts = new int[TileType.COUNT];
        WorldStats.countByType(world, counts);

        int total = 0;
        for (int count : counts) {
            total += count;
        }
        assertEquals(world.tileCount, total, "every tile must be counted exactly once");
    }

    @Test
    void countsMatchAManualTally() {
        World world = WorldGen.generate(5L);
        int[] counts = new int[TileType.COUNT];
        WorldStats.countByType(world, counts);

        int grassManual = 0;
        for (int i = 0; i < world.tileCount; i++) {
            if (world.tileType[i] == TileType.GRASS) {
                grassManual++;
            }
        }
        assertEquals(grassManual, counts[TileType.GRASS]);
    }

    @Test
    void reusingTheOutputArrayClearsStaleCounts() {
        // The HUD calls this every frame with the same array; leftovers from a
        // previous call would make the numbers climb forever.
        World world = WorldGen.generate(9L);
        int[] counts = new int[TileType.COUNT];
        WorldStats.countByType(world, counts);
        int firstGrass = counts[TileType.GRASS];

        WorldStats.countByType(world, counts);
        assertEquals(firstGrass, counts[TileType.GRASS], "counts must not accumulate across calls");
    }

    @Test
    void toleratesAnOversizedOutputArrayButRejectsAShortOne() {
        World world = WorldGen.generate(3L);
        WorldStats.countByType(world, new int[TileType.COUNT + 4]);
        assertThrows(IllegalArgumentException.class,
            () -> WorldStats.countByType(world, new int[TileType.COUNT - 1]));
    }

    @Test
    void landCountAgreesWithTheTypeBreakdown() {
        World world = WorldGen.generate(4242L);
        int[] counts = new int[TileType.COUNT];
        WorldStats.countByType(world, counts);

        int expectedLand = world.tileCount - counts[TileType.DEEP_WATER] - counts[TileType.SHALLOW_WATER];
        assertEquals(expectedLand, WorldStats.landTileCount(world));
        assertEquals(expectedLand / (float) world.tileCount, WorldStats.landFraction(world), 1e-6f);
    }

    @Test
    void landFractionRespondsToTerraforming() {
        World world = WorldGen.generate(11L);
        float before = WorldStats.landFraction(world);

        // Drown a large area; the reported land fraction must drop.
        for (int i = 0; i < 30; i++) {
            Terraform.addWater(world, 64, 64, 12, 0.6f);
        }
        assertTrue(WorldStats.landFraction(world) < before,
            "flooding a wide area should reduce the land fraction");
    }
}
