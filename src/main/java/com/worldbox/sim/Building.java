package com.worldbox.sim;

/** A real, individually-placed structure - not a decorative count recomputed
 * from population each frame. Built up block by block over time
 * (progress 0..1, see Settlement.update) and can be knocked back down by
 * war/disaster damage (integrity 0..1, see Military.inflictWarDamage) -
 * the render layer (EntityRenderer) shows whichever of the two is lower,
 * so a damaged building visibly looks half-built/rubbled using the exact
 * same staged blueprint meshes construction already uses. */
public class Building implements java.io.Serializable {
  private static int nextId = 1;

  public static void restoreNextId(int maxSeenId) { if (maxSeenId >= nextId) nextId = maxSeenId + 1; }

  public final int id;
  public int settlementId;
  public int nationId;
  /** "house" | "mansion" - see EntityRenderer's blueprint set for what
   * else a settlement's single government-tier marker uses (that one
   * isn't a per-Building instance; a settlement only ever has one). */
  public final String type;
  public final double x, z;
  public double progress;
  public double integrity = 1.0;

  public Building(int settlementId, int nationId, String type, double x, double z) {
    this.id = nextId++;
    this.settlementId = settlementId;
    this.nationId = nationId;
    this.type = type;
    this.x = x;
    this.z = z;
  }
}
