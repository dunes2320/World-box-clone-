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
    boolean sample = state.tick % 20 == 0;
    double worldTotal = 0;
    for (Nation n : new ArrayList<>(state.nations.values())) {
      if (!n.alive) continue;
      applyGovernmentEffects(n);
      updateStability(state, n);
      maybeSuccession(state, n);
      maybeRevolt(state, n);
      if (sample) {
        n.treasuryHistory.addLast(n.treasury);
        while (n.treasuryHistory.size() > 120) n.treasuryHistory.removeFirst();
        worldTotal += n.treasury;
      }
    }
    if (sample) {
      state.worldEconomyHistory.addLast(worldTotal);
      while (state.worldEconomyHistory.size() > 120) state.worldEconomyHistory.removeFirst();
    }
  }

  private static void applyGovernmentEffects(Nation n) {
    if (n.government.equals(AUTOCRACY)) {
      // corruption: a slice of the treasury quietly leaks away
      n.treasury -= Math.max(0, n.treasury) * 0.004;
    }
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

    int atWar = 0;
    for (DiplomacyManager.PairInfo p : state.diplomacy.pairsInvolving(n.id)) {
      if (p.relation.status.equals(Config.WAR)) atWar++;
    }
    drift -= atWar * 0.03;

    n.stability = clamp(n.stability + drift, 0, 100);
  }

  private static void maybeSuccession(GameState state, Nation n) {
    if (!n.government.equals(MONARCHY)) return;
    if (state.tick % 400 != 0) return;
    if (Math.random() < 0.05) {
      n.stability -= 10 + Math.random() * 10;
      if (Math.random() < 0.3) n.treasury *= 0.85; // a costly war of succession
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

  private static void maybeRevolt(GameState state, Nation n) {
    if (n.stability > 15) return;
    if (Math.random() > 0.02) return;

    for (int sid : n.settlementIds) {
      Settlement s = state.settlements.get(sid);
      if (s == null) continue;
      List<Human> residents = new ArrayList<>();
      for (Human h : state.humans) if (h.settlementId == s.id) residents.add(h);
      int losses = (int) (residents.size() * (0.05 + Math.random() * 0.1));
      for (int i = 0; i < losses && !residents.isEmpty(); i++) {
        residents.remove((int) (Math.random() * residents.size())).dead = true;
      }
      s.stock.merge("food", -s.stock.getOrDefault("food", 0.0) * 0.3, Double::sum);
    }
    state.humans.removeIf(h -> h.dead);
    n.treasury *= 0.7;

    if (n.settlementIds.size() > 1 && !n.government.equals(AUTOCRACY) && Math.random() < 0.35) {
      secede(state, n);
    } else {
      n.government = nextGovernmentAfterUnrest(n.government);
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
      n.alive = false;
      state.nations.remove(n.id);
    }
  }
}
