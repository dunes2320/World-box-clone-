package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PathfinderTest {

    /** A grass island in a deep-water sea, so the tile grid has real bounds. */
    private static World islandWorld(int size, int islandSize) {
        World world = new World(size);
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = -4.0f;
            world.tileType[i] = TileType.DEEP_WATER;
        }
        int islandStart = (size - islandSize) / 2;
        int islandEnd = islandStart + islandSize;
        for (int z = islandStart; z < islandEnd; z++) {
            for (int x = islandStart; x < islandEnd; x++) {
                int i = world.index(x, z);
                world.height[i] = 2.0f;
                world.tileType[i] = TileType.GRASS;
            }
        }
        return world;
    }

    private static World grassWorld(int size) {
        World world = new World(size);
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2.0f;
            world.tileType[i] = TileType.GRASS;
        }
        return world;
    }

    @Test
    void aPathToTheStartIsALengthOnePath() {
        World world = grassWorld(64);
        Pathfinder pf = new Pathfinder(world.size);
        assertTrue(pf.findPath(world, 10, 10, 10, 10));
        assertTrue(pf.getResultLength() == 1);
        assertTrue(pf.getResultPath()[0] == world.index(10, 10));
    }

    @Test
    void aStraightLinePathReturnsExactlyTheStepsBetween() {
        World world = grassWorld(64);
        Pathfinder pf = new Pathfinder(world.size);
        assertTrue(pf.findPath(world, 10, 10, 10, 15));
        // Octile A*: an orthogonal-only run takes six tiles (start + 5 steps).
        assertTrue(pf.getResultLength() == 6);
        int[] path = pf.getResultPath();
        // First tile is the start, last is the goal.
        assertTrue(path[0] == world.index(10, 10));
        assertTrue(path[pf.getResultLength() - 1] == world.index(10, 15));
    }

    @Test
    void aDiagonalPathUsesFewerStepsThanTaxicab() {
        World world = grassWorld(64);
        Pathfinder pf = new Pathfinder(world.size);
        assertTrue(pf.findPath(world, 10, 10, 15, 15));
        // Diagonal moves are allowed on open ground, so 5 diagonals + start = 6.
        assertTrue(pf.getResultLength() == 6,
            "diagonal-only run should be 6 tiles, got " + pf.getResultLength());
    }

    @Test
    void aPathAroundWaterFindsTheLongWay() {
        // A grass corridor with a water gap the pathfinder must go around.
        World world = grassWorld(32);
        for (int z = 5; z < 15; z++) {
            world.tileType[world.index(15, z)] = TileType.DEEP_WATER;
            world.height[world.index(15, z)] = -4f;
        }

        Pathfinder pf = new Pathfinder(world.size);
        assertTrue(pf.findPath(world, 10, 10, 20, 10));
        // Straight-line would be 11 tiles; going around the wall costs more.
        assertTrue(pf.getResultLength() > 11,
            "the detour should be longer than the straight line, got "
                + pf.getResultLength());
    }

    @Test
    void aBlockedGoalIsUnreachable() {
        World world = islandWorld(64, 20);
        Pathfinder pf = new Pathfinder(world.size);
        assertFalse(pf.findPath(world, 32, 32, 0, 0),
            "the goal is in deep water; no path can end there");
    }

    @Test
    void diagonalMovesRefuseToClipThroughACornerOfImpassableTiles() {
        // Two water tiles at (11,10) and (10,11); a diagonal step from
        // (10,10) to (11,11) would slip between them. That must not happen.
        World world = grassWorld(32);
        world.tileType[world.index(11, 10)] = TileType.DEEP_WATER;
        world.tileType[world.index(10, 11)] = TileType.DEEP_WATER;
        world.height[world.index(11, 10)] = -4f;
        world.height[world.index(10, 11)] = -4f;

        Pathfinder pf = new Pathfinder(world.size);
        assertTrue(pf.findPath(world, 10, 10, 11, 11));
        // The path must NOT be (10,10) -> (11,11) directly. It should go
        // around, so its length is greater than two tiles.
        assertTrue(pf.getResultLength() > 2,
            "diagonal must not clip corners; got a path of "
                + pf.getResultLength() + " tiles");
    }

    @Test
    void repeatedSearchesOnOneInstanceStayCorrect() {
        // The stamp-based scratch reset has to work across many searches
        // without residual state from a prior search.
        World world = grassWorld(64);
        Pathfinder pf = new Pathfinder(world.size);
        for (int i = 0; i < 50; i++) {
            assertTrue(pf.findPath(world, 5, 5, 5 + i % 30, 5 + (i * 7) % 30));
            assertTrue(pf.getResultLength() >= 1);
        }
    }

    @Test
    void aStartOnOpenWaterIsUnreachable() {
        World world = islandWorld(64, 20);
        Pathfinder pf = new Pathfinder(world.size);
        assertFalse(pf.findPath(world, 0, 0, 32, 32));
    }
}
