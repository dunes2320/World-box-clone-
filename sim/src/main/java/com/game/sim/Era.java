package com.game.sim;

/**
 * The four ages a kingdom passes through as its knowledge accrues.
 *
 * <p>Stored as a byte per kingdom in {@link Kingdoms#era}. Advancement is
 * one-way: {@link CultureSystem} moves a kingdom forward when its
 * {@link Kingdoms#knowledge} crosses the next threshold, and never
 * backward - a fallen empire's remnants keep what they learned.
 *
 * <p>Each era unlocks more of the {@link BuildingSystem}'s roster and
 * gets a distinguishing palette shift that the renderer reads back out.
 * A stone-age hamlet has houses and farms and nothing more; an iron-age
 * kingdom has markets and barracks; a medieval one adds temples and
 * walls.
 */
public final class Era {

    private Era() {
    }

    public static final byte STONE = 0;
    public static final byte BRONZE = 1;
    public static final byte IRON = 2;
    public static final byte MEDIEVAL = 3;
    public static final int COUNT = 4;

    private static final String[] NAMES = {"Stone", "Bronze", "Iron", "Medieval"};

    /** Knowledge needed to reach the *next* era from the given one. */
    public static int nextThreshold(byte era) {
        switch (era) {
            case STONE: return SimConfig.ERA_KNOWLEDGE_BRONZE;
            case BRONZE: return SimConfig.ERA_KNOWLEDGE_IRON;
            case IRON: return SimConfig.ERA_KNOWLEDGE_MEDIEVAL;
            case MEDIEVAL:
            default: return Integer.MAX_VALUE;
        }
    }

    public static String name(byte era) {
        return era >= 0 && era < COUNT ? NAMES[era] : "Unknown";
    }

    /**
     * Whether a building kind is unlocked for a kingdom in a given era.
     * Houses and farms are always available; the fancier stuff waits.
     * Stone: house, farm, lumber. Bronze: + mine, dock. Iron: + barracks,
     * market. Medieval: + temple, wall.
     */
    public static boolean unlocks(byte era, byte kind) {
        switch (kind) {
            case Features.KIND_HOUSE:
            case Features.KIND_FARM:
            case Features.KIND_LUMBER_CAMP:
            case Features.KIND_ROAD:
                return true;
            case Features.KIND_MINE:
            case Features.KIND_DOCK:
                return era >= BRONZE;
            case Features.KIND_BARRACKS:
            case Features.KIND_MARKET:
                return era >= IRON;
            case Features.KIND_TEMPLE:
            case Features.KIND_WALL:
                return era >= MEDIEVAL;
            default:
                return true;
        }
    }
}
