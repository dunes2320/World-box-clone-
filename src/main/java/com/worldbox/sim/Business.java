package com.worldbox.sim;

/** A private (capitalism) or state-owned (communism) economic actor that
 * specializes in one resource, skimming surplus into its own capital and
 * boosting local output. Businesses can go bankrupt in a market crash. */
public class Business {
  private static int nextId = 1;

  public final int id;
  public int settlementId;
  public int nationId;
  public final String resourceKey; // "wood", "stone", "iron"
  public double capital;
  public double productivity = 1.0;

  public Business(int settlementId, int nationId, String resourceKey) {
    this.id = nextId++;
    this.settlementId = settlementId;
    this.nationId = nationId;
    this.resourceKey = resourceKey;
    this.capital = 20;
  }
}
