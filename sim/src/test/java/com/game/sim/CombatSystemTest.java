package com.game.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Phase 10 moved organised violence into {@link MilitarySystem} - armies do
 * the killing, and civilians take collateral only when caught inside an
 * actively-besieging army's radius. These tests cover the surviving half
 * of {@link CombatSystem}: peacetime is free, an army's siege radius bites
 * enemy civilians, and units already in a fight do not heal that same tick.
 */
class CombatSystemTest {

    private static World grassWorld() {
        World world = new World();
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = 2.0f;
            world.tileType[i] = TileType.GRASS;
        }
        return world;
    }

    private static Kingdoms twoKingdomsHumansAndOrcs() {
        Kingdoms k = new Kingdoms(4);
        k.found(0, Species.HUMAN, 0);
        k.found(1, Species.ORC, 0);
        return k;
    }

    private static Relations warBetween(byte a, byte b) {
        Relations relations = new Relations(new Random(1));
        relations.set(a, b, -1f);
        relations.update(new int[Species.COUNT * Species.COUNT], new Random(1), 0);
        assertTrue(relations.isAtWar(a, b), "test setup should have started a war");
        return relations;
    }

    @Test
    void peacetimeCostsNothingAndKillsNobody() {
        World world = grassWorld();
        Units units = new Units(200);
        for (int i = 0; i < 20; i++) {
            units.spawn(64.5f, 64.5f, Species.HUMAN, 5000, 0f);
            units.spawn(64.5f, 64.5f, Species.ORC, 5000, 0f);
        }
        Villages villages = new Villages(8);
        Armies armies = new Armies(4);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        Relations peace = new Relations(new Random(1));

        int before = units.getLiveCount();
        for (int tick = 0; tick < 500; tick++) {
            assertEquals(0, CombatSystem.update(world, units, villages, armies, null,
                peace, density, new Random(tick)));
        }
        assertEquals(before, units.getLiveCount());
    }

    @Test
    void anArmyBesiegingKillsEnemyCiviliansInItsRadius() {
        World world = grassWorld();
        Units units = new Units(200);
        for (int i = 0; i < 40; i++) {
            units.spawn(64.5f, 64.5f, Species.ORC, 5000, 0f);
        }
        Villages villages = new Villages(4);
        Kingdoms kingdoms = twoKingdomsHumansAndOrcs();
        Armies armies = new Armies(4);
        int army = armies.raise(0, 64.5f, 64.5f, 10, 0, 0);
        armies.setState(army, Armies.STATE_BESIEGING, 0);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        Relations relations = warBetween(Species.HUMAN, Species.ORC);

        int deaths = 0;
        for (int tick = 0; tick < 300; tick++) {
            deaths += CombatSystem.update(world, units, villages, armies, kingdoms,
                relations, density, new Random(tick));
        }
        assertTrue(deaths > 0, "an army under siege should draw civilian blood in its radius");
        assertEquals(40 - units.getLiveCount(), deaths, "every death must come out of the pool");
    }

    @Test
    void aMarchingArmyPassesThroughWithoutSlaughter() {
        World world = grassWorld();
        Units units = new Units(80);
        for (int i = 0; i < 30; i++) {
            units.spawn(64.5f, 64.5f, Species.ORC, 5000, 0f);
        }
        Villages villages = new Villages(4);
        Kingdoms kingdoms = twoKingdomsHumansAndOrcs();
        Armies armies = new Armies(4);
        int army = armies.raise(0, 64.5f, 64.5f, 10, 0, 0);
        // STATE_MARCHING - the default from raise() - should hurt no civilian.
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        Relations relations = warBetween(Species.HUMAN, Species.ORC);

        int before = units.getLiveCount();
        for (int tick = 0; tick < 300; tick++) {
            CombatSystem.update(world, units, villages, armies, kingdoms,
                relations, density, new Random(tick));
        }
        assertEquals(before, units.getLiveCount(),
            "an army only sacking a village does damage; a column marching does not");
    }

    @Test
    void bystandersOfTheAttackingSpeciesAreNotHitByTheirOwnArmy() {
        World world = grassWorld();
        Units units = new Units(200);
        for (int i = 0; i < 20; i++) {
            units.spawn(64.5f, 64.5f, Species.ORC, 5000, 0f);
            units.spawn(64.5f, 64.5f, Species.HUMAN, 5000, 0f);
            // Elves are standing in exactly the same place and at war with nobody.
            units.spawn(64.5f, 64.5f, Species.ELF, 5000, 0f);
        }
        Villages villages = new Villages(4);
        Kingdoms kingdoms = twoKingdomsHumansAndOrcs();
        Armies armies = new Armies(4);
        // Human army besieging orcish ground. Elves are not the attacker so
        // they DO take collateral; own-species humans do not.
        int army = armies.raise(0, 64.5f, 64.5f, 12, 0, 0);
        armies.setState(army, Armies.STATE_BESIEGING, 0);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        Relations relations = warBetween(Species.HUMAN, Species.ORC);

        int humansBefore = units.countOf(Species.HUMAN);
        for (int tick = 0; tick < 300; tick++) {
            CombatSystem.update(world, units, villages, armies, kingdoms,
                relations, density, new Random(tick));
        }
        assertEquals(humansBefore, units.countOf(Species.HUMAN),
            "an army does not shoot its own species");
    }

    @Test
    void unitsInAnArmysBesiegingRadiusAreMarkedAsFighting() {
        World world = grassWorld();
        Units units = new Units(8);
        int u = units.spawn(64.5f, 64.5f, Species.ORC, 5000, 0f);
        Villages villages = new Villages(4);
        Kingdoms kingdoms = twoKingdomsHumansAndOrcs();
        Armies armies = new Armies(4);
        int army = armies.raise(0, 64.5f, 64.5f, 6, 0, 0);
        armies.setState(army, Armies.STATE_BESIEGING, 0);
        DensityGrid density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        Relations relations = warBetween(Species.HUMAN, Species.ORC);

        CombatSystem.update(world, units, villages, armies, kingdoms, relations, density,
            new Random(45));
        assertEquals(Units.STATE_FIGHT, units.state[u]);
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
