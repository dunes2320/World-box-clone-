package com.game.sim;

/**
 * All units in the world, stored struct-of-arrays with an int free list.
 *
 * <p>There is no {@code Unit} object anywhere. At a few thousand units updated
 * ten times a second, one object each would mean a few thousand pointer chases
 * per pass and a garbage churn every time the population turned over. Parallel
 * primitive arrays keep each pass linear through memory and allocation-free.
 *
 * <p>Slots are handed out from a free list and returned on death, so indices
 * stay stable for as long as a unit lives - which lets the renderer and the
 * inspector refer to a unit by index without worrying about compaction moving
 * it out from under them.
 */
public final class Units {

    // Behaviour states. Set by UnitSystem each tick, then possibly overridden by
    // CombatSystem, which runs after it - so a unit in a fight reads as fighting
    // rather than as whatever it was doing when the enemy arrived.
    public static final byte STATE_WANDER = 0;
    public static final byte STATE_SEEK_FOOD = 1;
    public static final byte STATE_FIGHT = 2;

    public final int capacity;

    public final float[] x;
    public final float[] z;
    /** Direction of travel in radians; re-rolled when the wander timer expires. */
    public final float[] heading;

    public final byte[] species;
    public final byte[] state;
    public final byte[] hunger;
    public final byte[] wanderTimer;
    /**
     * Infection state, three meanings in one byte: above zero is the ticks of
     * illness left, {@link #HEALTHY} is susceptible, {@link #IMMUNE} is a
     * survivor who cannot catch it again.
     */
    public final byte[] disease;

    public final short[] health;
    public final short[] age;
    public final short[] maxAge;
    public final short[] homeVillage;

    public final boolean[] alive;

    private final int[] freeList;
    private int freeCount;

    /**
     * One past the highest slot ever allocated. Iteration runs to here rather
     * than to capacity, so a world with fifty units does not walk three
     * thousand array entries every tick.
     */
    private int highWater;
    private int liveCount;

    public static final short NO_VILLAGE = -1;

    /** Susceptible: never been infected. */
    public static final byte HEALTHY = 0;
    /** Recovered from an infection and cannot catch it again. */
    public static final byte IMMUNE = -1;

    public Units(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;

        x = new float[capacity];
        z = new float[capacity];
        heading = new float[capacity];
        species = new byte[capacity];
        state = new byte[capacity];
        hunger = new byte[capacity];
        wanderTimer = new byte[capacity];
        disease = new byte[capacity];
        health = new short[capacity];
        age = new short[capacity];
        maxAge = new short[capacity];
        homeVillage = new short[capacity];
        alive = new boolean[capacity];

        freeList = new int[capacity];
        // Filled in reverse so the first allocations come back as 0, 1, 2...
        // That keeps the high-water mark tight and the live units packed near
        // the front of the arrays, which is what makes the bounded iteration
        // above worth having.
        for (int i = 0; i < capacity; i++) {
            freeList[i] = capacity - 1 - i;
        }
        freeCount = capacity;
    }

    public int getLiveCount() {
        return liveCount;
    }

    /** One past the highest slot ever used; the bound for iteration. */
    public int getHighWater() {
        return highWater;
    }

    public boolean isAlive(int index) {
        return index >= 0 && index < capacity && alive[index];
    }

    public boolean isFull() {
        return freeCount == 0;
    }

    /**
     * Claims a slot and initialises a unit in it.
     *
     * @return the unit's index, or -1 if the pool is full
     */
    public int spawn(float worldX, float worldZ, byte speciesId, int lifespan, float initialHeading) {
        if (freeCount == 0) {
            return -1;
        }
        int index = freeList[--freeCount];

        x[index] = worldX;
        z[index] = worldZ;
        heading[index] = initialHeading;
        species[index] = speciesId;
        state[index] = STATE_WANDER;
        hunger[index] = 0;
        wanderTimer[index] = 0;
        // Newborns are susceptible, so a plague can return to a population that
        // has already survived one - once the generation that was immune to it
        // has died of old age.
        disease[index] = HEALTHY;
        health[index] = (short) SimConfig.UNIT_MAX_HEALTH;
        age[index] = 0;
        maxAge[index] = (short) lifespan;
        homeVillage[index] = NO_VILLAGE;
        alive[index] = true;

        liveCount++;
        if (index >= highWater) {
            highWater = index + 1;
        }
        return index;
    }

    /** Returns a slot to the pool. Killing an already-dead unit is a no-op. */
    public void kill(int index) {
        if (!isAlive(index)) {
            return;
        }
        alive[index] = false;
        freeList[freeCount++] = index;
        liveCount--;
    }

    /** Removes every unit and resets the pool to its initial state. */
    public void clear() {
        for (int i = 0; i < highWater; i++) {
            alive[i] = false;
        }
        for (int i = 0; i < capacity; i++) {
            freeList[i] = capacity - 1 - i;
        }
        freeCount = capacity;
        liveCount = 0;
        highWater = 0;
    }

    /** Live units of one species. */
    public int countOf(byte speciesId) {
        int count = 0;
        for (int i = 0; i < highWater; i++) {
            if (alive[i] && species[i] == speciesId) {
                count++;
            }
        }
        return count;
    }

    /** Counts live units per species into {@code out}, indexed by species id. */
    public void countBySpecies(int[] out) {
        if (out.length < Species.COUNT) {
            throw new IllegalArgumentException(
                "out must hold at least " + Species.COUNT + " entries, got " + out.length);
        }
        java.util.Arrays.fill(out, 0, Species.COUNT, 0);
        for (int i = 0; i < highWater; i++) {
            if (alive[i]) {
                out[species[i]]++;
            }
        }
    }
}
