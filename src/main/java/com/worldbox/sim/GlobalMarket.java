package com.worldbox.sim;

import com.worldbox.config.Config;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/** A single shared world market. Nations sell surplus and buy scarcity from
 * it, which is what makes it "international" rather than a simple
 * nation-to-nation ledger: everyone trades against the same floating
 * prices. */
public class GlobalMarket implements java.io.Serializable {
  private static final String[] KEYS = {"food", "wood", "stone", "iron", "gold_ore"};
  private static final int HISTORY_LEN = 90;

  public final Map<String, Double> prices = new LinkedHashMap<>(Config.BASE_PRICES);
  public final Map<String, Deque<Double>> history = new LinkedHashMap<>();
  public final Map<String, Double> volume = new LinkedHashMap<>();
  /** 0..1 speculative pressure per resource; high + overpriced = crash risk. */
  public final Map<String, Double> greed = new LinkedHashMap<>();
  /** This tick's real trade flow per resource - what actually got sold onto
   * the market (supply) and bought off of it (demand). Prices are derived
   * from these, not from an artificial pull back to a fixed base price. */
  public final Map<String, Double> supplyFlow = new LinkedHashMap<>();
  public final Map<String, Double> demandFlow = new LinkedHashMap<>();
  public boolean crashedThisTick = false;
  /** Gold physically still sitting in the ground, world-wide - gold never
   * respawns once mined, so this only ever shrinks. -1 = not sampled yet. */
  public double goldRemainingInGround = -1;

  public GlobalMarket() {
    for (String k : KEYS) {
      Deque<Double> dq = new ArrayDeque<>();
      dq.add(prices.get(k));
      history.put(k, dq);
      volume.put(k, 0.0);
      greed.put(k, 0.0);
      supplyFlow.put(k, 0.0);
      demandFlow.put(k, 0.0);
    }
  }

  public void nudge(String key, double direction, double magnitude) {
    double base = Config.BASE_PRICES.get(key);
    double p = prices.get(key);
    p = p * (1 + direction * Config.MARKET_ELASTICITY * magnitude);
    p = Math.max(base * 0.35, Math.min(base * 3.2, p));
    prices.put(key, p);
  }

  public void snapshot() {
    for (String k : KEYS) {
      Deque<Double> dq = history.get(k);
      dq.addLast(prices.get(k));
      if (dq.size() > HISTORY_LEN) dq.removeFirst();
    }
  }

  public static String[] keys() { return KEYS; }
}
