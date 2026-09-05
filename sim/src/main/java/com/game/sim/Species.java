package com.game.sim;

/**
 * The four playable species, as byte constants for the same reason as
 * {@link TileType}: one is stored per unit in a flat array.
 */
public final class Species {

    private Species() {
    }

    public static final byte HUMAN = 0;
    public static final byte ORC = 1;
    public static final byte ELF = 2;
    public static final byte DWARF = 3;

    public static final int COUNT = 4;

    private static final String[] NAMES = {"Humans", "Orcs", "Elves", "Dwarves"};
    private static final String[] SHORT_NAMES = {"Hum", "Orc", "Elf", "Dwa"};

    public static String name(byte species) {
        return species >= 0 && species < COUNT ? NAMES[species] : "Unknown";
    }

    /** Three-letter form, for buttons and the pair-by-pair relations readout. */
    public static String shortName(byte species) {
        return species >= 0 && species < COUNT ? SHORT_NAMES[species] : "???";
    }

    /**
     * How long a member of this species lives, in ticks, before old age starts
     * claiming them. Elves last markedly longer and orcs burn out fast, which
     * gives the four populations visibly different rhythms once they are all
     * running at once.
     */
    public static int baseLifespan(byte species) {
        switch (species) {
            case ELF:
                return 4200;
            case DWARF:
                return 3200;
            case ORC:
                return 1900;
            case HUMAN:
            default:
                return 2600;
        }
    }

    /**
     * Relative breeding pressure, roughly inverse to lifespan so no species
     * runs away with the map purely by living longer.
     *
     * <p>The spread is deliberately narrower than it first was. Villages give
     * their residents a breeding bonus, and a bonus is a multiplier: it
     * compounds whatever fertility gap already exists, because more births mean
     * more villages mean more bonus. Tuned against the old 1.55/0.6 spread,
     * elves slid to a single village and a shrinking population in every run.
     */
    public static float fertility(byte species) {
        switch (species) {
            case ORC:
                return 1.40f;
            case HUMAN:
                return 1.0f;
            case DWARF:
                return 0.88f;
            case ELF:
            default:
                return 0.78f;
        }
    }
}
