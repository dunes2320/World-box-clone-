package com.worldbox.sim;

/** A single wild critter - no hunting/taming mechanics yet, just ambient
 * wildlife that makes a biome feel alive. Wanders locally forever, staying
 * off water and (see Population-style movement) turning gradually toward
 * its current target instead of snapping, same "organic movement" feel as
 * Human.moveToward. */
public class Animal implements java.io.Serializable {
  private static int nextId = 1;
  public static void restoreNextId(int maxSeenId) { if (maxSeenId >= nextId) nextId = maxSeenId + 1; }

  public final int id;
  public final String species; // "cow" | "sheep" | "deer" | "camel" | "wolf" | "goat"
  public double x, z;
  public double targetX, targetZ;
  public double heading = Math.random() * Math.PI * 2;
  public double walkPhase = Math.random() * Math.PI * 2;
  public int wanderTimer = 0;
  public boolean dead = false;

  public Animal(String species, double x, double z) {
    this.id = nextId++;
    this.species = species;
    this.x = x; this.z = z;
    this.targetX = x; this.targetZ = z;
  }
}
