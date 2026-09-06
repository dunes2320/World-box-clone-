package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EconomyTest {

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
    void aFreshVillageOpensWithFoundingSupplies() {
        Villages villages = new Villages(4);
        int v = villages.found(20, 20, Species.HUMAN, 0);
        assertEquals(SimConfig.FOUNDING_FOOD, villages.food[v]);
        assertEquals(SimConfig.FOUNDING_WOOD, villages.wood[v]);
        assertEquals(0, villages.stone[v]);
    }

    @Test
    void aReusedSlotOpensWithFreshSuppliesNotTheAbandonedStockpile() {
        Villages villages = new Villages(2);
        int a = villages.found(10, 10, Species.HUMAN, 0);
        villages.food[a] = 10_000;
        villages.wood[a] = 5_000;
        villages.abandon(a);

        int b = villages.found(30, 30, Species.HUMAN, 20);
        assertEquals(a, b, "the pool must reuse the slot for this test to be meaningful");
        assertEquals(SimConfig.FOUNDING_FOOD, villages.food[b],
            "a new village must not inherit the last owner's stockpile");
        assertEquals(SimConfig.FOUNDING_WOOD, villages.wood[b]);
    }

    @Test
    void completedFarmsProduceFoodAndConsumersReduceIt() {
        World world = grassWorld();
        Villages villages = new Villages(4);
        int v = villages.found(20, 20, Species.HUMAN, 0);
        villages.population[v] = 3;
        Units units = new Units(16);
        Features features = new Features(16, world.tileCount);
        // A finished farm - buildTime already zero, so it produces immediately.
        features.place(world.index(20, 20), Features.KIND_FARM, 128, 128, (short) v, 0);

        int before = villages.food[v];
        Economy.update(world, villages, units, features);

        int expected = before + SimConfig.FARM_YIELD_FOOD
            - 3 * SimConfig.FOOD_PER_RESIDENT;
        assertEquals(expected, villages.food[v]);
    }

    @Test
    void aBuildingProducesNothingWhileItIsStillUnderConstruction() {
        World world = grassWorld();
        Villages villages = new Villages(4);
        int v = villages.found(20, 20, Species.HUMAN, 0);
        villages.population[v] = 0; // isolate production from consumption
        Units units = new Units(4);
        Features features = new Features(4, world.tileCount);
        int fi = features.place(world.index(20, 20), Features.KIND_FARM, 128, 128, (short) v,
            SimConfig.BUILD_TIME_FARM);
        int before = villages.food[v];

        Economy.update(world, villages, units, features);
        assertEquals(before, villages.food[v],
            "a still-building farm must not yield any food");
        assertEquals(SimConfig.BUILD_TIME_FARM - 1, features.buildTime[fi],
            "the building timer must tick down each pass");
    }

    @Test
    void anEmptyPantryStopsBreedingAndSendsUnitsAway() {
        Villages villages = new Villages(4);
        int v = villages.found(20, 20, Species.HUMAN, 0);
        villages.population[v] = 100;
        villages.food[v] = 0;  // already empty
        Units units = new Units(128);
        for (int i = 0; i < 100; i++) {
            int u = units.spawn(20f, 20f, Species.HUMAN, 5000, 0f);
            units.homeVillage[u] = (short) v;
        }
        World world = new World();

        int homedBefore = countHomeIn(units, v);
        Economy.update(world, villages, units, new Features(4, world.tileCount));
        int homedAfter = countHomeIn(units, v);

        assertTrue(homedAfter < homedBefore,
            "some residents should have left; had " + homedBefore + ", now " + homedAfter);
        assertEquals(0.0, Economy.prosperityMultiplier(villages, v),
            "an empty pantry should silence breeding");
    }

    @Test
    void aFullPantryStacksTheProsperityBonus() {
        Villages villages = new Villages(4);
        int v = villages.found(20, 20, Species.HUMAN, 0);
        villages.food[v] = SimConfig.PROSPERITY_FOOD_THRESHOLD + 20;
        assertEquals(SimConfig.PROSPERITY_BONUS, Economy.prosperityMultiplier(villages, v));
    }

    @Test
    void villagesCannotBuildWhatTheyCannotAfford() {
        Villages villages = new Villages(4);
        int v = villages.found(20, 20, Species.HUMAN, 0);
        villages.wood[v] = 0;
        villages.stone[v] = 0;
        assertFalse(Economy.canAfford(villages, v, Features.KIND_HOUSE));
        villages.wood[v] = SimConfig.COST_HOUSE_WOOD;
        assertTrue(Economy.canAfford(villages, v, Features.KIND_HOUSE));
    }

    @Test
    void chargingBuildingCostsActuallyDeductsFromTheStockpile() {
        Villages villages = new Villages(4);
        int v = villages.found(20, 20, Species.HUMAN, 0);
        villages.wood[v] = 100;
        villages.stone[v] = 100;
        Economy.charge(villages, v, Features.KIND_MARKET);
        assertEquals(100 - SimConfig.COST_MARKET_WOOD, villages.wood[v]);
        assertEquals(100 - SimConfig.COST_MARKET_STONE, villages.stone[v]);
    }

    private static int countHomeIn(Units units, int v) {
        int count = 0;
        for (int i = 0; i < units.getHighWater(); i++) {
            if (units.alive[i] && units.homeVillage[i] == (short) v) {
                count++;
            }
        }
        return count;
    }
}
