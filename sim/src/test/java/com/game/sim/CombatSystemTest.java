package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class CombatSystemTest {

    private static final int[] NO_CONTACT = new int[Species.COUNT * Species.COUNT];

    private static World grassWorld() {
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2.0f;
            world.tileType[i] = TileType.GRASS;
        }
        return world;
    }

    private static Relations warBetween(byte a, byte b) {
        Relations relations = new Relations(new Random(1));
        relations.set(a, b, -1f);
        relations.update(NO_CONTACT, new Random(1), 0);
        assertTrue(relations.isAtWar(a, b), "test setup should have started a war");
        return relations;
    }

    /** Two crowds standing on the same spot, which is what a battle looks like here. */
    private static Units twoCrowdsAt(float x, float z, int perSide, byte a, byte b) {
        Units units = new Units(200);
        for (int i = 0; i < perSide; i++) {
            units.spawn(x, z, a, 5000, 0f);
            units.spawn(x, z, b, 5000, 0f);
        }
        return units;
    }

    @Test
    void peacetimeCostsNothingAndKillsNobody() {
        World world = grassWorld();
        Units units = twoCrowdsAt(64.5f, 64.5f, 20, Species.HUMAN, Species.ORC);
        Villages villages = new Villages(8);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        density.rebuild(units);
        Relations peace = new Relations(new Random(1));

        int before = units.getLiveCount();
        for (int tick = 0; tick < 500; tick++) {
            assertEquals(0, CombatSystem.update(world, units, villages, peace, density, new Random(tick)));
        }
        assertEquals(before, units.getLiveCount());
    }

    @Test
    void enemiesSharingGroundKillEachOther() {
        World world = grassWorld();
        Units units = twoCrowdsAt(64.5f, 64.5f, 20, Species.HUMAN, Species.ORC);
        Villages villages = new Villages(8);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        Relations relations = warBetween(Species.HUMAN, Species.ORC);
        Random random = new Random(42);

        int deaths = 0;
        for (int tick = 0; tick < 300; tick++) {
            density.rebuild(units);
            deaths += CombatSystem.update(world, units, villages, relations, density, random);
        }

        assertTrue(deaths > 0, "a battle should produce casualties");
        assertEquals(40 - units.getLiveCount(), deaths, "every death must come out of the pool");
        assertTrue(relations.warCasualties(Species.HUMAN, Species.ORC) > 0,
            "casualties should be reported back to the pair that caused them");
    }

    @Test
    void bystandersAtPeaceAreNotDrawnIn() {
        World world = grassWorld();
        Units units = new Units(200);
        for (int i = 0; i < 20; i++) {
            units.spawn(64.5f, 64.5f, Species.HUMAN, 5000, 0f);
            units.spawn(64.5f, 64.5f, Species.ORC, 5000, 0f);
            // Elves are standing in exactly the same place and at war with nobody.
            units.spawn(64.5f, 64.5f, Species.ELF, 5000, 0f);
        }
        Villages villages = new Villages(8);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        Relations relations = warBetween(Species.HUMAN, Species.ORC);
        Random random = new Random(43);

        for (int tick = 0; tick < 300; tick++) {
            density.rebuild(units);
            CombatSystem.update(world, units, villages, relations, density, random);
        }
        assertEquals(20, units.countOf(Species.ELF), "a neutral species should not take losses");
        assertTrue(units.countOf(Species.HUMAN) + units.countOf(Species.ORC) < 40);
    }

    /** A world where one village of {@code owner} holds the ground around (64, 64). */
    private static Villages territoryHeldBy(World world, byte owner) {
        Villages villages = new Villages(8);
        int village = villages.found(64, 64, owner, 0);
        villages.population[village] = 10;
        villages.radius[village] = 8f;
        new Territory(world.tileCount).recompute(world, villages);
        assertEquals(village, world.ownerVillage[world.index(64, 64)]);
        return villages;
    }

    @Test
    void emptyEnemyTerritoryIsNotLethalOnItsOwn() {
        World world = grassWorld();
        Villages villages = territoryHeldBy(world, Species.ORC);

        Units units = new Units(8);
        // One lone human deep in orc land, with no orc anywhere near it. This
        // is what a refugee from a lost war looks like, and it is the case that
        // used to wipe a defeated species off the map everywhere at once.
        int refugee = units.spawn(64.5f, 64.5f, Species.HUMAN, 5000, 0f);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        density.rebuild(units);
        Relations relations = warBetween(Species.HUMAN, Species.ORC);

        for (int tick = 0; tick < 2000; tick++) {
            assertEquals(0,
                CombatSystem.update(world, units, villages, relations, density, new Random(tick)));
        }
        assertTrue(units.isAlive(refugee));
        assertEquals(SimConfig.UNIT_MAX_HEALTH, units.health[refugee],
            "ground cannot hurt anyone; only enemies can");
    }

    @Test
    void fightingOnEnemyGroundIsDeadlierThanFightingAtHome() {
        int awayLosses = battleLosses(Species.ORC);
        int homeLosses = battleLosses(Species.HUMAN);
        assertTrue(awayLosses > homeLosses,
            "defenders should have the edge: " + homeLosses + " lost at home vs "
                + awayLosses + " lost away");
    }

    /**
     * Runs an identical human-versus-orc battle on ground held by {@code owner}
     * and returns the humans' losses, so the only difference between the two
     * runs is whose territory it was fought on.
     */
    private static int battleLosses(byte owner) {
        World world = grassWorld();
        Villages villages = territoryHeldBy(world, owner);
        Units units = twoCrowdsAt(64.5f, 64.5f, 25, Species.HUMAN, Species.ORC);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        Relations relations = warBetween(Species.HUMAN, Species.ORC);
        Random random = new Random(77);

        for (int tick = 0; tick < 120; tick++) {
            density.rebuild(units);
            CombatSystem.update(world, units, villages, relations, density, random);
        }
        return 25 - units.countOf(Species.HUMAN);
    }

    @Test
    void ownTerritoryIsSafeInPeaceAndInWar() {
        World world = grassWorld();
        Villages villages = territoryHeldBy(world, Species.HUMAN);

        Units units = new Units(8);
        units.spawn(64.5f, 64.5f, Species.HUMAN, 5000, 0f);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        density.rebuild(units);
        Relations relations = warBetween(Species.HUMAN, Species.ORC);

        for (int tick = 0; tick < 2000; tick++) {
            assertEquals(0,
                CombatSystem.update(world, units, villages, relations, density, new Random(tick)));
        }
        assertEquals(SimConfig.UNIT_MAX_HEALTH, units.health[0], "nobody should be hurt at home");
    }

    @Test
    void unitsInAFightAreMarkedAsFighting() {
        World world = grassWorld();
        Units units = twoCrowdsAt(64.5f, 64.5f, 10, Species.HUMAN, Species.ORC);
        Villages villages = new Villages(8);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        density.rebuild(units);
        Relations relations = warBetween(Species.HUMAN, Species.ORC);

        CombatSystem.update(world, units, villages, relations, density, new Random(45));
        assertEquals(Units.STATE_FIGHT, units.state[0]);
    }

    @Test
    void aWoundedUnitDoesNotHealWhileStillInTheFight() {
        World world = grassWorld();
        Units units = new Units(8);
        int i = units.spawn(64.5f, 64.5f, Species.HUMAN, 5000, 0f);
        units.health[i] = 50;
        units.state[i] = Units.STATE_FIGHT;
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);

        // Grass underfoot, so this unit is fed and would otherwise be healing.
        UnitSystem.update(world, units, density, new Random(46));
        assertEquals(50, units.health[i], "healing mid-battle would make a battle line unbreakable");

        // Out of the fight, the same unit recovers.
        units.state[i] = Units.STATE_WANDER;
        UnitSystem.update(world, units, density, new Random(46));
        assertEquals(51, units.health[i]);
    }
}
