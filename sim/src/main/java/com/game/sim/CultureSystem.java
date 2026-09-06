package com.game.sim;

/**
 * Advances every kingdom's culture: knowledge accrues from population
 * and culture-generating buildings (markets and temples), and eras
 * advance when {@link Kingdoms#knowledge} crosses each next threshold.
 *
 * <p>Runs on the village clock. Cheap: one pass over kingdoms plus one
 * pass over features to count temples and markets per kingdom.
 */
public final class CultureSystem {

    private CultureSystem() {
    }

    /** How many kingdoms advanced this pass, for tests and history. */
    public static int update(Villages villages, Kingdoms kingdoms, Features features) {
        int[] templesPerKingdom = new int[kingdoms.capacity];
        int[] marketsPerKingdom = new int[kingdoms.capacity];
        int fEnd = features.getHighWater();
        for (int f = 0; f < fEnd; f++) {
            if (!features.isAlive(f) || features.buildTime[f] > 0) continue;
            byte kind = features.kind[f];
            if (kind != Features.KIND_TEMPLE && kind != Features.KIND_MARKET) continue;
            short v = features.owner[f];
            if (!villages.isAlive(v)) continue;
            short k = villages.kingdom[v];
            if (k == Villages.NO_KINGDOM || !kingdoms.isAlive(k)) continue;
            if (kind == Features.KIND_TEMPLE) templesPerKingdom[k]++;
            else marketsPerKingdom[k]++;
        }

        int advanced = 0;
        int kEnd = kingdoms.getHighWater();
        for (int k = 0; k < kEnd; k++) {
            if (!kingdoms.alive[k]) continue;
            int gain = kingdoms.population[k] / SimConfig.CULTURE_PEOPLE_PER_POINT
                + templesPerKingdom[k] * SimConfig.CULTURE_PER_TEMPLE
                + marketsPerKingdom[k] * SimConfig.CULTURE_PER_MARKET
                + SimConfig.CULTURE_BASE_TRICKLE;
            kingdoms.knowledge[k] += gain;
            while (kingdoms.era[k] < Era.MEDIEVAL
                && kingdoms.knowledge[k] >= Era.nextThreshold(kingdoms.era[k])) {
                kingdoms.era[k]++;
                advanced++;
            }
        }
        return advanced;
    }
}
