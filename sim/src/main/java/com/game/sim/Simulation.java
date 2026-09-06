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
    private final Features features;
    private final Roads roads;
    private final RoadSystem roadSystem;
    private final Kingdoms kingdoms;
    private final KingdomRelations kingdomRelations;
    private final Armies armies;
    private final UnitLore unitLore;
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
        // Feature pool sized off the village pool: about 60 features per
        // village at peak covers houses + a handful of each economy building
        // + the road segments crossing that village's ground.
        int featureCapacity = Math.max(2048, villages.capacity * 80);
        this.features = new Features(featureCapacity, world.tileCount);
        this.roads = new Roads(world.tileCount);
        this.roadSystem = new RoadSystem(world.size);
        int kCap = SimConfig.kingdomsCapacityFor(worldSize);
        this.kingdoms = new Kingdoms(kCap);
        this.kingdomRelations = new KingdomRelations(kCap);
        this.armies = new Armies(SimConfig.armiesCapacityFor(worldSize));
        // Lore mirrors the units pool: one entry per slot, so a unit's
        // index is also the index into its story.
        this.unitLore = new UnitLore(this.units.capacity);
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

    public Features getFeatures() {
        return features;
    }

    public Roads getRoads() {
        return roads;
    }

    public Kingdoms getKingdoms() {
        return kingdoms;
    }

    public KingdomRelations getKingdomRelations() {
        return kingdomRelations;
    }

    public Armies getArmies() {
        return armies;
    }

    public UnitLore getUnitLore() {
        return unitLore;
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
        return UnitSystem.spawnBrush(world, units, unitLore, random,
            tileX, tileZ, radius, species, count, seed);
    }

    /**
     * Kills anything left stranded by a terrain edit. Called by the god tools
     * after terraforming rather than every tick, since it is a reaction to an
     * edit rather than a behaviour.
     */
    public int cullStrandedUnits() {
        return UnitSystem.cullStranded(world, units, unitLore);
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
        UnitSystem.update(world, units, villages, unitLore, density, random,
            populationCap, tickCount, seed);
        // Combat runs every tick and covers only the small half of the picture:
        // civilians caught in an army's firing line. Army-vs-army and siege
        // damage happens on the village pass in MilitarySystem, since that is
        // the cadence armies actually move on.
        warCasualties += CombatSystem.update(world, units, villages,
            armies, kingdoms, unitLore, relations, density, random);
        // Fire and plague advance every tick too, and cost nothing when the
        // world is neither alight nor sick.
        disasters.update(world, units, unitLore, random);
        // Villages move on a slower clock than footsteps: territory does not
        // need recomputing ten times a second, and settling should feel like it
        // takes a while rather than happening the instant a crowd forms.
        if (tickCount % SimConfig.VILLAGE_UPDATE_INTERVAL == 0) {
            VillageSystem.update(world, units, villages, territory, density, random, (int) tickCount);
            // Kingdom bookkeeping runs first at village pace: sort fresh
            // villages into kingdoms and recount memberships so later systems
            // read a settled political map.
            KingdomSystem.update(villages, kingdoms, kingdomRelations, units, random, (int) tickCount);
            // Once villages have moved and their territories have settled,
            // let them build. Buildings first so a fresh house has ground
            // reserved before roads try to reach it.
            BuildingSystem.update(world, villages, features, random, (int) tickCount);
            roadSystem.update(world, villages, features, roads);
            // Economy runs after buildings and roads: production reads what
            // BuildingSystem just placed, and trade reads the roads
            // RoadSystem just laid.
            Economy.update(world, villages, units, features);
            Trade.update(world, villages, roads);
            // Armies march on the village clock too - so a war between two
            // neighbouring kingdoms produces visible campaigns rather than a
            // frozen border.
            warCasualties += MilitarySystem.update(world, villages, kingdoms,
                kingdomRelations, armies, features, random, (int) tickCount);
        }
        // Diplomacy is slower still, and reads the borders the village pass just
        // drew - so a war is declared over the map as it currently stands.
        if (tickCount % SimConfig.RELATION_UPDATE_INTERVAL == 0) {
            relationSystem.update(world, villages, relations, random, (int) tickCount);
            // Kingdom-level relations move on the same slow clock, since a
            // kingdom war declaration should also feel like a considered
            // event rather than a per-tick coin flip.
            kingdomRelations.update(kingdoms, (int) tickCount, random);
        }
    }
}
