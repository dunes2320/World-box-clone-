package com.worldbox.sim;

import com.worldbox.util.NameGen;

/** An individual villager (or, if nationId == UNDEAD_NATION_ID, a zombie).
 * Every one is a named individual with a personality that colors how they
 * spend their day - not just an interchangeable population counter. */
public class Human {
  private static int nextId = 1;

  public final int id;
  public final String name = NameGen.fullName();
  public final Personality personality = Personality.random();
  public double x, z, prevX, prevZ;
  public int nationId, settlementId;
  public String job; // null, "wood", "stone", "iron"
  public String state = "wander"; // wander, gather, haul, flee
  /** "work" | "home" | "leisure" - the current phase of this person's day,
   * driven by tick-of-day and industriousness; see Population.updateRoutine. */
  public String routine = "work";
  public double targetX, targetZ;
  public int gatherX = -1, gatherY = -1, gatherTimer = 0;
  public String carryingType;
  public double carryingAmount;
  public int fleeTimer = 0;
  public int age = 0;
  public boolean dead = false;
  public float hue;
  public int isolationTicks = 0; // ticks spent as a nation-less wanderer with no settlement in reach
  public double wealth = 0;  // personal savings, paid as wages when they deliver a haul
  public double debt = 0;    // outstanding home loan, taken automatically when wealth runs dry
  /** A house is something a citizen has to actually get - built for them
   * by a settlement with spare housing capacity, or bought back after
   * losing one. A brand new wanderer with no settlement (let alone a
   * nation) starts with nothing. It's what the bank repossesses and sells
   * if they default on a loan they can't pay back. */
  public boolean hasHouse = false;

  public Human(double x, double z, int nationId, int settlementId) {
    this.id = nextId++;
    this.x = x; this.z = z; this.prevX = x; this.prevZ = z;
    this.nationId = nationId;
    this.settlementId = settlementId;
    this.targetX = x; this.targetZ = z;
    this.hue = 190f + (float) (Math.random() * 60);
  }
}
