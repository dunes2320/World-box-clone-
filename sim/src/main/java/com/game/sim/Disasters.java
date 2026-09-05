package com.game.sim;

import java.util.Random;

/**
 * The instant half of a disaster: what happens at the moment the player clicks.
 *
 * <p>Anything that then continues under its own steam - fire spreading through
 * a forest, a plague working through a population - is left to
 * {@link DisasterSystem}. So a meteor lands and digs its crater here, and the
 * fires it started burn there.
 *
 * <p>Every method takes the simulation's {@link Random}, so a disaster is as
 * reproducible from a seed as everything else. None of them allocate.
 */
public final class Disasters {

    private Disasters() {
    }

    /**
     * Applies one disaster.
     *
     * @return how many units it killed outright
     */
    public static int strike(Disaster kind, World world, Units units, Random random,
                             int tileX, int tileZ, int radius) {
        int r = Terraform.clampRadius(radius);
        switch (kind) {
            case METEOR:
                return meteor(world, units, random, tileX, tileZ, r);
            case LIGHTNING:
                return lightning(world, units, tileX, tileZ);
            case FIRE:
                return ignite(world, tileX, tileZ, r);
            case EARTHQUAKE:
                return earthquake(world, units, random, tileX, tileZ, r);
            case FLOOD:
                return flood(world, units, tileX, tileZ, r);
            case PLAGUE:
                return infect(units, tileX, tileZ, r);
            default:
                return 0;
        }
    }

    /**
     * A crater with a raised lip, a ring of burning forest, and nothing alive
     * at the bottom of it.
     */
    public static int meteor(World world, Units units, Random random,
                             int tileX, int tileZ, int radius) {
        forEachInRadius(world, tileX, tileZ, radius, (x, z, falloff) -> {
            // Dig in the middle, pile up at the rim: falloff is 1 at the centre
            // and 0 at the edge, so this crosses over about two thirds out and
            // the hole ends up ringed by its own spoil.
            float shaped = falloff * SimConfig.METEOR_DEPTH - (1f - falloff) * SimConfig.METEOR_RIM;
            world.setHeight(x, z, world.heightAt(x, z) - shaped);
        });

        // Fires spread wider than the impact, which is what turns a single
        // click into an event rather than a hole.
        int fireRadius = Math.round(radius * SimConfig.METEOR_FIRE_RADIUS);
        forEachInRadius(world, tileX, tileZ, fireRadius, (x, z, falloff) -> {
            if (random.nextDouble() < falloff) {
                igniteTile(world, x, z);
            }
        });

        return killWithin(units, tileX, tileZ, radius);
    }

    /** A pinpoint strike: lethal where it lands, and it starts a fire. */
    public static int lightning(World world, Units units, int tileX, int tileZ) {
        igniteTile(world, tileX, tileZ);
        return killWithin(units, tileX, tileZ, SimConfig.LIGHTNING_RADIUS);
    }

    /**
     * Sets light to every flammable tile under the brush.
     *
     * @return zero - fire kills over time, in {@link DisasterSystem}, not now
     */
    public static int ignite(World world, int tileX, int tileZ, int radius) {
        forEachInRadius(world, tileX, tileZ, radius, (x, z, falloff) -> igniteTile(world, x, z));
        return 0;
    }

    /** Lights a single tile if it has anything to burn. */
    public static void igniteTile(World world, int x, int z) {
        if (!world.inBounds(x, z)) {
            return;
        }
        int i = world.index(x, z);
        if (TileType.isFlammable(world.tileType[i]) && world.burn[i] == 0) {
            world.burn[i] = (byte) SimConfig.FIRE_DURATION;
            world.markDirty(x, z);
        }
    }

    /**
     * Throws the ground up and down. Land shoved below sea level floods, land
     * shoved up becomes hill or mountain - both handled by
     * {@link World#setHeight}, which reclassifies every tile it moves.
     */
    public static int earthquake(World world, Units units, Random random,
                                 int tileX, int tileZ, int radius) {
        forEachInRadius(world, tileX, tileZ, radius, (x, z, falloff) -> {
            float jolt = (float) (random.nextDouble() * 2.0 - 1.0)
                * SimConfig.QUAKE_AMPLITUDE * falloff;
            world.setHeight(x, z, world.heightAt(x, z) + jolt);
        });
        // Survivable, unlike a meteor: a quake hurts everyone standing on it
        // rather than erasing them, so the aftermath is a limping population.
        int killed = damageWithin(units, tileX, tileZ, radius, SimConfig.QUAKE_DAMAGE);
        return killed + UnitSystem.cullStranded(world, units);
    }

    /**
     * The sea comes in. Only ground already low enough to drown does - a flood
     * finds the coast and the river valleys by itself rather than carving a
     * disc, which is why it is worth having alongside the water brush.
     */
    public static int flood(World world, Units units, int tileX, int tileZ, int radius) {
        forEachInRadius(world, tileX, tileZ, radius, (x, z, falloff) -> {
            float height = world.heightAt(x, z);
            if (height >= SimConfig.SEA_LEVEL && height < SimConfig.FLOOD_LEVEL) {
                world.setHeight(x, z, SimConfig.SEA_LEVEL - 0.3f);
            }
        });
        return UnitSystem.cullStranded(world, units);
    }

    /**
     * Infects every susceptible unit under the brush. They then carry it to
     * everyone else - see {@link DisasterSystem}.
     *
     * @return how many were infected. Not a death toll: a plague kills slowly,
     *     over the following few hundred ticks, or not at all.
     */
    public static int infect(Units units, int tileX, int tileZ, int radius) {
        float centreX = tileX + 0.5f;
        float centreZ = tileZ + 0.5f;
        float radiusSquared = radius * (float) radius;
        int infected = 0;
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!units.alive[i] || units.disease[i] != Units.HEALTHY) {
                continue;
            }
            float dx = units.x[i] - centreX;
            float dz = units.z[i] - centreZ;
            if (dx * dx + dz * dz <= radiusSquared) {
                units.disease[i] = (byte) SimConfig.DISEASE_DURATION;
                infected++;
            }
        }
        return infected;
    }

    // ---- shared helpers ----

    /** Visits every in-bounds tile in a disc, with 1 at the centre falling to 0 at the rim. */
    private static void forEachInRadius(World world, int centreX, int centreZ, int radius,
                                        TileAction action) {
        int r = Math.max(1, radius);
        float radiusSquared = r * (float) r;
        for (int z = centreZ - r; z <= centreZ + r; z++) {
            for (int x = centreX - r; x <= centreX + r; x++) {
                if (!world.inBounds(x, z)) {
                    continue;
                }
                float dx = x - centreX;
                float dz = z - centreZ;
                float distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > radiusSquared) {
                    continue;
                }
                action.apply(x, z, 1f - (float) Math.sqrt(distanceSquared) / r);
            }
        }
    }

    private static int killWithin(Units units, int tileX, int tileZ, float radius) {
        float centreX = tileX + 0.5f;
        float centreZ = tileZ + 0.5f;
        float radiusSquared = radius * radius;
        int killed = 0;
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!units.alive[i]) {
                continue;
            }
            float dx = units.x[i] - centreX;
            float dz = units.z[i] - centreZ;
            if (dx * dx + dz * dz <= radiusSquared) {
                units.kill(i);
                killed++;
            }
        }
        return killed;
    }

    private static int damageWithin(Units units, int tileX, int tileZ, float radius, int damage) {
        float centreX = tileX + 0.5f;
        float centreZ = tileZ + 0.5f;
        float radiusSquared = radius * radius;
        int killed = 0;
        int end = units.getHighWater();
        for (int i = 0; i < end; i++) {
            if (!units.alive[i]) {
                continue;
            }
            float dx = units.x[i] - centreX;
            float dz = units.z[i] - centreZ;
            if (dx * dx + dz * dz > radiusSquared) {
                continue;
            }
            // Explicit cast: health is a short and damage is an int parameter,
            // so the compound form is a lossy conversion the compiler warns on.
            units.health[i] = (short) (units.health[i] - damage);
            if (units.health[i] <= 0) {
                units.kill(i);
                killed++;
            }
        }
        return killed;
    }

    /** Callback for {@link #forEachInRadius}; kept local so nothing allocates a lambda per tile. */
    private interface TileAction {
        void apply(int x, int z, float falloff);
    }
}
