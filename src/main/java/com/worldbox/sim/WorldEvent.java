package com.worldbox.sim;

/** A single recorded happening for the player-facing log book - wars,
 * disasters, nations rising and falling, that kind of thing. Nothing
 * reads this for simulation purposes; it exists purely so a player can
 * look back and see what actually happened in their world. */
public class WorldEvent implements java.io.Serializable {
  public final int tick;
  public final String category; // "war" | "disaster" | "nation" | "economy"
  public final String message;

  public WorldEvent(int tick, String category, String message) {
    this.tick = tick;
    this.category = category;
    this.message = message;
  }
}
