package com.worldbox.sim;

import com.worldbox.config.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Economy {
  private static final double LEVERAGE_LIMIT = 3.0;
  private static final int MAX_BUSINESSES_PER_SETTLEMENT = 2;
  private static final String[] BUSINESS_RESOURCES = {"wood", "stone", "iron"};

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
    for (String key : GlobalMarket.keys()) {
      market.supplyFlow.put(key, 0.0);
      market.demandFlow.put(key, 0.0);
    }

    for (Nation nation : state.nations.values()) {
      if (!nation.alive) continue;

      for (String key : GlobalMarket.keys()) {
        Settlement seller = biggestStock(state, nation, key);
        if (seller != null && seller.stock.get(key) > Config.SETTLEMENT_BUFFER * 1.6) {
          double sellAmt = (seller.stock.get(key) - Config.SETTLEMENT_BUFFER) * 0.08;
          seller.stock.merge(key, -sellAmt, Double::sum);
          double saleValue = sellAmt * market.prices.get(key);
          nation.treasury += saleValue;
          nation.gdpAccum += saleValue;
          market.volume.merge(key, sellAmt, Double::sum);
          market.supplyFlow.merge(key, sellAmt, Double::sum);
        }

        Settlement buyer = smallestStock(state, nation, key);
        if (buyer != null && buyer.stock.get(key) < 6 && nation.treasury > 40 && !key.equals("gold_ore")) {
          double buyAmt = 8;
          double cost = buyAmt * market.prices.get(key);
          if (nation.treasury >= cost) {
            nation.treasury -= cost;
            buyer.stock.merge(key, buyAmt, Double::sum);
            market.volume.merge(key, buyAmt, Double::sum);
            market.demandFlow.merge(key, buyAmt, Double::sum);
          }
        }
      }
    }

    settlePrices(state);

    updateBusinesses(state);
    updateBanks(state);
    updateBoomBust(state);

    if (state.tick % 4 == 0) market.snapshot();
  }

  /** Prices are set from real trade flow (what actually sold vs. what
   * actually got bought this tick) plus ongoing baseline supply and demand
   * tied to real world state - population consumes, settlements/businesses
   * produce - instead of decaying back to a fixed number. Baseline supply
   * and demand are scaled to roughly balance each other under normal
   * growth, so a price only really moves when something genuinely
   * disrupts that balance: a war or disaster wiping out settlements
   * (supply shock), population booming faster than production can keep up
   * (demand shock), a resource-heavy business boom, and so on. */
  private static void settlePrices(GameState state) {
    GlobalMarket market = state.market;
    int population = state.humans.size();
    int settlements = state.settlements.size();
    int businesses = state.businesses.size();
    int livingNations = 0;
    for (Nation n : state.nations.values()) if (n.alive) livingNations++;

    // Both sides share population as a base scale so they start balanced
    // even before any settlement exists (just wanderers foraging for
    // themselves) - settlements/businesses/nations then layer organized
    // production and consumption on top, which is what actually pulls a
    // price away from equilibrium.
    Map<String, Double> baselineDemand = new HashMap<>();
    baselineDemand.put("food", population * 0.05);
    baselineDemand.put("wood", population * 0.015);
    baselineDemand.put("stone", population * 0.01);
    baselineDemand.put("iron", population * 0.004 + businesses * 0.3);
    baselineDemand.put("gold_ore", population * 0.0015 + livingNations * 0.5);

    Map<String, Double> baselineSupply = new HashMap<>();
    baselineSupply.put("food", population * 0.052 + settlements * 1.0);
    baselineSupply.put("wood", population * 0.016 + settlements * 0.9);
    baselineSupply.put("stone", population * 0.011 + settlements * 0.7);
    baselineSupply.put("iron", population * 0.0045 + settlements * 0.45);
    baselineSupply.put("gold_ore", population * 0.0016 + settlements * 0.1);

    for (String key : GlobalMarket.keys()) {
      double base = Config.BASE_PRICES.get(key);
      double supply = market.supplyFlow.getOrDefault(key, 0.0) + baselineSupply.getOrDefault(key, 0.0) + 0.5;
      double demand = market.demandFlow.getOrDefault(key, 0.0) + baselineDemand.getOrDefault(key, 0.0) + 0.5;
      double ratio = demand / supply;
      double step = clamp(Math.log(ratio) * 0.02, -0.02, 0.02);
      double p = market.prices.get(key) * (1 + step);
      p = Math.max(base * 0.25, Math.min(base * 4.0, p));
      market.prices.put(key, p);
    }
  }

  private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

  // ---- businesses: private (capitalism) or state-owned (communism) ----
  private static void updateBusinesses(GameState state) {
    if (state.tick % 3 == 0) {
      for (Settlement s : state.settlements.values()) {
        if (s.populationCount < 10) continue;
        int existing = 0;
        for (Business b : state.businesses.values()) if (b.settlementId == s.id) existing++;
        if (existing >= MAX_BUSINESSES_PER_SETTLEMENT) continue;
        if (Math.random() > 0.02) continue;
        String key = BUSINESS_RESOURCES[(int) (Math.random() * BUSINESS_RESOURCES.length)];
        if (s.stock.getOrDefault(key, 0.0) < Config.SETTLEMENT_BUFFER) continue;
        boolean dup = false;
        for (Business b : state.businesses.values()) {
          if (b.settlementId == s.id && b.resourceKey.equals(key)) { dup = true; break; }
        }
        if (dup) continue;
        Business b = new Business(s.id, s.nationId, key);
        state.businesses.put(b.id, b);
      }
    }

    List<Integer> bankrupt = new ArrayList<>();
    for (Business b : state.businesses.values()) {
      Settlement s = state.settlements.get(b.settlementId);
      Nation n = state.nations.get(b.nationId);
      if (s == null || n == null) { bankrupt.add(b.id); continue; }
      b.nationId = s.nationId; // follow the settlement if it's conquered

      if (b.capital > 15) b.productivity = Math.min(3.0, b.productivity + 0.001);

      double surplus = Math.max(0, s.stock.getOrDefault(b.resourceKey, 0.0) - Config.SETTLEMENT_BUFFER);
      double skim = surplus * 0.15;
      s.stock.merge(b.resourceKey, -skim, Double::sum);
      // oligarchy: the business elite run the show, so private enterprise
      // is extra productive but the state's cut shrinks
      double govMultiplier = n.government.equals(Government.OLIGARCHY) ? 1.3 : 1.0;
      double revenue = skim * state.market.prices.get(b.resourceKey) * b.productivity * govMultiplier;
      n.gdpAccum += revenue;

      if (n.ideology.equals("communism")) {
        // state-owned: the enterprise's output goes straight to the treasury
        n.treasury += revenue;
        b.capital += revenue * 0.05;
      } else {
        double stateCut = n.government.equals(Government.OLIGARCHY) ? 0.15 : 0.3;
        b.capital += revenue * (1 - stateCut);
        n.treasury += revenue * stateCut;
        state.market.nudge(b.resourceKey, 1, 0.4);
      }

      b.capital -= 0.4; // upkeep

      b.trailingRevenue = b.trailingRevenue * 0.95 + revenue * 0.05;
      b.valuation = Math.max(0, Math.max(0, b.capital) + b.trailingRevenue * 12 - b.debt * 0.5);

      // business loans: borrow from the nation's bank when cash is tight,
      // repay out of future profit once healthy again
      if (b.capital < 10 && n.bank.reserves > 25) {
        double loanAmt = 25;
        n.bank.reserves -= loanAmt;
        n.bank.loans += loanAmt;
        b.capital += loanAmt;
        b.debt += loanAmt;
      } else if (b.debt > 0 && b.capital > 30) {
        double repay = Math.min(b.debt, (b.capital - 30) * 0.3);
        b.debt -= repay;
        b.capital -= repay;
        n.bank.loans = Math.max(0, n.bank.loans - repay);
      }

      if (b.capital < -20) bankrupt.add(b.id);
    }
    for (int id : bankrupt) {
      Business b = state.businesses.get(id);
      if (b != null && b.debt > 0) {
        // defaulted debt is written off the bank's books - it's gone either way
        Nation n = state.nations.get(b.nationId);
        if (n != null) n.bank.loans = Math.max(0, n.bank.loans - b.debt);
      }
      state.businesses.remove(id);
    }
  }

  // ---- national banks: reserves, emergency loans, and bank runs ----
  private static void updateBanks(GameState state) {
    for (Nation n : state.nations.values()) {
      Bank bank = n.bank;
      bank.justCrashed = false;

      if (n.treasury > 100) {
        double deposit = (n.treasury - 100) * 0.01;
        bank.reserves += deposit;
      }
      if (n.treasury < 0 && bank.reserves > 5) {
        double need = Math.min(-n.treasury, bank.reserves);
        bank.reserves -= need;
        bank.loans += need;
        n.treasury += need;
      }
      bank.loans *= 1.0006;

      if (bank.reserves > 0.01 && bank.loans > bank.reserves * LEVERAGE_LIMIT) {
        double crashChance = 0.01 * Math.min(4.0, bank.loans / (bank.reserves * LEVERAGE_LIMIT));
        if (Math.random() < crashChance) {
          bank.reserves = 0;
          bank.loans *= 0.4;
          n.treasury -= n.treasury * 0.35 + 40;
          for (Business b : state.businesses.values()) {
            if (b.nationId == n.id) b.capital -= 30;
          }
          bank.justCrashed = true;
        }
      }
    }
  }

  // ---- global market booms & crashes: greed, war jitters, disaster shocks ----
  private static void updateBoomBust(GameState state) {
    GlobalMarket market = state.market;
    market.crashedThisTick = false;

    boolean anyWar = false;
    for (DiplomacyManager.Relation r : state.diplomacy.relations.values()) {
      if (r.status.equals(Config.WAR)) { anyWar = true; break; }
    }

    for (String key : GlobalMarket.keys()) {
      double base = Config.BASE_PRICES.get(key);
      double price = market.prices.get(key);
      double ratio = price / base;

      double greed = market.greed.getOrDefault(key, 0.0);
      greed = ratio > 1.3 ? Math.min(1.0, greed + 0.01 * (ratio - 1.3)) : Math.max(0.0, greed - 0.01);
      market.greed.put(key, greed);

      if (anyWar) market.nudge(key, Math.random() < 0.5 ? 1 : -1, 0.6);

      if (ratio > 2.0 && greed > 0.5) {
        double crashChance = 0.003 * greed * (ratio - 2.0);
        if (Math.random() < crashChance) {
          market.prices.put(key, base * (0.4 + Math.random() * 0.3));
          market.greed.put(key, 0.0);
          market.crashedThisTick = true;
          for (Business b : state.businesses.values()) {
            if (b.resourceKey.equals(key)) b.capital -= 15;
          }
        }
      }
    }
  }
}
