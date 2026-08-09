package com.worldbox.sim;

/** An individual villager (or, if nationId == UNDEAD_NATION_ID, a zombie). */
public class Human {
  private static int nextId = 1;

  public final int id;
  public double x, z, prevX, prevZ;
  public int nationId, settlementId;
  public String job; // null, "wood", "stone", "iron"
  public String state = "wander"; // wander, gather, haul, flee
  public double targetX, targetZ;
  public int gatherX = -1, gatherY = -1, gatherTimer = 0;
  public String carryingType;
  public double carryingAmount;
  public int fleeTimer = 0;
  public int age = 0;
  public boolean dead = false;
  public float hue;
  public int isolationTicks = 0; // ticks spent as a nation-less wanderer with no settlement in reach

  public Human(double x, double z, int nationId, int settlementId) {
    this.id = nextId++;
    this.x = x; this.z = z; this.prevX = x; this.prevZ = z;
    this.nationId = nationId;
    this.settlementId = settlementId;
    this.targetX = x; this.targetZ = z;
    this.hue = 190f + (float) (Math.random() * 60);
  }
}
