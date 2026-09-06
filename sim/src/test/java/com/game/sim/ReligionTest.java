package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ReligionTest {

    private static World grassWorld() {
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2f;
            world.tileType[i] = TileType.GRASS;
        }
        return world;
    }

    @Test
    void aTempleInAKingdomWithoutAFaithEventuallyFoundsOne() {
        World world = grassWorld();
        Villages villages = new Villages(4);
        Kingdoms kingdoms = new Kingdoms(4);
        KingdomRelations relations = new KingdomRelations(4);
        Religions religions = new Religions(8);
        Roads roads = new Roads(world.tileCount);
        Features features = new Features(16, world.tileCount);

        int v = villages.found(20, 20, Species.HUMAN, 0);
        KingdomSystem.update(villages, kingdoms, relations, new Random(0L), 0);
        features.place(world.index(20, 20), Features.KIND_TEMPLE, 128, 128, (short) v, 0);

        Random random = new Random(0L);
        boolean founded = false;
        for (int t = 0; t < 400 && !founded; t++) {
            ReligionSystem.update(world, villages, kingdoms, religions, roads, features,
                random, t, 42L);
            if (religions.getLiveCount() > 0) founded = true;
        }
        assertTrue(founded, "40 passes of a 15% chance should reliably found something");
        assertTrue(villages.religion[v] != Religions.NO_RELIGION,
            "the founding village should follow its own new faith");
    }

    @Test
    void faithSpreadsOnlyAlongRoadConnections() {
        World world = grassWorld();
        Villages villages = new Villages(4);
        Kingdoms kingdoms = new Kingdoms(4);
        KingdomRelations relations = new KingdomRelations(4);
        Religions religions = new Religions(8);
        Roads roads = new Roads(world.tileCount);
        Features features = new Features(16, world.tileCount);

        int a = villages.found(20, 20, Species.HUMAN, 0);
        int b = villages.found(30, 20, Species.HUMAN, 0);
        int c = villages.found(80, 80, Species.HUMAN, 0);
        // Force both onto one kingdom for the setup.
        KingdomSystem.update(villages, kingdoms, relations, new Random(0L), 0);
        int k = villages.kingdom[a];
        villages.kingdom[b] = (short) k;
        villages.kingdom[c] = (short) k;

        int r = religions.found(k, Species.HUMAN, 0, 42L);
        villages.religion[a] = (short) r;
        // A road under both a and b, no road under c.
        roads.set(world.index(20, 20));
        roads.set(world.index(30, 20));

        Random random = new Random(1L);
        for (int t = 0; t < 500; t++) {
            ReligionSystem.update(world, villages, kingdoms, religions, roads, features,
                random, t, 42L);
        }
        assertEquals((short) r, villages.religion[b], "b sits on a road under a's faith");
        assertNotEquals((short) r, villages.religion[c],
            "c is not on the road and is out of range, so should never convert");
    }

    @Test
    void aReligionWithNoFollowersIsDissolved() {
        World world = grassWorld();
        Villages villages = new Villages(2);
        Kingdoms kingdoms = new Kingdoms(2);
        Religions religions = new Religions(4);
        Roads roads = new Roads(world.tileCount);
        Features features = new Features(4, world.tileCount);

        int v = villages.found(20, 20, Species.HUMAN, 0);
        KingdomSystem.update(villages, kingdoms, new KingdomRelations(2), new Random(0L), 0);
        int r = religions.found(villages.kingdom[v], Species.HUMAN, 0, 1L);
        // Nobody ever follows this faith - it should die at the next pass.
        assertTrue(religions.isAlive(r));

        ReligionSystem.update(world, villages, kingdoms, religions, roads, features,
            new Random(0L), 20, 1L);
        assertFalse(religions.isAlive(r),
            "a religion with zero followers should not stay alive taking up a slot");
    }
}
