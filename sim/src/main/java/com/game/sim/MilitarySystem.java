package com.game.sim;

import java.util.Random;

/**
 * Raises armies for kingdoms at war, marches them at their targets, and
 * resolves the sieges.
 *
 * <p>Runs on the same slow cadence as {@link KingdomSystem}. For each
 * kingdom-pair at war, this checks whether the attacker already has an
 * army in the field against that enemy: if not, it raises one from the
 * kingdom's capital by drafting soldiers from the capital village's own
 * population. The soldiers are subtracted from village population there
 * and then: the sim does not model each soldier as a
 * {@link Units} entry (that would double the pool for what is a per-kingdom
 * concept), only their count inside the army.
 *
 * <p>March step advances an army toward its target at a fixed speed. On
 * arrival it flips to besieging: siege ticks damage the defending village's
 * garrison and, when the village runs out of defenders, capture transfers
 * ownership to the attacking kingdom. Two hostile armies within
 * {@link SimConfig#ARMY_FIGHT_RANGE} tiles of each other fight instead of
 * either continuing on target, so wars actually intercept.
 */
public final class MilitarySystem {

    private MilitarySystem() {
    }

    /** How many war-dead across all armies this pass. */
    public static int update(World world, Villages villages, Kingdoms kingdoms,
                             KingdomRelations relations, Armies armies,
                             Features features, Random random, int tick) {
        int dead = raiseArmies(villages, kingdoms, relations, armies, tick);
        dead += fightAndMarch(world, villages, kingdoms, relations, armies, features, random, tick);
        return dead;
    }

    /**
     * For each kingdom at war with each enemy, raise one army against that
     * enemy if none exists yet. Stops raising when the capital village
     * cannot spare any more soldiers - a kingdom of six people does not put
     * an army of ten in the field.
     */
    private static int raiseArmies(Villages villages, Kingdoms kingdoms,
                                   KingdomRelations relations, Armies armies, int tick) {
        int end = kingdoms.getHighWater();
        for (int a = 0; a < end; a++) {
            if (!kingdoms.alive[a]) continue;
            for (int b = 0; b < end; b++) {
                if (a == b || !kingdoms.alive[b]) continue;
                if (!relations.isAtWar(a, b)) continue;
                if (hasArmyAgainst(armies, a, b, kingdoms)) continue;

                int capitalV = kingdoms.capital[a];
                if (!villages.isAlive(capitalV)) continue;
                int available = Math.min(
                    villages.population[capitalV] - SimConfig.ARMY_CAPITAL_RESERVE,
                    SimConfig.ARMY_MAX_SIZE);
                if (available < SimConfig.ARMY_MIN_SIZE) continue;

                int target = pickEnemyTarget(villages, kingdoms, b, capitalV);
                if (target < 0) continue;

                villages.population[capitalV] -= available;
                armies.raise(a, villages.x[capitalV] + 0.5f, villages.z[capitalV] + 0.5f,
                    available, target, tick);
            }
        }
        return 0;
    }

    private static boolean hasArmyAgainst(Armies armies, int attacker, int enemy,
                                          Kingdoms kingdoms) {
        int end = armies.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!armies.alive[i] || armies.kingdom[i] != (short) attacker) continue;
            // Match by target's owning kingdom, not by target index, so
            // several enemy villages in the same kingdom count as one target.
            short tv = armies.targetVillage[i];
            // targetVillage may have been captured or dissolved; that army
            // will retarget on its own next fightAndMarch pass. Do not
            // suppress raising a fresh one on that basis alone.
            if (tv < 0) continue;
            if (armies.state[i] == Armies.STATE_RETREATING) continue;
            return true;
        }
        return false;
    }

    private static int pickEnemyTarget(Villages villages, Kingdoms kingdoms,
                                       int enemyKingdom, int fromVillage) {
        int end = villages.getHighWater();
        int best = -1;
        long bestD2 = Long.MAX_VALUE;
        int fx = villages.x[fromVillage];
        int fz = villages.z[fromVillage];
        for (int v = 0; v < end; v++) {
            if (!villages.alive[v] || villages.kingdom[v] != (short) enemyKingdom) continue;
            int dx = villages.x[v] - fx;
            int dz = villages.z[v] - fz;
            long d2 = (long) dx * dx + (long) dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = v;
            }
        }
        return best;
    }

    private static int fightAndMarch(World world, Villages villages, Kingdoms kingdoms,
                                     KingdomRelations relations, Armies armies,
                                     Features features, Random random, int tick) {
        int dead = 0;
        int end = armies.getHighWater();

        // Field battles: any pair of hostile armies within ARMY_FIGHT_RANGE
        // exchange losses before either advances. Handled first so wars
        // actually intercept rather than the two sides marching straight
        // past each other.
        for (int i = 0; i < end; i++) {
            if (!armies.alive[i]) continue;
            for (int j = i + 1; j < end; j++) {
                if (!armies.alive[j]) continue;
                if (armies.kingdom[i] == armies.kingdom[j]) continue;
                if (!relations.isAtWar(armies.kingdom[i], armies.kingdom[j])) continue;
                float dx = armies.x[i] - armies.x[j];
                float dz = armies.z[i] - armies.z[j];
                if (dx * dx + dz * dz > SimConfig.ARMY_FIGHT_RANGE * SimConfig.ARMY_FIGHT_RANGE) continue;
                dead += resolveFieldBattle(armies, relations, i, j, random);
            }
        }

        // March survivors toward their targets, or besiege on arrival.
        for (int i = 0; i < end; i++) {
            if (!armies.alive[i]) continue;
            short tv = armies.targetVillage[i];
            if (!villages.isAlive(tv)) {
                // Target gone (captured, dissolved) - retarget or retreat.
                int newTarget = pickEnemyTargetForArmy(villages, armies, i);
                if (newTarget < 0) {
                    armies.disband(i);
                    continue;
                }
                armies.setTarget(i, newTarget);
                armies.setState(i, Armies.STATE_MARCHING, tick);
                tv = (short) newTarget;
            }
            // If the target is no longer hostile - captured back into a
            // friendly kingdom - retreat home.
            short defenderK = villages.kingdom[tv];
            if (defenderK == armies.kingdom[i]
                || defenderK == Villages.NO_KINGDOM
                || !relations.isAtWar(armies.kingdom[i], defenderK)) {
                armies.disband(i);
                continue;
            }

            float tx = villages.x[tv] + 0.5f;
            float tz = villages.z[tv] + 0.5f;
            float dx = tx - armies.x[i];
            float dz = tz - armies.z[i];
            float d2 = dx * dx + dz * dz;
            if (d2 <= SimConfig.ARMY_ARRIVE_RANGE * SimConfig.ARMY_ARRIVE_RANGE) {
                if (armies.state[i] != Armies.STATE_BESIEGING) {
                    armies.setState(i, Armies.STATE_BESIEGING, tick);
                }
                dead += siegeTick(villages, kingdoms, relations, armies, features, i, tick);
            } else {
                armies.setState(i, Armies.STATE_MARCHING, tick);
                float len = (float) Math.sqrt(d2);
                float step = Math.min(SimConfig.ARMY_MARCH_SPEED, len);
                armies.x[i] += dx / len * step;
                armies.z[i] += dz / len * step;
            }
        }
        return dead;
    }

    private static int resolveFieldBattle(Armies armies, KingdomRelations relations,
                                          int i, int j, Random random) {
        int a = armies.size[i];
        int b = armies.size[j];
        // Bigger army loses fewer, but both lose. Draw the losses proportional
        // to the enemy's size so a 100-vs-10 encounter is a lopsided blow
        // rather than mutual annihilation.
        int lossA = Math.max(1, b * SimConfig.ARMY_HIT_NUMERATOR / SimConfig.ARMY_HIT_DENOMINATOR
            + random.nextInt(3));
        int lossB = Math.max(1, a * SimConfig.ARMY_HIT_NUMERATOR / SimConfig.ARMY_HIT_DENOMINATOR
            + random.nextInt(3));
        armies.applyLosses(i, lossA);
        armies.applyLosses(j, lossB);
        relations.recordCasualty(armies.kingdom[i], armies.kingdom[j]);
        return lossA + lossB;
    }

    private static int siegeTick(Villages villages, Kingdoms kingdoms,
                                 KingdomRelations relations, Armies armies,
                                 Features features, int armyIndex, int tick) {
        short tv = armies.targetVillage[armyIndex];
        int attackerKingdom = armies.kingdom[armyIndex];
        // Each siege pass costs the village population - the defenders take
        // hits from the assault. Big armies do more damage per pass.
        int assault = Math.max(1, armies.size[armyIndex] / SimConfig.SIEGE_DAMAGE_DIVISOR);
        int dead = Math.min(villages.population[tv], assault);
        villages.population[tv] -= dead;

        // Defenders also inflict losses on the besieging army - not enough
        // to matter when the defender is thin, real when it is thick.
        int defense = Math.max(0, villages.population[tv] / SimConfig.SIEGE_DEFENSE_DIVISOR);
        armies.applyLosses(armyIndex, defense);
        if (!armies.isAlive(armyIndex)) {
            return dead + defense;
        }

        if (villages.population[tv] <= 0) {
            capture(villages, kingdoms, relations, features, tv, attackerKingdom, tick);
            // Army lingers briefly then disbands as garrison - the plan
            // treats a captured village as the endpoint of a campaign, not
            // a beachhead for the next one on the same march.
            armies.disband(armyIndex);
        }
        return dead + defense;
    }

    /**
     * Ownership transfer: village kingdom changes, some buildings damaged
     * as loot/destruction, a fraction of the population survives to become
     * subjects of the new kingdom.
     */
    private static void capture(Villages villages, Kingdoms kingdoms,
                                KingdomRelations relations, Features features,
                                int villageIndex, int attackerKingdom, int tick) {
        short oldKingdom = villages.kingdom[villageIndex];
        villages.kingdom[villageIndex] = (short) attackerKingdom;
        // Survivors: a small remnant repopulates under new colours.
        villages.population[villageIndex] = SimConfig.SIEGE_SURVIVORS;
        // Damage a chunk of the buildings on the tile - visible war damage.
        int end = features.getHighWater();
        int destroyed = 0;
        for (int f = 0; f < end && destroyed < SimConfig.SIEGE_BUILDINGS_LOST; f++) {
            if (!features.isAlive(f) || features.owner[f] != (short) villageIndex) continue;
            byte kind = features.kind[f];
            if (kind == Features.KIND_ROAD || kind == Features.KIND_HOUSE) continue;
            features.remove(f);
            destroyed++;
        }
        // A captured capital moves; the old kingdom picks a new one next
        // KingdomSystem pass. Nothing to do here beyond leaving the field.
        if (oldKingdom != Villages.NO_KINGDOM && kingdoms.isAlive(oldKingdom)) {
            relations.recordCasualty(attackerKingdom, oldKingdom);
        }
    }

    private static int pickEnemyTargetForArmy(Villages villages, Armies armies, int army) {
        // Repurposes the shared picker with the army's current position as
        // the "from" - retargeting to whatever hostile village is closest.
        int end = villages.getHighWater();
        int best = -1;
        double bestD2 = Double.MAX_VALUE;
        short myK = armies.kingdom[army];
        for (int v = 0; v < end; v++) {
            if (!villages.alive[v]) continue;
            short vk = villages.kingdom[v];
            if (vk == myK || vk == Villages.NO_KINGDOM) continue;
            double dx = (villages.x[v] + 0.5) - armies.x[army];
            double dz = (villages.z[v] + 0.5) - armies.z[army];
            double d2 = dx * dx + dz * dz;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = v;
            }
        }
        return best;
    }
}
