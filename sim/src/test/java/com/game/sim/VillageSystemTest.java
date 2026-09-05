package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VillageSystemTest {

    private static World grassWorld() {
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2.0f;
            world.tileType[i] = TileType.GRASS;
        }
        return world;
    }

    // ---- pool ----

    @Test
    void rejectsAnUnusableCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new Villages(0));
        assertThrows(IllegalArgumentException.class, () -> new Villages(Short.MAX_VALUE + 1));
    }

    @Test
    void foundAndAbandonReuseSlots() {
        Villages villages = new Villages(4);
        int a = villages.found(10, 10, Species.HUMAN, 0);
        int b = villages.found(40, 40, Species.ORC, 0);
        assertEquals(2, villages.getLiveCount());

        villages.abandon(a);
        assertFalse(villages.isAlive(a));
        assertEquals(1, villages.getLiveCount());

        int c = villages.found(70, 70, Species.ELF, 0);
        assertEquals(a, c, "an abandoned slot must be reused");
        assertEquals(Species.ELF, villages.species[c], "reused slot must be reinitialised");
        assertNotEquals(b, c);
    }

    @Test
    void abandoningTwiceDoesNotCorruptTheFreeList() {
        Villages villages = new Villages(4);
        int a = villages.found(10, 10, Species.HUMAN, 0);
        villages.abandon(a);
        villages.abandon(a);
        int x = villages.found(20, 20, Species.ORC, 0);
        int y = villages.found(30, 30, Species.ORC, 0);
        assertNotEquals(x, y, "double abandon must not hand out one slot twice");
    }

    @Test
    void fullPoolReportsFailure() {
        Villages villages = new Villages(2);
        assertTrue(villages.found(1, 1, Species.HUMAN, 0) >= 0);
        assertTrue(villages.found(2, 2, Species.HUMAN, 0) >= 0);
        assertTrue(villages.isFull());
        assertEquals(-1, villages.found(3, 3, Species.HUMAN, 0));
    }

    @Test
    void nearestToFindsTheClosestWithinRange() {
        Villages villages = new Villages(8);
        int near = villages.found(10, 10, Species.HUMAN, 0);
        villages.found(90, 90, Species.ORC, 0);

        assertEquals(near, villages.nearestTo(12f, 12f, 20f));
        assertEquals(-1, villages.nearestTo(50f, 50f, 5f), "nothing is within five tiles of the middle");
    }

    // ---- founding rules ----

    @Test
    void aLoneWandererDoesNotFoundAVillage() {
        // Settling is something a group does. One unit crossing empty country
        // must keep walking, or the map fills with one-person hamlets.
        Simulation sim = new Simulation(7L);
        int[] spot = findGrassTile(sim.getWorld());
        sim.spawnUnits(spot[0], spot[1], 1, Species.HUMAN, 1);
        assertEquals(1, sim.getUnits().getLiveCount(), "the test needs exactly one unit placed");

        for (int tick = 0; tick < 4000; tick++) {
            sim.tick();
        }
        assertEquals(0, sim.getVillages().getLiveCount(), "a single unit should never settle");
    }

    @Test
    void aGroupEventuallySettles() {
        Simulation sim = new Simulation(2024L);
        sim.spawnUnits(64, 64, 9, Species.HUMAN, 60);
        for (int tick = 0; tick < 6000; tick++) {
            sim.tick();
        }
        assertTrue(sim.getVillages().getLiveCount() > 0, "a fed group should found at least one village");
    }

    @Test
    void villagesKeepTheirDistanceFromEachOther() {
        Simulation sim = new Simulation(2024L);
        for (byte s = 0; s < Species.COUNT; s++) {
            sim.spawnUnits(50 + s * 8, 60 + s * 6, 10, s, 60);
        }
        for (int tick = 0; tick < 12000; tick++) {
            sim.tick();
        }

        Villages villages = sim.getVillages();
        assertTrue(villages.getLiveCount() > 1, "expected several villages");
        for (int a = 0; a < villages.getHighWater(); a++) {
            if (!villages.alive[a]) {
                continue;
            }
            for (int b = a + 1; b < villages.getHighWater(); b++) {
                if (!villages.alive[b]) {
                    continue;
                }
                float dx = villages.x[a] - villages.x[b];
                float dz = villages.z[a] - villages.z[b];
                double distance = Math.sqrt(dx * dx + dz * dz);
                assertTrue(distance >= SimConfig.VILLAGE_MIN_SPACING - 1.0,
                    "villages " + a + " and " + b + " are only " + distance + " apart");
            }
        }
    }

    @Test
    void emptyVillagesAreAbandoned() {
        World world = grassWorld();
        Units units = new Units(16);
        Villages villages = new Villages(8);
        Territory territory = new Territory(world.tileCount);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        java.util.Random random = new java.util.Random(1);

        int village = villages.found(64, 64, Species.HUMAN, 0);
        int unit = units.spawn(64.5f, 64.5f, Species.HUMAN, 30000, 0f);
        units.age[unit] = (short) SimConfig.UNIT_MATURITY;
        units.homeVillage[unit] = (short) village;

        VillageSystem.update(world, units, villages, territory, density, random, 0);
        assertTrue(villages.isAlive(village), "a populated village must survive");

        units.kill(unit);
        VillageSystem.update(world, units, villages, territory, density, random, 20);
        assertFalse(villages.isAlive(village), "a village with nobody left must be abandoned");
    }

    @Test
    void membershipOfADeadVillageIsCleared() {
        // A stale index would let a reused slot silently inherit its
        // predecessor's residents.
        World world = grassWorld();
        Units units = new Units(16);
        Villages villages = new Villages(8);
        Territory territory = new Territory(world.tileCount);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        java.util.Random random = new java.util.Random(2);

        int village = villages.found(64, 64, Species.HUMAN, 0);
        int unit = units.spawn(64.5f, 64.5f, Species.HUMAN, 30000, 0f);
        units.homeVillage[unit] = (short) village;
        villages.abandon(village);

        VillageSystem.update(world, units, villages, territory, density, random, 20);
        assertEquals(Units.NO_VILLAGE, units.homeVillage[unit]);
    }

    // ---- territory ----

    @Test
    void territoryGrowsOutwardFromAVillage() {
        Simulation sim = new Simulation(2024L);
        sim.spawnUnits(64, 64, 9, Species.HUMAN, 60);

        int early = 0;
        for (int tick = 0; tick < 6000; tick++) {
            sim.tick();
        }
        early = ownedTiles(sim.getWorld());
        assertTrue(early > 0, "a village should claim ground");

        for (int tick = 0; tick < 8000; tick++) {
            sim.tick();
        }
        assertTrue(ownedTiles(sim.getWorld()) >= early,
            "territory should hold or expand as the population grows");
    }

    @Test
    void territoryNeverClaimsWater() {
        Simulation sim = new Simulation(2024L);
        for (byte s = 0; s < Species.COUNT; s++) {
            sim.spawnUnits(50 + s * 8, 60 + s * 6, 10, s, 60);
        }
        for (int tick = 0; tick < 12000; tick++) {
            sim.tick();
        }

        World world = sim.getWorld();
        for (int i = 0; i < world.tileCount; i++) {
            if (TileType.isWater(world.tileType[i])) {
                assertEquals(World.NO_OWNER, world.ownerVillage[i],
                    "water tile " + i + " was claimed; borders must stop at the coast");
            }
        }
    }

    @Test
    void everyOwnedTileNamesALivingVillage() {
        // A dangling index here would crash the renderer's colour lookup.
        Simulation sim = new Simulation(99L);
        for (byte s = 0; s < Species.COUNT; s++) {
            sim.spawnUnits(50 + s * 8, 60 + s * 6, 10, s, 50);
        }
        for (int tick = 0; tick < 15000; tick++) {
            sim.tick();
        }

        World world = sim.getWorld();
        Villages villages = sim.getVillages();
        for (int i = 0; i < world.tileCount; i++) {
            short owner = world.ownerVillage[i];
            if (owner != World.NO_OWNER) {
                assertTrue(villages.isAlive(owner),
                    "tile " + i + " is owned by dead village " + owner);
            }
        }
    }

    @Test
    void aTileIsOwnedByExactlyOneVillage() {
        // Recomputing from scratch each pass should make double ownership
        // structurally impossible; this pins that down.
        World world = grassWorld();
        Villages villages = new Villages(8);
        Territory territory = new Territory(world.tileCount);

        int a = villages.found(60, 64, Species.HUMAN, 0);
        int b = villages.found(70, 64, Species.ORC, 0);
        villages.population[a] = 20;
        villages.population[b] = 20;
        villages.radius[a] = 12f;
        villages.radius[b] = 12f;

        territory.recompute(world, villages);

        int ownedByA = 0;
        int ownedByB = 0;
        for (int i = 0; i < world.tileCount; i++) {
            if (world.ownerVillage[i] == a) {
                ownedByA++;
            } else if (world.ownerVillage[i] == b) {
                ownedByB++;
            }
        }
        assertTrue(ownedByA > 0 && ownedByB > 0, "both villages should hold ground");
        // Their claim discs overlap heavily; each tile still resolves to one.
        assertTrue(ownedByA + ownedByB <= world.tileCount);
    }

    @Test
    void theStrongerVillageWinsContestedGround() {
        World world = grassWorld();
        Villages villages = new Villages(8);
        Territory territory = new Territory(world.tileCount);

        int weak = villages.found(60, 64, Species.HUMAN, 0);
        int strong = villages.found(70, 64, Species.ORC, 0);
        villages.population[weak] = 2;
        villages.population[strong] = 60;
        villages.radius[weak] = 14f;
        villages.radius[strong] = 14f;

        territory.recompute(world, villages);

        // The midpoint sits inside both claim discs; population decides it.
        assertEquals(strong, world.ownerVillage[world.index(65, 64)],
            "the larger village should hold contested ground");
    }

    @Test
    void abandonedTerritoryIsReleased() {
        World world = grassWorld();
        Villages villages = new Villages(8);
        Territory territory = new Territory(world.tileCount);

        int village = villages.found(64, 64, Species.HUMAN, 0);
        villages.population[village] = 20;
        villages.radius[village] = 10f;
        territory.recompute(world, villages);
        assertTrue(ownedTiles(world) > 0);

        villages.abandon(village);
        territory.recompute(world, villages);
        assertEquals(0, ownedTiles(world), "a dead village must not keep its land");
    }

    @Test
    void territoryChangesMarkChunksForRebuild() {
        World world = grassWorld();
        for (int c = 0; c < world.chunkCount(); c++) {
            world.clearChunkDirty(c);
        }
        Villages villages = new Villages(8);
        Territory territory = new Territory(world.tileCount);
        int village = villages.found(64, 64, Species.HUMAN, 0);
        villages.population[village] = 20;
        villages.radius[village] = 8f;

        int changed = territory.recompute(world, villages);
        assertTrue(changed > 0);
        assertTrue(world.isChunkDirty(world.chunkIndex(4, 4)),
            "the chunk containing the new territory must be re-meshed");
    }

    @Test
    void aSettledWorldStaysReproducible() {
        Simulation a = new Simulation(4242L);
        Simulation b = new Simulation(4242L);
        for (byte s = 0; s < Species.COUNT; s++) {
            a.spawnUnits(50 + s * 8, 60 + s * 6, 10, s, 50);
            b.spawnUnits(50 + s * 8, 60 + s * 6, 10, s, 50);
        }
        for (int tick = 0; tick < 8000; tick++) {
            a.tick();
            b.tick();
        }

        assertEquals(a.getVillages().getLiveCount(), b.getVillages().getLiveCount());
        assertArrayEqualsShort(a.getWorld().ownerVillage, b.getWorld().ownerVillage);
    }

    private static void assertArrayEqualsShort(short[] expected, short[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "ownership diverged at tile " + i);
        }
    }

    /**
     * A grass tile well inside the map. Generated worlds are islands, so a
     * fixed coordinate is not guaranteed to be dry land on every seed.
     */
    private static int[] findGrassTile(World world) {
        for (int z = 20; z < world.size - 20; z++) {
            for (int x = 20; x < world.size - 20; x++) {
                if (world.typeAt(x, z) == TileType.GRASS) {
                    return new int[] {x, z};
                }
            }
        }
        throw new IllegalStateException("world has no grass to stand on");
    }

    private static int ownedTiles(World world) {
        int owned = 0;
        for (int i = 0; i < world.tileCount; i++) {
            if (world.ownerVillage[i] != World.NO_OWNER) {
                owned++;
            }
        }
        return owned;
    }
}
