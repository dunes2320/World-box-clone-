package com.game.ui;

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

    /** The tools available in phase 2. Spawning and disasters join later. */
    public enum Tool {
        INSPECT("Inspect"),
        RAISE("Raise"),
        LOWER("Lower"),
        WATER("Water"),
        FOREST("Forest"),
        SPAWN("Spawn");

        private final String label;

        Tool(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** True for tools that should keep applying while the mouse is dragged. */
        public boolean isContinuous() {
            return this != INSPECT;
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
