package com.game.sim;

/**
 * Lays roads between a village's centre and its outlying buildings, and
 * between neighbouring villages of the same species (a stand-in for the
 * kingdom-allies rule that lands in phase 10).
 *
 * <p>Runs on the village update cadence rather than every tick. Building a
 * road is cheap - an A* search over a few dozen tiles - but calling this
 * every tick would still be waste, and roads only need to appear when
 * villages grow or new buildings show up.
 *
 * <p>Determinism: the pathfinder is deterministic given the world and start
 * point, and the caller iterates villages and features in ascending index
 * order. The road bits are therefore fully seed-reproducible.
 */
public final class RoadSystem {

    /** Villages closer than this in tile-space are connected by a road. */
    public static final float ALLY_ROAD_DISTANCE = 40f;

    /**
     * Per-village budget for how many road searches this pass can do. A busy
     * village with dozens of buildings would otherwise trigger dozens of full
     * A* searches every pass; without this the max-tick spike measured at
     * 79ms on the phase 8 medium smoke, because every road runs one search.
     * Roads land a few per pass and the network fills in over time, which
     * also reads better than a whole village blossoming a full grid at once.
     */
    private static final int ROAD_ATTEMPTS_PER_VILLAGE = 3;

    private final Pathfinder pathfinder;

    public RoadSystem(int worldSize) {
        this.pathfinder = new Pathfinder(worldSize);
    }

    /** Roads for every village to its own outlying buildings and neighbours. */
    public int update(World world, Villages villages, Features features, Roads roads) {
        int laid = 0;
        int end = villages.getHighWater();
        for (int v = 0; v < end; v++) {
            if (!villages.alive[v]) {
                continue;
            }
            laid += layToOwnBuildings(world, villages, features, roads, v);
            laid += layToNeighbouringVillages(world, villages, roads, v);
        }
        return laid;
    }

    private int layToOwnBuildings(World world, Villages villages, Features features,
                                  Roads roads, int v) {
        int centreX = villages.x[v];
        int centreZ = villages.z[v];
        int laid = 0;
        int attempts = 0;

        int radiusInt = (int) Math.ceil(villages.radius[v]);
        int minX = Math.max(0, centreX - radiusInt);
        int maxX = Math.min(world.size - 1, centreX + radiusInt);
        int minZ = Math.max(0, centreZ - radiusInt);
        int maxZ = Math.min(world.size - 1, centreZ + radiusInt);

        for (int z = minZ; z <= maxZ && attempts < ROAD_ATTEMPTS_PER_VILLAGE; z++) {
            for (int x = minX; x <= maxX && attempts < ROAD_ATTEMPTS_PER_VILLAGE; x++) {
                int tileIndex = world.index(x, z);
                // Skip anything already on a road: it is either connected
                // already or the road pass here would just re-stamp existing
                // tiles. This is the difference between one A* per new
                // building and one A* per building every pass forever.
                if (roads.isRoad(tileIndex)) {
                    continue;
                }
                for (int fi = features.headAt(tileIndex); fi != Features.NONE; fi = features.next[fi]) {
                    if (features.owner[fi] != (short) v) {
                        continue;
                    }
                    byte kind = features.kind[fi];
                    if (kind == Features.KIND_ROAD || kind == Features.KIND_WALL) {
                        continue;
                    }
                    if (pathfinder.findPath(world, roads, centreX, centreZ, x, z)) {
                        laid += stampPath(world, roads, features, v);
                    }
                    attempts++;
                    // One build per feature per pass; stop scanning this tile.
                    break;
                }
            }
        }
        return laid;
    }

    private int layToNeighbouringVillages(World world, Villages villages, Roads roads, int v) {
        int centreX = villages.x[v];
        int centreZ = villages.z[v];
        byte speciesId = villages.species[v];
        int laid = 0;

        int end = villages.getHighWater();
        for (int u = v + 1; u < end; u++) {
            if (!villages.alive[u] || villages.species[u] != speciesId) {
                continue;
            }
            // The neighbour's centre becomes a road the first time we
            // connect the two, so it becomes a cheap "already linked" test.
            int neighbourTile = world.index(villages.x[u], villages.z[u]);
            if (roads.isRoad(neighbourTile)) {
                continue;
            }
            float dx = villages.x[u] - centreX;
            float dz = villages.z[u] - centreZ;
            if (dx * dx + dz * dz > ALLY_ROAD_DISTANCE * ALLY_ROAD_DISTANCE) {
                continue;
            }
            if (pathfinder.findPath(world, roads, centreX, centreZ, villages.x[u], villages.z[u])) {
                laid += stampPath(world, roads, /* features */ null, v);
            }
        }
        return laid;
    }

    /**
     * Marks every tile along the pathfinder's last result as a road, and
     * drops a KIND_ROAD feature on each newly-roaded tile so the renderer
     * can find them without a second full-world scan.
     */
    private int stampPath(World world, Roads roads, Features features, int owner) {
        int[] path = pathfinder.getResultPath();
        int length = pathfinder.getResultLength();
        int stamped = 0;
        for (int i = 0; i < length; i++) {
            int tileIndex = path[i];
            if (roads.set(tileIndex)) {
                stamped++;
                if (features != null && !features.isFull()) {
                    features.place(tileIndex, Features.KIND_ROAD, 128, 128,
                        (short) owner, 0);
                }
            }
        }
        return stamped;
    }
}
