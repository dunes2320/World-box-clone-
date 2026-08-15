package com.worldbox.sim;

/** A drifting cloud - mostly just atmosphere, but any cloud can build into
 * a storm that actually rains: putting out fire underneath it and giving
 * the player a real (not just a brush) way to fight a wildfire that's
 * gotten away from them. */
public class Cloud implements java.io.Serializable {
  private static int nextId = 1;

  /** Loading a save must never let a freshly created instance reuse an id
   * already present in the loaded data - bump the counter past whatever
   * the save actually contained. */
  public static void restoreNextId(int maxSeenId) { if (maxSeenId >= nextId) nextId = maxSeenId + 1; }

  public final int id;
  public double x, z, prevX, prevZ;
  public double vx, vz;
  public double radius;
  public boolean stormy = false;
  public int stormTimer = 0;

  public Cloud(double x, double z, double vx, double vz, double radius) {
    this.id = nextId++;
    this.x = x; this.z = z; this.prevX = x; this.prevZ = z;
    this.vx = vx; this.vz = vz;
    this.radius = radius;
  }
}
