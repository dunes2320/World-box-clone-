package com.worldbox.sim;

import com.worldbox.util.Rng;
import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything about a running game world lives here so systems can be
 * simple static functions that take (GameState) and mutate it in place. */
public class GameState {
  public WorldGrid grid;
  public List<Human> humans = new ArrayList<>();
  public Map<Integer, Settlement> settlements = new LinkedHashMap<>();
  public Map<Integer, Nation> nations = new LinkedHashMap<>();
  public Map<Integer, Army> armies = new LinkedHashMap<>();
  public Map<Integer, Business> businesses = new LinkedHashMap<>();
  /** last ~120 samples of total living-nation treasury, for the world economy graph. */
  public final java.util.ArrayDeque<Double> worldEconomyHistory = new java.util.ArrayDeque<>();
  /** last ~120 samples of every business's combined market-cap style
   * valuation - the world "stock index", the primary series for the
   * world economy graph. */
  public final java.util.ArrayDeque<Double> worldMarketCapHistory = new java.util.ArrayDeque<>();
  public GlobalMarket market = new GlobalMarket();
  public DiplomacyManager diplomacy = new DiplomacyManager();
  public List<Tornado> tornadoes = new ArrayList<>();
  public Monster monster;
  public int tick = 0;
  public Rng rng;

  public static class Selection {
    public final String type; // "settlement" | "nation"
    public final int id;
    public Selection(String type, int id) { this.type = type; this.id = id; }
  }
  public Selection selection;
  public int speed = 1;
}
