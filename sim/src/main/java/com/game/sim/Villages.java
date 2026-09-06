package com.game.sim;

/**
 * Settlements, stored struct-of-arrays with a free list like {@link Units}.
 *
 * <p>There are far fewer villages than units, so the layout matters less here -
 * but {@code World.ownerVillage} stores a village index per tile as a short,
 * and keeping indices stable and reusable is exactly what makes that safe.
 */
public final class Villages {

    public final int capacity;

    /** Tile coordinates of the village centre. */
    public final short[] x;
    public final short[] z;
    public final byte[] species;
    /** Recounted from the unit pool each territory pass. */
    public final int[] population;
    /** Current territory claim radius in tiles; grows with population. */
    public final float[] radius;
    public final int[] foundedTick;
    public final boolean[] alive;

    // ---- economy stockpiles ----
    // Kept on Villages rather than a parallel Economy class so a slot reused
    // by a new village comes with fresh zeroes automatically. See found().
    public final int[] food;
    public final int[] wood;
    public final int[] stone;
    public final int[] gold;

    private final int[] freeList;
    private int freeCount;
    private int highWater;
    private int liveCount;

    public Villages(int capacity) {
        if (capacity <= 0 || capacity > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                "capacity must be in 1.." + Short.MAX_VALUE + ", got " + capacity);
        }
        this.capacity = capacity;

        x = new short[capacity];
        z = new short[capacity];
        species = new byte[capacity];
        population = new int[capacity];
        radius = new float[capacity];
        foundedTick = new int[capacity];
        alive = new boolean[capacity];
        food = new int[capacity];
        wood = new int[capacity];
        stone = new int[capacity];
        gold = new int[capacity];

        freeList = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            freeList[i] = capacity - 1 - i;
        }
        freeCount = capacity;
    }

    public int getLiveCount() {
        return liveCount;
    }

    public int getHighWater() {
        return highWater;
    }

    public boolean isAlive(int index) {
        return index >= 0 && index < capacity && alive[index];
    }

    public boolean isFull() {
        return freeCount == 0;
    }

    /** @return the new village's index, or -1 if the pool is full */
    public int found(int tileX, int tileZ, byte speciesId, int tick) {
        if (freeCount == 0) {
            return -1;
        }
        int index = freeList[--freeCount];

        x[index] = (short) tileX;
        z[index] = (short) tileZ;
        species[index] = speciesId;
        population[index] = 1;
        radius[index] = SimConfig.VILLAGE_BASE_RADIUS;
        foundedTick[index] = tick;
        alive[index] = true;
        // Fresh stockpiles - a reused slot starts penniless, no matter what
        // the abandoned village left behind.
        food[index] = SimConfig.FOUNDING_FOOD;
        wood[index] = SimConfig.FOUNDING_WOOD;
        stone[index] = 0;
        gold[index] = 0;

        liveCount++;
        if (index >= highWater) {
            highWater = index + 1;
        }
        return index;
    }

    /** Abandons a village and returns its slot to the pool. */
    public void abandon(int index) {
        if (!isAlive(index)) {
            return;
        }
        alive[index] = false;
        population[index] = 0;
        freeList[freeCount++] = index;
        liveCount--;
    }

    public int countOf(byte speciesId) {
        int count = 0;
        for (int i = 0; i < highWater; i++) {
            if (alive[i] && species[i] == speciesId) {
                count++;
            }
        }
        return count;
    }

    /** Squared distance from a village centre to a tile, in tiles. */
    public float distanceSquaredTo(int index, float worldX, float worldZ) {
        float dx = worldX - (x[index] + 0.5f);
        float dz = worldZ - (z[index] + 0.5f);
        return dx * dx + dz * dz;
    }

    /**
     * The nearest living village to a point, or -1 if none is within
     * {@code maxDistance}.
     */
    public int nearestTo(float worldX, float worldZ, float maxDistance) {
        int best = -1;
        float bestDistanceSquared = maxDistance * maxDistance;
        for (int i = 0; i < highWater; i++) {
            if (!alive[i]) {
                continue;
            }
            float distanceSquared = distanceSquaredTo(i, worldX, worldZ);
            if (distanceSquared <= bestDistanceSquared) {
                bestDistanceSquared = distanceSquared;
                best = i;
            }
        }
        return best;
    }
}
