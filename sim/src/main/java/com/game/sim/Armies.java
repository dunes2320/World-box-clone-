package com.game.sim;

/**
 * Kingdom armies - ordered groups of soldiers drawn from a kingdom's
 * villages. Unlike {@link Units}, an army is a single entity with a
 * position and a target: it marches toward a target village, besieges
 * it, and either wins (capturing the village) or is destroyed.
 *
 * <p>Same struct-of-arrays pool with a free list as every other pool in
 * the sim. Capacity is modest because armies are heavyweight: one per
 * kingdom per active war is roughly the ceiling anyone will ever need.
 */
public final class Armies {

    public final int capacity;

    public final boolean[] alive;
    public final short[] kingdom;
    public final float[] x;
    public final float[] z;
    /** Soldiers alive in this army; hits reduce it, and zero disbands. */
    public final int[] size;
    public final short[] targetVillage;
    public final byte[] state;
    public final int[] raisedTick;
    public final int[] stateTick;

    private final int[] freeList;
    private int freeCount;
    private int highWater;
    private int liveCount;

    // --- states ---
    public static final byte STATE_MARCHING = 0;
    public static final byte STATE_BESIEGING = 1;
    public static final byte STATE_RETREATING = 2;

    public Armies(int capacity) {
        if (capacity <= 0 || capacity > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                "capacity must be in 1.." + Short.MAX_VALUE + ", got " + capacity);
        }
        this.capacity = capacity;

        alive = new boolean[capacity];
        kingdom = new short[capacity];
        x = new float[capacity];
        z = new float[capacity];
        size = new int[capacity];
        targetVillage = new short[capacity];
        state = new byte[capacity];
        raisedTick = new int[capacity];
        stateTick = new int[capacity];

        freeList = new int[capacity];
        for (int i = 0; i < capacity; i++) {
            freeList[i] = capacity - 1 - i;
        }
        freeCount = capacity;
    }

    public int getLiveCount() {
        return liveCount;
    }

    public int getHighWater() {
        return highWater;
    }

    public boolean isAlive(int index) {
        return index >= 0 && index < capacity && alive[index];
    }

    public boolean isFull() {
        return freeCount == 0;
    }

    /** Raises a new army at a village centre, marching toward its target. */
    public int raise(int kingdomIndex, float startX, float startZ,
                     int soldiers, int targetVillageIndex, int tick) {
        if (freeCount == 0 || soldiers <= 0) {
            return -1;
        }
        int index = freeList[--freeCount];
        alive[index] = true;
        kingdom[index] = (short) kingdomIndex;
        x[index] = startX;
        z[index] = startZ;
        size[index] = soldiers;
        targetVillage[index] = (short) targetVillageIndex;
        state[index] = STATE_MARCHING;
        raisedTick[index] = tick;
        stateTick[index] = tick;

        liveCount++;
        if (index >= highWater) {
            highWater = index + 1;
        }
        return index;
    }

    /** Disbands an army - all soldiers dead, or a retreat completed. */
    public void disband(int index) {
        if (!isAlive(index)) {
            return;
        }
        alive[index] = false;
        size[index] = 0;
        freeList[freeCount++] = index;
        liveCount--;
    }

    /** Damage this army by n soldiers, disbanding if it hits zero. */
    public void applyLosses(int index, int losses) {
        if (!isAlive(index) || losses <= 0) {
            return;
        }
        size[index] -= losses;
        if (size[index] <= 0) {
            disband(index);
        }
    }

    public void setState(int index, byte newState, int tick) {
        if (!isAlive(index)) {
            return;
        }
        state[index] = newState;
        stateTick[index] = tick;
    }

    public void setTarget(int index, int villageIndex) {
        if (isAlive(index)) {
            targetVillage[index] = (short) villageIndex;
        }
    }
}
