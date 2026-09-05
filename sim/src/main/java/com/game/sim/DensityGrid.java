package com.game.sim;

/**
 * A coarse per-species count of how many units occupy each region of the map,
 * rebuilt once per tick.
 *
 * <p>Exists to make breeding density-dependent, which turns out to be what
 * decides whether four species can coexist at all.
 *
 * <p>With no density term, growth is limited only by the global population cap
 * and the outcome is winner-take-all: measured over 30,000 ticks, orcs took 91%
 * of the map and elves fell to about 1%. Limiting the *total* per region helped
 * the totals but not the mix - orcs still reached 85%, because a shared ceiling
 * caps how many live somewhere without deciding which species fills each freed
 * slot, and the fastest breeder always wins that race.
 *
 * <p>So the counts are kept per species, and breeding is throttled mainly by
 * how many of a unit's *own kind* are already nearby. That is the standard
 * condition for coexistence: competition within a species has to bite harder
 * than competition between them. It also puts the drama where it belongs -
 * a species should be wiped out by losing a war, not by quietly out-breeding
 * everyone during peacetime.
 */
public final class DensityGrid {

    private final int cellSize;
    private final int cellsPerAxis;
    /** cell-major, species-minor: {@code counts[cell * Species.COUNT + species]}. */
    private final int[] counts;
    private final int[] totals;

    public DensityGrid(int worldSize, int cellSize) {
        if (cellSize <= 0 || worldSize % cellSize != 0) {
            throw new IllegalArgumentException(
                "cellSize must divide worldSize; got " + cellSize + " for " + worldSize);
        }
        this.cellSize = cellSize;
        this.cellsPerAxis = worldSize / cellSize;
        int cells = cellsPerAxis * cellsPerAxis;
        this.counts = new int[cells * Species.COUNT];
        this.totals = new int[cells];
    }

    public int getCellsPerAxis() {
        return cellsPerAxis;
    }

    public int getCellSize() {
        return cellSize;
    }

    /** Recounts every live unit into its cell. One linear pass, no allocation. */
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

    /** Units of one species in the cell containing a world position. */
    public int speciesAt(float worldX, float worldZ, byte species) {
        int cell = cellIndex(worldX, worldZ);
        return cell < 0 ? 0 : counts[cell * Species.COUNT + species];
    }

    /** Units of every species in the cell containing a world position. */
    public int totalAt(float worldX, float worldZ) {
        int cell = cellIndex(worldX, worldZ);
        return cell < 0 ? 0 : totals[cell];
    }

    private int cellIndex(float worldX, float worldZ) {
        int cellX = (int) Math.floor(worldX) / cellSize;
        int cellZ = (int) Math.floor(worldZ) / cellSize;
        if (cellX < 0 || cellZ < 0 || cellX >= cellsPerAxis || cellZ >= cellsPerAxis) {
            return -1;
        }
        return cellZ * cellsPerAxis + cellX;
    }
}
