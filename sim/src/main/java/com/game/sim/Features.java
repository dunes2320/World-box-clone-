package com.game.sim;

/**
 * Sub-tile features - the objects a village places on the ground: houses,
 * farms, plazas, road segments, props.
 *
 * <p>A village growing from 3 tiles at the phase 4 scale to 20-40 tiles at
 * phase 7's scale means a house can no longer <em>be</em> a tile: several
 * features share one tile, offset within it. Each tile carries at most one
 * head index into a shared feature pool, and the pool links features on the
 * same tile into a per-tile chain via a {@code next} array. Deletion is
 * unlink-and-recycle, no allocation and no compaction, same design as the
 * unit pool.
 *
 * <p>Phase 7 lands this scaffolding empty. Phase 8 fills it with the first
 * building kinds; phase 9 uses it for economy props (marketplaces, stalls);
 * phase 12 for religious sites. Kept as one flat pool rather than one per
 * kind so a tile's contents can be enumerated in a single walk.
 */
public final class Features {

    /** Sentinel for "no more features on this tile" and "not on any tile". */
    public static final int NONE = -1;

    public final int capacity;

    /** The tile this feature sits on. -1 for a free slot. */
    public final int[] tile;
    /** Feature kind - one of the {@code KIND_*} byte constants below. */
    public final byte[] kind;
    /** Offset inside the tile, in units of 1/256th of a tile - fits in a byte. */
    public final byte[] dx;
    public final byte[] dz;
    /** Owning village, or {@link World#NO_OWNER} for a neutral prop. */
    public final short[] owner;
    /** Ticks of construction remaining; 0 means finished. */
    public final short[] buildTime;
    /** Chain to the next feature on the same tile, or {@link #NONE}. */
    public final int[] next;

    private final int[] freeList;
    private int freeCount;
    private int highWater;
    private int liveCount;
    /**
     * Bumped every time a feature is placed or removed. Renderers compare it
     * against a stored value and skip their rebuild if the set has not
     * changed - which, on a mature world, is most frames.
     */
    private int generation;

    // Kinds are byte constants for the same reason TileType is: they are
    // stored per feature in a flat array.
    public static final byte KIND_UNUSED = 0;
    public static final byte KIND_HOUSE = 1;
    public static final byte KIND_FARM = 2;
    public static final byte KIND_BARRACKS = 3;
    public static final byte KIND_DOCK = 4;
    public static final byte KIND_TEMPLE = 5;
    public static final byte KIND_MARKET = 6;
    public static final byte KIND_WALL = 7;
    public static final byte KIND_MINE = 8;
    public static final byte KIND_LUMBER_CAMP = 9;
    public static final byte KIND_ROAD = 10;

    public Features(int capacity, int tileCount) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
        tile = new int[capacity];
        kind = new byte[capacity];
        dx = new byte[capacity];
        dz = new byte[capacity];
        owner = new short[capacity];
        buildTime = new short[capacity];
        next = new int[capacity];

        for (int i = 0; i < capacity; i++) {
            tile[i] = -1;
            next[i] = NONE;
        }

        freeList = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            freeList[i] = capacity - 1 - i;
        }
        freeCount = capacity;

        // tileHeads is one entry per world tile. Callers pass the world's
        // actual tile count so this scales with the world without being fixed
        // at the SimConfig default.
        this.tileHeads = new int[tileCount];
        java.util.Arrays.fill(tileHeads, NONE);
    }

    /** Head-of-chain index into the feature pool per tile, or {@link #NONE}. */
    private final int[] tileHeads;

    public int getLiveCount() {
        return liveCount;
    }

    public int getHighWater() {
        return highWater;
    }

    public boolean isFull() {
        return freeCount == 0;
    }

    public boolean isAlive(int index) {
        return index >= 0 && index < capacity && tile[index] >= 0;
    }

    /** Head of the linked list of features on {@code tileIndex}, or {@link #NONE}. */
    public int headAt(int tileIndex) {
        return tileHeads[tileIndex];
    }

    /**
     * Places a feature on a tile. Feature offsets are in 1/256ths of a tile
     * for a byte-sized fit; convert to floats when rendering.
     *
     * @return the feature's slot index, or -1 if the pool is full
     */
    public int place(int tileIndex, byte kindId, int dxByte, int dzByte,
                     short ownerId, int buildTicks) {
        if (freeCount == 0) {
            return -1;
        }
        int index = freeList[--freeCount];

        tile[index] = tileIndex;
        kind[index] = kindId;
        dx[index] = (byte) dxByte;
        dz[index] = (byte) dzByte;
        owner[index] = ownerId;
        buildTime[index] = (short) Math.min(buildTicks, Short.MAX_VALUE);

        next[index] = tileHeads[tileIndex];
        tileHeads[tileIndex] = index;

        liveCount++;
        generation++;
        if (index >= highWater) {
            highWater = index + 1;
        }
        return index;
    }

    /** Removes a feature and hands its slot back to the pool. */
    public void remove(int index) {
        if (!isAlive(index)) {
            return;
        }
        int tileIndex = tile[index];

        // Unlink from the tile's chain.
        int prev = NONE;
        int cursor = tileHeads[tileIndex];
        while (cursor != NONE && cursor != index) {
            prev = cursor;
            cursor = next[cursor];
        }
        if (cursor == index) {
            if (prev == NONE) {
                tileHeads[tileIndex] = next[index];
            } else {
                next[prev] = next[index];
            }
        }

        tile[index] = -1;
        kind[index] = KIND_UNUSED;
        next[index] = NONE;
        freeList[freeCount++] = index;
        liveCount--;
        generation++;
    }

    /** Bumps once every time the feature set changes; renderers key their cache on it. */
    public int getGeneration() {
        return generation;
    }

    /** Counts placed features on a tile - the length of its chain. */
    public int countOnTile(int tileIndex) {
        int n = 0;
        for (int i = tileHeads[tileIndex]; i != NONE; i = next[i]) {
            n++;
        }
        return n;
    }
}
