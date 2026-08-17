package com.worldbox.sim;

import com.worldbox.config.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Economy {
  private static final double LEVERAGE_LIMIT = 3.0;
  /** A brand new nation hasn't had time to build real reserves yet, so it
   * crosses the leverage line almost immediately just from ordinary
   * founding-era lending - that read as bank runs happening "a lot at the
   * beginning" rather than as a real consequence of reckless policy. A
   * nation younger than this quietly gets propped up instead (see
   * updateBanks); only once a nation's had a real chance to build up
   * reserves does over-leverage carry actual crash risk. */
  private static final int BANK_RUN_IMMUNITY_AGE = 3 * com.worldbox.util.Calendar.DAYS_PER_YEAR;
  // farm + market + up to 3 extraction (wood/stone/iron) + a workshop
  // (tools) + a luxury workshop (luxury goods) - the full production
  // chain a settlement can eventually build out to.
  private static final int MAX_BUSINESSES_PER_SETTLEMENT = 7;
  private static final String[] BUSINESS_RESOURCES = {"wood", "stone", "iron"};
  // a workshop needs iron+wood on hand to actually make anything (see the
  // "workshop" production branch in updateBusinesses); a luxury workshop
  // needs a real gold_ore stockpile to draw on since nobody runs a
  // dedicated gold *extraction* business (gold mining is a government job
  // - see Population.assignJob - so there's no "gold" business to found
  // after)
  private static final double WORKSHOP_INPUT_THRESHOLD = 15;
  private static final double LUXURY_INPUT_THRESHOLD = 8;
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
      updateLandValue(nation);
      updateTradePolicy(nation);

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
    updateForeignTrade(state);
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
    // manufactured goods have no wanderer-level baseline at all (nobody
    // makes tools/luxury goods without a real workshop) - demand for them
    // only exists once there's an actual population wealthy enough to buy
    // them (see Population's food/luxury purchases), so this is scaled
    // off population alone, much smaller than raw-material demand
    baselineDemand.put("tools", population * 0.0012 + businesses * 0.05);
    baselineDemand.put("luxury", population * 0.0006);

    Map<String, Double> baselineSupply = new HashMap<>();
    baselineSupply.put("food", population * 0.052 + settlements * 1.0);
    baselineSupply.put("wood", population * 0.016 + settlements * 0.9);
    baselineSupply.put("stone", population * 0.011 + settlements * 0.7);
    baselineSupply.put("iron", population * 0.0045 + settlements * 0.45);
    baselineSupply.put("tools", settlements * 0.05);
    baselineSupply.put("luxury", settlements * 0.02);
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

  /** How much more (or less) than the shared world price this nation's own
   * goods actually cost, in its own currency - a stronger currency (at or
   * above its founding peg) commands higher local prices, a weak one
   * cheaper ones. At exchangeRate == 1.0 (par - where every currency
   * starts) this is exactly 1.0, so a brand new nation's prices match the
   * world price with no shock; it only diverges as that currency actually
   * strengthens or weakens. */
  private static double localPriceMultiplier(Nation n) {
    if (n == null) return 1.0;
    double rate = n.currencyCollapsed ? 0 : n.exchangeRate;
    return clamp(0.5 + rate * 0.5, 0.5, 2.0);
  }

  /** The real, nation-specific price for a good - what any transaction
   * involving this nation's own currency should actually use, instead of
   * reading the shared world price directly. Wages, business revenue,
   * citizen purchases and cross-border trade all go through this so a
   * currency's strength has a real, felt effect on the cost of everything
   * denominated in it. */
  public static double nationPrice(GameState state, Nation nation, String key) {
    double base = state.market.prices.getOrDefault(key, Config.BASE_PRICES.getOrDefault(key, 1.0));
    return base * localPriceMultiplier(nation);
  }

  /** Land/property value: a slow-moving index (1.0 = par, same as at
   * founding) that tracks this nation's currency strength and its
   * treasury's real health - a prosperous nation with a strong currency
   * makes its land genuinely worth more. Used to scale real costs (see
   * Population's HOUSE_PRICE and this class's FOUNDING_LOAN) so a
   * flourishing nation's citizens/businesses actually pay a premium for
   * that prosperity, the same way real property values track a strong
   * economy. */
  private static void updateLandValue(Nation n) {
    double prosperity = clamp(0.6 + Math.sqrt(Math.max(0, n.treasury)) * 0.012, 0.6, 2.2);
    double target = localPriceMultiplier(n) * prosperity;
    n.landValueIndex += (target - n.landValueIndex) * 0.01;
    n.landValueIndex = clamp(n.landValueIndex, 0.3, 4.0);
  }

  /** Real bilateral trade: a nation with a real stockpiled surplus of some
   * good sells it directly to whichever other living nation (not currently
   * at war with it) has the least of that good on hand, at the BUYER's own
   * local price. The seller's government skims its own exportTaxRate off
   * the top before the proceeds land in its treasury; the buyer's
   * importTaxRate makes the purchase cost that much more on top of the
   * sale price - two real, separate policy levers instead of one shared
   * tax. Throttled to a slow cadence and a shortlist of nations per tick
   * since it's an O(nations^2) scan otherwise. */
  private static void updateForeignTrade(GameState state) {
    if (state.tick % 5 != 0) return;
    List<Nation> living = new ArrayList<>();
    for (Nation n : state.nations.values()) if (n.alive) living.add(n);
    if (living.size() < 2) return;

    for (Nation seller : living) {
      String bestKey = null;
      double bestAmt = 5; // not worth exporting a token amount
      for (String key : GlobalMarket.keys()) {
        double amt = seller.stockpile.getOrDefault(key, 0.0);
        if (amt > bestAmt) { bestAmt = amt; bestKey = key; }
      }
      if (bestKey == null) continue;

      Nation buyer = null;
      double buyerLeast = Double.MAX_VALUE;
      for (Nation candidate : living) {
        if (candidate.id == seller.id) continue;
        if (state.diplomacy.getStatus(seller.id, candidate.id).equals(Config.WAR)) continue;
        double amt = candidate.stockpile.getOrDefault(bestKey, 0.0);
        if (amt < buyerLeast) { buyerLeast = amt; buyer = candidate; }
      }
      if (buyer == null) continue;

      double amount = Math.min(bestAmt * 0.2, 25);
      double saleValue = amount * nationPrice(state, buyer, bestKey);
      double sellerNet = saleValue * (1 - seller.exportTaxRate);
      double buyerCost = saleValue * (1 + buyer.importTaxRate);
      if (buyer.treasury < buyerCost) continue;

      seller.stockpile.merge(bestKey, -amount, Double::sum);
      buyer.stockpile.merge(bestKey, amount, Double::sum);
      buyer.treasury -= buyerCost;
      seller.treasury += sellerNet;
      seller.gdpAccum += sellerNet;
      seller.sectorRevenue.merge(Config.SECTOR_COMMERCE, sellerNet, Double::sum);
    }
  }

  /** Both tariff rates drift like ordinary policy, not a fixed constant -
   * a government leaning on its treasury reaches for tariff revenue the
   * same way it raises the domestic tax rate (see Nation's own
   * trySettlementUpkeepSpending). */
  private static void updateTradePolicy(Nation n) {
    if (n.treasury < -50) {
      n.exportTaxRate = Math.min(0.35, n.exportTaxRate + 0.002);
      n.importTaxRate = Math.min(0.35, n.importTaxRate + 0.002);
    } else if (n.treasury > 400) {
      n.exportTaxRate = Math.max(0.02, n.exportTaxRate - 0.001);
      n.importTaxRate = Math.max(0.02, n.importTaxRate - 0.001);
    }
  }

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
   * person decides to take out a loan" to build it. Costs more to found
   * in an expensive nation, same as the loan a citizen takes out to buy a
   * house - see Nation.landValueIndex. */
  private static void foundBusiness(GameState state, Settlement s, Nation n, String type, String resourceKey) {
    double loanAmt = FOUNDING_LOAN * n.landValueIndex;
    Business b = new Business(s.id, s.nationId, type, resourceKey);
    n.bank.reserves = Math.max(0, n.bank.reserves - loanAmt);
    n.bank.loans += loanAmt;
    b.debt = loanAmt;
    b.capital = loanAmt * 0.7; // the rest went straight to setup costs
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
        boolean hasIronExtraction = existing.stream().anyMatch(b -> b.type.equals("extraction") && "iron".equals(b.resourceKey));
        boolean hasWorkshop = existing.stream().anyMatch(b -> b.type.equals("workshop"));
        boolean hasLuxuryWorkshop = existing.stream().anyMatch(b -> b.type.equals("luxury_workshop"));

        // the economy has to be built in order: a settlement's first
        // business is always a farm, then a market - only once both exist
        // can resource-extraction businesses form, and only once there's a
        // real iron supply chain running can a workshop (tools) follow, and
        // only once there's a real gold_ore stockpile can a luxury workshop
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

        if (hasIronExtraction && !hasWorkshop && s.stock.getOrDefault("iron", 0.0) > WORKSHOP_INPUT_THRESHOLD) {
          if (Math.random() < 0.02) { foundBusiness(state, s, n, "workshop", "tools"); continue; }
        }
        if (!hasLuxuryWorkshop && s.stock.getOrDefault("gold_ore", 0.0) > LUXURY_INPUT_THRESHOLD) {
          if (Math.random() < 0.02) { foundBusiness(state, s, n, "luxury_workshop", "luxury"); continue; }
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
      } else if (b.type.equals("workshop")) {
        // tools: real manufacturing, iron worked with wood into a finished
        // good worth several times the raw iron it started as
        double iron = Math.min(1.2, s.stock.getOrDefault("iron", 0.0));
        double wood = Math.min(0.8, s.stock.getOrDefault("wood", 0.0));
        s.stock.merge("iron", -iron, Double::sum);
        s.stock.merge("wood", -wood, Double::sum);
        s.stock.merge("tools", (iron + wood) * b.productivity * 1.1, Double::sum);
      } else if (b.type.equals("luxury_workshop")) {
        // luxury goods: gold_ore and stone crafted into jewelry/fine
        // goods - low volume, high value per unit
        double gold = Math.min(0.4, s.stock.getOrDefault("gold_ore", 0.0));
        double stone = Math.min(0.8, s.stock.getOrDefault("stone", 0.0));
        s.stock.merge("gold_ore", -gold, Double::sum);
        s.stock.merge("stone", -stone, Double::sum);
        s.stock.merge("luxury", (gold * 2.5 + stone * 0.3) * b.productivity, Double::sum);
      }

      if (!b.type.equals("market")) {
        double surplus = Math.max(0, s.stock.getOrDefault(b.resourceKey, 0.0) - Config.SETTLEMENT_BUFFER);
        double skim = surplus * 0.15;
        s.stock.merge(b.resourceKey, -skim, Double::sum);
        revenue = skim * nationPrice(state, n, b.resourceKey) * b.productivity * govMultiplier * n.econCycle;
        state.market.nudge(b.resourceKey, 1, 0.4);
      }
      n.gdpAccum += revenue;
      n.sectorRevenue.merge(b.sector(), revenue, Double::sum);

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
        int age = state.tick - n.founded;
        if (age < BANK_RUN_IMMUNITY_AGE) {
          // too young to survive a real run - the central bank quietly
          // prints to close the gap instead (same hidden "print through
          // it" trick updateCurrency uses for a treasury deficit, just
          // triggered by over-leverage instead), gradually rather than an
          // instant top-up so it still shows up as real inflation, not a
          // free pass
          double shortfall = bank.loans / LEVERAGE_LIMIT - bank.reserves;
          double printed = shortfall * 0.12;
          bank.reserves += printed;
          n.moneySupply += printed;
          n.printedThisWindow += printed;
        } else {
          double crashChance = 0.01 * Math.min(4.0, bank.loans / (bank.reserves * LEVERAGE_LIMIT));
          if (Math.random() < crashChance) {
            bank.reserves = 0;
            bank.loans *= 0.4;
            n.treasury -= n.treasury * 0.35 + 40;
            for (Business b : state.businesses.values()) {
              if (b.nationId == n.id) b.capital -= 30;
            }
            bank.justCrashed = true;
            EventLog.log(state, "economy", "The " + n.currencyName + " bank of " + n.name
                + " suffered a run - over-leveraged on loans it couldn't cover, its reserves were wiped out");
          }
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
