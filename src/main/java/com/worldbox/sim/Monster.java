package com.worldbox.sim;

public class Monster implements java.io.Serializable {
  public double x, z;
  public double hp, maxHp;
  public int life;

  public Monster(double x, double z, double hp, int life) {
    this.x = x; this.z = z; this.hp = hp; this.maxHp = hp; this.life = life;
  }
}
