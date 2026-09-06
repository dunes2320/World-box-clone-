package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegionGridTest {

    @Test
    void rejectsAChunkSizeThatDoesNotDivideTheWorld() {
        assertThrows(IllegalArgumentException.class, () -> new RegionGrid(128, 7));
        assertThrows(IllegalArgumentException.class, () -> new RegionGrid(128, 0));
    }

    @Test
    void countsUnitsIntoTheRightCell() {
        RegionGrid grid = new RegionGrid(128, 32);
        Units units = new Units(16);
        units.spawn(5.5f, 5.5f, Species.HUMAN, 100, 0f);
        units.spawn(6.5f, 5.5f, Species.HUMAN, 100, 0f);
        units.spawn(40f, 5.5f, Species.ORC, 100, 0f);
        units.spawn(5.5f, 100f, Species.ELF, 100, 0f);

        grid.rebuild(units);
        assertEquals(2, grid.populationOf(5.5f, 5.5f, Species.HUMAN));
        assertEquals(1, grid.populationOf(40f, 5.5f, Species.ORC));
        assertEquals(1, grid.populationOf(5.5f, 100f, Species.ELF));
        assertEquals(0, grid.populationOf(5.5f, 5.5f, Species.ORC));
    }

    @Test
    void totalsMatchTheSpeciesBreakdown() {
        RegionGrid grid = new RegionGrid(128, 32);
        Units units = new Units(32);
        for (int i = 0; i < 10; i++) {
            units.spawn(20f + i, 20f, (byte) (i % Species.COUNT), 100, 0f);
        }
        grid.rebuild(units);

        int summed = 0;
        for (byte s = 0; s < Species.COUNT; s++) {
            summed += grid.populationOf(24f, 20f, s);
        }
        assertEquals(grid.totalAt(24f, 20f), summed);
        assertEquals(10, summed, "the whole cluster sits in one cell");
    }

    @Test
    void refreshSkipsWorkWhenTheStampMatches() {
        RegionGrid grid = new RegionGrid(128, 32);
        Units units = new Units(4);
        units.spawn(10f, 10f, Species.HUMAN, 100, 0f);
        grid.refresh(units, 42);
        assertEquals(1, grid.totalAt(10f, 10f));

        // Add a unit but ask for the same tick - the grid must not see it.
        units.spawn(11f, 10f, Species.HUMAN, 100, 0f);
        grid.refresh(units, 42);
        assertEquals(1, grid.totalAt(10f, 10f));

        // Bump the tick, and the fresh unit shows up.
        grid.refresh(units, 43);
        assertEquals(2, grid.totalAt(10f, 10f));
    }

    @Test
    void dominantSpeciesBreaksTiesByIdForDeterminism() {
        RegionGrid grid = new RegionGrid(128, 32);
        Units units = new Units(16);
        units.spawn(10f, 10f, Species.HUMAN, 100, 0f);
        units.spawn(10.5f, 10f, Species.HUMAN, 100, 0f);
        units.spawn(11f, 10f, Species.ORC, 100, 0f);
        units.spawn(11.5f, 10f, Species.ORC, 100, 0f);
        grid.rebuild(units);

        // Tied at 2-2: HUMAN wins by lower id.
        int cell = 0; // (0,0) chunk at 32-sized world
        assertTrue(grid.populationAtCell(cell, Species.HUMAN) >= 2);
        assertTrue(grid.populationAtCell(cell, Species.ORC) >= 2);
        assertEquals(Species.HUMAN, grid.dominantAtCell(cell));
    }

    @Test
    void positionsOutsideTheWorldReportZeroRatherThanThrowing() {
        RegionGrid grid = new RegionGrid(128, 32);
        Units units = new Units(1);
        grid.rebuild(units);
        assertEquals(0, grid.totalAt(-1f, -1f));
        assertEquals(0, grid.totalAt(1000f, 1000f));
    }
}
