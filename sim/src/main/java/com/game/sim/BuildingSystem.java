package com.game.sim;

import java.util.Random;

/**
 * Decides what a village builds next, and where.
 *
 * <p>Runs on the same slow cadence as the village pass. Each village has a
 * budget of construction slots that scales with its population, so a
 * hamlet has a handful of features while a proper town covers dozens of
 * tiles. When a village is under budget for a given kind, this system
 * scans tiles inside its footprint and picks the highest-scoring vacant
 * one via {@link Buildings#score}.
 *
 * <p>Candidate scans are bounded rather than exhaustive: for each build
 * attempt this checks a handful of random tiles inside the radius rather
 * than every one, and picks the best of that sample. Enough to place well
 * on good ground when it is there, and cheap enough that the pass is
 * still O(villages).
 */
public final class BuildingSystem {

    private BuildingSystem() {
    }

    /** How many candidates to sample per placement attempt. */
    private static final int CANDIDATES_PER_ATTEMPT = 12;

    /** How many placement attempts a village gets each pass. */
    private static final int ATTEMPTS_PER_VILLAGE = 3;

    /**
     * Builds what a village wants and can afford this pass.
     *
     * @return how many features were placed across all villages
     */
    public static int update(World world, Villages villages, Features features,
                             Random random, int tick) {
        int placed = 0;
        int villagesEnd = villages.getHighWater();
        for (int v = 0; v < villagesEnd; v++) {
            if (!villages.alive[v]) {
                continue;
            }
            placed += buildForVillage(world, villages, features, random, v, tick);
        }
        return placed;
    }

    private static int buildForVillage(World world, Villages villages, Features features,
                                       Random random, int v, int tick) {
        int placed = 0;
        // First few residents build shelter; later ones diversify. This keeps
        // a young village looking like homes clustered around the centre
        // before it starts sprouting mills and shrines.
        int wantHouses = Math.min(20, villages.population[v] / 2 + 1);
        int wantFarms = Math.min(12, villages.population[v] / 3);
        int wantLumberCamps = villages.population[v] >= 6 ? 1 + villages.population[v] / 15 : 0;
        int wantMines = villages.population[v] >= 8 ? 1 + villages.population[v] / 20 : 0;
        int wantDocks = villages.population[v] >= 6 ? 1 + villages.population[v] / 18 : 0;
        int wantMarkets = villages.population[v] >= 10 ? 1 : 0;
        int wantTemples = villages.population[v] >= 12 ? 1 : 0;
        int wantBarracks = villages.population[v] >= 14 ? 1 : 0;

        int[] have = countInFootprint(features, world, villages, v);

        // Order of attempts sets the priority when the pool is tight: houses
        // then farms then economy then civic then military.
        placed += tryBuild(world, villages, features, random, v, Features.KIND_HOUSE,
            have[Features.KIND_HOUSE], wantHouses, tick, ATTEMPTS_PER_VILLAGE);
        placed += tryBuild(world, villages, features, random, v, Features.KIND_FARM,
            have[Features.KIND_FARM], wantFarms, tick, ATTEMPTS_PER_VILLAGE);
        placed += tryBuild(world, villages, features, random, v, Features.KIND_LUMBER_CAMP,
            have[Features.KIND_LUMBER_CAMP], wantLumberCamps, tick, 2);
        placed += tryBuild(world, villages, features, random, v, Features.KIND_MINE,
            have[Features.KIND_MINE], wantMines, tick, 2);
        placed += tryBuild(world, villages, features, random, v, Features.KIND_DOCK,
            have[Features.KIND_DOCK], wantDocks, tick, 2);
        placed += tryBuild(world, villages, features, random, v, Features.KIND_MARKET,
            have[Features.KIND_MARKET], wantMarkets, tick, 1);
        placed += tryBuild(world, villages, features, random, v, Features.KIND_TEMPLE,
            have[Features.KIND_TEMPLE], wantTemples, tick, 1);
        placed += tryBuild(world, villages, features, random, v, Features.KIND_BARRACKS,
            have[Features.KIND_BARRACKS], wantBarracks, tick, 1);
        return placed;
    }

    private static int tryBuild(World world, Villages villages, Features features,
                                Random random, int v, byte kind, int have, int want,
                                int tick, int attempts) {
        if (have >= want || features.isFull()) {
            return 0;
        }
        int placed = 0;
        for (int a = 0; a < attempts && have + placed < want && !features.isFull(); a++) {
            if (!Economy.canAfford(villages, v, kind)) {
                break;
            }
            int siteTile = pickSite(world, villages, features, random, v, kind);
            if (siteTile < 0) {
                continue;
            }
            int index = features.place(siteTile, kind, 128, 128, (short) v,
                Economy.buildTime(kind));
            if (index >= 0) {
                Economy.charge(villages, v, kind);
                placed++;
            }
        }
        return placed;
    }

    /**
     * Picks the best of {@link #CANDIDATES_PER_ATTEMPT} random tiles inside
     * the village's radius that are unclaimed by any other feature and score
     * positive for this kind. Returns the tile index, or -1 if none scored.
     */
    private static int pickSite(World world, Villages villages, Features features,
                                Random random, int v, byte kind) {
        int centreX = villages.x[v];
        int centreZ = villages.z[v];
        float radius = villages.radius[v];

        int bestTile = -1;
        int bestScore = 0;
        for (int c = 0; c < CANDIDATES_PER_ATTEMPT; c++) {
            // Sample uniformly inside the disc via rejection sampling.
            float dx = (random.nextFloat() * 2f - 1f) * radius;
            float dz = (random.nextFloat() * 2f - 1f) * radius;
            if (dx * dx + dz * dz > radius * radius) {
                continue;
            }
            int tx = centreX + (int) dx;
            int tz = centreZ + (int) dz;
            if (!world.inBounds(tx, tz)) {
                continue;
            }
            int tileIndex = world.index(tx, tz);
            // Every tile only gets one significant feature - roads pass
            // through, but two houses do not share a tile.
            if (hasBlockingFeature(features, tileIndex)) {
                continue;
            }
            // Only build on this village's own claimed ground; otherwise
            // rival kingdoms end up with each other's warehouses inside
            // their walls, which reads terribly.
            if (world.ownerVillage[tileIndex] != v) {
                continue;
            }
            int score = Buildings.score(kind, world, tx, tz);
            if (score > bestScore) {
                bestScore = score;
                bestTile = tileIndex;
            }
        }
        return bestTile;
    }

    /** Roads coexist with anything; every other kind is exclusive on a tile. */
    private static boolean hasBlockingFeature(Features features, int tileIndex) {
        for (int i = features.headAt(tileIndex); i != Features.NONE; i = features.next[i]) {
            if (features.kind[i] != Features.KIND_ROAD) {
                return true;
            }
        }
        return false;
    }

    /**
     * Counts each feature kind inside the village's footprint. Returns a
     * fresh array indexed by kind byte; the caller reads by
     * {@code Features.KIND_HOUSE} etc.
     */
    private static int[] countInFootprint(Features features, World world, Villages villages, int v) {
        // 16 slots covers the current kinds with room to grow into phase 12+.
        int[] counts = new int[16];
        int centreX = villages.x[v];
        int centreZ = villages.z[v];
        float radius = villages.radius[v];
        int radiusInt = (int) Math.ceil(radius);
        int minX = Math.max(0, centreX - radiusInt);
        int maxX = Math.min(world.size - 1, centreX + radiusInt);
        int minZ = Math.max(0, centreZ - radiusInt);
        int maxZ = Math.min(world.size - 1, centreZ + radiusInt);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int tileIndex = world.index(x, z);
                for (int i = features.headAt(tileIndex); i != Features.NONE; i = features.next[i]) {
                    if (features.owner[i] == (short) v) {
                        counts[features.kind[i] & 0xff]++;
                    }
                }
            }
        }
        return counts;
    }
}
