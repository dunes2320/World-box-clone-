package com.game.sim;

import java.util.Random;

/**
 * Owns the whole simulation state and advances it one fixed tick at a time.
 *
 * <p>Determinism contract: the same seed plus the same sequence of god-tool
 * commands produces a bit-identical world. That means one seeded {@link Random}
 * for everything, no {@code Math.random()}, no wall-clock reads, and no
 * iteration over hash-ordered collections anywhere inside {@link #tick()}.
 */
public final class Simulation {

    private final long seed;
    private final World world;
    private final Units units;
    private final DensityGrid density;
    private final Villages villages;
    private final Territory territory;
    private final Random random;

    private long tickCount;

    public Simulation(long seed) {
        this.seed = seed;
        this.world = WorldGen.generate(seed);
        this.units = new Units(SimConfig.MAX_UNITS);
        this.density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        this.villages = new Villages(SimConfig.MAX_VILLAGES);
        this.territory = new Territory(world.tileCount);
        // Offset from the world seed so the gameplay stream is independent of
        // the terrain stream: regenerating terrain must not shift gameplay rolls.
        this.random = new Random(seed ^ 0x5DEECE66DL);
    }

    public Units getUnits() {
        return units;
    }

    public DensityGrid getDensity() {
        return density;
    }

    public Villages getVillages() {
        return villages;
    }

    /**
     * Scatters units of a species across walkable ground under the brush - the
     * spawn god tool.
     *
     * @return how many were actually placed
     */
    public int spawnUnits(int tileX, int tileZ, int radius, byte species, int count) {
        return UnitSystem.spawnBrush(world, units, random, tileX, tileZ, radius, species, count);
    }

    /**
     * Kills anything left stranded by a terrain edit. Called by the god tools
     * after terraforming rather than every tick, since it is a reaction to an
     * edit rather than a behaviour.
     */
    public int cullStrandedUnits() {
        return UnitSystem.cullStranded(world, units);
    }

    public long getSeed() {
        return seed;
    }

    public World getWorld() {
        return world;
    }

    public Random getRandom() {
        return random;
    }

    public long getTickCount() {
        return tickCount;
    }

    /** Advances the world by exactly one fixed step. */
    public void tick() {
        tickCount++;
        UnitSystem.update(world, units, density, random);
        // Villages move on a slower clock than footsteps: territory does not
        // need recomputing ten times a second, and settling should feel like it
        // takes a while rather than happening the instant a crowd forms.
        if (tickCount % SimConfig.VILLAGE_UPDATE_INTERVAL == 0) {
            VillageSystem.update(world, units, villages, territory, density, random, (int) tickCount);
        }
    }
}
