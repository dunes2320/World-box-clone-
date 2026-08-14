package com.worldbox.sim;

import com.worldbox.config.Config;

import java.util.ArrayList;
import java.util.List;

public class Diplomacy {

  private static final double CONTACT_RANGE = 34;

  /** Closest distance between any pair of the two nations' settlements, or
   * Double.MAX_VALUE if they share none close enough to compare. Used both
   * as the "are these nations even in contact" gate and, at the peace
   * branch below, to tell a tense border neighbor from a nation that's
   * merely within trading range. */
  private static double nearestDistance(GameState state, Nation a, Nation b) {
    double best = Double.MAX_VALUE;
    for (int sidA : a.settlementIds) {
      Settlement sA = state.settlements.get(sidA);
      if (sA == null) continue;
      for (int sidB : b.settlementIds) {
        Settlement sB = state.settlements.get(sidB);
        if (sB == null) continue;
        best = Math.min(best, Math.hypot(sA.x - sB.x, sA.z - sB.z));
      }
    }
    return best;
  }

  public static void update(GameState state) {
    DiplomacyManager dip = state.diplomacy;
    List<Nation> nations = new ArrayList<>();
    for (Nation n : state.nations.values()) if (n.alive) nations.add(n);

    for (DiplomacyManager.Relation r : dip.relations.values()) {
      if (r.status.equals(Config.WAR)) {
        r.score = clamp(r.score - 0.04, -100, 100);
      } else if (r.status.equals(Config.ALLIANCE)) {
        r.score = clamp(r.score + 0.02, -100, 100);
      } else if (r.status.equals(Config.TRUCE)) {
        r.truceTimer--;
        if (r.truceTimer <= 0) r.status = Config.PEACE;
      } else {
        r.score = clamp(r.score + (Math.random() - 0.5) * 0.06, -100, 100);
      }
    }

    for (Nation nation : nations) {
      if ((state.tick + nation.id * 3) % Config.DECISION_INTERVAL != 0) continue;
      double myPower = Nation.totalMilitaryPower(state, nation.id) + 1;

      for (Nation other : nations) {
        if (other.id == nation.id) continue;
        double dist = nearestDistance(state, nation, other);
        if (dist > CONTACT_RANGE) continue;
        String status = dip.getStatus(nation.id, other.id);
        double score = dip.getScore(nation.id, other.id);
        double theirPower = Nation.totalMilitaryPower(state, other.id) + 1;

        // an ambitious leader is bolder about starting a fight they might
        // not be fully ready for; a cautious one waits for a clearer edge
        double ambition = nation.leader != null ? nation.leader.personality.ambition : 0.5;
        if (status.equals(Config.PEACE)) {
          // border friction scales with how close the two actually are -
          // a shared border is real tension, but a nation only just within
          // contact range has room to trade instead of just grinding
          // toward war. Previously this was a flat -0.4 with nothing ever
          // pushing the other way, which meant every peaceful neighbor
          // inevitably slid toward war (or, once too weak to fight, an
          // inert dead stalemate) and an alliance - which needs a POSITIVE
          // score - could never actually be reached organically. That's
          // why wars stayed rare and alliances never happened at all.
          //
          // This only runs once every Config.DECISION_INTERVAL (45) ticks
          // per pair, not every tick - the magnitudes below are sized
          // against THAT cadence (roughly matching how the old flat -0.4
          // took ~11 years to grind a pair down to the -35 war threshold),
          // not a per-tick rate. A first attempt at this fix used
          // per-tick-sized deltas (~0.1-0.2) here and it was still 40+
          // years to reach an alliance in practice - re-verified against
          // an actual multi-year soak, not just the formula on paper.
          double proximity = clamp(1 - dist / CONTACT_RANGE, 0, 1);
          double friction = 0.6 * proximity;
          double tradeBenefit = 0.55 * (1 - proximity);
          dip.adjustScore(nation.id, other.id, tradeBenefit - friction);
          // real trade: an occasional mutual income bump, more likely the
          // more comfortable (less bordered) the relationship is - this is
          // the actual "trade" the diplomacy system was missing entirely
          if (Math.random() < 0.02 * (1 - proximity * 0.6)) {
            double value = 5 + Math.random() * 9;
            nation.treasury += value;
            other.treasury += value;
            nation.gdpAccum += value * 0.4;
            other.gdpAccum += value * 0.4;
          }
          if (score > 55 && Math.random() < 0.5) {
            dip.setStatus(nation.id, other.id, Config.ALLIANCE);
            dip.adjustScore(nation.id, other.id, 10);
            if (nation.id < other.id) EventLog.log(state, "war", nation.name + " and " + other.name + " formed an alliance");
          } else if (score < -35 && myPower > theirPower * (1.5 - ambition * 0.4) && Math.random() < 0.15 + ambition * 0.3) {
            dip.setStatus(nation.id, other.id, Config.WAR);
            dip.adjustScore(nation.id, other.id, -20);
            EventLog.log(state, "war", nation.name + " declared war on " + other.name);
          }
        } else if (status.equals(Config.WAR)) {
          if (myPower < theirPower * 0.35 || myPower < 3) {
            dip.setStatus(nation.id, other.id, Config.TRUCE, 220);
            dip.adjustScore(nation.id, other.id, 6);
            if (nation.id < other.id) EventLog.log(state, "war", nation.name + " and " + other.name + " agreed to a truce");
          }
        } else if (status.equals(Config.ALLIANCE)) {
          if (score < 10 && Math.random() < 0.03) {
            dip.setStatus(nation.id, other.id, Config.PEACE);
          }
        }
      }
    }
  }

  public static void onConquest(GameState state, int winnerNationId, int loserNationId) {
    state.diplomacy.adjustScore(winnerNationId, loserNationId, -15);
  }

  // --- player ("god") actions: any nation can be directly commanded ---
  public static void forceWar(GameState state, int a, int b) {
    state.diplomacy.setStatus(a, b, Config.WAR);
    state.diplomacy.adjustScore(a, b, -25);
  }

  public static void forcePeace(GameState state, int a, int b) {
    state.diplomacy.setStatus(a, b, Config.TRUCE, 260);
    state.diplomacy.adjustScore(a, b, 8);
  }

  public static void forceAlliance(GameState state, int a, int b) {
    state.diplomacy.setStatus(a, b, Config.ALLIANCE);
    state.diplomacy.adjustScore(a, b, 20);
  }

  public static void divineGift(GameState state, int nationId, double amount) {
    Nation n = state.nations.get(nationId);
    if (n != null) n.treasury += amount;
  }

  private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
