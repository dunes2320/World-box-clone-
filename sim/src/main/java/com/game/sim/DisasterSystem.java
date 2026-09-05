package com.game.sim;

import java.util.Random;

/**
 * The half of a disaster that keeps going after the click: fire working through
 * a forest, and a plague working through a population.
 *
 * <p>Both are built to burn out. Fire consumes the forest that carries it, so
 * the fuel left on the map strictly decreases and a fire cannot come back
 * through ground it has already crossed. A plague leaves survivors immune, so
 * the pool it can spread into strictly decreases too. Neither termination
 * depends on the tuning constants being generous - they are properties of the
 * rules, which is what keeps "fire burns out" from becoming a bug report.
 *
 * <p>An instance rather than a static utility because it owns two reusable
 * buffers, the same reason {@link Territory} and {@link RelationSystem} are.
 */
public final class DisasterSystem {

    /**
     * Tiles lit during the current sweep, applied after it finishes. A tile lit
     * by its neighbour must not also burn down in the same tick, and because
     * the sweep runs in index order that is exactly what would happen to every
     * neighbour at a higher index.
     */
    private final int[] pendingIgnition = new int[SimConfig.MAX_IGNITIONS_PER_TICK];
    private int pendingCount;

    /** Sick units per density cell, so infection can spread without a spatial query. */
    private final int[] infectedPerCell;
    private final int cellSize;
    private final int cellsPerAxis;

    private int burningTiles;
    private int infectedUnits;
    private int fireDeaths;
    private int plagueDeaths;
    /**
     * Bumped whenever a tile lights or goes out. The renderer rebuilds its
     * flames when this changes, which catches a fire whose size happens to stay
     * the same because one tile lit as another died.
     */
    private int fireGeneration;

    public DisasterSystem(int worldSize, int densityCellSize) {
        this.cellSize = densityCellSize;
        this.cellsPerAxis = worldSize / densityCellSize;
        this.infectedPerCell = new int[cellsPerAxis * cellsPerAxis];
    }

    public int getBurningTiles() {
        return burningTiles;
    }

    public int getInfectedUnits() {
        return infectedUnits;
    }

    public int getFireDeaths() {
        return fireDeaths;
    }

    public int getPlagueDeaths() {
        return plagueDeaths;
    }

    public int getFireGeneration() {
        return fireGeneration;
    }

    /** Advances every ongoing disaster by one tick. */
    public void update(World world, Units units, Random random) {
        advanceFire(world, units, random);
        advancePlague(units, random);
    }

    // ---- fire ----

    /**
     * Burns down every lit tile by one tick, spreading as it goes.
     *
     * <p>Sweeps the whole map, but only when something is actually alight - in
     * a world with no fire in it this method is one comparison. That is worth
     * more than an active-tile list here, because the sweep is a flat 16k pass
     * over a byte array and the list would have to be kept correct against
     * every terraform, meteor and flood that can change what is burning.
     */
    private void advanceFire(World world, Units units, Random random) {
        if (burningTiles == 0) {
            return;
        }
        pendingCount = 0;
        int stillBurning = 0;

        for (int z = 0; z < world.size; z++) {
            for (int x = 0; x < world.size; x++) {
                int i = world.index(x, z);
                int remaining = world.burn[i];
                if (remaining <= 0) {
                    continue;
                }

                remaining--;
                if (remaining == 0) {
                    // Fuel spent. The forest is gone, which is what stops the
                    // fire ever crossing this ground again.
                    world.burn[i] = 0;
                    world.setType(x, z, TileType.GRASS);
                    fireGeneration++;
                    continue;
                }

                world.burn[i] = (byte) remaining;
                stillBurning++;

                queueIfFlammable(world, x + 1, z, random);
                queueIfFlammable(world, x - 1, z, random);
                queueIfFlammable(world, x, z + 1, random);
                queueIfFlammable(world, x, z - 1, random);
            }
        }

        for (int p = 0; p < pendingCount; p++) {
            int i = pendingIgnition[p];
            if (world.burn[i] != 0) {
                continue;
            }
            world.burn[i] = (byte) SimConfig.FIRE_DURATION;
            world.markDirty(i % world.size, i / world.size);
            fireGeneration++;
            stillBurning++;
        }

        burningTiles = stillBurning;
        fireDeaths += burnUnits(world, units);
    }

    private void queueIfFlammable(World world, int x, int z, Random random) {
        if (pendingCount == pendingIgnition.length || !world.inBounds(x, z)) {
            return;
        }
        int i = world.index(x, z);
        if (world.burn[i] != 0 || !TileType.isFlammable(world.tileType[i])) {
            return;
        }
        if (random.nextDouble() < SimConfig.FIRE_SPREAD_CHANCE) {
            pendingIgnition[pendingCount++] = i;
        }
    }

    /** Hurts anything standing in the flames. */
    private static int burnUnits(World world, Units units) {
        int killed = 0;
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!units.alive[i]) {
                continue;
            }
            int tileX = (int) Math.floor(units.x[i]);
            int tileZ = (int) Math.floor(units.z[i]);
            if (!world.inBounds(tileX, tileZ) || world.burn[world.index(tileX, tileZ)] == 0) {
                continue;
            }
            units.health[i] -= SimConfig.FIRE_DAMAGE;
            if (units.health[i] <= 0) {
                units.kill(i);
                killed++;
            }
        }
        return killed;
    }

    /**
     * Recounts what is alight. Called after anything outside this class changes
     * the burn array - a lightning strike, a meteor - so the fast path above
     * knows there is work to do again.
     */
    public void refreshFireCount(World world) {
        int burning = 0;
        for (int i = 0; i < world.tileCount; i++) {
            if (world.burn[i] != 0) {
                burning++;
            }
        }
        if (burning != burningTiles) {
            fireGeneration++;
        }
        burningTiles = burning;
    }

    // ---- plague ----

    /**
     * Ages every infection by a tick and spreads it to the healthy.
     *
     * <p>Spread works off a per-cell count of the sick rather than asking who
     * is standing next to whom, for the same reason combat does: it is one
     * linear pass instead of a spatial query, and at this scale a density cell
     * is a good enough answer to "near".
     */
    private void advancePlague(Units units, Random random) {
        if (infectedUnits == 0) {
            return;
        }
        java.util.Arrays.fill(infectedPerCell, 0);
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (units.alive[i] && units.disease[i] > 0) {
                int cell = cellIndex(units.x[i], units.z[i]);
                if (cell >= 0) {
                    infectedPerCell[cell]++;
                }
            }
        }

        int stillSick = 0;
        for (int i = 0; i < end; i++) {
            if (!units.alive[i]) {
                continue;
            }
            byte disease = units.disease[i];

            if (disease > 0) {
                if (random.nextDouble() < SimConfig.DISEASE_FATALITY) {
                    units.kill(i);
                    plagueDeaths++;
                    continue;
                }
                disease--;
                if (disease == 0) {
                    // Survived it. Immunity is what makes a plague burn out
                    // rather than circulate forever: the pool it can still
                    // spread into only ever shrinks.
                    units.disease[i] = Units.IMMUNE;
                } else {
                    units.disease[i] = disease;
                    stillSick++;
                }
                continue;
            }

            if (disease != Units.HEALTHY) {
                continue;
            }
            int cell = cellIndex(units.x[i], units.z[i]);
            if (cell < 0 || infectedPerCell[cell] == 0) {
                continue;
            }
            if (random.nextDouble() < infectedPerCell[cell] * SimConfig.DISEASE_SPREAD_CHANCE) {
                units.disease[i] = (byte) SimConfig.DISEASE_DURATION;
                stillSick++;
            }
        }
        infectedUnits = stillSick;
    }

    /** Recounts the sick, after {@link Disasters#infect} has created some. */
    public void refreshInfectedCount(Units units) {
        int sick = 0;
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (units.alive[i] && units.disease[i] > 0) {
                sick++;
            }
        }
        infectedUnits = sick;
    }

    static {
        // Infection state lives in a signed byte alongside its two sentinels, so
        // a duration past 127 would be silently clamped by the cast and the game
        // would quietly run a shorter plague than the config says it does.
        if (SimConfig.DISEASE_DURATION < 1 || SimConfig.DISEASE_DURATION > Byte.MAX_VALUE) {
            throw new IllegalStateException(
                "DISEASE_DURATION must be 1.." + Byte.MAX_VALUE
                    + " to fit in Units.disease, got " + SimConfig.DISEASE_DURATION);
        }
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
