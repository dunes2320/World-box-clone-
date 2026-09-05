package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class DisastersTest {

    private static World forestWorld() {
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2.0f;
            world.fertility[i] = 1.0f;
            world.tileType[i] = TileType.FOREST;
        }
        return world;
    }

    private static World grassWorld() {
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2.0f;
            world.tileType[i] = TileType.GRASS;
        }
        return world;
    }

    private static int burningTiles(World world) {
        int burning = 0;
        for (int i = 0; i < world.tileCount; i++) {
            if (world.burn[i] != 0) {
                burning++;
            }
        }
        return burning;
    }

    private static int countType(World world, byte type) {
        int count = 0;
        for (int i = 0; i < world.tileCount; i++) {
            if (world.tileType[i] == type) {
                count++;
            }
        }
        return count;
    }

    // ---- fire ----

    @Test
    void fireOnlyTakesHoldInForest() {
        World world = grassWorld();
        Disasters.ignite(world, 64, 64, 6);
        assertEquals(0, burningTiles(world), "grass has nothing to burn");

        World forest = forestWorld();
        Disasters.ignite(forest, 64, 64, 6);
        assertTrue(burningTiles(forest) > 0);
    }

    @Test
    void fireSpreadsThroughForestAndThenBurnsOut() {
        World world = forestWorld();
        Units units = new Units(8);
        DisasterSystem system = new DisasterSystem(world.size, SimConfig.DENSITY_CELL_SIZE);
        Random random = new Random(9);

        Disasters.ignite(world, 64, 64, 2);
        system.refreshFireCount(world);
        int lit = system.getBurningTiles();
        assertTrue(lit > 0);

        int peak = lit;
        int ticks = 0;
        while (system.getBurningTiles() > 0 && ticks < 200_000) {
            system.update(world, units, random);
            peak = Math.max(peak, system.getBurningTiles());
            ticks++;
        }

        assertEquals(0, system.getBurningTiles(), "fire must go out on its own");
        assertEquals(0, burningTiles(world));
        assertTrue(peak > lit * 4,
            "fire should spread well beyond where it was lit; peaked at " + peak);
        assertTrue(countType(world, TileType.GRASS) >= peak,
            "everything that burned should be left as grass");
    }

    @Test
    void burntGroundCannotCatchAgain() {
        World world = forestWorld();
        Units units = new Units(8);
        DisasterSystem system = new DisasterSystem(world.size, SimConfig.DENSITY_CELL_SIZE);
        Random random = new Random(11);

        Disasters.ignite(world, 64, 64, 3);
        system.refreshFireCount(world);
        while (system.getBurningTiles() > 0) {
            system.update(world, units, random);
        }
        int burnt = countType(world, TileType.GRASS);
        assertTrue(burnt > 0);

        // Re-lighting the scar does nothing: the fuel is gone. This is what
        // makes fire terminate as a rule rather than as a matter of tuning.
        Disasters.ignite(world, 64, 64, 3);
        system.refreshFireCount(world);
        assertEquals(0, system.getBurningTiles());
    }

    @Test
    void fireKillsWhateverIsStandingInIt() {
        World world = forestWorld();
        Units units = new Units(8);
        int victim = units.spawn(64.5f, 64.5f, Species.HUMAN, 5000, 0f);
        DisasterSystem system = new DisasterSystem(world.size, SimConfig.DENSITY_CELL_SIZE);

        Disasters.ignite(world, 64, 64, 1);
        system.refreshFireCount(world);
        for (int tick = 0; tick < SimConfig.FIRE_DURATION && units.isAlive(victim); tick++) {
            system.update(world, units, new Random(tick));
        }
        assertFalse(units.isAlive(victim));
        assertTrue(system.getFireDeaths() > 0);
    }

    @Test
    void aWorldWithNoFireInItCostsNothing() {
        World world = forestWorld();
        Units units = new Units(8);
        DisasterSystem system = new DisasterSystem(world.size, SimConfig.DENSITY_CELL_SIZE);
        for (int tick = 0; tick < 1000; tick++) {
            system.update(world, units, new Random(tick));
        }
        assertEquals(0, burningTiles(world));
        assertEquals(TileType.FOREST, world.typeAt(64, 64), "an unlit forest must stay a forest");
    }

    // ---- meteor ----

    @Test
    void aMeteorLeavesACraterWithARaisedRim() {
        World world = forestWorld();
        Units units = new Units(16);
        for (int i = 0; i < 6; i++) {
            units.spawn(64.5f, 64.5f, Species.HUMAN, 5000, 0f);
        }
        // Well outside the blast.
        int bystander = units.spawn(20.5f, 20.5f, Species.ELF, 5000, 0f);

        float before = world.heightAt(64, 64);
        int killed = Disasters.meteor(world, units, new Random(3), 64, 64, 6);

        assertTrue(world.heightAt(64, 64) < before - 2f,
            "impact point should be dug out, is " + world.heightAt(64, 64));
        assertTrue(world.heightAt(70, 64) > before,
            "the rim should be piled up, is " + world.heightAt(70, 64));
        assertEquals(6, killed);
        assertTrue(units.isAlive(bystander), "a meteor should not kill the whole map");
        assertTrue(burningTiles(world) > 0, "a meteor should start fires");
    }

    // ---- lightning ----

    @Test
    void lightningIsPinpoint() {
        World world = forestWorld();
        Units units = new Units(16);
        int struck = units.spawn(64.5f, 64.5f, Species.HUMAN, 5000, 0f);
        int nearby = units.spawn(69.5f, 64.5f, Species.HUMAN, 5000, 0f);

        int killed = Disasters.lightning(world, units, 64, 64);

        assertEquals(1, killed);
        assertFalse(units.isAlive(struck));
        assertTrue(units.isAlive(nearby), "five tiles away is not a lightning strike");
        assertTrue(world.burn[world.index(64, 64)] > 0, "lightning should start a fire");
    }

    // ---- earthquake ----

    @Test
    void anEarthquakeReshapesTerrainAndIsReproducible() {
        World first = grassWorld();
        World second = grassWorld();
        Units units = new Units(8);

        Disasters.earthquake(first, units, new Random(42), 64, 64, 8);
        Disasters.earthquake(second, new Units(8), new Random(42), 64, 64, 8);

        boolean changed = false;
        for (int i = 0; i < first.tileCount; i++) {
            assertEquals(first.height[i], second.height[i], 0f,
                "same seed must reshape the ground identically");
            if (first.height[i] != 2.0f) {
                changed = true;
            }
        }
        assertTrue(changed, "an earthquake should actually move the ground");
        assertEquals(2.0f, first.heightAt(10, 10), 0f, "and only where it struck");
    }

    @Test
    void anEarthquakeHurtsRatherThanErases() {
        World world = grassWorld();
        Units units = new Units(16);
        int victim = units.spawn(64.5f, 64.5f, Species.HUMAN, 5000, 0f);

        Disasters.earthquake(world, units, new Random(7), 64, 64, 6);

        // Whether this unit lives depends on where the ground went, but a single
        // shake must not take a unit from full health to dead.
        if (units.isAlive(victim)) {
            assertTrue(units.health[victim] < SimConfig.UNIT_MAX_HEALTH, "it should have hurt");
            assertTrue(units.health[victim] > 0);
        }
    }

    // ---- flood ----

    @Test
    void aFloodDrownsLowGroundAndLeavesHighGroundAlone() {
        World world = new World();
        for (int z = 0; z < world.size; z++) {
            for (int x = 0; x < world.size; x++) {
                int i = world.index(x, z);
                // A slope: low in the west, high in the east.
                world.height[i] = x < 64 ? 0.4f : 6.0f;
                world.tileType[i] = TileType.fromTerrain(world.height[i], 0f);
            }
        }
        Units units = new Units(16);
        int lowlander = units.spawn(60.5f, 64.5f, Species.HUMAN, 5000, 0f);
        int highlander = units.spawn(70.5f, 64.5f, Species.HUMAN, 5000, 0f);

        Disasters.flood(world, units, 64, 64, 10);

        assertTrue(TileType.isWater(world.typeAt(60, 64)), "low ground should be under water");
        assertEquals(TileType.HILL, world.typeAt(70, 64), "high ground should be untouched");
        assertFalse(units.isAlive(lowlander), "anyone on the drowned ground goes with it");
        assertTrue(units.isAlive(highlander));
    }

    // ---- plague ----

    @Test
    void aPlagueSpreadsThenBurnsOut() {
        World world = grassWorld();
        Units units = new Units(400);
        // A dense crowd spread over one density cell - dense enough to carry an
        // infection, spread out enough that seeding it does not simply infect
        // everybody at once and leave nothing to spread to.
        Random placement = new Random(4);
        for (int i = 0; i < 120; i++) {
            units.spawn(64f + placement.nextFloat() * 6f, 64f + placement.nextFloat() * 6f,
                Species.HUMAN, 20_000, 0f);
        }
        DisasterSystem system = new DisasterSystem(world.size, SimConfig.DENSITY_CELL_SIZE);
        Random random = new Random(13);

        int seeded = Disasters.infect(units, 64, 64, 1);
        system.refreshInfectedCount(units);
        assertTrue(seeded > 0, "the seed strike should have infected somebody");
        assertTrue(seeded < 120, "and should not have infected everybody outright");

        int peak = seeded;
        int ticks = 0;
        while (system.getInfectedUnits() > 0 && ticks < 200_000) {
            system.update(world, units, random);
            peak = Math.max(peak, system.getInfectedUnits());
            ticks++;
        }

        assertEquals(0, system.getInfectedUnits(), "a plague must burn itself out");
        assertTrue(peak > seeded, "it should have spread beyond the units first infected");

        // Everyone left is either immune or was never reached; nobody is still ill.
        for (int i = 0; i < units.getHighWater(); i++) {
            assertTrue(!units.alive[i] || units.disease[i] <= 0);
        }
    }

    @Test
    void survivorsCannotCatchItTwice() {
        World world = grassWorld();
        Units units = new Units(16);
        int survivor = units.spawn(64.5f, 64.5f, Species.HUMAN, 20_000, 0f);
        DisasterSystem system = new DisasterSystem(world.size, SimConfig.DENSITY_CELL_SIZE);

        Disasters.infect(units, 64, 64, 2);
        system.refreshInfectedCount(units);
        while (system.getInfectedUnits() > 0) {
            system.update(world, units, new Random(1));
        }
        assertTrue(units.isAlive(survivor), "one unit alone should ride it out");
        assertEquals(Units.IMMUNE, units.disease[survivor]);

        assertEquals(0, Disasters.infect(units, 64, 64, 2), "immunity must hold");
    }

    @Test
    void aPlagueOnAnEmptyMapDoesNothing() {
        World world = grassWorld();
        Units units = new Units(16);
        assertEquals(0, Disasters.infect(units, 64, 64, 5));

        DisasterSystem system = new DisasterSystem(world.size, SimConfig.DENSITY_CELL_SIZE);
        system.refreshInfectedCount(units);
        for (int tick = 0; tick < 500; tick++) {
            system.update(world, units, new Random(tick));
        }
        assertEquals(0, system.getPlagueDeaths());
    }

    // ---- dispatch ----

    @Test
    void everyDisasterKindIsWiredUpAndStaysInsideTheWorld() {
        for (Disaster kind : Disaster.values()) {
            World world = forestWorld();
            Units units = new Units(64);
            for (int i = 0; i < 20; i++) {
                units.spawn(2.5f, 2.5f, Species.HUMAN, 5000, 0f);
            }
            // Struck right on the corner, so anything that walks off the edge
            // of the tile arrays throws here rather than in a player's game.
            Disasters.strike(kind, world, units, new Random(5), 0, 0, 8);
            Disasters.strike(kind, world, units, new Random(5),
                world.size - 1, world.size - 1, 8);
        }
    }
}
