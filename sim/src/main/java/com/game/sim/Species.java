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

    public static String name(byte species) {
        return species >= 0 && species < COUNT ? NAMES[species] : "Unknown";
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
     * Relative breeding pressure. Orcs multiply fastest and elves slowest,
     * roughly inverse to lifespan, so no single species runs away with the map
     * purely by living longer.
     */
    public static float fertility(byte species) {
        switch (species) {
            case ORC:
                return 1.55f;
            case HUMAN:
                return 1.0f;
            case DWARF:
                return 0.85f;
            case ELF:
            default:
                return 0.6f;
        }
    }
}
