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
    private final Relations relations;
    private final RelationSystem relationSystem;
    private final DisasterSystem disasters;
    private final Random random;

    private final int populationCap;

    private long tickCount;
    private int warCasualties;
    private int disasterCasualties;

    /** Default-size world at {@link SimConfig#DEFAULT_WORLD_SIZE}. */
    public Simulation(long seed) {
        this(seed, SimConfig.DEFAULT_WORLD_SIZE);
    }

    /**
     * Builds a world of {@code worldSize} tiles a side and sizes every pool,
     * cap and grid off it. All the per-scale constants live in one place
     * ({@link SimConfig}) so a 512 world is not just a larger map but a
     * larger population, a larger village pool and a larger territory buffer -
     * without a config file, a save format bump, or any behaviour code that
     * has to know its world's size.
     */
    public Simulation(long seed, int worldSize) {
        this.seed = seed;
        this.world = WorldGen.generate(seed, worldSize);
        this.units = new Units(SimConfig.unitsCapacityFor(worldSize));
        this.populationCap = SimConfig.populationCapFor(worldSize);
        this.density = new DensityGrid(world.size, SimConfig.DENSITY_CELL_SIZE);
        this.villages = new Villages(SimConfig.villagesCapacityFor(worldSize));
        this.territory = new Territory(world.tileCount);
        // Offset from the world seed so the gameplay stream is independent of
        // the terrain stream: regenerating terrain must not shift gameplay rolls.
        this.random = new Random(seed ^ 0x5DEECE66DL);
        // Draws from that same stream, so a seed also fixes the opening politics.
        this.relations = new Relations(random);
        this.relationSystem = new RelationSystem();
        this.disasters = new DisasterSystem(world.size, SimConfig.DENSITY_CELL_SIZE);
    }

    /** Population ceiling for this world's size, above which breeding stops. */
    public int getPopulationCap() {
        return populationCap;
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

    public Relations getRelations() {
        return relations;
    }

    public RelationSystem getRelationSystem() {
        return relationSystem;
    }

    public DisasterSystem getDisasters() {
        return disasters;
    }

    /** Units killed in war since the world began. */
    public int getWarCasualties() {
        return warCasualties;
    }

    /** Units killed outright by the player's disasters, before fire and plague. */
    public int getDisasterCasualties() {
        return disasterCasualties;
    }

    /**
     * Applies a disaster - the god tools' destructive half.
     *
     * <p>Refreshes the ongoing-disaster counts afterwards, because a strike can
     * light fires or start an infection and {@link DisasterSystem} skips its
     * whole pass when it believes there is nothing burning or nobody sick.
     *
     * @return units killed on the spot; fire and plague go on killing afterwards
     */
    public int strike(Disaster kind, int tileX, int tileZ, int radius) {
        int killed = Disasters.strike(kind, world, units, random, tileX, tileZ, radius);
        disasterCasualties += killed;
        disasters.refreshFireCount(world);
        disasters.refreshInfectedCount(units);
        return killed;
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
        UnitSystem.update(world, units, density, random, populationCap, tickCount);
        // Combat runs every tick, straight after movement, so fighting resolves
        // where the units actually are. In peacetime it returns immediately.
        warCasualties += CombatSystem.update(world, units, villages, relations, density, random);
        // Fire and plague advance every tick too, and cost nothing when the
        // world is neither alight nor sick.
        disasters.update(world, units, random);
        // Villages move on a slower clock than footsteps: territory does not
        // need recomputing ten times a second, and settling should feel like it
        // takes a while rather than happening the instant a crowd forms.
        if (tickCount % SimConfig.VILLAGE_UPDATE_INTERVAL == 0) {
            VillageSystem.update(world, units, villages, territory, density, random, (int) tickCount);
        }
        // Diplomacy is slower still, and reads the borders the village pass just
        // drew - so a war is declared over the map as it currently stands.
        if (tickCount % SimConfig.RELATION_UPDATE_INTERVAL == 0) {
            relationSystem.update(world, villages, relations, random, (int) tickCount);
        }
    }
}
