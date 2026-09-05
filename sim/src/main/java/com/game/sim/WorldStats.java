package com.game.sim;

/**
 * Read-only summaries of world state for the UI to display.
 *
 * <p>Lives in the simulation package because it is pure counting over the tile
 * arrays with no rendering concepts involved, and because the UI must never be
 * the only place a number like "how much land is there" is computed.
 */
public final class WorldStats {

    private WorldStats() {
    }

    /**
     * Counts tiles of each type into {@code out}, indexed by the
     * {@link TileType} constants.
     *
     * <p>Takes the destination array rather than allocating one, so the HUD can
     * call this every frame without producing garbage.
     *
     * @param out array of at least {@link TileType#COUNT} entries; cleared first
     */
    public static void countByType(World world, int[] out) {
        if (out.length < TileType.COUNT) {
            throw new IllegalArgumentException(
                "out must hold at least " + TileType.COUNT + " entries, got " + out.length);
        }
        java.util.Arrays.fill(out, 0, TileType.COUNT, 0);
        for (int i = 0; i < world.tileCount; i++) {
            out[world.tileType[i]]++;
        }
    }

    /** Number of tiles that are not water. */
    public static int landTileCount(World world) {
        int land = 0;
        for (int i = 0; i < world.tileCount; i++) {
            if (!TileType.isWater(world.tileType[i])) {
                land++;
            }
        }
        return land;
    }

    /** Fraction of the map that is dry land, 0..1. */
    public static float landFraction(World world) {
        return landTileCount(world) / (float) world.tileCount;
    }
}
