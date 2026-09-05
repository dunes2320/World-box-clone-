package com.game.sim;

/** Every tunable the simulation reads, in one place. */
public final class SimConfig {

    private SimConfig() {
    }

    // ---- world dimensions ----

    public static final int WORLD_SIZE = 128;
    public static final int TILE_COUNT = WORLD_SIZE * WORLD_SIZE;
    public static final int CHUNK_SIZE = 16;
    public static final int CHUNKS_PER_AXIS = WORLD_SIZE / CHUNK_SIZE;
    public static final int CHUNK_COUNT = CHUNKS_PER_AXIS * CHUNKS_PER_AXIS;

    // ---- timing ----

    /** Fixed simulation rate. Render framerate is entirely independent of this. */
    public static final int TICKS_PER_SECOND = 10;
    public static final double SECONDS_PER_TICK = 1.0 / TICKS_PER_SECOND;
    /**
     * Ceiling on how many ticks a single frame may run to catch up. Without it,
     * one slow frame queues a backlog that takes even longer to process, which
     * queues a bigger backlog - the classic death spiral.
     */
    public static final int MAX_TICKS_PER_FRAME = 8;

    // ---- terrain elevation ----

    /** Anything below this is underwater. Land heights are measured from here. */
    public static final float SEA_LEVEL = 0.0f;
    public static final float DEEP_WATER_LEVEL = -1.5f;
    public static final float SAND_LEVEL = 0.7f;
    public static final float HILL_LEVEL = 4.0f;
    public static final float MOUNTAIN_LEVEL = 7.0f;
    public static final float SNOW_LEVEL = 10.0f;

    /** Clamp range for terraforming, so the brush cannot dig to infinity. */
    public static final float MIN_HEIGHT = -6.0f;
    public static final float MAX_HEIGHT = 14.0f;

    /** Fertility above this grows forest rather than plain grass. */
    public static final float FOREST_FERTILITY = 0.55f;

    // ---- world generation ----

    public static final float NOISE_SCALE = 0.028f;
    public static final int ELEVATION_OCTAVES = 5;
    public static final float FERTILITY_SCALE = 0.045f;
    public static final int FERTILITY_OCTAVES = 3;
    /**
     * How hard the island falloff pulls the map edge underwater. The world is
     * a bounded island rather than a wrapping plane, so the border is always
     * deep water and the player can see where the world stops.
     *
     * <p>Applied on a fourth-power ramp (see WorldGen.islandFalloff), which
     * stays near zero across the middle of the map and then climbs steeply.
     * A gentler ramp drowned the interior as well as the rim - measured at
     * ~9% land across twelve seeds, which is an ocean with specks in it.
     */
    public static final float ISLAND_FALLOFF = 20.0f;
    /**
     * Normalised fbm mostly lands in about [-0.45, 0.45] rather than the full
     * [-1, 1], so the amplitude has to be generously larger than the height
     * range it is meant to fill or the peaks never reach the mountain and snow
     * bands at all.
     */
    public static final float ELEVATION_AMPLITUDE = 22.0f;
    public static final float ELEVATION_BIAS = 2.5f;

    // ---- units ----

    /** Pool size. Larger than the population cap so the spawn tool has headroom. */
    public static final int MAX_UNITS = 3000;
    /**
     * Breeding stops here. The brief's performance target is 2000 units, so the
     * cap is set to it rather than above it - a world that can only reach its
     * own target is a world that cannot quietly drift out of budget.
     */
    public static final int POPULATION_CAP = 2000;

    /** Tiles per tick. At 10 ticks/sec this is a bit over one tile a second. */
    public static final float UNIT_SPEED = 0.11f;
    public static final int UNIT_MAX_HEALTH = 100;

    /** Ticks before a unit can breed. */
    public static final int UNIT_MATURITY = 260;
    /** Random spread either side of a species' base lifespan. */
    public static final int LIFESPAN_VARIANCE = 800;

    /** Hunger rises by one a tick; at this point starvation starts biting. */
    public static final int HUNGER_STARVING = 118;
    /** Must be below this to breed, so only well-fed populations grow. */
    public static final int HUNGER_FED = 34;
    public static final int STARVATION_DAMAGE = 2;

    /** Hunger removed per tick standing on each kind of ground. */
    public static final int FOOD_FROM_GRASS = 3;
    public static final int FOOD_FROM_FOREST = 5;

    /** Per-tick breeding chance for a fed adult, before species fertility. */
    public static final double REPRODUCE_CHANCE = 0.0035;

    /** Side length in tiles of one density-grid cell (see DensityGrid). */
    public static final int DENSITY_CELL_SIZE = 8;
    /**
     * Units of ANY species per density cell at which breeding stops. Caps how
     * packed one region can get regardless of who lives there.
     */
    public static final int LOCAL_CROWDING_LIMIT = 18;
    /**
     * Units of the SAME species per cell at which that species stops breeding
     * there. Deliberately well below the total limit: competition has to bite
     * harder within a species than between them, or the fastest breeder simply
     * fills every cell and the others die out (see DensityGrid).
     */
    public static final int SPECIES_CROWDING_LIMIT = 6;

    // ---- villages ----

    public static final int MAX_VILLAGES = 96;
    /** Ticks between village passes. Territory need not keep up with footsteps. */
    public static final int VILLAGE_UPDATE_INTERVAL = 20;

    /** Per-pass chance an eligible homeless adult founds a village. */
    public static final double VILLAGE_FOUND_CHANCE = 0.012;
    /**
     * Same-species neighbours required in the density cell before settling.
     * Settling is something a group does; without this the map fills with
     * one-person hamlets wherever a wanderer happens to pause.
     */
    public static final int VILLAGE_FOUND_MIN_NEARBY = 4;
    /** Minimum gap between village centres, in tiles. */
    public static final float VILLAGE_MIN_SPACING = 13f;

    public static final float VILLAGE_BASE_RADIUS = 4f;
    public static final float VILLAGE_MAX_RADIUS = 17f;
    public static final float VILLAGE_RADIUS_PER_POP = 1.15f;
    /** Fraction of the gap to the target radius closed each pass. */
    public static final float VILLAGE_RADIUS_EASING = 0.12f;

    /** Villagers breed faster than drifters - the point of settling down. */
    public static final double VILLAGE_BREEDING_BONUS = 1.45;

    /** How many ticks a unit holds a heading before picking a new one. */
    public static final int WANDER_MIN_TICKS = 18;
    public static final int WANDER_MAX_TICKS = 55;

    // ---- terraform brush ----

    public static final int MIN_BRUSH_RADIUS = 1;
    public static final int MAX_BRUSH_RADIUS = 12;
    public static final float TERRAFORM_STRENGTH = 0.55f;
}
