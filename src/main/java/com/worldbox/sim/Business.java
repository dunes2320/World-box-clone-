package com.worldbox.sim;

/** A private (capitalism) or state-owned (communism) economic actor that
 * specializes in one resource, skimming surplus into its own capital and
 * boosting local output. Businesses can go bankrupt in a market crash. */
public class Business {
  private static int nextId = 1;

  public final int id;
  public int settlementId;
  public int nationId;
  /** "farm" | "market" | "extraction" - a settlement's economy has to be
   * built in that order: a farm first, then a market, only then can
   * resource-extraction businesses (wood/stone/iron) form. */
  public final String type;
  public final String resourceKey; // "food" for a farm, "wood"/"stone"/"iron" for extraction, "market" (unused) for a market
  public double capital;
  public double productivity = 1.0;
  public double debt = 0; // outstanding business loan from the nation's bank
  /** Smoothed (EMA) recent revenue - the "earnings" side of a market-cap
   * style valuation, so the stock chart reflects how the business is
   * actually performing rather than just its cash on hand. */
  public double trailingRevenue = 0;
  /** capital + an earnings multiple - book value, not just a raw treasury
   * number, so this reads like a real company's market cap. */
  public double valuation = 20;

  public Business(int settlementId, int nationId, String type, String resourceKey) {
    this.id = nextId++;
    this.settlementId = settlementId;
    this.nationId = nationId;
    this.type = type;
    this.resourceKey = resourceKey;
  }
}
