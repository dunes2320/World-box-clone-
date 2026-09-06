package com.game.sim;

/**
 * The story half of every unit: name, personal traits, parents and a
 * running tally of notable deeds. Parallel to {@link Units} - one lore
 * entry per unit slot, so a unit's index is also the index into its
 * lore. Slots turn over with unit slots: when a unit dies and its slot
 * comes back around for a newborn, its lore is overwritten in
 * {@link #recordBirth}.
 *
 * <p>Kept as a separate class rather than tacked onto {@link Units} so
 * simulations that do not need lore (headless perf tests, isolated
 * combat tests) do not pay the extra arrays or the {@link NameGen} call
 * on spawn. Systems check {@code lore != null} once at the top and
 * skip the whole thing when there is no lore attached.
 */
public final class UnitLore {

    public static final short NO_PARENT = -1;

    public final int capacity;
    public final byte[] traits;
    public final short[] parentA;
    public final short[] parentB;
    /** Notable acts credited to this unit - children fathered, sieges won, wars fought in. */
    public final int[] deeds;
    /** Cached names. Filled at recordBirth; nulled at clear so a stale slot cannot leak. */
    private final String[] name;

    public UnitLore(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, got " + capacity);
        }
        this.capacity = capacity;
        traits = new byte[capacity];
        parentA = new short[capacity];
        parentB = new short[capacity];
        deeds = new int[capacity];
        name = new String[capacity];
    }

    /**
     * Writes the lore for a newly-spawned unit. Inherits traits from the
     * two parents when known, or rolls fresh ones for a wanderer. Also
     * credits each parent with a "child fathered" deed - a simple way to
     * make a long-lived unit's story build up over time.
     */
    public void recordBirth(int unitIndex, byte species, int parentAIndex, int parentBIndex,
                            long worldSeed, java.util.Random random) {
        byte pa = parentAIndex >= 0 ? traits[parentAIndex] : -1;
        byte pb = parentBIndex >= 0 ? traits[parentBIndex] : -1;
        traits[unitIndex] = (pa == -1 && pb == -1)
            ? Traits.roll(random)
            : Traits.inherit(pa, pb, random);
        parentA[unitIndex] = (short) parentAIndex;
        parentB[unitIndex] = (short) parentBIndex;
        deeds[unitIndex] = 0;
        name[unitIndex] = NameGen.name(unitIndex, species, worldSeed);

        if (parentAIndex >= 0 && parentAIndex < capacity) {
            deeds[parentAIndex]++;
        }
        if (parentBIndex >= 0 && parentBIndex < capacity && parentBIndex != parentAIndex) {
            deeds[parentBIndex]++;
        }
    }

    /** Credits a unit with a deed - a fight won, a village founded, a war survived. */
    public void addDeed(int unitIndex) {
        if (unitIndex >= 0 && unitIndex < capacity) {
            deeds[unitIndex]++;
        }
    }

    public String nameOf(int unitIndex) {
        if (unitIndex < 0 || unitIndex >= capacity) return "";
        String n = name[unitIndex];
        return n == null ? "" : n;
    }

    /** Wipes a slot when a unit dies - stops a reused slot inheriting the previous owner. */
    public void clear(int unitIndex) {
        if (unitIndex < 0 || unitIndex >= capacity) return;
        traits[unitIndex] = 0;
        parentA[unitIndex] = NO_PARENT;
        parentB[unitIndex] = NO_PARENT;
        deeds[unitIndex] = 0;
        name[unitIndex] = null;
    }
}
