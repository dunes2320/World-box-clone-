package com.game.sim;

import java.util.Random;

/**
 * Civilian collateral inside an active army's radius.
 *
 * <p>Phase 10 moved organised violence into {@link MilitarySystem}: armies
 * are the ones doing the fighting, killing each other and reducing enemy
 * villages by siege. This system exists for the smaller half of the same
 * picture - civilian units caught inside a fighting army's radius take
 * hits from stray fire and rout. That is what makes a war look bloody on
 * the map without every wandering unit being a soldier.
 *
 * <p>Costs nothing outside a war: if no army is in the field this is one
 * boolean check.
 */
public final class CombatSystem {

    private CombatSystem() {
    }

    /** Kept as an overload for the tests that predate armies. */
    public static int update(World world, Units units, Villages villages,
                             Relations relations, DensityGrid density, Random random) {
        return update(world, units, villages, /* armies */ null, /* kingdoms */ null,
            /* lore */ null, relations, density, random);
    }

    /** Pre-lore overload retained for tests that do not touch traits. */
    public static int update(World world, Units units, Villages villages,
                             Armies armies, Kingdoms kingdoms,
                             Relations relations, DensityGrid density, Random random) {
        return update(world, units, villages, armies, kingdoms, /* lore */ null,
            relations, density, random);
    }

    /**
     * Resolves one tick of civilian casualties around armies.
     *
     * @return civilians killed
     */
    public static int update(World world, Units units, Villages villages,
                             Armies armies, Kingdoms kingdoms, UnitLore lore,
                             Relations relations, DensityGrid density, Random random) {
        if (armies == null || armies.getLiveCount() == 0) {
            return 0;
        }
        int casualties = 0;
        int armiesEnd = armies.getHighWater();
        for (int a = 0; a < armiesEnd; a++) {
            if (!armies.alive[a]) continue;
            // Only actively fighting armies threaten civilians. A marching
            // column passes through without slaughter.
            if (armies.state[a] != Armies.STATE_BESIEGING) continue;
            float ax = armies.x[a];
            float az = armies.z[a];
            byte attackerSpecies = kingdoms != null && kingdoms.isAlive(armies.kingdom[a])
                ? kingdoms.species[armies.kingdom[a]] : -1;
            float rSq = SimConfig.ARMY_COLLATERAL_RADIUS * SimConfig.ARMY_COLLATERAL_RADIUS;

            int end = units.getHighWater();
            for (int i = 0; i < end; i++) {
                if (!units.alive[i]) continue;
                if (attackerSpecies >= 0 && units.species[i] == attackerSpecies) continue;
                float dx = units.x[i] - ax;
                float dz = units.z[i] - az;
                if (dx * dx + dz * dz > rSq) continue;
                units.state[i] = Units.STATE_FIGHT;
                double hitChance = SimConfig.ARMY_COLLATERAL_CHANCE;
                if (lore != null && Traits.has(lore.traits[i], Traits.STRONG)) {
                    hitChance *= SimConfig.TRAIT_STRONG_COLLATERAL_MULT;
                }
                if (random.nextDouble() >= hitChance) continue;
                units.health[i] -= SimConfig.COMBAT_DAMAGE;
                if (units.health[i] <= 0) {
                    if (attackerSpecies >= 0 && relations != null) {
                        relations.recordCasualty(units.species[i], attackerSpecies);
                    }
                    if (lore != null) lore.clear(i);
                    units.kill(i);
                    casualties++;
                }
            }
        }
        return casualties;
    }
}
