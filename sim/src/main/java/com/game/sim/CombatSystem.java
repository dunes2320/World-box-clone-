package com.game.sim;

import java.util.Random;

/**
 * Fighting between species that are at war.
 *
 * <p>There are no armies and no orders. A unit is in danger when enemies of a
 * species it is at war with share its patch of ground, and in more danger when
 * it is standing on their territory. That produces exactly what a god looking
 * down wants to see: fighting concentrated where two colours meet, thinning out
 * behind the lines, and a front that moves when one side starts winning - all
 * without a single pathfinding call.
 *
 * <p>Costs nothing in peacetime: with no war anywhere the whole pass is one
 * boolean check.
 */
public final class CombatSystem {

    private CombatSystem() {
    }

    /**
     * Resolves one tick of fighting.
     *
     * @return how many units died
     */
    public static int update(World world, Units units, Villages villages,
                             Relations relations, DensityGrid density, Random random) {
        if (!relations.anyWar()) {
            return 0;
        }

        int casualties = 0;
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!units.alive[i]) {
                continue;
            }
            byte species = units.species[i];

            // The most numerous hostile species sharing this cell. Tracked
            // rather than just totalled so a death can be blamed on someone -
            // casualties feed back into that pair's relations, which is what
            // makes a bloody war last longer than a quiet one.
            int enemies = 0;
            byte chiefEnemy = -1;
            int chiefEnemyCount = 0;
            for (byte other = 0; other < Species.COUNT; other++) {
                if (other == species || !relations.isAtWar(species, other)) {
                    continue;
                }
                int count = density.speciesAt(units.x[i], units.z[i], other);
                enemies += count;
                if (count > chiefEnemyCount) {
                    chiefEnemyCount = count;
                    chiefEnemy = other;
                }
            }

            if (enemies == 0) {
                // Nobody to fight. No need to clear a stale fighting state
                // either: UnitSystem rewrites every unit's state earlier in the
                // same tick, so this pass only ever adds STATE_FIGHT on top of
                // a fresh value.
                continue;
            }

            units.state[i] = Units.STATE_FIGHT;

            double risk = enemies * SimConfig.COMBAT_RISK_PER_ENEMY;
            if (isTrespassing(world, villages, relations, units, i)) {
                risk *= SimConfig.COMBAT_DEFENDER_ADVANTAGE;
            }
            if (random.nextDouble() >= risk) {
                continue;
            }

            units.health[i] -= SimConfig.COMBAT_DAMAGE;
            if (units.health[i] > 0) {
                continue;
            }

            relations.recordCasualty(species, chiefEnemy);
            units.kill(i);
            casualties++;
        }
        return casualties;
    }

    /** True if this unit is standing on the territory of a species it is at war with. */
    private static boolean isTrespassing(World world, Villages villages, Relations relations,
                                         Units units, int i) {
        int tileX = (int) Math.floor(units.x[i]);
        int tileZ = (int) Math.floor(units.z[i]);
        if (!world.inBounds(tileX, tileZ)) {
            return false;
        }
        short owner = world.ownerVillage[world.index(tileX, tileZ)];
        if (owner == World.NO_OWNER || !villages.isAlive(owner)) {
            return false;
        }
        byte ownerSpecies = villages.species[owner];
        return ownerSpecies != units.species[i]
            && relations.isAtWar(units.species[i], ownerSpecies);
    }
}
