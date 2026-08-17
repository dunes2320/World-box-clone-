package com.worldbox.sim;

import com.worldbox.config.Config;

import java.util.ArrayList;
import java.util.List;

/** Each nation has a form of government that meaningfully shapes how it
 * behaves: corruption, stability, and how it responds to crisis. Let a
 * nation's stability collapse and it can suffer a real revolt - population
 * losses, a change of government, or a settlement seceding into its own
 * new nation. */
public class Government {
  public static final String DEMOCRACY = "democracy";
  public static final String AUTOCRACY = "autocracy";
  public static final String MONARCHY = "monarchy";
  public static final String OLIGARCHY = "oligarchy";
  public static final String[] TYPES = {DEMOCRACY, AUTOCRACY, MONARCHY, OLIGARCHY};

  public static String random() { return TYPES[(int) (Math.random() * TYPES.length)]; }

  public static void update(GameState state) {
    // once a month, not every 20 ticks - a monthly cadence paired with
    // the 10-year retention window below gives a graph with real,
    // legible resolution instead of a nearly-continuous firehose of
    // samples that just piles up forever
    boolean sample = state.tick % com.worldbox.util.Calendar.DAYS_PER_MONTH == 0;
    double worldTreasury = 0;
    double worldMarketCap = 0;
    double worldGdp = 0;
    double worldStabilityWeighted = 0;
    double worldMilitary = 0;
    int worldPop = 0;
    java.util.Map<String, Double> worldSectorAccum = new java.util.HashMap<>();
    for (String sector : Config.SECTORS) worldSectorAccum.put(sector, 0.0);
    java.util.Map<Integer, Double> marketCapByNation = null;
    java.util.Map<Integer, int[]> laborByNation = null; // [total, unemployed]
    java.util.Map<Integer, double[]> wealthByNation = null; // [totalWealth, count]
    if (sample) {
      marketCapByNation = new java.util.HashMap<>();
      for (Business b : state.businesses.values()) {
        marketCapByNation.merge(b.nationId, b.valuation, Double::sum);
        worldMarketCap += b.valuation;
      }
      laborByNation = new java.util.HashMap<>();
      wealthByNation = new java.util.HashMap<>();
      for (Human h : state.humans) {
        if (h.nationId == Config.UNDEAD_NATION_ID || h.nationId < 0) continue;
        int[] counts = laborByNation.computeIfAbsent(h.nationId, k -> new int[2]);
        counts[0]++;
        // only count someone as unemployed if they're actually trying to
        // work and can't - not just off duty on their scheduled home/
        // leisure time, which would otherwise inflate this hugely
        if (h.job == null && h.routine.equals("work")) counts[1]++;

        double[] wealth = wealthByNation.computeIfAbsent(h.nationId, k -> new double[2]);
        wealth[0] += h.wealth;
        wealth[1]++;
      }
    }
    for (Nation n : new ArrayList<>(state.nations.values())) {
      if (!n.alive) continue;
      applyGovernmentEffects(n);
      updateStability(state, n);
      maybeSuccession(state, n);
      maybeRevolt(state, n);
      if (sample) {
        n.treasuryHistory.addLast(n.treasury);
        trim(n.treasuryHistory);
        worldTreasury += n.treasury;

        n.marketCapHistory.addLast(marketCapByNation.getOrDefault(n.id, 0.0));
        trim(n.marketCapHistory);

        int[] labor = laborByNation.getOrDefault(n.id, new int[2]);
        double unemployment = labor[0] == 0 ? 0 : (double) labor[1] / labor[0];
        n.unemploymentRate = unemployment;
        n.unemploymentHistory.addLast(unemployment);
        trim(n.unemploymentHistory);

        n.gdpHistory.addLast(n.gdpAccum);
        trim(n.gdpHistory);
        worldGdp += n.gdpAccum;

        // money in circulation should track how big the real economy
        // actually is, not just sit frozen at its founding value while
        // treasury/bank/business figures grow around it - this is
        // separate from (and doesn't count toward) printedThisWindow, so
        // it never shows up as inflation; only reckless deficit printing
        // does that
        n.moneySupply += Math.max(0, n.gdpAccum) * 0.15;

        updateInflationAndExchangeRate(state, n);
        n.gdpAccum = 0;

        n.currencyHistory.addLast(n.exchangeRate);
        trim(n.currencyHistory);
        n.inflationHistory.addLast(n.inflationRate);
        trim(n.inflationHistory);

        double[] wealth = wealthByNation.getOrDefault(n.id, new double[2]);
        n.wealthHistory.addLast(wealth[1] > 0 ? wealth[0] / wealth[1] : 0);
        trim(n.wealthHistory);

        n.stabilityHistory.addLast(n.stability);
        trim(n.stabilityHistory);
        worldStabilityWeighted += n.stability * labor[0];
        worldPop += labor[0];

        double military = Nation.totalMilitaryPower(state, n.id);
        n.militaryHistory.addLast(military);
        trim(n.militaryHistory);
        worldMilitary += military;

        for (String sector : Config.SECTORS) {
          double rev = n.sectorRevenue.getOrDefault(sector, 0.0);
          java.util.ArrayDeque<Double> hist = n.sectorHistory.get(sector);
          hist.addLast(rev);
          trim(hist);
          worldSectorAccum.merge(sector, rev, Double::sum);
          n.sectorRevenue.put(sector, 0.0);
        }
      }
    }
    if (sample) {
      state.worldStabilityHistory.addLast(worldPop > 0 ? worldStabilityWeighted / worldPop : 0);
      trim(state.worldStabilityHistory);

      state.worldMilitaryHistory.addLast(worldMilitary);
      trim(state.worldMilitaryHistory);

      state.worldEconomyHistory.addLast(worldTreasury);
      trim(state.worldEconomyHistory);

      state.worldMarketCapHistory.addLast(worldMarketCap);
      trim(state.worldMarketCapHistory);

      state.worldGdpHistory.addLast(worldGdp);
      trim(state.worldGdpHistory);

      for (String sector : Config.SECTORS) {
        java.util.ArrayDeque<Double> hist = state.worldSectorHistory.get(sector);
        hist.addLast(worldSectorAccum.getOrDefault(sector, 0.0));
        trim(hist);
      }
    }
  }

  // 120 monthly samples = a rolling 10-year window, per the game's own
  // request: recent trend detail matters more than the whole game's
  // history piling up in one chart forever
  private static void trim(java.util.ArrayDeque<Double> dq) {
    while (dq.size() > 120) dq.removeFirst();
  }

  /** Inflation is measured the way it actually happens: new money entering
   * circulation this window versus how much real output grew to absorb
   * it. Printing to cover a deficit with no matching real growth is what
   * debases a currency; growing the real economy instead doesn't. A
   * currency that hyperinflates past recognition collapses for good -
   * exactly like a real currency that loses all public confidence never
   * gets it back. */
  private static void updateInflationAndExchangeRate(GameState state, Nation n) {
    if (n.currencyCollapsed) {
      n.exchangeRate = 0;
      n.printedThisWindow = 0;
      return;
    }

    // real output growth is a much gentler counterweight than money
    // printing is a danger - this is deliberately asymmetric so healthy
    // growth nudges a currency up slowly, while reckless printing can
    // genuinely wreck it. This math itself is correct as designed (a
    // healthy, non-printing economy trending mildly deflationary, which
    // is what actually drives exchangeRate strengthening below) - the
    // earlier "fix" here was wrong and has been reverted. What actually
    // needed to change was only how that number gets DISPLAYED to the
    // player (see GameHud.annualInflation and metricHistory's "inflation"
    // case), not the underlying calculation.
    double supplyGrowth = n.moneySupply > 1 ? n.printedThisWindow / n.moneySupply : 0;
    double outputGrowth = n.moneySupply > 1 ? Math.max(0, n.gdpAccum) / Math.max(400, n.moneySupply * 4) : 0;
    double windowInflation = supplyGrowth - outputGrowth * 0.1;
    n.inflationRate = n.inflationRate * 0.85 + windowInflation * 0.15;
    n.printedThisWindow = 0;

    double drag = clamp(n.inflationRate, -0.03, 0.5) * 0.3;
    n.exchangeRate = Math.max(0, Math.min(3.0, n.exchangeRate * (1 - drag)));

    if (n.exchangeRate < 0.02) {
      n.currencyCollapsed = true;
      n.exchangeRate = 0;
      n.treasury *= 0.1; // hyperinflation wipes out real savings, not just the number on paper
      EventLog.log(state, "economy", "The " + n.currencyName + " has collapsed - hyperinflation wipes out savings across " + n.name);
    }
  }

  private static void applyGovernmentEffects(Nation n) {
    // corruption: a slice of the treasury quietly leaks away - how much
    // depends on the leader personally (greed), not just the system they
    // sit in, though an autocracy gives them more room to get away with it
    double corruption = n.leader != null ? n.leader.personality.greed * 0.006 : 0.003;
    if (n.government.equals(AUTOCRACY)) corruption += 0.003;
    n.treasury -= Math.max(0, n.treasury) * corruption;
  }

  private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

  private static void updateStability(GameState state, Nation n) {
    double drift;
    switch (n.government) {
      case DEMOCRACY: drift = 0.05; break;
      case MONARCHY: drift = 0.015; break;
      case OLIGARCHY: drift = -0.01; break;
      default: drift = -0.02; break; // autocracy: resentment simmers
    }
    if (n.treasury < 0) drift -= 0.05;
    if (n.bank.justCrashed) drift -= 8;
    if (n.leader != null) drift += (n.leader.personality.wisdom - 0.5) * 0.04 - (n.leader.personality.greed - 0.5) * 0.02;

    int atWar = 0;
    for (DiplomacyManager.PairInfo p : state.diplomacy.pairsInvolving(n.id)) {
      if (p.relation.status.equals(Config.WAR)) atWar++;
    }
    drift -= atWar * 0.03;

    // unemployment erodes stability, but a young/small nation gets a lot
    // of slack (teething problems aren't a crisis yet) while an old,
    // established nation is expected to have its economy figured out by
    // now, so the same jobless rate hurts it far more
    int pop = 0;
    for (int sid : n.settlementIds) {
      Settlement s = state.settlements.get(sid);
      if (s != null) pop += s.populationCount;
    }
    double ageYears = com.worldbox.util.Calendar.ageYears(Math.max(0, state.tick - n.founded));
    double maturity = clamp(ageYears / 40.0, 0, 1) * 0.6 + clamp(pop / 150.0, 0, 1) * 0.4;
    if (n.unemploymentRate > 0.15) {
      drift -= (n.unemploymentRate - 0.15) * (0.4 + maturity * 2.6);
    }

    n.stability = clamp(n.stability + drift, 0, 100);
  }

  private static void maybeSuccession(GameState state, Nation n) {
    if (!n.government.equals(MONARCHY)) return;
    if (state.tick % 400 != 0) return;
    if (Math.random() < 0.05) {
      n.stability -= 10 + Math.random() * 10;
      if (Math.random() < 0.3) n.treasury *= 0.85; // a costly war of succession
      n.leader = new Leader(n.government); // the throne passes to someone new
    }
  }

  private static String nextGovernmentAfterUnrest(String current) {
    switch (current) {
      case AUTOCRACY: return Math.random() < 0.7 ? DEMOCRACY : OLIGARCHY;
      case OLIGARCHY: return Math.random() < 0.6 ? DEMOCRACY : AUTOCRACY;
      case MONARCHY: return Math.random() < 0.5 ? DEMOCRACY : AUTOCRACY;
      default: return Math.random() < 0.5 ? AUTOCRACY : OLIGARCHY; // democracy collapsing into a coup
    }
  }

  /** Stability is one clamped number folding in half a dozen different
   * pressures (see updateStability) - by the time it actually triggers a
   * revolt, a player reading "X was rocked by revolt" has no way to tell
   * which of those pressures actually did it. Re-checks the same signals
   * updateStability reads and names whichever is currently worst, so the
   * log entry says why, not just what. */
  private static String dominantInstabilityCause(GameState state, Nation n) {
    int atWar = 0;
    for (DiplomacyManager.PairInfo p : state.diplomacy.pairsInvolving(n.id)) {
      if (p.relation.status.equals(Config.WAR)) atWar++;
    }
    if (n.bank.justCrashed) return "a bank run";
    if (atWar >= 2) return "fighting on multiple fronts";
    if (atWar == 1) return "the strain of war";
    if (n.unemploymentRate > 0.25) return "runaway unemployment";
    if (n.treasury < 0) return "an empty treasury";
    if (n.leader != null && n.leader.personality.greed > 0.75) return "a corrupt, self-dealing leader";
    if (n.unemploymentRate > 0.15) return "rising unemployment";
    return "long-simmering discontent";
  }

  private static void maybeRevolt(GameState state, Nation n) {
    if (n.stability > 15) return;
    if (Math.random() > 0.02) return;
    String cause = dominantInstabilityCause(state, n);

    for (int sid : n.settlementIds) {
      Settlement s = state.settlements.get(sid);
      if (s == null) continue;
      List<Human> residents = new ArrayList<>();
      for (Human h : state.humans) if (h.settlementId == s.id) residents.add(h);
      // rounding down a 5-15% loss hits zero for any settlement under ~7
      // people, so a tiny failed nation could revolt forever and never
      // actually lose anyone - a real collapse has to draw blood even
      // when there are only a couple of citizens left to lose
      int losses = (int) (residents.size() * (0.05 + Math.random() * 0.1));
      if (losses == 0 && !residents.isEmpty()) losses = 1;
      for (int i = 0; i < losses && !residents.isEmpty(); i++) {
        residents.remove((int) (Math.random() * residents.size())).dead = true;
        DeathStats.revolt++;
      }
      s.stock.merge("food", -s.stock.getOrDefault("food", 0.0) * 0.3, Double::sum);
    }
    state.humans.removeIf(h -> h.dead);
    n.treasury *= 0.7;

    if (n.settlementIds.size() > 1 && !n.government.equals(AUTOCRACY) && Math.random() < 0.35
        && state.nations.size() < Config.MAX_NATIONS) {
      String oldName = n.name;
      secede(state, n);
      EventLog.log(state, "nation", "A breakaway settlement seceded from " + oldName + ", driven by " + cause);
    } else {
      String oldGov = n.government;
      n.government = nextGovernmentAfterUnrest(n.government);
      n.leader = new Leader(n.government); // a coup/revolt installs a new leader
      n.issueNewCurrency(); // ...and the new regime issues its own currency
      EventLog.log(state, "nation", n.name + " was rocked by revolt over " + cause + " - " + oldGov + " gave way to " + n.government);
    }
    n.stability = 40;
  }

  private static void secede(GameState state, Nation n) {
    Integer breakawayId = null;
    for (int sid : n.settlementIds) {
      if (sid != n.capitalSettlementId) { breakawayId = sid; break; }
    }
    if (breakawayId == null) return;
    Settlement breakaway = state.settlements.get(breakawayId);
    if (breakaway == null) return;
    n.settlementIds.remove(breakawayId);
    Nation seceded = Nation.create(state, breakaway, Nation.randomNationName(state.rng));
    seceded.stability = 35;
    state.diplomacy.adjustScore(n.id, seceded.id, -40);
    if (n.settlementIds.isEmpty()) {
      Nation.killNation(state, n);
    }
  }
}
