package com.game.sim;

/**
 * Tile types as byte constants rather than an enum: the world stores one per
 * tile in a flat {@code byte[]}, and an enum array would cost a reference and
 * a pointer chase per tile for no gain.
 */
public final class TileType {

    private TileType() {
    }

    public static final byte DEEP_WATER = 0;
    public static final byte SHALLOW_WATER = 1;
    public static final byte SAND = 2;
    public static final byte GRASS = 3;
    public static final byte FOREST = 4;
    public static final byte HILL = 5;
    public static final byte MOUNTAIN = 6;
    public static final byte SNOW = 7;

    public static final int COUNT = 8;

    private static final String[] NAMES = {
        "Deep Water", "Shallow Water", "Sand", "Grass", "Forest", "Hill", "Mountain", "Snow",
    };

    public static String name(byte type) {
        return type >= 0 && type < COUNT ? NAMES[type] : "Unknown";
    }

    public static boolean isWater(byte type) {
        return type == DEEP_WATER || type == SHALLOW_WATER;
    }

    /** Land a unit can stand on. Water and bare mountain peaks are not walkable. */
    public static boolean isWalkable(byte type) {
        return type == SAND || type == GRASS || type == FOREST || type == HILL;
    }

    /** Fire needs fuel; only forest carries it. */
    public static boolean isFlammable(byte type) {
        return type == FOREST;
    }

    /**
     * Classifies a tile purely from its elevation and fertility, so the same
     * rule that generates the world also reclassifies a tile the player has
     * terraformed - raise the seabed and it becomes sand, then grass.
     */
    public static byte fromTerrain(float height, float fertility) {
        if (height < SimConfig.DEEP_WATER_LEVEL) {
            return DEEP_WATER;
        }
        if (height < SimConfig.SEA_LEVEL) {
            return SHALLOW_WATER;
        }
        if (height < SimConfig.SAND_LEVEL) {
            return SAND;
        }
        if (height < SimConfig.HILL_LEVEL) {
            return fertility >= SimConfig.FOREST_FERTILITY ? FOREST : GRASS;
        }
        if (height < SimConfig.MOUNTAIN_LEVEL) {
            return HILL;
        }
        if (height < SimConfig.SNOW_LEVEL) {
            return MOUNTAIN;
        }
        return SNOW;
    }
}
