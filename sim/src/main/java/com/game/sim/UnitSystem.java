package com.game.sim;

import java.util.Random;

/**
 * Per-tick unit behaviour: wander, eat, age, starve, breed, die.
 *
 * <p>One linear pass over the pool, no allocation, and every random draw comes
 * from the simulation's single seeded {@link Random} in array order - which is
 * what keeps a thousand-tick run reproducible from the seed alone.
 */
public final class UnitSystem {

    private UnitSystem() {
    }

    /**
     * Advances every living unit by one tick.
     *
     * <p>Births are appended to the pool as they happen. Because slots are
     * handed out from a free list, a newborn can land on an index below the
     * one currently being iterated - in which case it simply waits for the next
     * tick, which is correct: a unit born this instant should not also act this
     * instant.
     */
    public static void update(World world, Units units, DensityGrid density, Random random) {
        density.rebuild(units);
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!units.alive[i]) {
                continue;
            }

            // --- ageing ---
            int age = units.age[i] + 1;
            units.age[i] = (short) Math.min(age, Short.MAX_VALUE);
            if (age >= units.maxAge[i]) {
                units.kill(i);
                continue;
            }

            // --- feeding ---
            int food = foodAt(world, units.x[i], units.z[i]);
            int hunger = units.hunger[i] + 1 - food;
            if (hunger < 0) {
                hunger = 0;
            }
            if (hunger > SimConfig.HUNGER_STARVING) {
                hunger = SimConfig.HUNGER_STARVING;
                units.health[i] -= SimConfig.STARVATION_DAMAGE;
                if (units.health[i] <= 0) {
                    units.kill(i);
                    continue;
                }
            } else if (food > 0 && units.health[i] < SimConfig.UNIT_MAX_HEALTH) {
                // Recover slowly once back on good ground, so one bad crossing
                // of a beach is a setback rather than a death sentence.
                units.health[i]++;
            }
            units.hunger[i] = (byte) hunger;
            units.state[i] = hunger > SimConfig.HUNGER_FED ? Units.STATE_SEEK_FOOD : Units.STATE_WANDER;

            move(world, units, i, random);

            // --- breeding ---
            if (units.getLiveCount() < SimConfig.POPULATION_CAP
                && age >= SimConfig.UNIT_MATURITY
                && hunger <= SimConfig.HUNGER_FED) {
                // Density-dependent breeding. Two ceilings: how packed the
                // region is overall, and - biting harder - how many of this
                // unit's own kind are already here. The second is what lets
                // four species coexist instead of the fastest breeder taking
                // the map; see DensityGrid for the measurements behind it.
                byte speciesId = units.species[i];
                int sameKind = density.speciesAt(units.x[i], units.z[i], speciesId);
                int allKinds = density.totalAt(units.x[i], units.z[i]);
                if (sameKind < SimConfig.SPECIES_CROWDING_LIMIT
                    && allKinds < SimConfig.LOCAL_CROWDING_LIMIT) {
                    double ownRoom = 1.0 - sameKind / (double) SimConfig.SPECIES_CROWDING_LIMIT;
                    double sharedRoom = 1.0 - allKinds / (double) SimConfig.LOCAL_CROWDING_LIMIT;
                    double chance = SimConfig.REPRODUCE_CHANCE
                        * Species.fertility(speciesId)
                        * Math.min(ownRoom, sharedRoom);
                    if (random.nextDouble() < chance) {
                        breed(world, units, i, random);
                    }
                }
            }
        }
    }

    /**
     * Wanders. A unit holds a heading for a while, then picks a new one; if the
     * next step would leave walkable ground it turns instead of stepping, so
     * units never end up standing in the sea or on a bare peak.
     */
    private static void move(World world, Units units, int i, Random random) {
        int timer = units.wanderTimer[i] - 1;
        if (timer <= 0) {
            units.heading[i] = (float) (random.nextDouble() * Math.PI * 2.0);
            timer = SimConfig.WANDER_MIN_TICKS
                + random.nextInt(SimConfig.WANDER_MAX_TICKS - SimConfig.WANDER_MIN_TICKS);
        }
        units.wanderTimer[i] = (byte) Math.min(timer, Byte.MAX_VALUE);

        float heading = units.heading[i];
        float nextX = units.x[i] + (float) Math.sin(heading) * SimConfig.UNIT_SPEED;
        float nextZ = units.z[i] + (float) Math.cos(heading) * SimConfig.UNIT_SPEED;

        if (isWalkable(world, nextX, nextZ)) {
            units.x[i] = nextX;
            units.z[i] = nextZ;
        } else {
            // Turn away rather than stopping dead, and re-roll soon, so units
            // do not pile up along a coastline all facing the water.
            units.heading[i] = (float) (heading + Math.PI * 0.5 + random.nextDouble());
            units.wanderTimer[i] = (byte) 4;
        }
    }

    /** Places a newborn on walkable ground next to its parent, if there is any. */
    private static void breed(World world, Units units, int parent, Random random) {
        for (int attempt = 0; attempt < 6; attempt++) {
            float angle = (float) (random.nextDouble() * Math.PI * 2.0);
            float distance = 0.6f + (float) random.nextDouble() * 1.4f;
            float childX = units.x[parent] + (float) Math.sin(angle) * distance;
            float childZ = units.z[parent] + (float) Math.cos(angle) * distance;
            if (!isWalkable(world, childX, childZ)) {
                continue;
            }
            byte species = units.species[parent];
            units.spawn(childX, childZ, species, lifespanFor(species, random), angle);
            return;
        }
    }

    /** A species' base lifespan with per-individual variation. */
    public static int lifespanFor(byte species, Random random) {
        int base = Species.baseLifespan(species);
        int spread = random.nextInt(SimConfig.LIFESPAN_VARIANCE * 2 + 1) - SimConfig.LIFESPAN_VARIANCE;
        return Math.max(400, base + spread);
    }

    /**
     * Scatters units of one species across the walkable tiles under a brush.
     *
     * <p>Returns how many actually got placed, which can be fewer than asked
     * for if the area is mostly water or the pool is full.
     */
    public static int spawnBrush(World world, Units units, Random random,
                                 int centreX, int centreZ, int radius, byte species, int count) {
        int placed = 0;
        int attempts = 0;
        int maxAttempts = count * 12;
        float r = Math.max(1, radius);

        while (placed < count && attempts < maxAttempts) {
            attempts++;
            if (units.isFull()) {
                break;
            }
            // Rejection-sample the disc rather than sampling polar coordinates,
            // which would bunch spawns towards the centre.
            float dx = (float) (random.nextDouble() * 2.0 - 1.0) * r;
            float dz = (float) (random.nextDouble() * 2.0 - 1.0) * r;
            if (dx * dx + dz * dz > r * r) {
                continue;
            }
            float worldX = centreX + 0.5f + dx;
            float worldZ = centreZ + 0.5f + dz;
            if (!isWalkable(world, worldX, worldZ)) {
                continue;
            }
            float heading = (float) (random.nextDouble() * Math.PI * 2.0);
            int index = units.spawn(worldX, worldZ, species, lifespanFor(species, random), heading);
            if (index < 0) {
                break;
            }
            // Stagger starting ages so a spawned group does not later die of
            // old age all at once, leaving a hole in the population curve.
            units.age[index] = (short) random.nextInt(SimConfig.UNIT_MATURITY);
            placed++;
        }
        return placed;
    }

    /** Hunger removed per tick by the ground at a position. Barren ground gives nothing. */
    private static int foodAt(World world, float worldX, float worldZ) {
        int tileX = (int) Math.floor(worldX);
        int tileZ = (int) Math.floor(worldZ);
        if (!world.inBounds(tileX, tileZ)) {
            return 0;
        }
        byte type = world.typeAt(tileX, tileZ);
        if (type == TileType.FOREST) {
            return SimConfig.FOOD_FROM_FOREST;
        }
        if (type == TileType.GRASS) {
            return SimConfig.FOOD_FROM_GRASS;
        }
        return 0;
    }

    public static boolean isWalkable(World world, float worldX, float worldZ) {
        int tileX = (int) Math.floor(worldX);
        int tileZ = (int) Math.floor(worldZ);
        if (!world.inBounds(tileX, tileZ)) {
            return false;
        }
        return TileType.isWalkable(world.typeAt(tileX, tileZ));
    }

    /**
     * Kills any unit left standing somewhere it cannot be - the player dropped
     * an ocean on it, or raised a mountain under it. Called after terraforming
     * rather than every tick, since it is a reaction to an edit, not a
     * behaviour.
     */
    public static int cullStranded(World world, Units units) {
        int culled = 0;
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (units.alive[i] && !isWalkable(world, units.x[i], units.z[i])) {
                units.kill(i);
                culled++;
            }
        }
        return culled;
    }
}
