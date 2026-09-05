package com.game.sim;

/**
 * Recomputes which village owns which tile.
 *
 * <p>Ownership is decided by pressure - a village's population spread over
 * distance - rather than by whoever claimed a tile first. Otherwise borders
 * would be frozen history: an early village that later shrank to nothing would
 * hold its land forever, and a thriving neighbour could never push into it.
 *
 * <p>Recomputing from scratch also sidesteps a whole class of ordering bugs.
 * There is no incremental claim/release to get wrong, and the result depends
 * only on the current state, so it is identical however the world got here.
 */
public final class Territory {

    /**
     * Best pressure seen so far per tile during a pass. Kept as a field rather
     * than allocated per call: this runs several times a second forever.
     */
    private final float[] pressure;

    public Territory(int tileCount) {
        pressure = new float[tileCount];
    }

    /**
     * Rebuilds {@code world.ownerVillage} from the current villages, and marks
     * the chunks whose ownership actually changed so only those re-mesh.
     *
     * @return how many tiles changed hands
     */
    public int recompute(World world, Villages villages) {
        java.util.Arrays.fill(pressure, 0f);

        int changed = 0;
        int end = villages.getHighWater();

        // Walk each village's own footprint rather than every tile for every
        // village: the areas are small and mostly disjoint, so this is far
        // cheaper than a full tiles-by-villages sweep.
        for (int v = 0; v < end; v++) {
            if (!villages.alive[v]) {
                continue;
            }
            float radius = villages.radius[v];
            float strength = Math.max(1f, villages.population[v]);
            int centreX = villages.x[v];
            int centreZ = villages.z[v];

            int minX = Math.max(0, (int) Math.floor(centreX - radius));
            int maxX = Math.min(world.size - 1, (int) Math.ceil(centreX + radius));
            int minZ = Math.max(0, (int) Math.floor(centreZ - radius));
            int maxZ = Math.min(world.size - 1, (int) Math.ceil(centreZ + radius));
            float radiusSquared = radius * radius;

            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    float dx = x - centreX;
                    float dz = z - centreZ;
                    float distanceSquared = dx * dx + dz * dz;
                    if (distanceSquared > radiusSquared) {
                        continue;
                    }
                    int i = world.index(x, z);
                    // Nobody claims open water - borders stop at the coast.
                    if (TileType.isWater(world.tileType[i])) {
                        continue;
                    }
                    // Falls off with distance, so a big village reaches further
                    // than a hamlet but neither owns ground it is nowhere near.
                    float claim = strength / (1f + (float) Math.sqrt(distanceSquared));
                    if (claim > pressure[i]) {
                        pressure[i] = claim;
                        if (world.ownerVillage[i] != (short) v) {
                            world.ownerVillage[i] = (short) v;
                            world.markDirty(x, z);
                            changed++;
                        }
                    }
                }
            }
        }

        // Anything nobody exerted pressure on this pass has been abandoned:
        // its village died, shrank, or was outcompeted.
        for (int z = 0; z < world.size; z++) {
            for (int x = 0; x < world.size; x++) {
                int i = world.index(x, z);
                if (pressure[i] == 0f && world.ownerVillage[i] != World.NO_OWNER) {
                    world.ownerVillage[i] = World.NO_OWNER;
                    world.markDirty(x, z);
                    changed++;
                }
            }
        }
        return changed;
    }

    /** Clears all ownership, for a fresh world. */
    public static void clear(World world) {
        for (int z = 0; z < world.size; z++) {
            for (int x = 0; x < world.size; x++) {
                int i = world.index(x, z);
                if (world.ownerVillage[i] != World.NO_OWNER) {
                    world.ownerVillage[i] = World.NO_OWNER;
                    world.markDirty(x, z);
                }
            }
        }
    }
}
