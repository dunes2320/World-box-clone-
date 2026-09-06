package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RoadsTest {

    @Test
    void rejectsAnUnusableCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new Roads(0));
    }

    @Test
    void setAndClearMaintainTheCount() {
        Roads roads = new Roads(64);
        assertEquals(0, roads.getCount());
        assertTrue(roads.set(10));
        assertTrue(roads.isRoad(10));
        assertEquals(1, roads.getCount());
        assertFalse(roads.set(10), "second set on the same tile is a no-op");
        assertEquals(1, roads.getCount());
        assertTrue(roads.clear(10));
        assertFalse(roads.isRoad(10));
        assertEquals(0, roads.getCount());
    }

    @Test
    void pathfinderPrefersARoadWhenStartAndGoalSitOnIt() {
        // Road running the length of z=20. Start and goal both on the strip -
        // A* should stay on the road end to end.
        World world = new World(64);
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2f;
            world.tileType[i] = TileType.GRASS;
        }
        Roads roads = new Roads(world.tileCount);
        for (int x = 0; x < world.size; x++) {
            roads.set(world.index(x, 20));
        }
        Pathfinder pf = new Pathfinder(world.size);
        assertTrue(pf.findPath(world, roads, 10, 20, 30, 20));
        int[] path = pf.getResultPath();
        for (int i = 0; i < pf.getResultLength(); i++) {
            assertTrue(roads.isRoad(path[i]),
                "every tile on a road-only route should be a road; broke at index " + i);
        }
    }

    @Test
    void pathfinderStillTakesAShortcutWhenRoadDetourIsTooExpensive() {
        // Road far away; the straight open-ground path is still cheaper than
        // a detour. Road-awareness must not force paths through roads it
        // does not save on.
        World world = new World(64);
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2f;
            world.tileType[i] = TileType.GRASS;
        }
        Roads roads = new Roads(world.tileCount);
        for (int x = 0; x < world.size; x++) {
            roads.set(world.index(x, 60));
        }
        Pathfinder pf = new Pathfinder(world.size);
        assertTrue(pf.findPath(world, roads, 10, 10, 20, 10));
        // Should be a straight 11-tile hop, not a 100+-tile detour to z=60.
        assertTrue(pf.getResultLength() <= 12,
            "a distant road must not force a detour, got " + pf.getResultLength());
    }
}
