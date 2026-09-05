package com.game.sim;

import java.util.Random;

/**
 * Founding, joining, growth and abandonment of villages.
 *
 * <p>Runs on a slower cadence than unit behaviour - territory does not need
 * recomputing ten times a second, and settlements should feel like they take a
 * while to establish themselves.
 */
public final class VillageSystem {

    private VillageSystem() {
    }

    /**
     * One village pass. Expects to be called every
     * {@link SimConfig#VILLAGE_UPDATE_INTERVAL} ticks.
     */
    public static void update(World world, Units units, Villages villages, Territory territory,
                              DensityGrid density, Random random, int tick) {
        recountPopulations(units, villages);
        abandonEmpty(units, villages);
        growRadii(villages);
        joinNearby(units, villages);
        tryFound(world, units, villages, density, random, tick);
        territory.recompute(world, villages);
    }

    /**
     * Recounts village membership from the units themselves, and clears any
     * membership pointing at a village that no longer exists - otherwise a
     * dead village's index could later be reused and silently inherit its
     * former residents.
     */
    private static void recountPopulations(Units units, Villages villages) {
        for (int v = 0; v < villages.getHighWater(); v++) {
            villages.population[v] = 0;
        }
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!units.alive[i]) {
                continue;
            }
            short home = units.homeVillage[i];
            if (home == Units.NO_VILLAGE) {
                continue;
            }
            if (!villages.isAlive(home) || villages.species[home] != units.species[i]) {
                units.homeVillage[i] = Units.NO_VILLAGE;
                continue;
            }
            villages.population[home]++;
        }
    }

    private static void abandonEmpty(Units units, Villages villages) {
        for (int v = 0; v < villages.getHighWater(); v++) {
            if (villages.alive[v] && villages.population[v] == 0) {
                villages.abandon(v);
                // Membership is cleared on the next recount; nothing points at
                // this slot right now because its population was zero.
            }
        }
        // Guard against a reused slot inheriting stale members.
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (units.alive[i] && units.homeVillage[i] != Units.NO_VILLAGE
                && !villages.isAlive(units.homeVillage[i])) {
                units.homeVillage[i] = Units.NO_VILLAGE;
            }
        }
    }

    /** Territory grows with population, up to a ceiling. */
    private static void growRadii(Villages villages) {
        for (int v = 0; v < villages.getHighWater(); v++) {
            if (!villages.alive[v]) {
                continue;
            }
            float target = SimConfig.VILLAGE_BASE_RADIUS
                + (float) Math.sqrt(villages.population[v]) * SimConfig.VILLAGE_RADIUS_PER_POP;
            target = Math.min(target, SimConfig.VILLAGE_MAX_RADIUS);
            // Ease towards the target so borders creep outward visibly instead
            // of snapping the moment a population ticks over.
            villages.radius[v] += (target - villages.radius[v]) * SimConfig.VILLAGE_RADIUS_EASING;
        }
    }

    /** Homeless units standing inside a village of their own species join it. */
    private static void joinNearby(Units units, Villages villages) {
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!units.alive[i] || units.homeVillage[i] != Units.NO_VILLAGE) {
                continue;
            }
            if (units.age[i] < SimConfig.UNIT_MATURITY) {
                continue;
            }
            for (int v = 0; v < villages.getHighWater(); v++) {
                if (!villages.alive[v] || villages.species[v] != units.species[i]) {
                    continue;
                }
                float radius = villages.radius[v];
                if (villages.distanceSquaredTo(v, units.x[i], units.z[i]) <= radius * radius) {
                    units.homeVillage[i] = (short) v;
                    break;
                }
            }
        }
    }

    /**
     * Homeless adults standing on good ground, among enough of their own kind,
     * occasionally found a new village.
     *
     * <p>The "enough of their own kind" test is what stops the map filling with
     * one-person hamlets: settling is something a group does, so a lone wanderer
     * crossing empty country keeps walking.
     */
    private static void tryFound(World world, Units units, Villages villages,
                                 DensityGrid density, Random random, int tick) {
        if (villages.isFull()) {
            return;
        }
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!units.alive[i] || units.homeVillage[i] != Units.NO_VILLAGE) {
                continue;
            }
            if (units.age[i] < SimConfig.UNIT_MATURITY) {
                continue;
            }
            if (units.hunger[i] > SimConfig.HUNGER_FED) {
                continue;
            }
            if (random.nextDouble() >= SimConfig.VILLAGE_FOUND_CHANCE) {
                continue;
            }

            int tileX = (int) Math.floor(units.x[i]);
            int tileZ = (int) Math.floor(units.z[i]);
            if (!world.inBounds(tileX, tileZ)) {
                continue;
            }
            byte type = world.typeAt(tileX, tileZ);
            if (type != TileType.GRASS && type != TileType.FOREST) {
                continue;
            }
            if (density.speciesAt(units.x[i], units.z[i], units.species[i])
                < SimConfig.VILLAGE_FOUND_MIN_NEARBY) {
                continue;
            }
            // Keep settlements apart regardless of species, so villages read as
            // distinct places rather than one sprawl with several centres.
            if (villages.nearestTo(units.x[i], units.z[i], SimConfig.VILLAGE_MIN_SPACING) >= 0) {
                continue;
            }

            int founded = villages.found(tileX, tileZ, units.species[i], tick);
            if (founded < 0) {
                return;
            }
            units.homeVillage[i] = (short) founded;
            if (villages.isFull()) {
                return;
            }
        }
    }
}
