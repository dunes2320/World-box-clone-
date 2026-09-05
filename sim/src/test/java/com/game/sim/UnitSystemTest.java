package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class UnitSystemTest {

    /** A world of uniform terrain, so behaviour is isolated from world generation. */
    private static World flatWorld(byte tileType, float height) {
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = height;
            world.tileType[i] = tileType;
        }
        return world;
    }

    private static World grassWorld() {
        return flatWorld(TileType.GRASS, 2.0f);
    }

    private static DensityGrid newDensity() {
        return new DensityGrid(SimConfig.WORLD_SIZE, SimConfig.DENSITY_CELL_SIZE);
    }

    @Test
    void unitsDieOfOldAge() {
        World world = grassWorld();
        Units units = new Units(8);
        DensityGrid density = newDensity();
        Random random = new Random(1);
        int i = units.spawn(64f, 64f, Species.HUMAN, 50, 0f);

        for (int tick = 0; tick < 49; tick++) {
            UnitSystem.update(world, units, density, random);
        }
        assertTrue(units.isAlive(i), "should still be alive one tick short of its lifespan");

        UnitSystem.update(world, units, density, random);
        assertFalse(units.isAlive(i), "should die on reaching its maximum age");
        assertEquals(0, units.getLiveCount());
    }

    @Test
    void unitsStarveOnBarrenGroundAndSurviveOnGrass() {
        Random random = new Random(2);
        DensityGrid density = newDensity();

        World barren = flatWorld(TileType.SAND, 1.0f);
        Units starving = new Units(4);
        starving.spawn(64f, 64f, Species.HUMAN, 30000, 0f);

        int ticksToStarve = 0;
        while (starving.getLiveCount() > 0 && ticksToStarve < 5000) {
            UnitSystem.update(barren, starving, density, random);
            ticksToStarve++;
        }
        assertTrue(ticksToStarve < 5000, "a unit on barren ground must eventually starve");

        // The same unit on grass must not starve over the same span.
        World fed = grassWorld();
        DensityGrid density2 = newDensity();
        Units grazing = new Units(4);
        int survivor = grazing.spawn(64f, 64f, Species.HUMAN, 30000, 0f);
        for (int tick = 0; tick < ticksToStarve * 2; tick++) {
            UnitSystem.update(fed, grazing, density2, new Random(3));
        }
        assertTrue(grazing.isAlive(survivor), "a unit standing on grass should never starve");
        assertEquals(0, grazing.hunger[survivor], "grass should keep hunger fully satisfied");
    }

    @Test
    void unitsNeverLeaveWalkableGround() {
        // An island of grass ringed by deep water: wandering must not walk
        // anyone into the sea, however long they roam.
        World world = flatWorld(TileType.DEEP_WATER, -4.0f);
        for (int z = 60; z < 70; z++) {
            for (int x = 60; x < 70; x++) {
                int i = world.index(x, z);
                world.height[i] = 2.0f;
                world.tileType[i] = TileType.GRASS;
            }
        }

        Units units = new Units(64);
        DensityGrid density = newDensity();
        Random random = new Random(4);
        for (int n = 0; n < 20; n++) {
            units.spawn(65f, 65f, Species.HUMAN, 30000, n * 0.3f);
        }

        for (int tick = 0; tick < 1200; tick++) {
            UnitSystem.update(world, units, density, random);
            for (int i = 0; i < units.getHighWater(); i++) {
                if (units.alive[i]) {
                    assertTrue(UnitSystem.isWalkable(world, units.x[i], units.z[i]),
                        "unit " + i + " left walkable ground at tick " + tick
                            + " (" + units.x[i] + ", " + units.z[i] + ")");
                }
            }
        }
    }

    @Test
    void populationGrowsThenHoldsAtTheCap() {
        World world = grassWorld();
        Units units = new Units(SimConfig.MAX_UNITS);
        DensityGrid density = newDensity();
        Random random = new Random(5);

        UnitSystem.spawnBrush(world, units, random, 64, 64, 10, Species.ORC, 60);
        int seeded = units.getLiveCount();
        assertTrue(seeded > 0, "brush should have placed units on an all-grass world");

        int peak = 0;
        for (int tick = 0; tick < 20000; tick++) {
            UnitSystem.update(world, units, density, random);
            peak = Math.max(peak, units.getLiveCount());
            assertTrue(units.getLiveCount() <= SimConfig.POPULATION_CAP,
                "population blew past the cap at tick " + tick + ": " + units.getLiveCount());
        }

        assertTrue(peak > seeded, "a fed population should grow, peaked at " + peak);
        assertTrue(units.getLiveCount() > 0, "a fed population should not die out");
    }

    @Test
    void spawnBrushOnlyPlacesUnitsOnWalkableGround() {
        World world = flatWorld(TileType.DEEP_WATER, -4.0f);
        for (int z = 62; z < 68; z++) {
            for (int x = 62; x < 68; x++) {
                int i = world.index(x, z);
                world.height[i] = 2.0f;
                world.tileType[i] = TileType.GRASS;
            }
        }

        Units units = new Units(200);
        int placed = UnitSystem.spawnBrush(world, units, new Random(6), 65, 65, 12, Species.DWARF, 80);

        assertTrue(placed > 0, "should place at least some units on the island");
        for (int i = 0; i < units.getHighWater(); i++) {
            if (units.alive[i]) {
                assertTrue(UnitSystem.isWalkable(world, units.x[i], units.z[i]),
                    "spawned a unit on unwalkable ground");
            }
        }
    }

    @Test
    void spawnBrushOnOpenWaterPlacesNothing() {
        World world = flatWorld(TileType.DEEP_WATER, -4.0f);
        Units units = new Units(64);
        assertEquals(0, UnitSystem.spawnBrush(world, units, new Random(7), 64, 64, 8, Species.HUMAN, 40));
        assertEquals(0, units.getLiveCount());
    }

    @Test
    void spawnBrushStopsWhenThePoolIsFull() {
        World world = grassWorld();
        Units units = new Units(10);
        int placed = UnitSystem.spawnBrush(world, units, new Random(8), 64, 64, 10, Species.HUMAN, 500);
        assertEquals(10, placed, "should fill the pool exactly and stop");
        assertTrue(units.isFull());
    }

    @Test
    void spawnedUnitsHaveStaggeredAges() {
        // All-identical starting ages would make a spawned group die together
        // later, punching a hole in the population curve.
        World world = grassWorld();
        Units units = new Units(200);
        UnitSystem.spawnBrush(world, units, new Random(9), 64, 64, 10, Species.HUMAN, 60);

        int distinct = 0;
        boolean[] seen = new boolean[SimConfig.UNIT_MATURITY + 1];
        for (int i = 0; i < units.getHighWater(); i++) {
            if (units.alive[i] && !seen[units.age[i]]) {
                seen[units.age[i]] = true;
                distinct++;
            }
        }
        assertTrue(distinct > 10, "spawned ages should be spread out, got " + distinct + " distinct values");
    }

    @Test
    void cullStrandedRemovesUnitsLeftOnUnwalkableGround() {
        World world = grassWorld();
        Units units = new Units(32);
        Random random = new Random(10);
        UnitSystem.spawnBrush(world, units, random, 64, 64, 5, Species.HUMAN, 20);
        int before = units.getLiveCount();
        assertTrue(before > 0);

        // Drop an ocean on the whole map, as the water brush would.
        for (int i = 0; i < world.tileCount; i++) {
            world.tileType[i] = TileType.DEEP_WATER;
            world.height[i] = -4.0f;
        }
        int culled = UnitSystem.cullStranded(world, units);

        assertEquals(before, culled, "everything should have been stranded");
        assertEquals(0, units.getLiveCount());
    }

    @Test
    void cullStrandedLeavesHealthyUnitsAlone() {
        World world = grassWorld();
        Units units = new Units(32);
        UnitSystem.spawnBrush(world, units, new Random(11), 64, 64, 5, Species.HUMAN, 20);
        int before = units.getLiveCount();

        assertEquals(0, UnitSystem.cullStranded(world, units));
        assertEquals(before, units.getLiveCount());
    }

    @Test
    void aThousandTickRunIsReproducibleFromTheSeed() {
        // The whole determinism contract in one test: same seed, same world,
        // same sequence of unit states a thousand ticks later.
        Simulation a = new Simulation(31337L);
        Simulation b = new Simulation(31337L);

        a.spawnUnits(64, 64, 12, Species.HUMAN, 120);
        b.spawnUnits(64, 64, 12, Species.HUMAN, 120);
        a.spawnUnits(40, 80, 10, Species.ORC, 80);
        b.spawnUnits(40, 80, 10, Species.ORC, 80);

        for (int tick = 0; tick < 1000; tick++) {
            a.tick();
            b.tick();
        }

        Units ua = a.getUnits();
        Units ub = b.getUnits();
        assertEquals(ua.getLiveCount(), ub.getLiveCount(), "populations diverged");
        assertEquals(ua.getHighWater(), ub.getHighWater(), "pool usage diverged");

        for (int i = 0; i < ua.getHighWater(); i++) {
            assertEquals(ua.alive[i], ub.alive[i], "liveness diverged at slot " + i);
            if (!ua.alive[i]) {
                continue;
            }
            assertEquals(ua.x[i], ub.x[i], 0f, "x diverged at slot " + i);
            assertEquals(ua.z[i], ub.z[i], 0f, "z diverged at slot " + i);
            assertEquals(ua.age[i], ub.age[i], "age diverged at slot " + i);
            assertEquals(ua.hunger[i], ub.hunger[i], "hunger diverged at slot " + i);
            assertEquals(ua.species[i], ub.species[i], "species diverged at slot " + i);
        }
    }

    @Test
    void differentSeedsProduceDifferentRuns() {
        Simulation a = new Simulation(1L);
        Simulation b = new Simulation(2L);
        a.spawnUnits(64, 64, 12, Species.HUMAN, 100);
        b.spawnUnits(64, 64, 12, Species.HUMAN, 100);
        for (int tick = 0; tick < 300; tick++) {
            a.tick();
            b.tick();
        }
        // Different terrain and different rolls: identical outcomes would mean
        // the seed is not actually reaching the unit simulation.
        boolean identical = a.getUnits().getLiveCount() == b.getUnits().getLiveCount()
            && a.getUnits().getHighWater() == b.getUnits().getHighWater()
            && a.getUnits().x[0] == b.getUnits().x[0];
        assertFalse(identical, "two different seeds produced an identical run");
    }
}
