package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RelationSystemTest {

    private static World grassWorld() {
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2.0f;
            world.tileType[i] = TileType.GRASS;
        }
        return world;
    }

    /** Two villages of different species close enough for their claims to meet. */
    private static Villages adjacentRivals(World world, byte a, byte b) {
        Villages villages = new Villages(8);
        int first = villages.found(58, 64, a, 0);
        int second = villages.found(70, 64, b, 0);
        villages.population[first] = 20;
        villages.population[second] = 20;
        villages.radius[first] = 10f;
        villages.radius[second] = 10f;
        new Territory(world.tileCount).recompute(world, villages);
        return villages;
    }

    @Test
    void interlockingTerritoriesRegisterContact() {
        World world = grassWorld();
        Villages villages = adjacentRivals(world, Species.HUMAN, Species.ORC);
        RelationSystem system = new RelationSystem();
        Relations relations = new Relations(new Random(1));

        system.update(world, villages, relations, new Random(1), 0);

        assertTrue(system.contactsBetween(Species.HUMAN, Species.ORC) > 0,
            "two territories meeting should register a contested border");
        assertEquals(system.contactsBetween(Species.HUMAN, Species.ORC),
            system.contactsBetween(Species.ORC, Species.HUMAN));
        assertEquals(0, system.contactsBetween(Species.ELF, Species.DWARF));
    }

    @Test
    void territoriesOfTheSameSpeciesDoNotRubAgainstEachOther() {
        World world = grassWorld();
        Villages villages = adjacentRivals(world, Species.HUMAN, Species.HUMAN);
        RelationSystem system = new RelationSystem();

        system.update(world, villages, new Relations(new Random(1)), new Random(1), 0);

        for (byte a = 0; a < Species.COUNT; a++) {
            for (byte b = 0; b < Species.COUNT; b++) {
                assertEquals(0, system.contactsBetween(a, b),
                    "two human villages side by side is not a border dispute");
            }
        }
    }

    @Test
    void unclaimedGroundBetweenTwoSpeciesMeansNoFriction() {
        World world = grassWorld();
        Villages villages = new Villages(8);
        // Far apart, so a wide band of nobody's land lies between them.
        int first = villages.found(20, 20, Species.HUMAN, 0);
        int second = villages.found(100, 100, Species.ORC, 0);
        villages.population[first] = 20;
        villages.population[second] = 20;
        villages.radius[first] = 8f;
        villages.radius[second] = 8f;
        new Territory(world.tileCount).recompute(world, villages);

        RelationSystem system = new RelationSystem();
        system.update(world, villages, new Relations(new Random(1)), new Random(1), 0);

        assertEquals(0, system.contactsBetween(Species.HUMAN, Species.ORC));
    }

    @Test
    void neighboursEventuallyGoToWarAndDistantSpeciesDoNot() {
        World world = grassWorld();
        Villages villages = adjacentRivals(world, Species.HUMAN, Species.ORC);
        RelationSystem system = new RelationSystem();
        Relations relations = new Relations(new Random(2));
        Random random = new Random(99);

        boolean war = false;
        int tick = 0;
        for (int pass = 0; pass < 200 && !war; pass++) {
            tick += SimConfig.RELATION_UPDATE_INTERVAL;
            system.update(world, villages, relations, random, tick);
            war = relations.isAtWar(Species.HUMAN, Species.ORC);
        }

        assertTrue(war, "neighbours grinding against each other should end up at war");
        assertTrue(relations.between(Species.ELF, Species.DWARF) > SimConfig.WAR_THRESHOLD,
            "two species with no shared border have nothing to fight about");
    }

    @Test
    void aDeadVillagesTilesStopCountingAsBorder() {
        World world = grassWorld();
        Villages villages = adjacentRivals(world, Species.HUMAN, Species.ORC);
        RelationSystem system = new RelationSystem();
        Relations relations = new Relations(new Random(1));
        system.update(world, villages, relations, new Random(1), 0);
        assertTrue(system.contactsBetween(Species.HUMAN, Species.ORC) > 0);

        // Abandon the orc village without recomputing territory, so the world
        // still holds stale ownership pointing at a dead slot.
        for (int v = 0; v < villages.getHighWater(); v++) {
            if (villages.isAlive(v) && villages.species[v] == Species.ORC) {
                villages.abandon(v);
            }
        }
        system.update(world, villages, relations, new Random(1), 0);

        assertEquals(0, system.contactsBetween(Species.HUMAN, Species.ORC),
            "ownership pointing at an abandoned village must not count as territory");
    }
}
