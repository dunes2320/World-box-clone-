package com.game.sim;

/** Every tunable the simulation reads, in one place. */
public final class SimConfig {

    private SimConfig() {
    }

    // ---- world dimensions ----

    /**
     * Chunk side in tiles. Every world size must divide by this.
     *
     * <p>Bumped from 16 to 32 in phase 7 for the scale-up. Smaller chunks
     * would give more of them than the CPU can iterate cheaply at 512x512;
     * larger chunks would make a single terraform edit rebuild too much
     * geometry at once. 32x32 = 1024 tiles per chunk, comfortably under the
     * 65535 short-index vertex cap even with all five wall quads populated.
     */
    public static final int CHUNK_SIZE = 32;

    /**
     * Default world side length in tiles - what a new {@link Simulation} uses
     * when the launcher does not pass {@code --size}. Every dimension the sim
     * needs (chunk grid, unit pool, population cap, village pool) is now
     * derived from a world size rather than a fixed constant, so the same
     * codebase runs at 128 for tests and 512 for a big playthrough.
     */
    public static final int DEFAULT_WORLD_SIZE = 384;

    /** Small / Medium / Large presets for the world-setup picker. */
    public static final int WORLD_SIZE_SMALL = 256;
    public static final int WORLD_SIZE_MEDIUM = 384;
    public static final int WORLD_SIZE_LARGE = 512;

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

    /**
     * Legacy pool size, retained for tests that construct {@link Units}
     * directly at the original 128-tile scale. Live {@link Simulation}
     * instances now size the pool with {@link #unitsCapacityFor(int)} so a
     * bigger world gets a bigger pool without recompiling.
     */
    public static final int MAX_UNITS = 3000;
    /**
     * Legacy population cap for the original 128 world. New code should call
     * {@link #populationCapFor(int)} instead. Kept here so tests already
     * built against this number keep meaning the same thing.
     */
    public static final int POPULATION_CAP = 2000;

    /**
     * Population cap that scales with world size.
     *
     * <p>Twenty units per tile of world side length keeps the units-per-tile
     * ratio steady across scales: 2,560 at 128, 5,120 at 256, 7,680 at 384,
     * and 10,240 at 512 - enough headroom for the brief's 8,000-unit target
     * at the large size, without exploding on the small one.
     */
    public static int populationCapFor(int worldSize) {
        return Math.max(POPULATION_CAP, worldSize * 20);
    }

    /**
     * Pool capacity, sized 1.5x the population cap so the spawn tool always
     * has headroom above the natural ceiling.
     */
    public static int unitsCapacityFor(int worldSize) {
        return Math.max(MAX_UNITS, populationCapFor(worldSize) * 3 / 2);
    }

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

    /** Legacy village pool size at 128; new code uses {@link #villagesCapacityFor(int)}. */
    public static final int MAX_VILLAGES = 96;

    /**
     * Village pool sized from world area.
     *
     * <p>One village per ~250 tiles matches the empirical density from phase 4
     * measurements: 96 at 128 was comfortable, and this same ratio gives 262
     * at 256, 590 at 384, and 1,048 at 512 - all safely inside the signed-short
     * index we store per tile in {@code World.ownerVillage}.
     */
    public static int villagesCapacityFor(int worldSize) {
        return Math.max(MAX_VILLAGES, worldSize * worldSize / 250);
    }
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

    // ---- economy ----

    /** Food a village opens with, enough to get a couple of farms running. */
    public static final int FOUNDING_FOOD = 40;
    /** Wood a village opens with, enough to build a house or two. */
    public static final int FOUNDING_WOOD = 20;

    public static final int RESOURCE_FOOD = 0;
    public static final int RESOURCE_WOOD = 1;
    public static final int RESOURCE_STONE = 2;
    public static final int RESOURCE_GOLD = 3;

    /** Food a completed farm yields each village pass. */
    public static final int FARM_YIELD_FOOD = 4;
    /** Wood a completed lumber camp yields each pass. */
    public static final int LUMBER_YIELD_WOOD = 3;
    /** Stone a completed mine yields each pass. */
    public static final int MINE_YIELD_STONE = 2;
    /** Gold a market yields each pass. */
    public static final int MARKET_YIELD_GOLD = 1;
    /** Gold a dock yields per pass. */
    public static final int DOCK_YIELD_GOLD = 1;

    /** How much food one resident eats per village pass. */
    public static final int FOOD_PER_RESIDENT = 1;

    /**
     * Above this stockpile, villagers get an economy-fed breeding boost on top
     * of the base village bonus. Below it, breeding falls back to the base
     * bonus. That is what makes prosperity look prosperous.
     */
    public static final int PROSPERITY_FOOD_THRESHOLD = 60;
    /** Multiplier stacked on VILLAGE_BREEDING_BONUS when the pantry is full. */
    public static final double PROSPERITY_BONUS = 1.35;
    /**
     * When food runs out, breeding stops entirely and a fraction of residents
     * leave home each pass looking for grass. Not a total shutdown of the
     * village - a village of nomads that gets fed again can revive.
     */
    public static final double HUNGER_DEPARTURE_CHANCE = 0.03;

    // Build costs by kind. Wood is the currency of a young village; stone
    // takes over as mines start producing. Central buildings cost gold.
    public static final int COST_HOUSE_WOOD = 6;
    public static final int COST_FARM_WOOD = 4;
    public static final int COST_LUMBER_WOOD = 5;
    public static final int COST_MINE_WOOD = 8;
    public static final int COST_MINE_STONE = 4;
    public static final int COST_DOCK_WOOD = 8;
    public static final int COST_MARKET_WOOD = 10;
    public static final int COST_MARKET_STONE = 6;
    public static final int COST_TEMPLE_WOOD = 8;
    public static final int COST_TEMPLE_STONE = 12;
    public static final int COST_BARRACKS_WOOD = 8;
    public static final int COST_BARRACKS_STONE = 8;
    public static final int COST_WALL_STONE = 4;

    /** Ticks a house takes to finish; everything else is a small multiple of this. */
    public static final int BUILD_TIME_HOUSE = 40;
    public static final int BUILD_TIME_FARM = 40;
    public static final int BUILD_TIME_LUMBER = 60;
    public static final int BUILD_TIME_MINE = 80;
    public static final int BUILD_TIME_DOCK = 80;
    public static final int BUILD_TIME_MARKET = 120;
    public static final int BUILD_TIME_TEMPLE = 140;
    public static final int BUILD_TIME_BARRACKS = 100;
    public static final int BUILD_TIME_WALL = 20;

    // ---- trade ----

    /** Villages within this tile distance of each other can trade. */
    public static final float TRADE_RANGE = 30f;
    /** Only villages of the same species trade (until phase 10's kingdoms). */
    public static final int TRADE_MIN_SURPLUS = 30;
    /** Fraction of the surplus that moves in a single trade pass. */
    public static final float TRADE_TRANSFER_FRACTION = 0.20f;

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

    // ---- disasters ----

    /**
     * Ticks a forest tile burns before its fuel is gone. Fire terminates
     * because burning consumes the forest that carries it: a burnt tile becomes
     * grass, grass is not flammable, and the number of tiles that can ever burn
     * is finite and strictly decreasing. That is a property of the rules rather
     * than of these numbers, which is what makes a runaway fire impossible.
     */
    public static final int FIRE_DURATION = 26;
    /** Per-tick chance a burning tile sets light to each flammable neighbour. */
    public static final double FIRE_SPREAD_CHANCE = 0.055;
    /** Per-tick damage to a unit standing in the flames. */
    public static final int FIRE_DAMAGE = 9;
    /**
     * Ceiling on ignitions applied in one tick. Spread is collected during the
     * sweep and applied after it, so a tile lit this tick cannot also burn down
     * this tick; the cap bounds that buffer. Dropped ignitions are not lost -
     * the neighbour that would have lit them is still burning next tick.
     */
    public static final int MAX_IGNITIONS_PER_TICK = 1024;

    /** How deep a meteor digs at the point of impact, before distance falloff. */
    public static final float METEOR_DEPTH = 5.5f;
    /** How high the spoil piles up around the crater lip. */
    public static final float METEOR_RIM = 1.8f;
    /** Multiplier on the brush radius for the ring of forest a meteor sets alight. */
    public static final float METEOR_FIRE_RADIUS = 1.7f;

    /** Lightning is a pinpoint strike: lethal, tiny, and it starts fires. */
    public static final float LIGHTNING_RADIUS = 1.8f;

    /** How far an earthquake throws the ground up or down at its centre. */
    public static final float QUAKE_AMPLITUDE = 2.6f;
    public static final int QUAKE_DAMAGE = 34;

    /** Land below this drowns when the water comes in. */
    public static final float FLOOD_LEVEL = SEA_LEVEL + 1.3f;

    /**
     * Ticks an infection runs before the host dies or recovers.
     *
     * <p>Must fit in a signed byte, because that is where {@code Units.disease}
     * keeps it. This was 220 for a while and silently ran as 127: the cast
     * clamped it and nothing said so, leaving a constant that named one number
     * while the game played another. {@link DisasterSystem} now refuses to load
     * if this goes out of range.
     */
    public static final int DISEASE_DURATION = 120;
    /**
     * Per-tick chance an infection kills its host, which over a full illness
     * works out at roughly a 40% death rate.
     *
     * <p>A roll rather than health attrition. Draining health per tick made
     * mortality a knife edge: at one damage a tick every single victim died
     * with room to spare, and any constant that let anyone live at all let
     * almost everyone live. A death roll moves smoothly with the number, so
     * "how deadly is the plague" is one legible dial.
     */
    public static final double DISEASE_FATALITY = 0.0045;
    /** Per-tick infection chance, per sick unit sharing a density cell. */
    public static final double DISEASE_SPREAD_CHANCE = 0.0022;

    // ---- terraform brush ----

    public static final int MIN_BRUSH_RADIUS = 1;
    public static final int MAX_BRUSH_RADIUS = 12;
    public static final float TERRAFORM_STRENGTH = 0.55f;
}
