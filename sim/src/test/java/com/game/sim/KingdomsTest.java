package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class KingdomsTest {

    @Test
    void aFreshVillageGetsSortedIntoOrAroundAKingdom() {
        Villages villages = new Villages(8);
        Kingdoms kingdoms = new Kingdoms(8);
        KingdomRelations relations = new KingdomRelations(8);

        int v = villages.found(10, 10, Species.HUMAN, 0);
        assertEquals(Villages.NO_KINGDOM, villages.kingdom[v]);

        KingdomSystem.update(villages, kingdoms, relations, new Random(1L), 0);

        assertNotEquals(Villages.NO_KINGDOM, villages.kingdom[v]);
        int k = villages.kingdom[v];
        assertTrue(kingdoms.isAlive(k));
        assertEquals(v, kingdoms.capital[k]);
        assertEquals(1, kingdoms.villageCount[k]);
    }

    @Test
    void nearbySameSpeciesVillagesJoinTheSameKingdom() {
        Villages villages = new Villages(4);
        Kingdoms kingdoms = new Kingdoms(4);
        KingdomRelations relations = new KingdomRelations(4);

        int a = villages.found(10, 10, Species.ELF, 0);
        int b = villages.found(20, 20, Species.ELF, 0);
        KingdomSystem.update(villages, kingdoms, relations, new Random(1L), 0);

        assertEquals(villages.kingdom[a], villages.kingdom[b],
            "two elves within KINGDOM_JOIN_RANGE should share one kingdom");
        assertEquals(2, kingdoms.villageCount[villages.kingdom[a]]);
    }

    @Test
    void aDistantColonyFoundsItsOwnKingdomEvenIfSameSpecies() {
        Villages villages = new Villages(4);
        Kingdoms kingdoms = new Kingdoms(4);
        KingdomRelations relations = new KingdomRelations(4);

        int a = villages.found(10, 10, Species.HUMAN, 0);
        int b = villages.found(200, 200, Species.HUMAN, 0);
        KingdomSystem.update(villages, kingdoms, relations, new Random(1L), 0);

        assertNotEquals(villages.kingdom[a], villages.kingdom[b],
            "a village a whole map away is not joining somebody's kingdom");
    }

    @Test
    void differentSpeciesNeverShareAKingdomEvenAdjacent() {
        Villages villages = new Villages(4);
        Kingdoms kingdoms = new Kingdoms(4);
        KingdomRelations relations = new KingdomRelations(4);

        int a = villages.found(10, 10, Species.DWARF, 0);
        int b = villages.found(11, 11, Species.ORC, 0);
        KingdomSystem.update(villages, kingdoms, relations, new Random(1L), 0);

        assertNotEquals(villages.kingdom[a], villages.kingdom[b]);
        assertEquals(Species.DWARF, kingdoms.species[villages.kingdom[a]]);
        assertEquals(Species.ORC, kingdoms.species[villages.kingdom[b]]);
    }

    @Test
    void aKingdomWithNoVillagesLeftIsDissolved() {
        Villages villages = new Villages(4);
        Kingdoms kingdoms = new Kingdoms(4);
        KingdomRelations relations = new KingdomRelations(4);

        int v = villages.found(10, 10, Species.HUMAN, 0);
        KingdomSystem.update(villages, kingdoms, relations, new Random(1L), 0);
        int k = villages.kingdom[v];
        assertTrue(kingdoms.isAlive(k));

        villages.abandon(v);
        KingdomSystem.update(villages, kingdoms, relations, new Random(1L), 20);

        assertFalse(kingdoms.isAlive(k),
            "a kingdom with no villages is not a political entity - dissolve it");
    }

    @Test
    void capitalIsPromotedWhenTheOriginalOneFalls() {
        Villages villages = new Villages(4);
        Kingdoms kingdoms = new Kingdoms(4);
        KingdomRelations relations = new KingdomRelations(4);

        int a = villages.found(10, 10, Species.HUMAN, 0);
        int b = villages.found(12, 12, Species.HUMAN, 0);
        KingdomSystem.update(villages, kingdoms, relations, new Random(1L), 0);
        int k = villages.kingdom[a];
        assertEquals(a, kingdoms.capital[k]);

        villages.abandon(a);
        KingdomSystem.update(villages, kingdoms, relations, new Random(1L), 20);

        assertTrue(kingdoms.isAlive(k),
            "one member still stands - the kingdom is not dissolved");
        assertEquals(b, kingdoms.capital[k],
            "the surviving member should have been promoted to capital");
    }

    @Test
    void reusedKingdomSlotStartsWithFreshRelations() {
        Villages villages = new Villages(4);
        Kingdoms kingdoms = new Kingdoms(2);
        KingdomRelations relations = new KingdomRelations(2);

        int a = villages.found(10, 10, Species.HUMAN, 0);
        int b = villages.found(200, 200, Species.ORC, 0);
        KingdomSystem.update(villages, kingdoms, relations, new Random(1L), 0);
        int kA = villages.kingdom[a];
        int kB = villages.kingdom[b];

        relations.set(kA, kB, -0.9f);
        relations.declareWar(kA, kB, 0);
        assertTrue(relations.isAtWar(kA, kB));

        villages.abandon(a);
        KingdomSystem.update(villages, kingdoms, relations, new Random(1L), 20);
        assertFalse(kingdoms.isAlive(kA));
        assertFalse(relations.isAtWar(kA, kB));
    }

    @Test
    void speciesAffinityDrivesTheOpeningRelation() {
        Kingdoms kingdoms = new Kingdoms(4);
        KingdomRelations relations = new KingdomRelations(4);
        int humans = kingdoms.found(0, Species.HUMAN, 0);
        int orcs = kingdoms.found(1, Species.ORC, 0);
        int elves = kingdoms.found(2, Species.ELF, 0);

        relations.seedIfNew(humans, orcs, Species.HUMAN, Species.ORC);
        relations.seedIfNew(humans, elves, Species.HUMAN, Species.ELF);

        assertTrue(relations.between(humans, orcs) < 0f,
            "humans-orcs should open hostile");
        assertTrue(relations.between(humans, elves) > 0f,
            "humans-elves should open warm");
    }
}
