package com.worldbox.sim;

public class Tornado implements java.io.Serializable {
  public double x, z;
  public double angle;
  public int life;

  public Tornado(double x, double z, double angle, int life) {
    this.x = x; this.z = z; this.angle = angle; this.life = life;
  }
}
