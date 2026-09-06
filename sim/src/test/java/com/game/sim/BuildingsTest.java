package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuildingsTest {

    private static World grassWorld() {
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2f;
            world.tileType[i] = TileType.GRASS;
            world.fertility[i] = 0.4f;
        }
        return world;
    }

    @Test
    void farmsRefuseAnythingButGrass() {
        World world = grassWorld();
        world.tileType[world.index(10, 10)] = TileType.MOUNTAIN;
        world.tileType[world.index(11, 10)] = TileType.SAND;
        world.tileType[world.index(12, 10)] = TileType.HILL;
        assertEquals(0, Buildings.score(Features.KIND_FARM, world, 10, 10),
            "farm on mountain must refuse");
        assertEquals(0, Buildings.score(Features.KIND_FARM, world, 11, 10),
            "farm on sand must refuse");
        assertEquals(0, Buildings.score(Features.KIND_FARM, world, 12, 10),
            "farm on hill must refuse");
        assertTrue(Buildings.score(Features.KIND_FARM, world, 20, 20) > 0,
            "farm on grass should score positive");
    }

    @Test
    void farmsRewardFertileGround() {
        World world = grassWorld();
        world.fertility[world.index(20, 20)] = 0.1f;
        world.fertility[world.index(30, 30)] = 0.9f;
        int poor = Buildings.score(Features.KIND_FARM, world, 20, 20);
        int rich = Buildings.score(Features.KIND_FARM, world, 30, 30);
        assertTrue(rich > poor,
            "richer soil should score higher: rich=" + rich + " poor=" + poor);
    }

    @Test
    void docksNeedSandAndWater() {
        World world = grassWorld();
        // A little beach with water on one side.
        world.tileType[world.index(50, 50)] = TileType.SAND;
        world.height[world.index(50, 50)] = 0.3f;
        // A sand tile with no water neighbours whatsoever.
        world.tileType[world.index(70, 70)] = TileType.SAND;
        world.height[world.index(70, 70)] = 0.3f;
        // The neighbour that makes the first a dock candidate.
        world.tileType[world.index(51, 50)] = TileType.SHALLOW_WATER;
        world.height[world.index(51, 50)] = -0.3f;

        assertTrue(Buildings.score(Features.KIND_DOCK, world, 50, 50) > 0,
            "sand touching water should score");
        assertEquals(0, Buildings.score(Features.KIND_DOCK, world, 70, 70),
            "sand with no water neighbour is not a dock");
        assertEquals(0, Buildings.score(Features.KIND_DOCK, world, 20, 20),
            "dock on plain grass must refuse");
    }

    @Test
    void minesNeedHillAdjacentToStone() {
        World world = grassWorld();
        world.tileType[world.index(40, 40)] = TileType.HILL;
        // No stone nearby - a lonely hill.
        assertEquals(0, Buildings.score(Features.KIND_MINE, world, 40, 40));

        world.tileType[world.index(41, 40)] = TileType.MOUNTAIN;
        assertTrue(Buildings.score(Features.KIND_MINE, world, 40, 40) > 0,
            "a hill against a mountain is a mine site");
    }

    @Test
    void lumberCampsNeedNearbyForest() {
        World world = grassWorld();
        assertEquals(0, Buildings.score(Features.KIND_LUMBER_CAMP, world, 60, 60),
            "grass with no forest around is not a lumber site");
        world.tileType[world.index(61, 60)] = TileType.FOREST;
        assertTrue(Buildings.score(Features.KIND_LUMBER_CAMP, world, 60, 60) > 0);
    }

    @Test
    void housesGoOnAnyReasonableGround() {
        World world = grassWorld();
        world.tileType[world.index(10, 10)] = TileType.SAND;
        world.tileType[world.index(11, 10)] = TileType.HILL;
        world.tileType[world.index(12, 10)] = TileType.MOUNTAIN;
        world.tileType[world.index(13, 10)] = TileType.DEEP_WATER;
        assertTrue(Buildings.score(Features.KIND_HOUSE, world, 20, 20) > 0);
        assertTrue(Buildings.score(Features.KIND_HOUSE, world, 10, 10) > 0);
        assertTrue(Buildings.score(Features.KIND_HOUSE, world, 11, 10) > 0);
        assertEquals(0, Buildings.score(Features.KIND_HOUSE, world, 12, 10));
        assertEquals(0, Buildings.score(Features.KIND_HOUSE, world, 13, 10));
    }
}
