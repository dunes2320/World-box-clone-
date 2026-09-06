package com.game.sim;

import java.util.Random;

/**
 * Founds and spreads religions.
 *
 * <p>Two things happen per pass. First, any village that has a temple and
 * belongs to a kingdom without a state religion may - at a low chance -
 * found a new one and become its first follower. That religion becomes
 * the kingdom's official faith and quietly seeds every kingdomless of
 * its own villages that has not adopted another one.
 *
 * <p>Second, faiths spread along the road network. For every road-connected
 * pair of same-kingdom villages, an unconverted village may take on the
 * neighbour's religion at {@link SimConfig#RELIGION_SPREAD_CHANCE}. Trade
 * routes carry ideas as well as goods.
 *
 * <p>Occasional splits: a very old religion in a distant village
 * occasionally spawns a sect - a new religion whose founder is the
 * splitting village's kingdom, but visibly a different faith.
 */
public final class ReligionSystem {

    private ReligionSystem() {
    }

    public static void update(World world, Villages villages, Kingdoms kingdoms,
                              Religions religions, Roads roads, Features features,
                              Random random, int tick, long worldSeed) {
        foundReligions(villages, kingdoms, features, religions, random, tick, worldSeed);
        assignKingdomFaithToNewMembers(villages, kingdoms, religions);
        spreadAlongRoads(world, villages, kingdoms, religions, roads, random);
        maybeSplit(villages, kingdoms, religions, random, tick, worldSeed);
        recountFollowers(villages, religions);
        dissolveEmpty(religions);
    }

    private static void foundReligions(Villages villages, Kingdoms kingdoms, Features features,
                                       Religions religions, Random random, int tick,
                                       long worldSeed) {
        int fEnd = features.getHighWater();
        for (int f = 0; f < fEnd; f++) {
            if (!features.isAlive(f) || features.buildTime[f] > 0) continue;
            if (features.kind[f] != Features.KIND_TEMPLE) continue;
            short v = features.owner[f];
            if (!villages.isAlive(v)) continue;
            short k = villages.kingdom[v];
            if (k == Villages.NO_KINGDOM || !kingdoms.isAlive(k)) continue;
            if (kingdoms.religion[k] != Religions.NO_RELIGION) continue;
            if (random.nextDouble() >= SimConfig.RELIGION_FOUND_CHANCE) continue;
            int r = religions.found(k, kingdoms.species[k], tick, worldSeed);
            if (r < 0) continue;
            kingdoms.religion[k] = (short) r;
            villages.religion[v] = (short) r;
        }
    }

    private static void assignKingdomFaithToNewMembers(Villages villages, Kingdoms kingdoms,
                                                       Religions religions) {
        int end = villages.getHighWater();
        for (int v = 0; v < end; v++) {
            if (!villages.alive[v]) continue;
            short k = villages.kingdom[v];
            if (k == Villages.NO_KINGDOM || !kingdoms.isAlive(k)) continue;
            short kFaith = kingdoms.religion[k];
            if (kFaith == Religions.NO_RELIGION) continue;
            if (villages.religion[v] == Religions.NO_RELIGION) {
                villages.religion[v] = kFaith;
            }
        }
    }

    private static void spreadAlongRoads(World world, Villages villages, Kingdoms kingdoms,
                                         Religions religions, Roads roads, Random random) {
        if (roads.getCount() == 0) return;
        int end = villages.getHighWater();
        // For every pair of same-kingdom villages, if both centres are on
        // the road network, the one with a faith can convert the one
        // without. Deliberately not "any neighbour" - the trade-route
        // dependency is the whole point.
        for (int a = 0; a < end; a++) {
            if (!villages.alive[a]) continue;
            short ra = villages.religion[a];
            if (ra == Religions.NO_RELIGION) continue;
            int ax = villages.x[a], az = villages.z[a];
            if (!roads.isRoad(world.index(ax, az))) continue;
            short kA = villages.kingdom[a];
            for (int b = 0; b < end; b++) {
                if (a == b || !villages.alive[b]) continue;
                if (villages.kingdom[b] != kA) continue;
                if (villages.religion[b] == ra) continue;
                int bx = villages.x[b], bz = villages.z[b];
                float dx = bx - ax, dz = bz - az;
                if (dx * dx + dz * dz > SimConfig.RELIGION_SPREAD_RANGE
                    * SimConfig.RELIGION_SPREAD_RANGE) continue;
                if (!roads.isRoad(world.index(bx, bz))) continue;
                if (random.nextDouble() < SimConfig.RELIGION_SPREAD_CHANCE) {
                    villages.religion[b] = ra;
                }
            }
        }
    }

    private static void maybeSplit(Villages villages, Kingdoms kingdoms, Religions religions,
                                   Random random, int tick, long worldSeed) {
        int rEnd = religions.getHighWater();
        for (int r = 0; r < rEnd; r++) {
            if (!religions.isAlive(r)) continue;
            if (tick - religions.foundedTick[r] < SimConfig.RELIGION_SPLIT_MIN_AGE) continue;
            if (random.nextDouble() >= SimConfig.RELIGION_SPLIT_CHANCE) continue;
            // Find any village of this religion that is not the capital of
            // its kingdom - a distant follower is the natural cradle for a
            // sect. Pick the first that fits; the RNG has already voted on
            // whether this split happens at all.
            int vEnd = villages.getHighWater();
            for (int v = 0; v < vEnd; v++) {
                if (!villages.alive[v] || villages.religion[v] != (short) r) continue;
                short k = villages.kingdom[v];
                if (k == Villages.NO_KINGDOM || !kingdoms.isAlive(k)) continue;
                if (kingdoms.capital[k] == (short) v) continue;
                int sect = religions.found(k, kingdoms.species[k], tick, worldSeed ^ r);
                if (sect < 0) return;
                villages.religion[v] = (short) sect;
                return;
            }
        }
    }

    private static void recountFollowers(Villages villages, Religions religions) {
        int rEnd = religions.getHighWater();
        for (int r = 0; r < rEnd; r++) {
            if (religions.isAlive(r)) religions.followers[r] = 0;
        }
        int vEnd = villages.getHighWater();
        for (int v = 0; v < vEnd; v++) {
            if (!villages.alive[v]) continue;
            short r = villages.religion[v];
            if (r != Religions.NO_RELIGION && religions.isAlive(r)) {
                religions.followers[r]++;
            }
        }
    }

    private static void dissolveEmpty(Religions religions) {
        int rEnd = religions.getHighWater();
        for (int r = 0; r < rEnd; r++) {
            if (religions.isAlive(r) && religions.followers[r] == 0) {
                religions.dissolve(r);
            }
        }
    }
}
