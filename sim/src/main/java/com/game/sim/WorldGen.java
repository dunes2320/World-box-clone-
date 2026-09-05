package com.game.sim;

/**
 * Builds a world from a seed. Pure function of the seed: the same seed always
 * produces a bit-identical world, which is what makes the whole simulation
 * replayable and testable.
 */
public final class WorldGen {

    private WorldGen() {
    }

    public static World generate(long seed) {
        World world = new World();
        generateInto(world, seed);
        return world;
    }

    public static void generateInto(World world, long seed) {
        // Two independent noise fields. The fertility seed is offset rather
        // than reused so elevation and fertility are not correlated - sharing
        // a seed would put every forest on the same contour line.
        Noise elevation = new Noise(seed);
        Noise fertility = new Noise(seed * 6364136223846793005L + 1442695040888963407L);

        int size = world.size;
        float half = size * 0.5f;

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                int i = world.index(x, z);

                float fert = fertility.fbm(
                    x * SimConfig.FERTILITY_SCALE,
                    z * SimConfig.FERTILITY_SCALE,
                    SimConfig.FERTILITY_OCTAVES, 2.0f, 0.5f);
                // fbm returns roughly [-1, 1]; fertility is used as a 0..1 weight.
                fert = (fert + 1.0f) * 0.5f;
                world.fertility[i] = fert;

                float raw = elevation.fbm(
                    x * SimConfig.NOISE_SCALE,
                    z * SimConfig.NOISE_SCALE,
                    SimConfig.ELEVATION_OCTAVES, 2.0f, 0.5f);

                float h = raw * SimConfig.ELEVATION_AMPLITUDE + SimConfig.ELEVATION_BIAS;
                h -= islandFalloff(x, z, half);

                if (h < SimConfig.MIN_HEIGHT) {
                    h = SimConfig.MIN_HEIGHT;
                } else if (h > SimConfig.MAX_HEIGHT) {
                    h = SimConfig.MAX_HEIGHT;
                }

                world.height[i] = h;
                world.tileType[i] = TileType.fromTerrain(h, fert);
                world.ownerVillage[i] = World.NO_OWNER;
                world.burn[i] = 0;
            }
        }
        world.markAllChunksDirty();
    }

    /**
     * Pulls elevation down towards the map edge so the world is a bounded
     * island ringed by deep water, rather than terrain that simply stops at a
     * cliff. Uses a squared radial ramp that stays near zero across the middle
     * and then climbs steeply, so the interior keeps its noise shape intact
     * and only the outer band gets dragged under.
     */
    private static float islandFalloff(int x, int z, float half) {
        float dx = (x - half + 0.5f) / half;
        float dz = (z - half + 0.5f) / half;
        // Chebyshev distance: a rounded-square coastline fills a square map
        // better than a circle would.
        float d = Math.max(Math.abs(dx), Math.abs(dz));
        // Fourth power, not cube: a cube ramp already subtracts over a metre
        // of elevation halfway to the edge, which drowned the interior along
        // with the rim. This stays near zero across the middle of the map and
        // then climbs hard over the last quarter.
        float dSquared = d * d;
        float ramp = dSquared * dSquared;
        return ramp * SimConfig.ISLAND_FALLOFF;
    }
}
