package com.game.sim;

/**
 * Which building goes on which ground.
 *
 * <p>All the placement rules live in one place so the "why won't villages
 * build farms on stone" question has one file to open. Each kind returns a
 * {@code score} for a candidate tile - higher is better, negative or zero
 * means it will not build there. That lets a village compare a farm slot
 * against a house slot against a mine slot with one comparison, rather than
 * running each kind's placement rule as a separate pass.
 *
 * <p>Scores are integer rather than float because determinism is worth more
 * than the smoothness: ordering ties matters, and floats leave that up to
 * whatever the JIT decided this run.
 */
public final class Buildings {

    private Buildings() {
    }

    /**
     * @return positive if the kind can be built on this tile, higher for a
     *     better fit; zero or negative to refuse
     */
    public static int score(byte kind, World world, int tileX, int tileZ) {
        if (!world.inBounds(tileX, tileZ)) {
            return 0;
        }
        int i = world.index(tileX, tileZ);
        byte type = world.tileType[i];
        float fertility = world.fertility[i];
        float height = world.height[i];

        switch (kind) {
            case Features.KIND_HOUSE:
                return scoreHouse(type);
            case Features.KIND_FARM:
                return scoreFarm(type, fertility);
            case Features.KIND_LUMBER_CAMP:
                return scoreLumberCamp(world, tileX, tileZ, type);
            case Features.KIND_MINE:
                return scoreMine(world, tileX, tileZ, type);
            case Features.KIND_DOCK:
                return scoreDock(world, tileX, tileZ, type, height);
            case Features.KIND_MARKET:
            case Features.KIND_TEMPLE:
            case Features.KIND_BARRACKS:
                return scoreCentralBuilding(type);
            case Features.KIND_WALL:
                return scoreWall(type);
            default:
                return 0;
        }
    }

    /** Any walkable land is habitable, grass most, sand least. */
    private static int scoreHouse(byte type) {
        switch (type) {
            case TileType.GRASS: return 10;
            case TileType.FOREST: return 6;
            case TileType.HILL: return 5;
            case TileType.SAND: return 3;
            default: return 0;
        }
    }

    /** Farms need grass and pay attention to how good the soil actually is. */
    private static int scoreFarm(byte type, float fertility) {
        if (type != TileType.GRASS) {
            return 0;
        }
        // fertility runs 0..1; a strong farmland tile is worth twice a
        // borderline one, so this gap actually decides placement.
        return 4 + (int) (fertility * 12);
    }

    /** Lumber camps live on grass next to at least one forest tile. */
    private static int scoreLumberCamp(World world, int tileX, int tileZ, byte type) {
        if (type != TileType.GRASS && type != TileType.HILL) {
            return 0;
        }
        int neighbouringForest = countNeighbourType(world, tileX, tileZ, TileType.FOREST);
        return neighbouringForest > 0 ? 5 + neighbouringForest * 3 : 0;
    }

    /**
     * Mines sit on hill tiles that are actually next to a mountain, so the
     * player can read them as "worked stone", not "shed on grass". A hill
     * that touches no stone is just a hill.
     */
    private static int scoreMine(World world, int tileX, int tileZ, byte type) {
        if (type != TileType.HILL) {
            return 0;
        }
        int stone = countNeighbourType(world, tileX, tileZ, TileType.MOUNTAIN)
            + countNeighbourType(world, tileX, tileZ, TileType.SNOW);
        return stone > 0 ? 6 + stone * 2 : 0;
    }

    /** Docks sit on sand and must touch shallow or deep water. */
    private static int scoreDock(World world, int tileX, int tileZ, byte type, float height) {
        if (type != TileType.SAND || height >= SimConfig.SAND_LEVEL) {
            return 0;
        }
        boolean touchesWater = isWaterNeighbour(world, tileX - 1, tileZ)
            || isWaterNeighbour(world, tileX + 1, tileZ)
            || isWaterNeighbour(world, tileX, tileZ - 1)
            || isWaterNeighbour(world, tileX, tileZ + 1);
        return touchesWater ? 8 : 0;
    }

    /**
     * Central buildings - market, temple, barracks - want stable flat land,
     * not a beach or a forest floor. Handed the same score as a house's
     * good ground so placement uses distance-from-centre as the tiebreaker.
     */
    private static int scoreCentralBuilding(byte type) {
        if (type == TileType.GRASS) return 12;
        if (type == TileType.HILL) return 6;
        return 0;
    }

    /** Walls need dry ground and go anywhere solid. */
    private static int scoreWall(byte type) {
        return TileType.isWalkable(type) ? 4 : 0;
    }

    private static int countNeighbourType(World world, int tileX, int tileZ, byte type) {
        int count = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                int nx = tileX + dx;
                int nz = tileZ + dz;
                if (world.inBounds(nx, nz) && world.typeAt(nx, nz) == type) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isWaterNeighbour(World world, int x, int z) {
        return world.inBounds(x, z) && TileType.isWater(world.typeAt(x, z));
    }
}
