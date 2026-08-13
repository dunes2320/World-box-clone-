package com.worldbox.sim;

/** A drifting cloud - mostly just atmosphere, but any cloud can build into
 * a storm that actually rains: putting out fire underneath it and giving
 * the player a real (not just a brush) way to fight a wildfire that's
 * gotten away from them. */
public class Cloud {
  private static int nextId = 1;

  public final int id;
  public double x, z;
  public double vx, vz;
  public double radius;
  public boolean stormy = false;
  public int stormTimer = 0;

  public Cloud(double x, double z, double vx, double vz, double radius) {
    this.id = nextId++;
    this.x = x; this.z = z;
    this.vx = vx; this.vz = vz;
    this.radius = radius;
  }
}
