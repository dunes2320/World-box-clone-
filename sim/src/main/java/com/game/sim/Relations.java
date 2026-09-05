package com.game.sim;

import java.util.Random;

/**
 * How the four species feel about each other, and who is currently at war.
 *
 * <p>A symmetric {@code Species.COUNT} square matrix of values in
 * {@code [-1, +1]}: {@code +1} is allied warmth, {@code 0} indifference,
 * {@code -1} hatred. Symmetry is enforced by making {@link #set} the only way
 * to write a value and having it fill both halves - a one-sided grudge would be
 * a bug that only showed up as a war one species did not know it was in.
 *
 * <p>This class deliberately knows nothing about the world, villages or units.
 * It is handed the numbers that describe the situation - contested border tiles,
 * battlefield deaths - and turns them into feelings. {@link RelationSystem}
 * measures the situation; {@link CombatSystem} reports the deaths.
 */
public final class Relations {

    public static final float MIN = -1f;
    public static final float MAX = 1f;

    /** Row-major {@code a * Species.COUNT + b}, kept symmetric. */
    private final float[] value = new float[Species.COUNT * Species.COUNT];
    private final boolean[] atWar = new boolean[Species.COUNT * Species.COUNT];
    private final int[] warStartTick = new int[Species.COUNT * Species.COUNT];
    /** When the last war between a pair ended; border friction pauses for a while after. */
    private final int[] warEndTick = new int[Species.COUNT * Species.COUNT];
    /** Deaths in the current war only; reset when one is declared. */
    private final int[] warCasualties = new int[Species.COUNT * Species.COUNT];

    private boolean anyWar;
    private int warsDeclared;

    /**
     * Seeds the opening state. Every pair starts mildly friendly with a little
     * spread, drawn from the simulation's own generator so a given seed always
     * produces the same starting politics.
     */
    public Relations(Random random) {
        for (byte a = 0; a < Species.COUNT; a++) {
            value[a * Species.COUNT + a] = MAX;
            for (byte b = (byte) (a + 1); b < Species.COUNT; b++) {
                float spread = (random.nextFloat() * 2f - 1f) * SimConfig.RELATION_INITIAL_SPREAD;
                set(a, b, SimConfig.RELATION_START + spread);
            }
        }
    }

    private static int pair(byte a, byte b) {
        return a * Species.COUNT + b;
    }

    /** How {@code a} and {@code b} feel about each other, in {@code [-1, +1]}. */
    public float between(byte a, byte b) {
        return value[pair(a, b)];
    }

    public boolean isAtWar(byte a, byte b) {
        return atWar[pair(a, b)];
    }

    /** True if any pair anywhere is at war - lets combat skip its whole pass in peacetime. */
    public boolean anyWar() {
        return anyWar;
    }

    /** The tick the current war between these two started; meaningless if they are at peace. */
    public int warStartTick(byte a, byte b) {
        return warStartTick[pair(a, b)];
    }

    /** Deaths so far in the current war between these two. */
    public int warCasualties(byte a, byte b) {
        return warCasualties[pair(a, b)];
    }

    /** Total wars declared since the world began - a rough measure of how bloody it has been. */
    public int getWarsDeclared() {
        return warsDeclared;
    }

    /**
     * Sets a pair's relation, clamped and written to both halves of the matrix.
     * Self-relations are ignored: a species is not at war with itself, and
     * letting that entry move would put a meaningless number in the readout.
     */
    public void set(byte a, byte b, float newValue) {
        if (a == b) {
            return;
        }
        float clamped = newValue < MIN ? MIN : (newValue > MAX ? MAX : newValue);
        value[pair(a, b)] = clamped;
        value[pair(b, a)] = clamped;
    }

    /**
     * Reports a battlefield death to the pair responsible for it. Casualties
     * deepen the grudge, which is what lets a bloody war outlast a phoney one:
     * weariness is pushing relations back up the whole time, and every death
     * pushes back.
     */
    public void recordCasualty(byte victim, byte killer) {
        if (victim == killer) {
            return;
        }
        warCasualties[pair(victim, killer)]++;
        warCasualties[pair(killer, victim)]++;
        set(victim, killer, between(victim, killer) - SimConfig.RELATION_CASUALTY_GRUDGE);
    }

    /**
     * One diplomatic pass: drift every pair, then check the war and peace
     * thresholds.
     *
     * @param contacts contested border tiles per pair, indexed like the matrix
     * @param tick     the current simulation tick, recorded on war declarations
     */
    public void update(int[] contacts, Random random, int tick) {
        if (contacts.length < value.length) {
            throw new IllegalArgumentException(
                "contacts must hold " + value.length + " entries, got " + contacts.length);
        }
        anyWar = false;

        for (byte a = 0; a < Species.COUNT; a++) {
            for (byte b = (byte) (a + 1); b < Species.COUNT; b++) {
                int p = pair(a, b);
                float v = value[p] + (random.nextFloat() * 2f - 1f) * SimConfig.RELATION_DRIFT;

                if (atWar[p]) {
                    // War weariness. Border friction is deliberately not applied
                    // while fighting: the grudge is already at the bottom of the
                    // scale, and letting the longest border keep pushing down
                    // would mean the two species most likely to go to war are
                    // the two that could never stop.
                    v += SimConfig.RELATION_WAR_WEARINESS;
                } else if (tick - warEndTick[p] >= SimConfig.WAR_COOLDOWN_TICKS) {
                    int contested = Math.min(contacts[p], SimConfig.RELATION_FRICTION_CAP_TILES);
                    v -= contested * SimConfig.RELATION_BORDER_FRICTION;
                }
                set(a, b, v);

                if (atWar[p]) {
                    boolean exhausted = tick - warStartTick[p] >= SimConfig.MAX_WAR_TICKS;
                    if (value[p] >= SimConfig.PEACE_THRESHOLD || exhausted) {
                        makePeace(a, b, tick);
                    }
                } else if (value[p] <= SimConfig.WAR_THRESHOLD) {
                    declareWar(a, b, tick);
                }
                if (atWar[p]) {
                    anyWar = true;
                }
            }
        }
    }

    private void declareWar(byte a, byte b, int tick) {
        int p = pair(a, b);
        atWar[p] = true;
        atWar[pair(b, a)] = true;
        warStartTick[p] = tick;
        warStartTick[pair(b, a)] = tick;
        warCasualties[p] = 0;
        warCasualties[pair(b, a)] = 0;
        warsDeclared++;
    }

    private void makePeace(byte a, byte b, int tick) {
        int p = pair(a, b);
        atWar[p] = false;
        atWar[pair(b, a)] = false;
        warEndTick[p] = tick;
        warEndTick[pair(b, a)] = tick;
        // Agreeing to stop is worth something in itself, so peace pays a little
        // better than the threshold that triggered it. It also lifts a war that
        // was stopped by the clock out of the war band, which it would
        // otherwise re-enter on the very next pass.
        set(a, b, SimConfig.RELATION_POST_WAR);
    }
}
