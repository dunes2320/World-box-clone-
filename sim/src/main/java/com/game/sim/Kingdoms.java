package com.game.sim;

/**
 * The political layer above {@link Villages}. A kingdom owns one or more
 * villages of a single species, has a capital, a flag colour, and a name;
 * relations between kingdoms are tracked in {@link KingdomRelations}.
 *
 * <p>Villages join their species' nearest kingdom on their first kingdom
 * pass, or found a new kingdom if nothing near is within
 * {@link SimConfig#KINGDOM_JOIN_RANGE}. Kingdoms without any villages
 * die at the next pass, and their slot goes back to the free list - which
 * is what makes it safe for {@link Villages#kingdom} to store a short
 * index per village.
 *
 * <p>Same struct-of-arrays layout as everything else in the sim: no
 * per-tick allocation, stable indices, and one linear pass to update.
 */
public final class Kingdoms {

    public final int capacity;

    public final boolean[] alive;
    public final byte[] species;
    public final short[] capital;
    /** Recounted each pass from {@link Villages#kingdom}. */
    public final int[] villageCount;
    public final int[] population;
    public final int foundedTick[];
    /** Packed 0xRRGGBB flag colour; derived from species + slot. */
    public final int[] color;
    /** Short display name, e.g. "H1" for Human kingdom in slot 1. */
    private final String[] name;

    private final int[] freeList;
    private int freeCount;
    private int highWater;
    private int liveCount;

    public Kingdoms(int capacity) {
        if (capacity <= 0 || capacity > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                "capacity must be in 1.." + Short.MAX_VALUE + ", got " + capacity);
        }
        this.capacity = capacity;

        alive = new boolean[capacity];
        species = new byte[capacity];
        capital = new short[capacity];
        villageCount = new int[capacity];
        population = new int[capacity];
        foundedTick = new int[capacity];
        color = new int[capacity];
        name = new String[capacity];

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

    public String nameOf(int index) {
        return isAlive(index) ? name[index] : "";
    }

    /**
     * Founds a new kingdom around a capital village. Fills in a colour and
     * a short name derived from the species and slot. Returns the kingdom
     * index, or -1 if the pool is full.
     */
    public int found(int capitalVillage, byte speciesId, int tick) {
        if (freeCount == 0) {
            return -1;
        }
        int index = freeList[--freeCount];

        alive[index] = true;
        species[index] = speciesId;
        capital[index] = (short) capitalVillage;
        villageCount[index] = 1;
        population[index] = 0;
        foundedTick[index] = tick;
        color[index] = deriveColor(speciesId, index);
        name[index] = Species.shortName(speciesId) + (index + 1);

        liveCount++;
        if (index >= highWater) {
            highWater = index + 1;
        }
        return index;
    }

    /** Releases a slot back to the pool. Callers must have zeroed member counts. */
    public void dissolve(int index) {
        if (!isAlive(index)) {
            return;
        }
        alive[index] = false;
        villageCount[index] = 0;
        population[index] = 0;
        name[index] = null;
        freeList[freeCount++] = index;
        liveCount--;
    }

    /** Renames the capital, used when the current one is captured or dies. */
    public void setCapital(int index, int newCapitalVillage) {
        if (isAlive(index)) {
            capital[index] = (short) newCapitalVillage;
        }
    }

    /**
     * Palette derived from the species base hue and the kingdom index, so
     * two human kingdoms are recognisably human but visibly different from
     * each other. Kept deterministic on the index alone - a saved kingdom
     * lands on the same colour when it is restored.
     */
    private static int deriveColor(byte speciesId, int slot) {
        int r, g, b;
        // Base hue by species, then a per-slot swing so kingdom 1 and
        // kingdom 5 of the same species read differently.
        int shift = (slot * 37) & 0x3f;
        switch (speciesId) {
            case Species.HUMAN:
                r = 210 - shift; g = 190; b = 90 + shift; break;
            case Species.ORC:
                r = 200 - shift / 2; g = 90 + shift / 2; b = 60; break;
            case Species.ELF:
                r = 90 + shift / 2; g = 200 - shift / 4; b = 130 + shift / 2; break;
            case Species.DWARF:
            default:
                r = 170 - shift / 3; g = 120 - shift / 4; b = 90; break;
        }
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int c) {
        return c < 0 ? 0 : (c > 255 ? 255 : c);
    }
}
