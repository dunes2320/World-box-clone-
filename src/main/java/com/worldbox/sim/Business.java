package com.worldbox.sim;

import com.worldbox.config.Config;

/** A privately owned, nation-taxed economic actor that specializes in one
 * resource, skimming surplus into its own capital and boosting local
 * output. Businesses can go bankrupt in a market crash. */
public class Business implements java.io.Serializable {
  private static int nextId = 1;

  /** Loading a save must never let a freshly created instance reuse an id
   * already present in the loaded data - bump the counter past whatever
   * the save actually contained. */
  public static void restoreNextId(int maxSeenId) { if (maxSeenId >= nextId) nextId = maxSeenId + 1; }

  public final int id;
  public int settlementId;
  public int nationId;
  /** "farm" | "market" | "extraction" | "workshop" | "luxury_workshop" - a
   * settlement's economy has to be built in that order: a farm first, then
   * a market, then resource-extraction (wood/stone/iron), then a workshop
   * (needs an iron extraction business already running) and finally a
   * luxury workshop (needs a real gold_ore stockpile to draw on). */
  public final String type;
  // "food" for a farm, "wood"/"stone"/"iron" for extraction, "tools" for a
  // workshop, "luxury" for a luxury workshop, "market" (unused) for a market
  public final String resourceKey;

  /** Which of Config.SECTORS this business's output counts toward - what
   * Nation/GameState's sectorHistory groups revenue by for the HUD's
   * "Sectors" graph. */
  public String sector() {
    switch (type) {
      case "farm": return Config.SECTOR_AGRICULTURE;
      case "extraction": return Config.SECTOR_EXTRACTION;
      case "workshop": return Config.SECTOR_MANUFACTURING;
      case "luxury_workshop": return Config.SECTOR_LUXURY;
      default: return Config.SECTOR_COMMERCE;
    }
  }
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
