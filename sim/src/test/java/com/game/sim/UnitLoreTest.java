package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class UnitLoreTest {

    @Test
    void aWandererGetsANameAndSomeTraitsAndNoParents() {
        UnitLore lore = new UnitLore(16);
        Random random = new Random(1L);
        lore.recordBirth(0, Species.HUMAN, UnitLore.NO_PARENT, UnitLore.NO_PARENT, 42L, random);

        assertNotNull(lore.nameOf(0));
        assertFalse(lore.nameOf(0).isEmpty(), "a wanderer must get a real name");
        assertEquals(UnitLore.NO_PARENT, lore.parentA[0]);
        assertEquals(UnitLore.NO_PARENT, lore.parentB[0]);
    }

    @Test
    void namesAreStableForTheSameSeedAndIndex() {
        String a = NameGen.name(5, Species.ORC, 12345L);
        String b = NameGen.name(5, Species.ORC, 12345L);
        assertEquals(a, b, "the same slot in the same world must round-trip to the same name");
    }

    @Test
    void differentSeedsGiveDifferentNamesForTheSameSlot() {
        String a = NameGen.name(3, Species.HUMAN, 1L);
        String b = NameGen.name(3, Species.HUMAN, 2L);
        assertNotEquals(a, b, "the salt should push adjacent slots off different seeds");
    }

    @Test
    void aChildInheritsFromItsParentAndCreditsThemADeed() {
        UnitLore lore = new UnitLore(16);
        Random random = new Random(2L);
        // Give parent 0 every trait so inheritance is easy to see.
        lore.recordBirth(0, Species.HUMAN, UnitLore.NO_PARENT, UnitLore.NO_PARENT, 1L, random);
        lore.traits[0] = (byte) (Traits.BRAVE | Traits.STRONG | Traits.FERTILE
            | Traits.SICKLY | Traits.GREEDY);

        int deedsBefore = lore.deeds[0];
        lore.recordBirth(1, Species.HUMAN, 0, UnitLore.NO_PARENT, 1L, random);

        assertEquals(0, lore.parentA[1]);
        assertTrue(lore.deeds[0] > deedsBefore,
            "the parent should have gained a 'child fathered' deed");
    }

    @Test
    void familyTreeStaysConsistentAcrossManyGenerations() {
        UnitLore lore = new UnitLore(500);
        Random random = new Random(3L);
        lore.recordBirth(0, Species.HUMAN, UnitLore.NO_PARENT, UnitLore.NO_PARENT, 1L, random);

        // Chain of five hundred descendants.
        for (int i = 1; i < 500; i++) {
            lore.recordBirth(i, Species.HUMAN, i - 1, UnitLore.NO_PARENT, 1L, random);
        }

        // Every descendant should trace back to the founder via parentA.
        for (int i = 1; i < 500; i++) {
            int p = lore.parentA[i];
            assertEquals(i - 1, p, "lineage broken at index " + i);
        }
    }

    @Test
    void slotReuseWipesTheOldStorySoTheNewOwnerStartsFresh() {
        UnitLore lore = new UnitLore(4);
        Random random = new Random(4L);
        lore.recordBirth(0, Species.HUMAN, UnitLore.NO_PARENT, UnitLore.NO_PARENT, 1L, random);
        lore.deeds[0] = 99;
        lore.traits[0] = Traits.GREEDY;

        lore.clear(0);
        assertEquals(0, lore.deeds[0]);
        assertEquals(0, lore.traits[0]);
        assertEquals("", lore.nameOf(0));

        lore.recordBirth(0, Species.ORC, UnitLore.NO_PARENT, UnitLore.NO_PARENT, 1L, random);
        assertEquals(0, lore.deeds[0], "a reused slot must not carry the previous unit's deeds");
        assertFalse(lore.nameOf(0).isEmpty());
    }

    @Test
    void traitDistributionStaysBoundedAcrossManyWanderers() {
        UnitLore lore = new UnitLore(1000);
        Random random = new Random(5L);
        for (int i = 0; i < 1000; i++) {
            lore.recordBirth(i, Species.HUMAN, UnitLore.NO_PARENT, UnitLore.NO_PARENT, 1L, random);
        }
        int brave = 0, greedy = 0, sickly = 0, strong = 0, fertile = 0;
        for (int i = 0; i < 1000; i++) {
            if (Traits.has(lore.traits[i], Traits.BRAVE)) brave++;
            if (Traits.has(lore.traits[i], Traits.GREEDY)) greedy++;
            if (Traits.has(lore.traits[i], Traits.SICKLY)) sickly++;
            if (Traits.has(lore.traits[i], Traits.STRONG)) strong++;
            if (Traits.has(lore.traits[i], Traits.FERTILE)) fertile++;
        }
        // Base chance 0.16 across 1000 rolls: each trait's count should sit
        // roughly around 160 with plenty of noise, well inside [50, 350].
        assertTrue(brave > 50 && brave < 350, "brave: " + brave);
        assertTrue(greedy > 50 && greedy < 350, "greedy: " + greedy);
        assertTrue(sickly > 50 && sickly < 350, "sickly: " + sickly);
        assertTrue(strong > 50 && strong < 350, "strong: " + strong);
        assertTrue(fertile > 50 && fertile < 350, "fertile: " + fertile);
    }

    @Test
    void kingsAreCrownedAndPassOnWhenTheyDie() {
        Villages villages = new Villages(2);
        Kingdoms kingdoms = new Kingdoms(2);
        KingdomRelations relations = new KingdomRelations(2);
        Units units = new Units(8);

        int v = villages.found(20, 20, Species.HUMAN, 0);
        int elder = units.spawn(20f, 20f, Species.HUMAN, 5000, 0f);
        units.age[elder] = 2000;
        units.homeVillage[elder] = (short) v;
        int youngster = units.spawn(20f, 20f, Species.HUMAN, 5000, 0f);
        units.age[youngster] = 500;
        units.homeVillage[youngster] = (short) v;

        KingdomSystem.update(villages, kingdoms, relations, units, new Random(0L), 0);
        int k = villages.kingdom[v];
        assertEquals(elder, kingdoms.king[k], "the oldest subject should wear the crown");

        units.kill(elder);
        KingdomSystem.update(villages, kingdoms, relations, units, new Random(0L), 20);
        assertEquals(youngster, kingdoms.king[k],
            "when the king dies the next-oldest inherits");
    }
}
