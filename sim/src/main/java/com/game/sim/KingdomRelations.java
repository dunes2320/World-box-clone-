package com.game.sim;

/**
 * How a specific kingdom feels about every other kingdom, and who is
 * currently at war with whom.
 *
 * <p>Same shape as species-level {@link Relations}: a symmetric square
 * matrix in {@code [-1, +1]}, plus a war bit per pair. Species-level
 * relations still track the world's mood between the four races; this is
 * the kingdom layer's own numbers, seeded from
 * {@link Species#affinity(byte, byte)} when a pair first meets and
 * drifted by border friction and battlefield deaths from that pair alone.
 *
 * <p>Sized off the kingdom pool capacity rather than the live count, so
 * indices are stable: dissolving kingdom 3 does not renumber anyone.
 * Rows for a dissolved kingdom are simply skipped when the caller checks
 * {@link Kingdoms#isAlive(int)}.
 */
public final class KingdomRelations {

    public static final float MIN = -1f;
    public static final float MAX = 1f;

    private final int capacity;
    private final float[] value;
    private final boolean[] atWar;
    private final int[] warStartTick;
    private final int[] warEndTick;
    private final boolean[] seeded;

    private boolean anyWar;
    private int warsDeclared;

    public KingdomRelations(int capacity) {
        this.capacity = capacity;
        int cells = capacity * capacity;
        value = new float[cells];
        atWar = new boolean[cells];
        warStartTick = new int[cells];
        warEndTick = new int[cells];
        seeded = new boolean[cells];
    }

    public int capacity() {
        return capacity;
    }

    private int pair(int a, int b) {
        return a * capacity + b;
    }

    /**
     * Seeds a pair from the species affinity of their owning species. Called
     * lazily the first time the two are compared: kingdoms that never
     * encounter each other pay nothing to keep numbers on.
     */
    public void seedIfNew(int a, int b, byte speciesA, byte speciesB) {
        if (a == b || seeded[pair(a, b)]) {
            return;
        }
        float v = Species.affinity(speciesA, speciesB);
        value[pair(a, b)] = v;
        value[pair(b, a)] = v;
        seeded[pair(a, b)] = true;
        seeded[pair(b, a)] = true;
    }

    public float between(int a, int b) {
        return value[pair(a, b)];
    }

    public boolean isAtWar(int a, int b) {
        return atWar[pair(a, b)];
    }

    public boolean anyWar() {
        return anyWar;
    }

    public int warStartTick(int a, int b) {
        return warStartTick[pair(a, b)];
    }

    public int getWarsDeclared() {
        return warsDeclared;
    }

    /** Sets a pair's relation, clamped and mirrored. */
    public void set(int a, int b, float newValue) {
        if (a == b) {
            return;
        }
        float clamped = newValue < MIN ? MIN : (newValue > MAX ? MAX : newValue);
        value[pair(a, b)] = clamped;
        value[pair(b, a)] = clamped;
        seeded[pair(a, b)] = true;
        seeded[pair(b, a)] = true;
    }

    /** Damage nudges the pair toward hostility - a battlefield grudge is real. */
    public void recordCasualty(int a, int b) {
        if (a == b) {
            return;
        }
        set(a, b, between(a, b) - SimConfig.KINGDOM_CASUALTY_GRUDGE);
    }

    /**
     * Declares war between two kingdoms unconditionally. Used by the
     * relations pass when the number crosses the war threshold, and by
     * {@link KingdomSystem} on rare hard-triggered wars (a rebellion).
     */
    public void declareWar(int a, int b, int tick) {
        int p = pair(a, b);
        if (atWar[p]) {
            return;
        }
        atWar[p] = true;
        atWar[pair(b, a)] = true;
        warStartTick[p] = tick;
        warStartTick[pair(b, a)] = tick;
        warsDeclared++;
        anyWar = true;
    }

    public void makePeace(int a, int b, int tick) {
        int p = pair(a, b);
        if (!atWar[p]) {
            return;
        }
        atWar[p] = false;
        atWar[pair(b, a)] = false;
        warEndTick[p] = tick;
        warEndTick[pair(b, a)] = tick;
        // Same trick as species-level Relations: land above the threshold
        // that started the war, so the war does not immediately re-declare
        // on the next drift.
        set(a, b, SimConfig.RELATION_POST_WAR);
    }

    /** Zeroes every entry involving a dissolved kingdom, so a reused slot starts clean. */
    public void clearSlot(int index, int tick) {
        for (int j = 0; j < capacity; j++) {
            if (j == index) continue;
            int p = pair(index, j);
            if (atWar[p]) {
                atWar[p] = false;
                atWar[pair(j, index)] = false;
                warEndTick[p] = tick;
                warEndTick[pair(j, index)] = tick;
            }
            value[p] = 0f;
            value[pair(j, index)] = 0f;
            seeded[p] = false;
            seeded[pair(j, index)] = false;
        }
        recomputeAnyWar();
    }

    /**
     * Drifts every live pair and applies war/peace thresholds. Uses the
     * same hysteresis as species Relations to keep pairs from oscillating.
     */
    public void update(Kingdoms kingdoms, int tick, java.util.Random random) {
        anyWar = false;
        int end = kingdoms.getHighWater();
        for (int a = 0; a < end; a++) {
            if (!kingdoms.alive[a]) continue;
            for (int b = a + 1; b < end; b++) {
                if (!kingdoms.alive[b]) continue;
                int p = pair(a, b);
                if (!seeded[p]) {
                    seedIfNew(a, b, kingdoms.species[a], kingdoms.species[b]);
                }
                float v = value[p] + (random.nextFloat() * 2f - 1f) * SimConfig.KINGDOM_DRIFT;
                if (atWar[p]) {
                    v += SimConfig.KINGDOM_WAR_WEARINESS;
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

    private void recomputeAnyWar() {
        for (int i = 0; i < atWar.length; i++) {
            if (atWar[i]) {
                anyWar = true;
                return;
            }
        }
        anyWar = false;
    }
}
