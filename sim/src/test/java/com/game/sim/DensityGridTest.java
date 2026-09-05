package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class DensityGridTest {

    private static DensityGrid grid() {
        return new DensityGrid(128, 8);
    }

    @Test
    void rejectsACellSizeThatDoesNotDivideTheWorld() {
        assertThrows(IllegalArgumentException.class, () -> new DensityGrid(128, 7));
        assertThrows(IllegalArgumentException.class, () -> new DensityGrid(128, 0));
    }

    @Test
    void countsUnitsIntoTheCorrectCell() {
        DensityGrid density = grid();
        Units units = new Units(16);
        // Both inside the 8x8 cell starting at (8, 8).
        units.spawn(9f, 9f, Species.HUMAN, 1000, 0f);
        units.spawn(14.5f, 12f, Species.HUMAN, 1000, 0f);
        // Just across the boundary, in the next cell along.
        units.spawn(16.5f, 9f, Species.HUMAN, 1000, 0f);

        density.rebuild(units);

        assertEquals(2, density.totalAt(10f, 10f));
        assertEquals(1, density.totalAt(17f, 10f));
        assertEquals(0, density.totalAt(60f, 60f));
    }

    @Test
    void separatesCountsBySpecies() {
        DensityGrid density = grid();
        Units units = new Units(16);
        units.spawn(9f, 9f, Species.ORC, 1000, 0f);
        units.spawn(10f, 10f, Species.ORC, 1000, 0f);
        units.spawn(11f, 11f, Species.ELF, 1000, 0f);

        density.rebuild(units);

        assertEquals(2, density.speciesAt(9f, 9f, Species.ORC));
        assertEquals(1, density.speciesAt(9f, 9f, Species.ELF));
        assertEquals(0, density.speciesAt(9f, 9f, Species.DWARF));
        assertEquals(3, density.totalAt(9f, 9f));
    }

    @Test
    void deadUnitsAreNotCounted() {
        DensityGrid density = grid();
        Units units = new Units(16);
        int a = units.spawn(9f, 9f, Species.HUMAN, 1000, 0f);
        units.spawn(9f, 9f, Species.HUMAN, 1000, 0f);
        units.kill(a);

        density.rebuild(units);
        assertEquals(1, density.totalAt(9f, 9f));
    }

    @Test
    void rebuildingClearsPreviousCounts() {
        DensityGrid density = grid();
        Units units = new Units(16);
        int a = units.spawn(9f, 9f, Species.HUMAN, 1000, 0f);
        density.rebuild(units);
        assertEquals(1, density.totalAt(9f, 9f));

        units.kill(a);
        density.rebuild(units);
        assertEquals(0, density.totalAt(9f, 9f), "counts must not accumulate across rebuilds");
    }

    @Test
    void positionsOutsideTheWorldReportEmptyRatherThanThrowing() {
        DensityGrid density = grid();
        density.rebuild(new Units(4));
        assertEquals(0, density.totalAt(-5f, -5f));
        assertEquals(0, density.totalAt(500f, 500f));
        assertEquals(0, density.speciesAt(-5f, 20f, Species.HUMAN));
    }

    @Test
    void totalsAlwaysEqualTheSumOfTheSpeciesCounts() {
        DensityGrid density = grid();
        Units units = new Units(600);
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.tileType[i] = TileType.GRASS;
            world.height[i] = 2f;
        }
        Random random = new Random(42);
        for (byte s = 0; s < Species.COUNT; s++) {
            UnitSystem.spawnBrush(world, units, random, 64, 64, 20, s, 120);
        }
        density.rebuild(units);

        int cells = density.getCellsPerAxis();
        int summed = 0;
        for (int cz = 0; cz < cells; cz++) {
            for (int cx = 0; cx < cells; cx++) {
                float x = cx * density.getCellSize() + 0.5f;
                float z = cz * density.getCellSize() + 0.5f;
                int perSpecies = 0;
                for (byte s = 0; s < Species.COUNT; s++) {
                    perSpecies += density.speciesAt(x, z, s);
                }
                assertEquals(density.totalAt(x, z), perSpecies,
                    "total disagrees with species breakdown in cell " + cx + "," + cz);
                summed += perSpecies;
            }
        }
        assertEquals(units.getLiveCount(), summed, "grid lost or double-counted units");
    }

    @Test
    void speciesCrowdingKeepsFourPopulationsAlive() {
        // The regression this whole class exists for. With only a global cap,
        // orcs reached 91% of the map by tick 30,000 and elves about 1%.
        //
        // Driven through UnitSystem directly rather than through Simulation, so
        // only breeding is under test. Running the full simulation would let a
        // war decide the outcome instead, and a species losing a war is exactly
        // the thing this design is happy to allow - it is being out-bred in
        // peacetime that must not happen.
        World world = WorldGen.generate(2024L);
        Units units = new Units(SimConfig.MAX_UNITS);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        Random random = new Random(2024L);
        for (byte s = 0; s < Species.COUNT; s++) {
            UnitSystem.spawnBrush(world, units, random, 56 + s * 6, 60 + s * 5, 10, s, 45);
        }
        for (int tick = 0; tick < 20000; tick++) {
            UnitSystem.update(world, units, density, random);
        }

        int[] counts = new int[Species.COUNT];
        units.countBySpecies(counts);
        int total = units.getLiveCount();
        assertTrue(total > 100, "population collapsed to " + total);

        for (byte s = 0; s < Species.COUNT; s++) {
            assertTrue(counts[s] > 0, Species.name(s) + " died out entirely");
            double share = counts[s] / (double) total;
            assertTrue(share < 0.75,
                Species.name(s) + " monopolised the map at " + Math.round(share * 100) + "%");
        }
    }

    @Test
    void aBeatenSpeciesCanStillHoldOnSomewhere() {
        // The other half of the same balance: war may cost a species its
        // villages and most of its people, but a survivor standing somewhere
        // quiet must not be killed by the map itself. See CombatSystem.
        World world = WorldGen.generate(2024L);
        Units units = new Units(64);
        Villages villages = new Villages(8);
        Random random = new Random(5L);

        int survivors = UnitSystem.spawnBrush(world, units, random, 64, 64, 6, Species.HUMAN, 12);
        assertTrue(survivors > 0, "test needs somewhere walkable to stand");

        Relations relations = new Relations(new Random(1));
        relations.set(Species.HUMAN, Species.ORC, -1f);
        relations.update(new int[Species.COUNT * Species.COUNT], new Random(1), 0);

        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        for (int tick = 0; tick < 5000; tick++) {
            density.rebuild(units);
            CombatSystem.update(world, units, villages, relations, density, random);
        }
        assertEquals(survivors, units.countOf(Species.HUMAN),
            "a war with no orcs in it should not kill a single human");
    }
}
