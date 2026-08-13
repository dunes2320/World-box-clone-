package com.worldbox.sim;

import com.worldbox.config.Config;

import java.util.ArrayList;
import java.util.List;

public class Diplomacy {

  private static boolean nationsAreNear(GameState state, Nation a, Nation b, double maxDist) {
    for (int sidA : a.settlementIds) {
      Settlement sA = state.settlements.get(sidA);
      if (sA == null) continue;
      for (int sidB : b.settlementIds) {
        Settlement sB = state.settlements.get(sidB);
        if (sB == null) continue;
        if (Math.hypot(sA.x - sB.x, sA.z - sB.z) <= maxDist) return true;
      }
    }
    return false;
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
        if (!nationsAreNear(state, nation, other, 34)) continue;
        String status = dip.getStatus(nation.id, other.id);
        double score = dip.getScore(nation.id, other.id);
        double theirPower = Nation.totalMilitaryPower(state, other.id) + 1;

        // an ambitious leader is bolder about starting a fight they might
        // not be fully ready for; a cautious one waits for a clearer edge
        double ambition = nation.leader != null ? nation.leader.personality.ambition : 0.5;
        if (status.equals(Config.PEACE)) {
          // neighbors compete for the same land and resources - without
          // some steady friction here, the relationship score only ever
          // moves via a tiny random walk that would take millions of
          // ticks to organically drift past the war threshold below, so
          // wars in practice never happened on their own and every army
          // just sat home forever with nothing to do
          dip.adjustScore(nation.id, other.id, -0.4);
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
