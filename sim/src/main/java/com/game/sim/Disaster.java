package com.game.sim;

/**
 * The six ways a god can ruin an afternoon.
 *
 * <p>An enum rather than the byte constants {@link TileType} and
 * {@link Species} use, because unlike those this is never stored per tile or
 * per unit - it is only ever a command passed from the palette to
 * {@link Disasters}, so there is nothing to pack.
 */
public enum Disaster {

    METEOR("Meteor"),
    LIGHTNING("Lightning"),
    FIRE("Fire"),
    EARTHQUAKE("Quake"),
    FLOOD("Flood"),
    PLAGUE("Plague");

    private final String label;

    Disaster(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
