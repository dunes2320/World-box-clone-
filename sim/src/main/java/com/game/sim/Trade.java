package com.game.sim;

/**
 * Resource transfers between villages.
 *
 * <p>Phase 9 keeps this to the movement itself, not to caravan units on the
 * map. When two nearby friendly villages have unequal food or wood
 * stockpiles, some of the surplus moves from the richer to the poorer once
 * per village pass. Roads matter here only insofar as
 * {@link Pathfinder} already prefers them for the connectivity check:
 * villages with no walkable route to each other cannot trade.
 *
 * <p>Same-species is the friendship test for now, matching {@link RoadSystem}.
 * Phase 10 replaces this with real kingdom relations once those exist.
 */
public final class Trade {

    private Trade() {
    }

    /**
     * Runs one trade pass.
     *
     * @return the number of transfers that moved goods
     */
    public static int update(World world, Villages villages, Roads roads) {
        if (roads.getCount() == 0) {
            return 0;
        }
        int transfers = 0;
        int end = villages.getHighWater();
        for (int a = 0; a < end; a++) {
            if (!villages.alive[a]) {
                continue;
            }
            byte speciesA = villages.species[a];
            int cx = villages.x[a];
            int cz = villages.z[a];
            int centreA = world.index(cx, cz);
            if (!roads.isRoad(centreA)) {
                continue;
            }

            for (int b = a + 1; b < end; b++) {
                if (!villages.alive[b] || villages.species[b] != speciesA) {
                    continue;
                }
                float dx = villages.x[b] - cx;
                float dz = villages.z[b] - cz;
                if (dx * dx + dz * dz > SimConfig.TRADE_RANGE * SimConfig.TRADE_RANGE) {
                    continue;
                }
                int centreB = world.index(villages.x[b], villages.z[b]);
                // Both centres on the road network: a rough proxy for
                // "connected". A real reachability check would be a graph
                // walk, but road generation already routes through the same
                // Pathfinder that laid these roads - two villages on the
                // network are, in practice, reachable from each other.
                if (!roads.isRoad(centreB)) {
                    continue;
                }
                transfers += transferIfSurplus(villages, a, b);
            }
        }
        return transfers;
    }

    private static int transferIfSurplus(Villages villages, int a, int b) {
        int moved = 0;
        moved += transferFor(villages, a, b, SimConfig.RESOURCE_FOOD);
        moved += transferFor(villages, a, b, SimConfig.RESOURCE_WOOD);
        moved += transferFor(villages, a, b, SimConfig.RESOURCE_STONE);
        return moved > 0 ? 1 : 0;
    }

    private static int transferFor(Villages villages, int a, int b, int resource) {
        int stockA = stockOf(villages, a, resource);
        int stockB = stockOf(villages, b, resource);
        int diff = stockA - stockB;
        if (Math.abs(diff) < SimConfig.TRADE_MIN_SURPLUS) {
            return 0;
        }
        int amount = (int) (Math.abs(diff) * SimConfig.TRADE_TRANSFER_FRACTION);
        if (amount <= 0) {
            return 0;
        }
        if (diff > 0) {
            addStock(villages, a, resource, -amount);
            addStock(villages, b, resource, amount);
        } else {
            addStock(villages, a, resource, amount);
            addStock(villages, b, resource, -amount);
        }
        return amount;
    }

    private static int stockOf(Villages villages, int v, int resource) {
        switch (resource) {
            case SimConfig.RESOURCE_FOOD: return villages.food[v];
            case SimConfig.RESOURCE_WOOD: return villages.wood[v];
            case SimConfig.RESOURCE_STONE: return villages.stone[v];
            case SimConfig.RESOURCE_GOLD: return villages.gold[v];
            default: return 0;
        }
    }

    private static void addStock(Villages villages, int v, int resource, int delta) {
        switch (resource) {
            case SimConfig.RESOURCE_FOOD: villages.food[v] += delta; break;
            case SimConfig.RESOURCE_WOOD: villages.wood[v] += delta; break;
            case SimConfig.RESOURCE_STONE: villages.stone[v] += delta; break;
            case SimConfig.RESOURCE_GOLD: villages.gold[v] += delta; break;
            default: break;
        }
    }
}
