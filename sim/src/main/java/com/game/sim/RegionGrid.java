package com.game.sim;

/**
 * Per-chunk aggregate of who lives where.
 *
 * <p>Diplomacy and economy do not need per-unit granularity - "how many humans
 * are in the north-west quadrant" is not a question worth scanning the pool
 * for eight thousand times a tick. RegionGrid answers it in one lookup. It is
 * rebuilt lazily when a system that needs it asks, not every tick, because
 * the answer is only sampled by systems that themselves run on slow clocks.
 *
 * <p>Storage: cell-major, species-minor. {@code populationOf(cell, species)}
 * lives at {@code counts[cell * Species.COUNT + species]}; totals live in a
 * parallel array so "total population in this cell" is one indexed read
 * rather than a per-species sum.
 *
 * <p>Not thread safe. Live callers are expected on the sim thread.
 */
public final class RegionGrid {

    private final int chunkSize;
    private final int chunksPerAxis;
    private final int[] counts;
    private final int[] totals;

    /** Which tick the counts reflect; -1 means never populated. */
    private long stampTick = -1;

    public RegionGrid(int worldSize, int chunkSize) {
        if (chunkSize <= 0 || worldSize % chunkSize != 0) {
            throw new IllegalArgumentException(
                "chunkSize must divide worldSize; got " + chunkSize + " for " + worldSize);
        }
        this.chunkSize = chunkSize;
        this.chunksPerAxis = worldSize / chunkSize;
        int cellCount = chunksPerAxis * chunksPerAxis;
        this.counts = new int[cellCount * Species.COUNT];
        this.totals = new int[cellCount];
    }

    public int getChunksPerAxis() {
        return chunksPerAxis;
    }

    public int getChunkSize() {
        return chunkSize;
    }

    public long getStampTick() {
        return stampTick;
    }

    /**
     * Refreshes the counts if the stored stamp is older than {@code atTick}.
     * A caller can therefore ask "give me the aggregate for tick N" and get
     * either a freshly rebuilt one or a stored one from the same tick, without
     * paying for a rebuild the previous caller already covered.
     */
    public void refresh(Units units, long atTick) {
        if (stampTick == atTick) {
            return;
        }
        rebuild(units);
        stampTick = atTick;
    }

    /** Unconditional rebuild; useful for tests that want to snapshot a specific state. */
    public void rebuild(Units units) {
        java.util.Arrays.fill(counts, 0);
        java.util.Arrays.fill(totals, 0);
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!units.alive[i]) {
                continue;
            }
            int cell = cellIndex(units.x[i], units.z[i]);
            if (cell < 0) {
                continue;
            }
            counts[cell * Species.COUNT + units.species[i]]++;
            totals[cell]++;
        }
    }

    /** Total live population in the region containing the point. */
    public int totalAt(float worldX, float worldZ) {
        int cell = cellIndex(worldX, worldZ);
        return cell < 0 ? 0 : totals[cell];
    }

    /** Population of one species in the region containing the point. */
    public int populationOf(float worldX, float worldZ, byte species) {
        int cell = cellIndex(worldX, worldZ);
        return cell < 0 ? 0 : counts[cell * Species.COUNT + species];
    }

    /** Directly indexed lookup, for callers that already know the cell. */
    public int totalAtCell(int cell) {
        return totals[cell];
    }

    public int populationAtCell(int cell, byte species) {
        return counts[cell * Species.COUNT + species];
    }

    /**
     * The species with the most residents in a cell, or -1 for an empty cell.
     * Broken ties by species id, so the answer is deterministic.
     */
    public int dominantAtCell(int cell) {
        int bestSpecies = -1;
        int bestCount = 0;
        int base = cell * Species.COUNT;
        for (int s = 0; s < Species.COUNT; s++) {
            int c = counts[base + s];
            if (c > bestCount) {
                bestCount = c;
                bestSpecies = s;
            }
        }
        return bestSpecies;
    }

    private int cellIndex(float worldX, float worldZ) {
        int cellX = (int) Math.floor(worldX) / chunkSize;
        int cellZ = (int) Math.floor(worldZ) / chunkSize;
        if (cellX < 0 || cellZ < 0 || cellX >= chunksPerAxis || cellZ >= chunksPerAxis) {
            return -1;
        }
        return cellZ * chunksPerAxis + cellX;
    }
}
