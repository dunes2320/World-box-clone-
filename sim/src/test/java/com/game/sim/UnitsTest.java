package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UnitsTest {

    private static int spawn(Units units, byte species) {
        return units.spawn(10f, 10f, species, 1000, 0f);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new Units(0));
        assertThrows(IllegalArgumentException.class, () -> new Units(-5));
    }

    @Test
    void startsEmpty() {
        Units units = new Units(16);
        assertEquals(0, units.getLiveCount());
        assertEquals(0, units.getHighWater());
        assertFalse(units.isFull());
        assertFalse(units.isAlive(0));
    }

    @Test
    void firstAllocationsComeBackInOrderFromZero() {
        // Keeps live units packed near the front, which is what makes bounded
        // iteration to the high-water mark worth having.
        Units units = new Units(64);
        assertEquals(0, spawn(units, Species.HUMAN));
        assertEquals(1, spawn(units, Species.HUMAN));
        assertEquals(2, spawn(units, Species.HUMAN));
        assertEquals(3, units.getHighWater());
    }

    @Test
    void spawnInitialisesEveryField() {
        Units units = new Units(8);
        int i = units.spawn(4.5f, 7.25f, Species.ELF, 1234, 1.5f);

        assertTrue(units.isAlive(i));
        assertEquals(4.5f, units.x[i]);
        assertEquals(7.25f, units.z[i]);
        assertEquals(1.5f, units.heading[i]);
        assertEquals(Species.ELF, units.species[i]);
        assertEquals(1234, units.maxAge[i]);
        assertEquals(0, units.age[i]);
        assertEquals(0, units.hunger[i]);
        assertEquals(SimConfig.UNIT_MAX_HEALTH, units.health[i]);
        assertEquals(Units.NO_VILLAGE, units.homeVillage[i]);
    }

    @Test
    void killFreesTheSlotForReuse() {
        Units units = new Units(8);
        int first = spawn(units, Species.HUMAN);
        int second = spawn(units, Species.ORC);
        assertEquals(2, units.getLiveCount());

        units.kill(first);
        assertFalse(units.isAlive(first));
        assertTrue(units.isAlive(second));
        assertEquals(1, units.getLiveCount());

        // The freed slot must come back, not be leaked.
        int reused = spawn(units, Species.DWARF);
        assertEquals(first, reused);
        assertEquals(Species.DWARF, units.species[reused], "reused slot must be fully reinitialised");
        assertEquals(2, units.getLiveCount());
    }

    @Test
    void killingTwiceIsHarmless() {
        Units units = new Units(8);
        int i = spawn(units, Species.HUMAN);
        units.kill(i);
        units.kill(i);
        assertEquals(0, units.getLiveCount());

        // A double free would have put the slot on the list twice, handing the
        // same index to two different units.
        int a = spawn(units, Species.HUMAN);
        int b = spawn(units, Species.ORC);
        assertNotEquals(a, b, "double kill must not let one slot be allocated twice");
    }

    @Test
    void killingAnInvalidIndexIsIgnored() {
        Units units = new Units(4);
        units.kill(-1);
        units.kill(99);
        units.kill(0);
        assertEquals(0, units.getLiveCount());
    }

    @Test
    void poolExhaustionReportsFailureRatherThanOverflowing() {
        Units units = new Units(3);
        assertTrue(spawn(units, Species.HUMAN) >= 0);
        assertTrue(spawn(units, Species.HUMAN) >= 0);
        assertTrue(spawn(units, Species.HUMAN) >= 0);
        assertTrue(units.isFull());
        assertEquals(-1, spawn(units, Species.HUMAN), "a full pool must report failure");
        assertEquals(3, units.getLiveCount());
    }

    @Test
    void everyLiveIndexIsUniqueUnderChurn() {
        // Hammer alloc/free and confirm the pool never hands out a slot that is
        // already occupied.
        Units units = new Units(32);
        java.util.Random random = new java.util.Random(1);
        boolean[] occupied = new boolean[32];

        for (int step = 0; step < 5000; step++) {
            if (random.nextBoolean() && !units.isFull()) {
                int i = spawn(units, Species.HUMAN);
                assertFalse(occupied[i], "pool handed out an occupied slot " + i);
                occupied[i] = true;
            } else {
                for (int i = 0; i < 32; i++) {
                    if (occupied[i]) {
                        units.kill(i);
                        occupied[i] = false;
                        break;
                    }
                }
            }
            int expected = 0;
            for (boolean o : occupied) {
                if (o) {
                    expected++;
                }
            }
            assertEquals(expected, units.getLiveCount(), "live count drifted at step " + step);
        }
    }

    @Test
    void countBySpeciesMatchesIndividualCounts() {
        Units units = new Units(64);
        spawn(units, Species.HUMAN);
        spawn(units, Species.HUMAN);
        spawn(units, Species.ORC);
        int elf = spawn(units, Species.ELF);
        units.kill(elf);

        int[] counts = new int[Species.COUNT];
        units.countBySpecies(counts);

        assertEquals(2, counts[Species.HUMAN]);
        assertEquals(1, counts[Species.ORC]);
        assertEquals(0, counts[Species.ELF], "dead units must not be counted");
        assertEquals(0, counts[Species.DWARF]);
        assertEquals(2, units.countOf(Species.HUMAN));
        assertEquals(units.getLiveCount(), counts[0] + counts[1] + counts[2] + counts[3]);
    }

    @Test
    void countBySpeciesRejectsAShortArray() {
        Units units = new Units(4);
        assertThrows(IllegalArgumentException.class, () -> units.countBySpecies(new int[Species.COUNT - 1]));
    }

    @Test
    void clearEmptiesThePoolAndResetsIteration() {
        Units units = new Units(16);
        for (int i = 0; i < 10; i++) {
            spawn(units, Species.HUMAN);
        }
        units.clear();

        assertEquals(0, units.getLiveCount());
        assertEquals(0, units.getHighWater());
        assertEquals(0, spawn(units, Species.ORC), "after clear, allocation restarts from zero");
    }
}
