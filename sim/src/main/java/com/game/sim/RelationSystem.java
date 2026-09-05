package com.game.sim;

import java.util.Random;

/**
 * Measures how much the species are rubbing against each other and feeds that
 * to {@link Relations}.
 *
 * <p>Wars here are caused by geography rather than dice. Two species whose
 * territories interlock share hundreds of border tiles and sour fast; two on
 * opposite coasts never do, and drift around neutral forever. That is what
 * makes a war readable on the map before it is readable in the panel - you can
 * see the pressure building where the colours meet.
 *
 * <p>An instance rather than a static utility because it owns a reusable
 * contact buffer, the same reason {@link Territory} is one.
 */
public final class RelationSystem {

    /** Contested border tiles per species pair, indexed like the relations matrix. */
    private final int[] contacts = new int[Species.COUNT * Species.COUNT];

    /**
     * One diplomatic pass. Expects to be called every
     * {@link SimConfig#RELATION_UPDATE_INTERVAL} ticks.
     */
    public void update(World world, Villages villages, Relations relations, Random random, int tick) {
        countContacts(world, villages, contacts);
        relations.update(contacts, random, tick);
    }

    /** Contested border tiles per pair from the last pass, for the readout. */
    public int contactsBetween(byte a, byte b) {
        return contacts[a * Species.COUNT + b];
    }

    /**
     * Counts adjacent pairs of tiles owned by villages of different species.
     *
     * <p>Only the east and south neighbours are checked. Every adjacency has
     * two tiles and would otherwise be counted from both ends; looking one way
     * along each axis visits each shared edge exactly once.
     */
    private static void countContacts(World world, Villages villages, int[] out) {
        java.util.Arrays.fill(out, 0);
        for (int z = 0; z < world.size; z++) {
            for (int x = 0; x < world.size; x++) {
                int here = speciesOwning(world, villages, world.index(x, z));
                if (here < 0) {
                    continue;
                }
                if (x + 1 < world.size) {
                    tally(out, here, speciesOwning(world, villages, world.index(x + 1, z)));
                }
                if (z + 1 < world.size) {
                    tally(out, here, speciesOwning(world, villages, world.index(x, z + 1)));
                }
            }
        }
    }

    private static void tally(int[] out, int here, int there) {
        if (there < 0 || there == here) {
            return;
        }
        out[here * Species.COUNT + there]++;
        out[there * Species.COUNT + here]++;
    }

    /** The species owning a tile, or -1 for unclaimed ground. */
    private static int speciesOwning(World world, Villages villages, int tileIndex) {
        short owner = world.ownerVillage[tileIndex];
        if (owner == World.NO_OWNER || !villages.isAlive(owner)) {
            return -1;
        }
        return villages.species[owner];
    }
}
