package com.game.sim;

/**
 * Personal traits packed into one byte per unit. Five flags cover the
 * spread from cowardly to brave, honest to greedy, hale to sickly, weak
 * to strong, and sterile to fertile. Traits inherit imperfectly at birth
 * and drive small differences in combat, disease and breeding without
 * needing per-unit stat lines.
 *
 * <p>Kept as bit flags so the whole trait sheet lives in the same
 * {@code byte[]} the other {@link Units} fields do, one array probe per
 * lookup, no allocation on read.
 */
public final class Traits {

    private Traits() {
    }

    public static final byte BRAVE = 1 << 0;
    public static final byte GREEDY = 1 << 1;
    public static final byte SICKLY = 1 << 2;
    public static final byte STRONG = 1 << 3;
    public static final byte FERTILE = 1 << 4;

    /**
     * Rolls a fresh trait sheet with each flag independently drawn at a
     * modest base chance. Callers biasing off parents use {@link #inherit}
     * instead.
     */
    public static byte roll(java.util.Random random) {
        byte flags = 0;
        for (byte t : new byte[]{BRAVE, GREEDY, SICKLY, STRONG, FERTILE}) {
            if (random.nextDouble() < SimConfig.TRAIT_BASE_CHANCE) flags |= t;
        }
        return flags;
    }

    /**
     * Inherits trait flags from two parents with a small mutation rate.
     * A flag present in both parents almost always carries; in one parent
     * carries about half the time; absent in both is a rare de-novo roll.
     * Passing {@code -1} for a parent skips it - a wanderer with one
     * unknown parent still inherits from the one you have.
     */
    public static byte inherit(byte parentA, byte parentB, java.util.Random random) {
        byte flags = 0;
        for (byte t : new byte[]{BRAVE, GREEDY, SICKLY, STRONG, FERTILE}) {
            boolean fromA = parentA != -1 && (parentA & t) != 0;
            boolean fromB = parentB != -1 && (parentB & t) != 0;
            double chance;
            if (fromA && fromB) chance = SimConfig.TRAIT_BOTH_PARENTS_CHANCE;
            else if (fromA || fromB) chance = SimConfig.TRAIT_ONE_PARENT_CHANCE;
            else chance = SimConfig.TRAIT_MUTATION_CHANCE;
            if (random.nextDouble() < chance) flags |= t;
        }
        return flags;
    }

    public static boolean has(byte flags, byte trait) {
        return (flags & trait) != 0;
    }

    /** Human-readable list, e.g. "brave strong". Empty for a plain unit. */
    public static String describe(byte flags) {
        if (flags == 0) return "";
        StringBuilder sb = new StringBuilder();
        if (has(flags, BRAVE)) sb.append("brave ");
        if (has(flags, GREEDY)) sb.append("greedy ");
        if (has(flags, SICKLY)) sb.append("sickly ");
        if (has(flags, STRONG)) sb.append("strong ");
        if (has(flags, FERTILE)) sb.append("fertile ");
        return sb.toString().trim();
    }
}
