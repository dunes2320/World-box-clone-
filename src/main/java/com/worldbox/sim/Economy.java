package com.worldbox.sim;

import com.worldbox.config.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Economy {
  private static final double LEVERAGE_LIMIT = 3.0;
  private static final int MAX_BUSINESSES_PER_SETTLEMENT = 4; // farm + market + up to 2 extraction
  private static final String[] BUSINESS_RESOURCES = {"wood", "stone", "iron"};
  /** Founding capital comes from a bank loan, not free money - "one person
   * decides to take out a loan" to build it. Kept modest so a normal
   * settlement can service it comfortably; only reckless policy (taxing/
   * printing a business into the ground) or a genuine string of bad luck
   * pushes it to the existing bankruptcy threshold. */
  private static final double FOUNDING_LOAN = 15;

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

  private static boolean hasMarketBusiness(GameState state, int settlementId) {
    for (Business b : state.businesses.values()) {
      if (b.settlementId == settlementId && b.type.equals("market")) return true;
    }
    return false;
  }

  public static void update(GameState state) {
    GlobalMarket market = state.market;
    for (String key : GlobalMarket.keys()) {
      market.supplyFlow.put(key, 0.0);
      market.demandFlow.put(key, 0.0);
    }

    for (Nation nation : state.nations.values()) {
      if (!nation.alive) continue;
      updateEconCycle(nation);

      for (String key : GlobalMarket.keys()) {
        Settlement seller = biggestStock(state, nation, key);
        if (seller != null && seller.stock.get(key) > Config.SETTLEMENT_BUFFER * 1.6) {
          double sellAmt = (seller.stock.get(key) - Config.SETTLEMENT_BUFFER) * 0.08;
          seller.stock.merge(key, -sellAmt, Double::sum);
          double saleValue = sellAmt * market.prices.get(key) * nation.econCycle;
          // a market business handles trade better than an ad hoc sale -
          // this is its whole reason to exist once a settlement has one
          if (hasMarketBusiness(state, seller.id)) saleValue *= 1.25;
          nation.treasury += saleValue;
          nation.gdpAccum += saleValue;
          market.volume.merge(key, sellAmt, Double::sum);
          market.supplyFlow.merge(key, sellAmt, Double::sum);
          if (key.equals("gold_ore")) nation.goldReserves += sellAmt;
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

    if (state.tick % 20 == 0) sampleGoldRemaining(state);
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
    // gold's baseline supply is choked down as real in-ground deposits run
    // out - unlike every other resource, it can't just keep flowing from
    // population growth alone once the world is actually out of it
    double goldRemaining = market.goldRemainingInGround;
    double goldScarcity = goldRemaining < 0 ? 1.0 : clamp(goldRemaining / 500.0, 0.02, 1.0);
    baselineSupply.put("gold_ore", (population * 0.0016 + settlements * 0.1) * goldScarcity);

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

  /** A mature, saturated economy (population capped, businesses maxed
   * out per settlement) has nothing left to make its GDP move once
   * everything's built - it just sits dead flat at one equilibrium value
   * forever except for the rare price crash. Real economies keep having
   * genuine multi-year expansions and recessions even after "growing up".
   * This is a slow mean-reverting random walk - small daily nudges that
   * only really show up as a trend over months, not the instant noise a
   * plain random multiplier would produce. */
  private static void updateEconCycle(Nation n) {
    n.econCycle += (Math.random() - 0.5) * 0.012;
    n.econCycle += (1.0 - n.econCycle) * 0.003;
    n.econCycle = clamp(n.econCycle, 0.55, 1.6);
  }

  /** Gold never respawns once mined - this is what makes "gold can't go up
   * if there isn't any more" literally true instead of just a vibe. */
  private static void sampleGoldRemaining(GameState state) {
    var grid = state.grid;
    double remaining = 0;
    for (int i = 0; i < grid.cols * grid.rows; i++) {
      if (grid.resource[i] == Config.RES_GOLD) remaining += grid.resourceAmount[i];
    }
    state.market.goldRemainingInGround = remaining;
  }

  /** A new business always starts as a bank loan, never free money - "one
   * person decides to take out a loan" to build it. */
  private static void foundBusiness(GameState state, Settlement s, Nation n, String type, String resourceKey) {
    Business b = new Business(s.id, s.nationId, type, resourceKey);
    n.bank.reserves = Math.max(0, n.bank.reserves - FOUNDING_LOAN);
    n.bank.loans += FOUNDING_LOAN;
    b.debt = FOUNDING_LOAN;
    b.capital = FOUNDING_LOAN * 0.7; // the rest went straight to setup costs
    state.businesses.put(b.id, b);
  }

  // ---- businesses: privately owned, taxed by the nation ----
  private static void updateBusinesses(GameState state) {
    if (state.tick % 3 == 0) {
      for (Settlement s : state.settlements.values()) {
        if (s.populationCount < 10) continue;
        List<Business> existing = new ArrayList<>();
        for (Business b : state.businesses.values()) if (b.settlementId == s.id) existing.add(b);
        if (existing.size() >= MAX_BUSINESSES_PER_SETTLEMENT) continue;
        Nation n = state.nations.get(s.nationId);
        if (n == null) continue;

        boolean hasFarm = existing.stream().anyMatch(b -> b.type.equals("farm"));
        boolean hasMarket = existing.stream().anyMatch(b -> b.type.equals("market"));

        // the economy has to be built in order: a settlement's first
        // business is always a farm, then a market - only once both exist
        // can resource-extraction businesses form
        if (!hasFarm) {
          if (Math.random() < 0.03 && s.stock.getOrDefault("food", 0.0) > Config.SETTLEMENT_BUFFER * 0.5) {
            foundBusiness(state, s, n, "farm", "food");
          }
          continue;
        }
        if (!hasMarket) {
          if (Math.random() < 0.03) foundBusiness(state, s, n, "market", "market");
          continue;
        }

        if (Math.random() > 0.02) continue;
        String key = BUSINESS_RESOURCES[(int) (Math.random() * BUSINESS_RESOURCES.length)];
        if (s.stock.getOrDefault(key, 0.0) < Config.SETTLEMENT_BUFFER) continue;
        boolean dup = existing.stream().anyMatch(b -> key.equals(b.resourceKey));
        if (dup) continue;
        foundBusiness(state, s, n, "extraction", key);
      }
    }

    List<Integer> bankrupt = new ArrayList<>();
    for (Business b : state.businesses.values()) {
      Settlement s = state.settlements.get(b.settlementId);
      Nation n = state.nations.get(b.nationId);
      if (s == null || n == null) { bankrupt.add(b.id); continue; }
      b.nationId = s.nationId; // follow the settlement if it's conquered

      if (b.capital > 15) b.productivity = Math.min(3.0, b.productivity + 0.001);

      // oligarchy: the business elite run the show, so private enterprise
      // is extra productive but the state's cut shrinks
      double govMultiplier = n.government.equals(Government.OLIGARCHY) ? 1.3 : 1.0;
      double revenue = 0;

      if (b.type.equals("farm")) {
        // a farm turns wood and stone into food - real production, not
        // just a resale - on top of whatever food surplus it also sells
        double wood = Math.min(2.0, s.stock.getOrDefault("wood", 0.0));
        double stone = Math.min(1.0, s.stock.getOrDefault("stone", 0.0));
        s.stock.merge("wood", -wood, Double::sum);
        s.stock.merge("stone", -stone, Double::sum);
        s.stock.merge("food", (wood + stone) * b.productivity * 1.5, Double::sum);
      }

      if (!b.type.equals("market")) {
        double surplus = Math.max(0, s.stock.getOrDefault(b.resourceKey, 0.0) - Config.SETTLEMENT_BUFFER);
        double skim = surplus * 0.15;
        s.stock.merge(b.resourceKey, -skim, Double::sum);
        revenue = skim * state.market.prices.get(b.resourceKey) * b.productivity * govMultiplier * n.econCycle;
        state.market.nudge(b.resourceKey, 1, 0.4);
      }
      n.gdpAccum += revenue;

      double stateCut = n.government.equals(Government.OLIGARCHY) ? 0.15 : 0.3;
      b.capital += revenue * (1 - stateCut);
      n.treasury += revenue * stateCut;

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
        // this has to actually leave the treasury when it lands in the
        // bank - crediting reserves without debiting treasury was
        // manufacturing money out of nothing every single tick, which is
        // exactly how reserves ballooned into the millions over a long
        // run while treasury and the money supply stayed sane. The rate
        // is also a tenth of what it was: even a real transfer, run every
        // single tick with no return flow, still drags the whole
        // treasury surplus into reserves within a few years.
        double deposit = (n.treasury - 100) * 0.001;
        n.treasury -= deposit;
        bank.reserves += deposit;
      }
      if (n.treasury < 0 && bank.reserves > 5) {
        double need = Math.min(-n.treasury, bank.reserves);
        bank.reserves -= need;
        bank.loans += need;
        n.treasury += need;
      }
      bank.loans *= 1.0006;

      // hard consistency floor: whatever the deposit/withdrawal/interest
      // math above works out to, the bank can never hold more money than
      // its nation's own currency supply actually contains - this is the
      // guarantee the whole banking rework exists for
      bank.reserves = Math.min(bank.reserves, n.moneySupply);

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
