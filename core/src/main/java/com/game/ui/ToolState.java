package com.game.ui;

import com.game.sim.Disaster;
import com.game.sim.Terraform;

/**
 * Which god tool is armed and how big its brush is.
 *
 * <p>Deliberately a plain holder shared between the palette and the input
 * handler, rather than state owned by a widget: the keyboard shortcuts and the
 * palette buttons are two views onto one value, and neither should be the
 * authority.
 */
public final class ToolState {

    /**
     * Every god tool. The first six shape and populate the world and are held
     * down like a brush; the six disasters are single events and fire once per
     * click, however long the button is held.
     */
    public enum Tool {
        INSPECT("Inspect", null),
        RAISE("Raise", null),
        LOWER("Lower", null),
        WATER("Water", null),
        FOREST("Forest", null),
        SPAWN("Spawn", null),
        METEOR("Meteor", Disaster.METEOR),
        LIGHTNING("Lightning", Disaster.LIGHTNING),
        FIRE("Fire", Disaster.FIRE),
        QUAKE("Quake", Disaster.EARTHQUAKE),
        FLOOD("Flood", Disaster.FLOOD),
        PLAGUE("Plague", Disaster.PLAGUE);

        private final String label;
        private final Disaster disaster;

        Tool(String label, Disaster disaster) {
            this.label = label;
            this.disaster = disaster;
        }

        public String label() {
            return label;
        }

        /** The disaster this tool unleashes, or null for the shaping tools. */
        public Disaster disaster() {
            return disaster;
        }

        public boolean isDisaster() {
            return disaster != null;
        }

        /**
         * True for tools that keep applying while the mouse is dragged.
         *
         * <p>Terraforming is a stroke, so it repeats. Inspect is a single pick.
         * Disasters repeat only in the sense that holding the button would drop
         * a meteor every frame, which is not a brush - it is sixty meteors.
         */
        public boolean isContinuous() {
            return this != INSPECT && !isDisaster();
        }
    }

    // Starts on the non-destructive tool: the palette makes the others one
    // click away, and nobody wants their first exploratory drag to gouge a
    // trench through the map.
    private Tool tool = Tool.INSPECT;
    private int radius = 4;
    private byte spawnSpecies = com.game.sim.Species.HUMAN;

    public Tool getTool() {
        return tool;
    }

    public void setTool(Tool tool) {
        this.tool = tool;
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = Terraform.clampRadius(radius);
    }

    /** Which species the spawn tool places. */
    public byte getSpawnSpecies() {
        return spawnSpecies;
    }

    public void setSpawnSpecies(byte species) {
        this.spawnSpecies = species;
    }

    /**
     * How many units one click of the spawn brush drops. Scaled by area so a
     * wide brush seeds a real population rather than the same handful spread
     * thinner.
     */
    public int spawnCountForRadius() {
        return Math.max(3, radius * radius / 2);
    }
}
