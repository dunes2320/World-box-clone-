package com.game.sim;

/**
 * Turns a village's completed buildings into resources, and its residents
 * into hungry mouths.
 *
 * <p>Runs on the same slow clock as the village update: production and
 * consumption move in discrete pulses matching the pass everything else in
 * a village runs on. That keeps stockpiles legible - a farm produces a
 * concrete number every twenty ticks, rather than an invisible drip units
 * try to keep up with per frame.
 *
 * <p>Building costs are enforced by {@link BuildingSystem} at placement
 * time; construction time is enforced here by decrementing
 * {@code features.buildTime} each pass. A building only produces when its
 * {@code buildTime} has reached zero, so a village that just placed a farm
 * has to wait forty ticks before the first food comes in.
 *
 * <p>Prosperity ties the stockpile back into breeding: a full pantry
 * multiplies the village breeding bonus, an empty pantry pauses breeding
 * entirely and thins the village as residents leave to find food. That is
 * what makes "prosper or wither on their own" a property of the numbers
 * rather than of the tuning.
 */
public final class Economy {

    private Economy() {
    }

    /**
     * Advances the whole world's economy by one village pass.
     *
     * @return array of totals summed across all villages, for tests and
     *     for the HUD: [totalFood, totalWood, totalStone, totalGold]
     */
    public static int[] update(World world, Villages villages, Units units, Features features) {
        int[] totals = new int[4];
        int end = villages.getHighWater();

        // Age construction: every building loses one tick from its build time.
        // Doing it once upfront rather than inside the per-village loop keeps
        // the ordering deterministic - the feature index is what decides who
        // finishes first when several buildings share a completion tick.
        int fEnd = features.getHighWater();
        for (int i = 0; i < fEnd; i++) {
            if (features.isAlive(i) && features.buildTime[i] > 0) {
                features.buildTime[i] = (short) (features.buildTime[i] - 1);
            }
        }

        for (int v = 0; v < end; v++) {
            if (!villages.alive[v]) {
                continue;
            }
            produce(villages, features, v, totals);
            consume(villages, units, v, totals);
        }
        return totals;
    }

    private static void produce(Villages villages, Features features, int v, int[] totals) {
        int food = 0;
        int wood = 0;
        int stone = 0;
        int gold = 0;
        int end = features.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!features.isAlive(i) || features.owner[i] != (short) v
                || features.buildTime[i] > 0) {
                continue;
            }
            switch (features.kind[i]) {
                case Features.KIND_FARM: food += SimConfig.FARM_YIELD_FOOD; break;
                case Features.KIND_LUMBER_CAMP: wood += SimConfig.LUMBER_YIELD_WOOD; break;
                case Features.KIND_MINE: stone += SimConfig.MINE_YIELD_STONE; break;
                case Features.KIND_MARKET: gold += SimConfig.MARKET_YIELD_GOLD; break;
                case Features.KIND_DOCK: gold += SimConfig.DOCK_YIELD_GOLD; break;
                default: break;
            }
        }
        villages.food[v] += food;
        villages.wood[v] += wood;
        villages.stone[v] += stone;
        villages.gold[v] += gold;
        totals[SimConfig.RESOURCE_FOOD] += villages.food[v];
        totals[SimConfig.RESOURCE_WOOD] += villages.wood[v];
        totals[SimConfig.RESOURCE_STONE] += villages.stone[v];
        totals[SimConfig.RESOURCE_GOLD] += villages.gold[v];
    }

    private static void consume(Villages villages, Units units, int v, int[] totals) {
        int consumed = villages.population[v] * SimConfig.FOOD_PER_RESIDENT;
        villages.food[v] -= consumed;
        if (villages.food[v] < 0) {
            villages.food[v] = 0;
            // A hungry village sheds residents: some fraction of the villagers
            // pack their heading and leave. See UnitSystem for what happens
            // once they are nomadic again.
            shed(villages, units, v);
        }
    }

    /**
     * Detaches a fraction of the village's own units and sends them nomadic.
     * Uses a deterministic hash of village + unit index rather than the world
     * RNG so the decision is reproducible from state alone - useful for the
     * planned save/replay in phase 15.
     */
    private static void shed(Villages villages, Units units, int v) {
        int end = units.getHighWater();
        int threshold = (int) (SimConfig.HUNGER_DEPARTURE_CHANCE * 0x10000);
        for (int i = 0; i < end; i++) {
            if (!units.alive[i] || units.homeVillage[i] != (short) v) {
                continue;
            }
            // 16-bit LCG hash of (village, unit): rot then multiply, take the
            // low bits. Cheap, no RNG state to advance.
            int mixed = (int) (((v * 2654435761L) ^ (i * 40503L)) & 0xffffL);
            if (mixed < threshold) {
                units.homeVillage[i] = Units.NO_VILLAGE;
            }
        }
    }

    /**
     * The economy-driven breeding multiplier for a village's members. Above
     * {@link SimConfig#PROSPERITY_FOOD_THRESHOLD} it stacks a prosperity
     * bonus; at zero it silences breeding entirely.
     */
    public static double prosperityMultiplier(Villages villages, int v) {
        if (villages.food[v] <= 0) {
            return 0.0;
        }
        if (villages.food[v] >= SimConfig.PROSPERITY_FOOD_THRESHOLD) {
            return SimConfig.PROSPERITY_BONUS;
        }
        return 1.0;
    }

    // ---- costs ----

    public static int woodCost(byte kind) {
        switch (kind) {
            case Features.KIND_HOUSE: return SimConfig.COST_HOUSE_WOOD;
            case Features.KIND_FARM: return SimConfig.COST_FARM_WOOD;
            case Features.KIND_LUMBER_CAMP: return SimConfig.COST_LUMBER_WOOD;
            case Features.KIND_MINE: return SimConfig.COST_MINE_WOOD;
            case Features.KIND_DOCK: return SimConfig.COST_DOCK_WOOD;
            case Features.KIND_MARKET: return SimConfig.COST_MARKET_WOOD;
            case Features.KIND_TEMPLE: return SimConfig.COST_TEMPLE_WOOD;
            case Features.KIND_BARRACKS: return SimConfig.COST_BARRACKS_WOOD;
            default: return 0;
        }
    }

    public static int stoneCost(byte kind) {
        switch (kind) {
            case Features.KIND_MINE: return SimConfig.COST_MINE_STONE;
            case Features.KIND_MARKET: return SimConfig.COST_MARKET_STONE;
            case Features.KIND_TEMPLE: return SimConfig.COST_TEMPLE_STONE;
            case Features.KIND_BARRACKS: return SimConfig.COST_BARRACKS_STONE;
            case Features.KIND_WALL: return SimConfig.COST_WALL_STONE;
            default: return 0;
        }
    }

    /** How long a kind takes to build, in village-pass ticks. */
    public static int buildTime(byte kind) {
        switch (kind) {
            case Features.KIND_HOUSE: return SimConfig.BUILD_TIME_HOUSE;
            case Features.KIND_FARM: return SimConfig.BUILD_TIME_FARM;
            case Features.KIND_LUMBER_CAMP: return SimConfig.BUILD_TIME_LUMBER;
            case Features.KIND_MINE: return SimConfig.BUILD_TIME_MINE;
            case Features.KIND_DOCK: return SimConfig.BUILD_TIME_DOCK;
            case Features.KIND_MARKET: return SimConfig.BUILD_TIME_MARKET;
            case Features.KIND_TEMPLE: return SimConfig.BUILD_TIME_TEMPLE;
            case Features.KIND_BARRACKS: return SimConfig.BUILD_TIME_BARRACKS;
            case Features.KIND_WALL: return SimConfig.BUILD_TIME_WALL;
            default: return 0;
        }
    }

    /** True if the village has both the wood and the stone to place this. */
    public static boolean canAfford(Villages villages, int v, byte kind) {
        return villages.wood[v] >= woodCost(kind)
            && villages.stone[v] >= stoneCost(kind);
    }

    /** Deducts the cost from the village's stockpiles. Caller must have checked. */
    public static void charge(Villages villages, int v, byte kind) {
        villages.wood[v] -= woodCost(kind);
        villages.stone[v] -= stoneCost(kind);
    }
}
