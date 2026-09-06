package com.game.sim;

import java.util.Random;

/**
 * Sorts villages into kingdoms and keeps the kingdom bookkeeping current.
 *
 * <p>Runs on the same slow clock as the village pass. Each village without
 * a kingdom either joins its species' nearest kingdom within
 * {@link SimConfig#KINGDOM_JOIN_RANGE} or founds a new one. After that,
 * this pass recounts villageCount and population for every kingdom,
 * dissolves any kingdom whose last village fell, and picks a new capital
 * when the old one is captured or destroyed.
 *
 * <p>Nothing here declares wars - that is {@link KingdomRelations#update}
 * on its own slower clock. This pass only shapes the map that diplomacy
 * then reads.
 */
public final class KingdomSystem {

    private KingdomSystem() {
    }

    /**
     * One kingdom pass. Call every {@link SimConfig#VILLAGE_UPDATE_INTERVAL}
     * ticks alongside the village pass.
     */
    public static void update(Villages villages, Kingdoms kingdoms,
                              KingdomRelations relations, Random random, int tick) {
        assignFreshVillages(villages, kingdoms, tick);
        recountKingdoms(villages, kingdoms);
        dissolveEmpty(villages, kingdoms, relations, tick);
        checkRebellion(villages, kingdoms, relations, tick);
    }

    /**
     * For each village that has no kingdom yet, either put it in the nearest
     * same-species kingdom that is close enough, or found a new kingdom
     * around it.
     */
    private static void assignFreshVillages(Villages villages, Kingdoms kingdoms, int tick) {
        int end = villages.getHighWater();
        for (int v = 0; v < end; v++) {
            if (!villages.alive[v] || villages.kingdom[v] != Villages.NO_KINGDOM) {
                continue;
            }
            int host = findNearestKingdom(villages, kingdoms, v);
            if (host >= 0) {
                villages.kingdom[v] = (short) host;
            } else {
                // Found a fresh kingdom around this village. If the pool is
                // full the village stays kingdomless until a slot opens up -
                // which is fine, since no kingdom means no army and no war
                // for it yet, not a broken invariant.
                int newKingdom = kingdoms.found(v, villages.species[v], tick);
                if (newKingdom >= 0) {
                    villages.kingdom[v] = (short) newKingdom;
                }
            }
        }
    }

    private static int findNearestKingdom(Villages villages, Kingdoms kingdoms, int v) {
        byte speciesId = villages.species[v];
        int vx = villages.x[v];
        int vz = villages.z[v];
        float rangeSq = SimConfig.KINGDOM_JOIN_RANGE * SimConfig.KINGDOM_JOIN_RANGE;
        int best = -1;
        float bestDistSq = rangeSq;
        int end = kingdoms.getHighWater();
        for (int k = 0; k < end; k++) {
            if (!kingdoms.alive[k] || kingdoms.species[k] != speciesId) {
                continue;
            }
            int capital = kingdoms.capital[k];
            if (!villages.isAlive(capital)) {
                continue;
            }
            int dx = villages.x[capital] - vx;
            int dz = villages.z[capital] - vz;
            float d2 = dx * dx + dz * dz;
            if (d2 <= bestDistSq) {
                bestDistSq = d2;
                best = k;
            }
        }
        return best;
    }

    private static void recountKingdoms(Villages villages, Kingdoms kingdoms) {
        for (int k = 0; k < kingdoms.getHighWater(); k++) {
            if (!kingdoms.alive[k]) continue;
            kingdoms.villageCount[k] = 0;
            kingdoms.population[k] = 0;
        }
        int end = villages.getHighWater();
        for (int v = 0; v < end; v++) {
            if (!villages.alive[v]) continue;
            short k = villages.kingdom[v];
            if (k == Villages.NO_KINGDOM || !kingdoms.isAlive(k)) continue;
            kingdoms.villageCount[k]++;
            kingdoms.population[k] += villages.population[v];
        }
    }

    /**
     * Dissolves any kingdom left with zero villages. Rare after founding
     * (villages join before they die), common after a war of conquest.
     */
    private static void dissolveEmpty(Villages villages, Kingdoms kingdoms,
                                      KingdomRelations relations, int tick) {
        int end = kingdoms.getHighWater();
        for (int k = 0; k < end; k++) {
            if (!kingdoms.alive[k]) continue;
            if (kingdoms.villageCount[k] > 0) {
                // The capital might have fallen even while other villages
                // survive: promote one of them to the capital.
                if (!villages.isAlive(kingdoms.capital[k])
                    || villages.kingdom[kingdoms.capital[k]] != (short) k) {
                    int replacement = findAnyMemberVillage(villages, k);
                    if (replacement >= 0) {
                        kingdoms.setCapital(k, replacement);
                    }
                }
                continue;
            }
            relations.clearSlot(k, tick);
            kingdoms.dissolve(k);
        }
    }

    /**
     * A village too far from its capital and unhappy enough with it splits
     * off into its own kingdom. This is what makes an over-large empire
     * fracture on its own - a fresh kingdom appears in the rebel village
     * and the parent loses that member.
     */
    private static void checkRebellion(Villages villages, Kingdoms kingdoms,
                                       KingdomRelations relations, int tick) {
        int end = villages.getHighWater();
        for (int v = 0; v < end; v++) {
            if (!villages.alive[v]) continue;
            short k = villages.kingdom[v];
            if (k == Villages.NO_KINGDOM || !kingdoms.isAlive(k)) continue;
            if (kingdoms.capital[k] == (short) v) continue;
            int capital = kingdoms.capital[k];
            if (!villages.isAlive(capital)) continue;
            int dx = villages.x[capital] - villages.x[v];
            int dz = villages.z[capital] - villages.z[v];
            float d2 = dx * dx + dz * dz;
            if (d2 < SimConfig.KINGDOM_REBEL_DISTANCE * SimConfig.KINGDOM_REBEL_DISTANCE) {
                continue;
            }
            // Distance alone is not enough - the age of the parent kingdom
            // and its size push the odds up, so a young or tiny kingdom
            // does not immediately fracture the day it stretches too far.
            if (tick - kingdoms.foundedTick[k] < SimConfig.KINGDOM_REBEL_MIN_AGE) {
                continue;
            }
            if (kingdoms.villageCount[k] < 3) {
                continue;
            }
            // Rebel: the village founds a new kingdom of the same species.
            int newK = kingdoms.found(v, villages.species[v], tick);
            if (newK < 0) continue;
            villages.kingdom[v] = (short) newK;
            kingdoms.villageCount[k]--;
            kingdoms.villageCount[newK] = 1;
            // Rebels do not open at peace with the parent - the split was
            // grievance-driven, and that shows in the opening number.
            relations.set(newK, k, -0.35f);
        }
    }

    private static int findAnyMemberVillage(Villages villages, int kingdomIndex) {
        int end = villages.getHighWater();
        for (int v = 0; v < end; v++) {
            if (villages.alive[v] && villages.kingdom[v] == (short) kingdomIndex) {
                return v;
            }
        }
        return -1;
    }
}
