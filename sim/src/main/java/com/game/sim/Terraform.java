package com.game.sim;

/**
 * The terraform brushes. Every operation is a pure function of (world, centre,
 * radius) with no hidden state, so a god tool is just a command the render
 * layer sends in - the simulation never has to know a mouse exists.
 */
public final class Terraform {

    private Terraform() {
    }

    /** Raises terrain, strongest at the brush centre and fading to nothing at the rim. */
    public static void raise(World world, int centreX, int centreZ, int radius, float strength) {
        applyHeightDelta(world, centreX, centreZ, radius, strength);
    }

    /** Lowers terrain with the same falloff. */
    public static void lower(World world, int centreX, int centreZ, int radius, float strength) {
        applyHeightDelta(world, centreX, centreZ, radius, -strength);
    }

    private static void applyHeightDelta(World world, int centreX, int centreZ, int radius, float delta) {
        int r = clampRadius(radius);
        float rSquared = (float) r * r;
        int minX = Math.max(0, centreX - r);
        int maxX = Math.min(world.size - 1, centreX + r);
        int minZ = Math.max(0, centreZ - r);
        int maxZ = Math.min(world.size - 1, centreZ + r);

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                float dx = x - centreX;
                float dz = z - centreZ;
                float distSquared = dx * dx + dz * dz;
                if (distSquared > rSquared) {
                    continue;
                }
                // Smooth cosine-ish falloff via a squared 1-d^2 term: flat in
                // the middle, tapering to exactly zero at the rim so repeated
                // strokes do not leave a hard circular lip.
                float t = 1.0f - distSquared / rSquared;
                float weight = t * t;
                int i = world.index(x, z);
                world.setHeight(x, z, world.height[i] + delta * weight);
            }
        }
    }

    /**
     * Floods an area: drops any tile above sea level down just under it, so the
     * brush reads as "add water" rather than "paint a blue texture on a hill".
     */
    public static void addWater(World world, int centreX, int centreZ, int radius, float strength) {
        int r = clampRadius(radius);
        float rSquared = (float) r * r;
        int minX = Math.max(0, centreX - r);
        int maxX = Math.min(world.size - 1, centreX + r);
        int minZ = Math.max(0, centreZ - r);
        int maxZ = Math.min(world.size - 1, centreZ + r);

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                float dx = x - centreX;
                float dz = z - centreZ;
                if (dx * dx + dz * dz > rSquared) {
                    continue;
                }
                int i = world.index(x, z);
                float target = SimConfig.SEA_LEVEL - 0.35f;
                if (world.height[i] > target) {
                    // Ease down rather than snapping, so holding the brush
                    // carves a basin gradually like the raise/lower tools do.
                    float next = world.height[i] - Math.max(strength, 0.05f);
                    world.setHeight(x, z, Math.max(target, next));
                }
            }
        }
    }

    /**
     * Plants forest on any tile that could support it. Deliberately does not
     * change elevation - it only sets the tile type, and only where the ground
     * is already grassy lowland.
     */
    public static void addForest(World world, int centreX, int centreZ, int radius) {
        int r = clampRadius(radius);
        float rSquared = (float) r * r;
        int minX = Math.max(0, centreX - r);
        int maxX = Math.min(world.size - 1, centreX + r);
        int minZ = Math.max(0, centreZ - r);
        int maxZ = Math.min(world.size - 1, centreZ + r);

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                float dx = x - centreX;
                float dz = z - centreZ;
                if (dx * dx + dz * dz > rSquared) {
                    continue;
                }
                if (world.typeAt(x, z) == TileType.GRASS) {
                    world.setType(x, z, TileType.FOREST);
                }
            }
        }
    }

    public static int clampRadius(int radius) {
        if (radius < SimConfig.MIN_BRUSH_RADIUS) {
            return SimConfig.MIN_BRUSH_RADIUS;
        }
        return Math.min(radius, SimConfig.MAX_BRUSH_RADIUS);
    }
}
