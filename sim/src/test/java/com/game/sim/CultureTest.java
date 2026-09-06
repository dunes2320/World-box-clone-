package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class CultureTest {

    private static World grassWorld() {
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2f;
            world.tileType[i] = TileType.GRASS;
        }
        return world;
    }

    @Test
    void aFreshKingdomOpensInTheStoneAge() {
        Villages villages = new Villages(2);
        Kingdoms kingdoms = new Kingdoms(2);
        KingdomRelations relations = new KingdomRelations(2);
        int v = villages.found(20, 20, Species.HUMAN, 0);
        KingdomSystem.update(villages, kingdoms, relations, new Random(0L), 0);
        assertEquals(Era.STONE, kingdoms.era[villages.kingdom[v]]);
    }

    @Test
    void kingdomsAdvanceMonotonicallyAndOnlyOneWay() {
        World world = grassWorld();
        Villages villages = new Villages(4);
        Kingdoms kingdoms = new Kingdoms(4);
        KingdomRelations relations = new KingdomRelations(4);
        Features features = new Features(64, world.tileCount);

        int v = villages.found(20, 20, Species.HUMAN, 0);
        villages.population[v] = 200;
        KingdomSystem.update(villages, kingdoms, relations, new Random(0L), 0);
        int k = villages.kingdom[v];

        byte last = Era.STONE;
        for (int pass = 0; pass < 200; pass++) {
            CultureSystem.update(villages, kingdoms, features);
            byte now = kingdoms.era[k];
            assertTrue(now >= last, "eras must never rewind: was " + last + " now " + now);
            last = now;
            if (now == Era.MEDIEVAL) break;
        }
        assertEquals(Era.MEDIEVAL, kingdoms.era[k], "a fed-up kingdom should reach medieval");
    }

    @Test
    void stoneAgeKingdomsCannotBuildMarketsOrTemples() {
        assertFalse(Era.unlocks(Era.STONE, Features.KIND_MARKET));
        assertFalse(Era.unlocks(Era.STONE, Features.KIND_TEMPLE));
        assertFalse(Era.unlocks(Era.STONE, Features.KIND_BARRACKS));
        assertTrue(Era.unlocks(Era.STONE, Features.KIND_HOUSE));
        assertTrue(Era.unlocks(Era.STONE, Features.KIND_FARM));
    }

    @Test
    void medievalUnlocksTheWholeRoster() {
        for (byte kind : new byte[]{Features.KIND_HOUSE, Features.KIND_FARM,
            Features.KIND_LUMBER_CAMP, Features.KIND_MINE, Features.KIND_DOCK,
            Features.KIND_BARRACKS, Features.KIND_MARKET, Features.KIND_TEMPLE,
            Features.KIND_WALL}) {
            assertTrue(Era.unlocks(Era.MEDIEVAL, kind),
                "medieval should unlock " + kind);
        }
    }

    @Test
    void temples_andMarkets_producedByThisKingdomBoostItsKnowledge() {
        World world = grassWorld();
        Villages villages = new Villages(4);
        Kingdoms kingdoms = new Kingdoms(4);
        KingdomRelations relations = new KingdomRelations(4);
        Features features = new Features(16, world.tileCount);

        int v = villages.found(20, 20, Species.HUMAN, 0);
        villages.population[v] = 30;
        KingdomSystem.update(villages, kingdoms, relations, new Random(0L), 0);
        int k = villages.kingdom[v];
        // A completed temple (buildTime=0) inside this village's owner set.
        features.place(world.index(20, 20), Features.KIND_TEMPLE, 128, 128, (short) v, 0);
        features.place(world.index(21, 20), Features.KIND_MARKET, 128, 128, (short) v, 0);

        int before = kingdoms.knowledge[k];
        CultureSystem.update(villages, kingdoms, features);
        assertTrue(kingdoms.knowledge[k] > before + 5,
            "temple + market + population should give a real boost, not a trickle");
    }
}
