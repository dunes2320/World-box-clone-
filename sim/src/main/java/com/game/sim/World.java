package com.game.sim;

/**
 * The tile grid, stored as parallel flat arrays rather than an object per
 * tile. A 128x128 world is 16,384 tiles; as objects that would be 16k
 * allocations and 16k pointer chases per full scan, and every per-tick sweep
 * would thrash cache. Flat arrays keep each sweep linear and allocation-free.
 *
 * <p>Indices are {@code z * size + x} throughout - row-major, so scanning x
 * innermost walks memory forwards.
 */
public final class World {

    public final int size;
    public final int tileCount;
    public final int chunksPerAxis;

    public final byte[] tileType;
    public final float[] height;
    /** Owning village index, or {@link #NO_OWNER} for unclaimed ground. */
    public final short[] ownerVillage;
    /** Remaining fire ticks; 0 means not burning. */
    public final byte[] burn;
    /** 0..1 broad noise field: drives biome choice at genesis and forest regrowth later. */
    public final float[] fertility;

    public static final short NO_OWNER = -1;

    /**
     * One flag per 16x16 chunk. The renderer rebuilds only the chunks flagged
     * here, so a brush stroke touching four tiles does not re-mesh the world.
     */
    private final boolean[] chunkDirty;

    public World(int size) {
        if (size <= 0 || size % SimConfig.CHUNK_SIZE != 0) {
            throw new IllegalArgumentException(
                "world size must be a positive multiple of " + SimConfig.CHUNK_SIZE + ", got " + size);
        }
        this.size = size;
        this.tileCount = size * size;
        this.chunksPerAxis = size / SimConfig.CHUNK_SIZE;

        this.tileType = new byte[tileCount];
        this.height = new float[tileCount];
        this.ownerVillage = new short[tileCount];
        this.burn = new byte[tileCount];
        this.fertility = new float[tileCount];
        this.chunkDirty = new boolean[chunksPerAxis * chunksPerAxis];

        java.util.Arrays.fill(ownerVillage, NO_OWNER);
        markAllChunksDirty();
    }

    public World() {
        this(SimConfig.WORLD_SIZE);
    }

    public int index(int x, int z) {
        return z * size + x;
    }

    public boolean inBounds(int x, int z) {
        return x >= 0 && z >= 0 && x < size && z < size;
    }

    public byte typeAt(int x, int z) {
        return tileType[index(x, z)];
    }

    public float heightAt(int x, int z) {
        return height[index(x, z)];
    }

    /**
     * Height sampled with edge clamping - handy for meshing and ray picking,
     * which both legitimately ask about tiles just past the border.
     */
    public float heightClamped(int x, int z) {
        int cx = x < 0 ? 0 : (x >= size ? size - 1 : x);
        int cz = z < 0 ? 0 : (z >= size ? size - 1 : z);
        return height[index(cx, cz)];
    }

    // ---- chunk dirty tracking ----

    public int chunkIndex(int chunkX, int chunkZ) {
        return chunkZ * chunksPerAxis + chunkX;
    }

    public int chunkCount() {
        return chunkDirty.length;
    }

    public boolean isChunkDirty(int chunk) {
        return chunkDirty[chunk];
    }

    public void clearChunkDirty(int chunk) {
        chunkDirty[chunk] = false;
    }

    public void markAllChunksDirty() {
        java.util.Arrays.fill(chunkDirty, true);
    }

    /**
     * Flags the chunk containing (x, z), plus any neighbouring chunk across a
     * shared seam. A tile's mesh includes the side walls it drops to its
     * neighbours, so editing a tile on a chunk border changes geometry in the
     * chunk next door too - without this, edits leave visible cracks at chunk
     * boundaries.
     */
    public void markDirty(int x, int z) {
        if (!inBounds(x, z)) {
            return;
        }
        int chunkX = x / SimConfig.CHUNK_SIZE;
        int chunkZ = z / SimConfig.CHUNK_SIZE;
        chunkDirty[chunkIndex(chunkX, chunkZ)] = true;

        int localX = x % SimConfig.CHUNK_SIZE;
        int localZ = z % SimConfig.CHUNK_SIZE;
        if (localX == 0 && chunkX > 0) {
            chunkDirty[chunkIndex(chunkX - 1, chunkZ)] = true;
        }
        if (localX == SimConfig.CHUNK_SIZE - 1 && chunkX < chunksPerAxis - 1) {
            chunkDirty[chunkIndex(chunkX + 1, chunkZ)] = true;
        }
        if (localZ == 0 && chunkZ > 0) {
            chunkDirty[chunkIndex(chunkX, chunkZ - 1)] = true;
        }
        if (localZ == SimConfig.CHUNK_SIZE - 1 && chunkZ < chunksPerAxis - 1) {
            chunkDirty[chunkIndex(chunkX, chunkZ + 1)] = true;
        }
    }

    // ---- mutation ----

    /**
     * Sets a tile's elevation, clamps it to the legal range, reclassifies the
     * tile type from the new height, and flags the affected chunks. Every
     * terrain edit in the game funnels through here so none of those three
     * steps can be forgotten at a call site.
     */
    public void setHeight(int x, int z, float newHeight) {
        if (!inBounds(x, z)) {
            return;
        }
        int i = index(x, z);
        float clamped = newHeight < SimConfig.MIN_HEIGHT ? SimConfig.MIN_HEIGHT
            : (newHeight > SimConfig.MAX_HEIGHT ? SimConfig.MAX_HEIGHT : newHeight);
        if (clamped == height[i]) {
            return;
        }
        height[i] = clamped;
        tileType[i] = TileType.fromTerrain(clamped, fertility[i]);
        markDirty(x, z);
    }

    /** Forces a tile type independently of elevation (used by the water and forest brushes). */
    public void setType(int x, int z, byte type) {
        if (!inBounds(x, z)) {
            return;
        }
        int i = index(x, z);
        if (tileType[i] == type) {
            return;
        }
        tileType[i] = type;
        markDirty(x, z);
    }
}
