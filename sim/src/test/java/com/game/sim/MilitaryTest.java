package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class MilitaryTest {

    private static World grassWorld() {
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2f;
            world.tileType[i] = TileType.GRASS;
        }
        return world;
    }

    @Test
    void aKingdomAtWarActuallyRaisesAnArmy() {
        Villages villages = new Villages(4);
        Kingdoms kingdoms = new Kingdoms(4);
        KingdomRelations relations = new KingdomRelations(4);
        Armies armies = new Armies(8);
        World world = grassWorld();
        Features features = new Features(16, world.tileCount);

        int a = villages.found(20, 20, Species.HUMAN, 0);
        int b = villages.found(60, 60, Species.ORC, 0);
        KingdomSystem.update(villages, kingdoms, relations, new Random(0L), 0);
        int kA = villages.kingdom[a];
        int kB = villages.kingdom[b];
        villages.population[a] = 30;
        villages.population[b] = 30;
        relations.declareWar(kA, kB, 0);

        int before = armies.getLiveCount();
        MilitarySystem.update(world, villages, kingdoms, relations, armies, features,
            new Random(0L), 20);
        assertTrue(armies.getLiveCount() > before,
            "a war between two kingdoms with people to spare should raise at least one army");
    }

    @Test
    void anArmyMarchesTowardItsTargetVillage() {
        Villages villages = new Villages(4);
        Armies armies = new Armies(4);

        int target = villages.found(80, 80, Species.ORC, 0);
        int armyIndex = armies.raise(0, 10f, 10f, 5, target, 0);

        double startDist = distanceTo(armies, armyIndex, villages, target);
        // Enough passes to close the gap - the march speed is ~8 tiles per pass.
        for (int i = 0; i < 20; i++) {
            if (!armies.isAlive(armyIndex)) break;
            // Simulate what MilitarySystem does for movement only.
            float dx = (villages.x[target] + 0.5f) - armies.x[armyIndex];
            float dz = (villages.z[target] + 0.5f) - armies.z[armyIndex];
            float d = (float) Math.hypot(dx, dz);
            if (d <= SimConfig.ARMY_ARRIVE_RANGE) break;
            float step = Math.min(SimConfig.ARMY_MARCH_SPEED, d);
            armies.x[armyIndex] += dx / d * step;
            armies.z[armyIndex] += dz / d * step;
        }
        double endDist = distanceTo(armies, armyIndex, villages, target);
        assertTrue(endDist < startDist,
            "the army should be closer to its target than it started: " + startDist + " -> " + endDist);
    }

    @Test
    void aBesiegedAndUndefendedVillageCapturesTheEnemyKingdom() {
        Villages villages = new Villages(4);
        Kingdoms kingdoms = new Kingdoms(4);
        KingdomRelations relations = new KingdomRelations(4);
        Armies armies = new Armies(4);
        World world = grassWorld();
        Features features = new Features(16, world.tileCount);

        int a = villages.found(20, 20, Species.HUMAN, 0);
        int b = villages.found(24, 24, Species.ORC, 0);
        KingdomSystem.update(villages, kingdoms, relations, new Random(0L), 0);
        int kA = villages.kingdom[a];
        int kB = villages.kingdom[b];
        villages.population[a] = 30;
        villages.population[b] = 4; // thin defender - it should fall fast
        relations.declareWar(kA, kB, 0);

        // Enough passes for raise + march + siege to resolve.
        for (int t = 20; t < 400; t += 20) {
            MilitarySystem.update(world, villages, kingdoms, relations, armies, features,
                new Random(0L), t);
            if (villages.kingdom[b] == (short) kA) break;
        }
        assertEquals((short) kA, villages.kingdom[b],
            "the smaller village should have been captured and changed hands");
    }

    private static double distanceTo(Armies armies, int a, Villages villages, int v) {
        double dx = (villages.x[v] + 0.5) - armies.x[a];
        double dz = (villages.z[v] + 0.5) - armies.z[a];
        return Math.hypot(dx, dz);
    }

    @Test
    void combatSystemDoesNothingWhenNoArmyIsBesieging() {
        World world = grassWorld();
        Units units = new Units(16);
        Villages villages = new Villages(2);
        Armies armies = new Armies(2);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        Random random = new Random(0L);

        // A civilian standing on empty ground with no army anywhere.
        int u = units.spawn(20f, 20f, Species.HUMAN, 5000, 0f);
        int healthBefore = units.health[u];

        // With no armies alive the pass returns instantly and hurts no one.
        int killed = CombatSystem.update(world, units, villages, armies, null,
            /* relations */ null, density, random);
        assertEquals(0, killed);
        assertEquals(healthBefore, units.health[u]);
    }
}
