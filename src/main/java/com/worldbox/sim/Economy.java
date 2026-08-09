package com.worldbox.sim;

import com.worldbox.config.Config;

import java.util.ArrayList;
import java.util.List;

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

    updateBusinesses(state);
    updateBanks(state);
    updateBoomBust(state);

    if (state.tick % 4 == 0) market.snapshot();
  }

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
      double revenue = skim * state.market.prices.get(b.resourceKey) * b.productivity;

      if (n.ideology.equals("communism")) {
        // state-owned: the enterprise's output goes straight to the treasury
        n.treasury += revenue;
        b.capital += revenue * 0.05;
      } else {
        // private profit, taxed by the state, and reinvestment fuels speculation
        b.capital += revenue * 0.7;
        n.treasury += revenue * 0.3;
        state.market.nudge(b.resourceKey, 1, 0.4);
      }

      b.capital -= 0.4; // upkeep
      if (b.capital < -20) bankrupt.add(b.id);
    }
    for (int id : bankrupt) state.businesses.remove(id);
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
