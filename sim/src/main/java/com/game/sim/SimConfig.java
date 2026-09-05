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

    // ---- species relations ----

    /**
     * Ticks between relation passes. Diplomacy moves slower than settlement,
     * and deliberately a whole multiple of {@link #VILLAGE_UPDATE_INTERVAL} so
     * a relations pass always lands right after a territory pass and reads
     * borders that were drawn this instant rather than up to a pass ago.
     */
    public static final int RELATION_UPDATE_INTERVAL = 60;

    /** Where every pair starts: mildly friendly, so nobody opens the game at war. */
    public static final float RELATION_START = 0.20f;
    /** Random spread either side of the start, so the four pairs are not identical. */
    public static final float RELATION_INITIAL_SPREAD = 0.25f;

    /** Random wobble applied to every pair each pass, either direction. */
    public static final float RELATION_DRIFT = 0.035f;
    /**
     * How much one tile of contested border sours a pair each pass. Rubbing up
     * against each other is what actually causes wars here - two species on
     * opposite coasts drift around neutral forever, which is as it should be.
     */
    public static final float RELATION_BORDER_FRICTION = 0.0018f;
    /** Ceiling on the friction one pass can apply, so a long border is not instant war. */
    public static final int RELATION_FRICTION_CAP_TILES = 50;
    /**
     * Where a pair lands when a war ends. Agreeing to stop is itself worth
     * something, so peace pays a little more than the threshold that triggered
     * it. Landing exactly on the threshold instead was measured at thirteen
     * wars between the same two species on one seed: two passes of friction put
     * them straight back under, and "peace" never lasted long enough to read as
     * peace.
     */
    public static final float RELATION_POST_WAR = 0.10f;
    /**
     * How long after a war a pair is left alone by border friction. Sharing a
     * border is a slow grievance, and the two species who just stopped fighting
     * over one are precisely the two who need a while before it starts counting
     * against them again.
     */
    public static final int WAR_COOLDOWN_TICKS = 1500;
    /** How fast a war talks itself out once it has started. */
    public static final float RELATION_WAR_WEARINESS = 0.055f;
    /** How much each battlefield death deepens the grudge, slowing that recovery. */
    public static final float RELATION_CASUALTY_GRUDGE = 0.0040f;

    /** Relations at or below this declare war. */
    public static final float WAR_THRESHOLD = -0.55f;
    /**
     * Relations at or above this end one. The gap between the two thresholds is
     * hysteresis: with a single threshold a pair sitting on it would flip
     * between war and peace on the random drift alone.
     */
    public static final float PEACE_THRESHOLD = -0.15f;
    /**
     * Hard ceiling on a war's length. Weariness normally ends a war well before
     * this, but casualties push the other way, and a backstop means "wars end"
     * is a guarantee of the design rather than a property of the tuning.
     */
    public static final int MAX_WAR_TICKS = 4000;

    // ---- combat ----

    /**
     * Per-tick chance of being struck, per enemy sharing your density cell.
     *
     * <p>Tuned down from 0.030 against five 40,000-tick runs. At the higher
     * figure a species was wiped out on two of the five seeds; at this one all
     * four survive on all five, and the runs still produce 9 to 26 wars and
     * 1,300 to 4,400 battlefield dead apiece. Losing a war costs villages,
     * villages are what make a species breed faster, and above this rate the
     * loser never gets back on its feet between wars.
     */
    public static final double COMBAT_RISK_PER_ENEMY = 0.018;
    /**
     * How much more dangerous a fight is when you are the one standing on the
     * enemy's territory. Defending your own ground is worth something, which is
     * what makes a border war push back and forth instead of sliding one way.
     *
     * <p>This is a multiplier on the danger enemies pose, deliberately not a
     * danger of its own. An earlier version had territory hurt trespassers
     * outright, with no enemy needed - which meant a species that lost its
     * villages was killed everywhere at once by ground it merely stood on,
     * with nobody nearby. Measured on seed 2024: humans went from 214 alive to
     * extinct in 2,500 ticks. Danger comes from enemies now, so a beaten
     * species can survive in the gaps rather than being erased from the map.
     */
    public static final double COMBAT_DEFENDER_ADVANTAGE = 1.8;
    /**
     * Damage per hit. Comfortably ahead of the one-per-tick healing a fed unit
     * gets, or a battle line would be two crowds regenerating at each other.
     */
    public static final int COMBAT_DAMAGE = 20;

    // ---- terraform brush ----

    public static final int MIN_BRUSH_RADIUS = 1;
    public static final int MAX_BRUSH_RADIUS = 12;
    public static final float TERRAFORM_STRENGTH = 0.55f;
}
