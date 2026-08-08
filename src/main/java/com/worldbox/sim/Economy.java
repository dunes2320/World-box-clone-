package com.worldbox.sim;

import com.worldbox.config.Config;

public class Economy {

  private static Settlement biggestStock(GameState state, Nation nation, String key) {
    Settlement best = null;
    double bestAmt = -Double.MAX_VALUE;
    for (int sid : nation.settlementIds) {
      Settlement s = state.settlements.get(sid);
      if (s == null) continue;
      if (s.stock.get(key) > bestAmt) { bestAmt = s.stock.get(key); best = s; }
    }
    return best;
  }

  private static Settlement smallestStock(GameState state, Nation nation, String key) {
    Settlement best = null;
    double bestAmt = Double.MAX_VALUE;
    for (int sid : nation.settlementIds) {
      Settlement s = state.settlements.get(sid);
      if (s == null) continue;
      if (s.stock.get(key) < bestAmt) { bestAmt = s.stock.get(key); best = s; }
    }
    return best;
  }

  public static void update(GameState state) {
    GlobalMarket market = state.market;
    for (Nation nation : state.nations.values()) {
      if (!nation.alive) continue;

      for (String key : GlobalMarket.keys()) {
        Settlement seller = biggestStock(state, nation, key);
        if (seller != null && seller.stock.get(key) > Config.SETTLEMENT_BUFFER * 1.6) {
          double sellAmt = (seller.stock.get(key) - Config.SETTLEMENT_BUFFER) * 0.08;
          seller.stock.merge(key, -sellAmt, Double::sum);
          nation.treasury += sellAmt * market.prices.get(key);
          market.volume.merge(key, sellAmt, Double::sum);
          market.nudge(key, -1, sellAmt / 12);
        }

        Settlement buyer = smallestStock(state, nation, key);
        if (buyer != null && buyer.stock.get(key) < 6 && nation.treasury > 40 && !key.equals("gold_ore")) {
          double buyAmt = 8;
          double cost = buyAmt * market.prices.get(key);
          if (nation.treasury >= cost) {
            nation.treasury -= cost;
            buyer.stock.merge(key, buyAmt, Double::sum);
            market.volume.merge(key, buyAmt, Double::sum);
            market.nudge(key, 1, buyAmt / 12);
          }
        }
      }
    }

    for (String key : GlobalMarket.keys()) {
      double base = Config.BASE_PRICES.get(key);
      double p = market.prices.get(key);
      market.prices.put(key, p + (base - p) * 0.008);
    }

    if (state.tick % 4 == 0) market.snapshot();
  }
}
