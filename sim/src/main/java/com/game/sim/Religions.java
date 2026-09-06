package com.game.sim;

/**
 * Faiths in the world: name, colour, and a running count of villages
 * that follow them. Same struct-of-arrays pool with a free list as the
 * other pools; a religion whose last follower converts away has its
 * slot returned so the pool can grow forever with a small live count.
 *
 * <p>Religions are cheaper than kingdoms - no relations matrix, no
 * armies - so a modest capacity covers the map. {@link ReligionSystem}
 * founds and spreads them; {@link Villages#religion} names which one a
 * given village currently follows.
 */
public final class Religions {

    public static final short NO_RELIGION = -1;

    public final int capacity;

    public final boolean[] alive;
    /** Kingdom that founded this faith, for the readout. */
    public final short[] founderKingdom;
    public final int[] foundedTick;
    public final int[] followers;
    /** Packed 0xRRGGBB. */
    public final int[] color;
    private final String[] name;

    private final int[] freeList;
    private int freeCount;
    private int highWater;
    private int liveCount;

    public Religions(int capacity) {
        if (capacity <= 0 || capacity > Short.MAX_VALUE) {
            throw new IllegalArgumentException(
                "capacity must be in 1.." + Short.MAX_VALUE + ", got " + capacity);
        }
        this.capacity = capacity;
        alive = new boolean[capacity];
        founderKingdom = new short[capacity];
        foundedTick = new int[capacity];
        followers = new int[capacity];
        color = new int[capacity];
        name = new String[capacity];

        freeList = new int[capacity];
        for (int i = 0; i < capacity; i++) freeList[i] = capacity - 1 - i;
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
        return isAlive(index) && name[index] != null ? name[index] : "";
    }

    /**
     * Founds a new religion. The name is derived from the founding
     * kingdom's species + slot so a saved world round-trips to the same
     * faiths.
     */
    public int found(int founderKingdomIndex, byte speciesId, int tick, long worldSeed) {
        if (freeCount == 0) return -1;
        int index = freeList[--freeCount];
        alive[index] = true;
        founderKingdom[index] = (short) founderKingdomIndex;
        foundedTick[index] = tick;
        followers[index] = 1;
        color[index] = deriveColor(speciesId, index, worldSeed);
        name[index] = deriveName(speciesId, index, worldSeed);
        liveCount++;
        if (index >= highWater) highWater = index + 1;
        return index;
    }

    public void dissolve(int index) {
        if (!isAlive(index)) return;
        alive[index] = false;
        followers[index] = 0;
        name[index] = null;
        freeList[freeCount++] = index;
        liveCount--;
    }

    // Names picked from a small pool that reads as mythic across species.
    // The list is deliberately short - each world only ever surfaces a
    // handful of faiths, and repetition across species reads as a shared
    // pantheon rather than a bug.
    private static final String[] SUFFIXES = {
        "ism", "ianity", "ery", " Way", " Path", " Faith", " Cult"};
    private static final String[] ROOTS_HUMAN = {"Sol", "Astra", "Verid", "Ora", "Lumen"};
    private static final String[] ROOTS_ORC = {"Grukk", "Krar", "Zurr", "Grash", "Vorm"};
    private static final String[] ROOTS_ELF = {"Silva", "Melia", "Ariel", "Nym", "Cael"};
    private static final String[] ROOTS_DWARF = {"Kaz", "Grund", "Barund", "Thane", "Ord"};

    private static String deriveName(byte speciesId, int index, long worldSeed) {
        String[] roots;
        switch (speciesId) {
            case Species.ORC: roots = ROOTS_ORC; break;
            case Species.ELF: roots = ROOTS_ELF; break;
            case Species.DWARF: roots = ROOTS_DWARF; break;
            case Species.HUMAN:
            default: roots = ROOTS_HUMAN;
        }
        long h = worldSeed * 0x9E3779B97F4A7C15L + index * 0xBF58476D1CE4E5B9L;
        int r = (int) ((h >>> 16) & 0x7fffffff) % roots.length;
        int s = (int) ((h >>> 32) & 0x7fffffff) % SUFFIXES.length;
        return roots[r] + SUFFIXES[s];
    }

    private static int deriveColor(byte speciesId, int slot, long worldSeed) {
        // Distinct hue per slot; slightly biased by species so orc faiths
        // read warm and elf faiths read cool without being locked to one
        // colour.
        long h = worldSeed * 0xBF58476D1CE4E5B9L + slot * 0x94D049BB133111EBL;
        int r = 120 + (int) ((h >>> 8) & 0x7f);
        int g = 120 + (int) ((h >>> 16) & 0x7f);
        int b = 120 + (int) ((h >>> 24) & 0x7f);
        switch (speciesId) {
            case Species.ORC: r = Math.min(255, r + 40); g = Math.max(0, g - 30); break;
            case Species.ELF: g = Math.min(255, g + 30); b = Math.min(255, b + 20); break;
            case Species.DWARF: b = Math.max(0, b - 30); break;
            default: break;
        }
        return (r << 16) | (g << 8) | b;
    }
}
