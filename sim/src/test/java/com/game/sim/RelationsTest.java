package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class RelationsTest {

    private static final int[] NO_CONTACT = new int[Species.COUNT * Species.COUNT];

    private static Relations newRelations() {
        return new Relations(new Random(7));
    }

    @Test
    void everyPairStartsAtPeaceAndFriendly() {
        Relations relations = newRelations();
        assertFalse(relations.anyWar());
        for (byte a = 0; a < Species.COUNT; a++) {
            for (byte b = (byte) (a + 1); b < Species.COUNT; b++) {
                assertFalse(relations.isAtWar(a, b), a + " vs " + b);
                assertTrue(relations.between(a, b) > SimConfig.PEACE_THRESHOLD,
                    "pair " + a + "," + b + " starts at " + relations.between(a, b));
            }
        }
    }

    @Test
    void theMatrixIsSymmetric() {
        Relations relations = newRelations();
        relations.set(Species.HUMAN, Species.ORC, -0.4f);
        assertEquals(relations.between(Species.HUMAN, Species.ORC),
            relations.between(Species.ORC, Species.HUMAN));
    }

    @Test
    void driftKeepsTheMatrixSymmetric() {
        Relations relations = newRelations();
        Random random = new Random(11);
        int[] contacts = new int[Species.COUNT * Species.COUNT];
        contacts[Species.HUMAN * Species.COUNT + Species.ORC] = 40;
        contacts[Species.ORC * Species.COUNT + Species.HUMAN] = 40;

        for (int tick = 0; tick < 500; tick++) {
            relations.update(contacts, random, tick);
            for (byte a = 0; a < Species.COUNT; a++) {
                for (byte b = (byte) (a + 1); b < Species.COUNT; b++) {
                    assertEquals(relations.between(a, b), relations.between(b, a));
                    assertEquals(relations.isAtWar(a, b), relations.isAtWar(b, a));
                }
            }
        }
    }

    @Test
    void driftStaysInsideTheLegalRange() {
        Relations relations = newRelations();
        Random random = new Random(3);
        // Maximum friction on every pair, for far longer than it takes to
        // bottom out - the clamp has to hold indefinitely, not just briefly.
        int[] contacts = new int[Species.COUNT * Species.COUNT];
        java.util.Arrays.fill(contacts, 10_000);

        for (int tick = 0; tick < 2000; tick++) {
            relations.update(contacts, random, tick);
            for (byte a = 0; a < Species.COUNT; a++) {
                for (byte b = 0; b < Species.COUNT; b++) {
                    float v = relations.between(a, b);
                    assertTrue(v >= Relations.MIN && v <= Relations.MAX,
                        "relation out of range: " + v);
                }
            }
        }
    }

    @Test
    void aSpeciesIsNeverAtWarWithItself() {
        Relations relations = newRelations();
        Random random = new Random(5);
        int[] contacts = new int[Species.COUNT * Species.COUNT];
        java.util.Arrays.fill(contacts, 500);

        for (int tick = 0; tick < 400; tick++) {
            relations.update(contacts, random, tick);
        }
        for (byte a = 0; a < Species.COUNT; a++) {
            assertFalse(relations.isAtWar(a, a));
            assertEquals(Relations.MAX, relations.between(a, a));
        }
    }

    @Test
    void warIsDeclaredWhenRelationsCrossTheThreshold() {
        Relations relations = newRelations();
        relations.set(Species.HUMAN, Species.ORC, SimConfig.WAR_THRESHOLD + 0.01f);
        // No drift and no friction, so only the threshold decides.
        relations.update(NO_CONTACT, new Random(1), 100);
        assertFalse(relations.isAtWar(Species.HUMAN, Species.ORC));

        relations.set(Species.HUMAN, Species.ORC, SimConfig.WAR_THRESHOLD - 0.2f);
        relations.update(NO_CONTACT, new Random(1), 200);
        assertTrue(relations.isAtWar(Species.HUMAN, Species.ORC));
        assertTrue(relations.anyWar());
    }

    @Test
    void aLongSharedBorderIsWhatDrivesAPairToWar() {
        Relations relations = newRelations();
        Random random = new Random(13);
        int[] contacts = new int[Species.COUNT * Species.COUNT];
        contacts[Species.HUMAN * Species.COUNT + Species.ORC] = 60;
        contacts[Species.ORC * Species.COUNT + Species.HUMAN] = 60;

        boolean humanOrcWar = false;
        for (int tick = 0; tick < 60 && !humanOrcWar; tick++) {
            relations.update(contacts, random, tick * SimConfig.RELATION_UPDATE_INTERVAL);
            humanOrcWar = relations.isAtWar(Species.HUMAN, Species.ORC);
        }
        assertTrue(humanOrcWar, "a contested border should eventually mean war");
        // Nobody else touched anybody, so nobody else fell out.
        assertFalse(relations.isAtWar(Species.ELF, Species.DWARF));
    }

    @Test
    void warsEndOnTheirOwn() {
        Relations relations = newRelations();
        Random random = new Random(17);
        relations.set(Species.HUMAN, Species.ORC, -1f);
        relations.update(NO_CONTACT, random, 0);
        assertTrue(relations.isAtWar(Species.HUMAN, Species.ORC));

        int tick = 0;
        while (relations.isAtWar(Species.HUMAN, Species.ORC) && tick < SimConfig.MAX_WAR_TICKS * 2) {
            tick += SimConfig.RELATION_UPDATE_INTERVAL;
            relations.update(NO_CONTACT, random, tick);
        }
        assertFalse(relations.isAtWar(Species.HUMAN, Species.ORC),
            "war weariness should have ended it by tick " + tick);
    }

    @Test
    void anEndlessWarIsStoppedByTheTruceBackstop() {
        Relations relations = newRelations();
        Random random = new Random(19);
        relations.set(Species.HUMAN, Species.ORC, -1f);
        relations.update(NO_CONTACT, random, 0);
        assertTrue(relations.isAtWar(Species.HUMAN, Species.ORC));

        // Casualties every pass, faster than weariness can undo them: without
        // the backstop this pair would fight forever.
        int tick = 0;
        while (relations.isAtWar(Species.HUMAN, Species.ORC) && tick < SimConfig.MAX_WAR_TICKS * 3) {
            for (int death = 0; death < 60; death++) {
                relations.recordCasualty(Species.HUMAN, Species.ORC);
            }
            tick += SimConfig.RELATION_UPDATE_INTERVAL;
            relations.update(NO_CONTACT, random, tick);
        }
        assertFalse(relations.isAtWar(Species.HUMAN, Species.ORC));
        assertTrue(tick <= SimConfig.MAX_WAR_TICKS + SimConfig.RELATION_UPDATE_INTERVAL,
            "the truce should fire at the cap, not later; fired at " + tick);
    }

    @Test
    void aTruceDoesNotImmediatelyRestartTheWar() {
        Relations relations = newRelations();
        Random random = new Random(23);
        relations.set(Species.HUMAN, Species.ORC, -1f);
        relations.update(NO_CONTACT, random, 0);

        // Push straight past the cap so peace is imposed rather than felt.
        for (int death = 0; death < 400; death++) {
            relations.recordCasualty(Species.HUMAN, Species.ORC);
        }
        relations.update(NO_CONTACT, random, SimConfig.MAX_WAR_TICKS);
        assertFalse(relations.isAtWar(Species.HUMAN, Species.ORC));

        relations.update(NO_CONTACT, random, SimConfig.MAX_WAR_TICKS + SimConfig.RELATION_UPDATE_INTERVAL);
        assertFalse(relations.isAtWar(Species.HUMAN, Species.ORC),
            "a truce that lands back inside the war band would restart instantly");
    }

    @Test
    void casualtiesDeepenTheGrudgeAndAreCounted() {
        Relations relations = newRelations();
        relations.set(Species.ELF, Species.DWARF, 0f);
        relations.recordCasualty(Species.ELF, Species.DWARF);

        assertEquals(-SimConfig.RELATION_CASUALTY_GRUDGE,
            relations.between(Species.ELF, Species.DWARF), 1e-6f);
        assertEquals(1, relations.warCasualties(Species.ELF, Species.DWARF));
        assertEquals(1, relations.warCasualties(Species.DWARF, Species.ELF));
    }

    @Test
    void aFreshWarStartsWithACleanCasualtyCount() {
        Relations relations = newRelations();
        Random random = new Random(29);
        relations.set(Species.HUMAN, Species.ORC, -1f);
        relations.update(NO_CONTACT, random, 0);
        relations.recordCasualty(Species.HUMAN, Species.ORC);
        assertEquals(1, relations.warCasualties(Species.HUMAN, Species.ORC));

        int tick = 0;
        while (relations.isAtWar(Species.HUMAN, Species.ORC)) {
            tick += SimConfig.RELATION_UPDATE_INTERVAL;
            relations.update(NO_CONTACT, random, tick);
        }
        relations.set(Species.HUMAN, Species.ORC, -1f);
        relations.update(NO_CONTACT, random, tick + SimConfig.RELATION_UPDATE_INTERVAL);

        assertTrue(relations.isAtWar(Species.HUMAN, Species.ORC));
        assertEquals(0, relations.warCasualties(Species.HUMAN, Species.ORC),
            "the second war should not inherit the first war's dead");
        assertTrue(relations.getWarsDeclared() >= 2);
    }
}
