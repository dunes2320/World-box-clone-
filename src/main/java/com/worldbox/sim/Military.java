package com.worldbox.sim;

import com.worldbox.config.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Military {
  private static final double ENGAGE_RANGE = 1.7;

  public static double armyStrength(Army army) {
    double s = 0;
    for (Map.Entry<String, Integer> e : army.units.entrySet()) {
      s += e.getValue() * Config.UNIT_TYPES.get(e.getKey()).power;
    }
    return s;
  }

  public static double armySpeed(Army army) {
    double total = 0, weight = 0;
    for (Map.Entry<String, Integer> e : army.units.entrySet()) {
      int n = e.getValue();
      total += Config.UNIT_TYPES.get(e.getKey()).speed * n;
      weight += n;
    }
    return weight > 0 ? total / weight : 0.1;
  }

  public static int armyUnitCount(Army army) {
    int n = 0;
    for (int v : army.units.values()) n += v;
    return n;
  }

  private static void applyDamage(GameState state, Army army, double dmg) {
    double total = armyStrength(army);
    if (total <= 0 || dmg <= 0) return;
    double ratio = Math.min(1, dmg / total);
    for (String key : army.units.keySet()) {
      army.units.put(key, Math.max(0, (int) Math.floor(army.units.get(key) * (1 - ratio))));
    }
    army.strength = armyStrength(army);
    army.combatFlashTimer = 18;
    if (armyUnitCount(army) <= 0) killArmy(state, army);
  }

  public static void damageArmy(GameState state, Army army, double dmg) { applyDamage(state, army, dmg); }

  private static void killArmy(GameState state, Army army) {
    army.dead = true;
    Nation nation = state.nations.get(army.nationId);
    if (nation != null) nation.armyIds.remove(army.id);
  }

  public static class RaiseResult {
    public final boolean ok;
    public final String reason;
    public final int count;
    RaiseResult(boolean ok, String reason, int count) { this.ok = ok; this.reason = reason; this.count = count; }
  }

  /** Pulls villagers out of a settlement's workforce and turns them into a
   * unit batch, funded by the settlement's raw stock + the nation's gold
   * treasury. */
  public static RaiseResult raiseArmy(GameState state, int settlementId, String unitType) {
    Settlement settlement = state.settlements.get(settlementId);
    if (settlement == null) return new RaiseResult(false, "no settlement", 0);
    Nation nation = state.nations.get(settlement.nationId);
    if (nation == null) return new RaiseResult(false, "no nation", 0);
    Config.UnitSpec spec = Config.UNIT_TYPES.get(unitType);
    if (spec == null) return new RaiseResult(false, "bad unit", 0);

    List<Human> civilians = new ArrayList<>();
    for (Human h : state.humans) if (h.settlementId == settlementId) civilians.add(h);
    int count = Math.min(Config.RAISE_BATCH, civilians.size() - 3); // keep a few workers behind
    if (count <= 0) return new RaiseResult(false, "not enough population", 0);

    double goldCost = spec.cost.getOrDefault("gold", 0.0) * count;
    if (nation.treasury < goldCost) return new RaiseResult(false, "not enough gold", 0);
    for (Map.Entry<String, Double> e : spec.cost.entrySet()) {
      if (e.getKey().equals("gold")) continue;
      double need = e.getValue() * count;
      if (settlement.stock.getOrDefault(e.getKey(), 0.0) < need) return new RaiseResult(false, "not enough " + e.getKey(), 0);
    }

    nation.treasury -= goldCost;
    for (Map.Entry<String, Double> e : spec.cost.entrySet()) {
      if (e.getKey().equals("gold")) continue;
      settlement.stock.merge(e.getKey(), -(e.getValue() * count), Double::sum);
    }

    for (int i = 0; i < count; i++) {
      state.humans.remove(civilians.get(i));
    }

    Army army = null;
    for (Army a : state.armies.values()) {
      if (a.nationId == settlement.nationId && a.homeSettlementId == settlementId
          && a.targetSettlementId == null && a.targetArmyId == null) { army = a; break; }
    }
    if (army == null) {
      army = new Army(settlement.nationId, settlementId, settlement.x + 0.5, settlement.z + 0.5);
      state.armies.put(army.id, army);
      nation.armyIds.add(army.id);
    }
    army.units.merge(unitType, count, Integer::sum);
    army.strength = armyStrength(army);
    return new RaiseResult(true, null, count);
  }

  private static Settlement nearestEnemySettlement(GameState state, Nation nation) {
    Settlement best = null;
    double bestD = Double.MAX_VALUE;
    for (Settlement s : state.settlements.values()) {
      if (s.abandoned || s.nationId == nation.id) continue;
      String rel = state.diplomacy.getStatus(nation.id, s.nationId);
      if (!rel.equals(Config.WAR)) continue;
      for (int sid : nation.settlementIds) {
        Settlement home = state.settlements.get(sid);
        if (home == null) continue;
        double d = Math.hypot(s.x - home.x, s.z - home.z);
        if (d < bestD) { bestD = d; best = s; }
      }
    }
    return best;
  }

  private static double moveArmyToward(Army army, double tx, double tz, double speed) {
    double dx = tx - army.x, dz = tz - army.z;
    double dist = Math.hypot(dx, dz);
    if (dist < 0.05) return 0;
    double step = Math.min(dist, speed);
    army.x += (dx / dist) * step;
    army.z += (dz / dist) * step;
    return dist;
  }

  public static void update(GameState state) {
    for (Nation nation : state.nations.values()) {
      double upkeep = 0;
      for (int aid : nation.armyIds) {
        Army a = state.armies.get(aid);
        if (a != null) for (Map.Entry<String, Integer> e : a.units.entrySet()) {
          upkeep += e.getValue() * Config.UNIT_TYPES.get(e.getKey()).upkeep;
        }
      }
      nation.treasury -= upkeep;
      if (nation.treasury < -80) {
        for (int aid : new ArrayList<>(nation.armyIds)) {
          Army a = state.armies.get(aid);
          if (a != null && Math.random() < 0.15) applyDamage(state, a, armyStrength(a) * 0.2);
        }
        nation.treasury = -80;
      }

      if ((state.tick + nation.id) % 12 == 0) {
        for (int aid : new ArrayList<>(nation.armyIds)) {
          Army army = state.armies.get(aid);
          if (army == null || army.dead) continue;
          if (army.targetSettlementId != null || army.targetArmyId != null) continue;
          double strength = armyStrength(army);
          if (strength < 4) continue;
          Settlement target = nearestEnemySettlement(state, nation);
          if (target != null) { army.targetSettlementId = target.id; army.state = "marching"; }
        }

        // a nation actually at war and with nothing to fight with was the
        // core of "wars are broken, nobody fights" - the old flat
        // treasury>260/35% gate was well above what even a militia batch
        // costs (6 x 15 gold), so most nations sat at war for its whole
        // duration without ever fielding a single unit. A nation under
        // real threat now raises much more readily than one that's just
        // idly stockpiling for eventual conquest.
        boolean atWar = false;
        for (DiplomacyManager.PairInfo p : state.diplomacy.pairsInvolving(nation.id)) {
          if (p.relation.status.equals(Config.WAR)) { atWar = true; break; }
        }
        double raiseThreshold = atWar ? 60 : 150;
        double raiseChance = atWar ? 0.7 : 0.35;
        if (nation.treasury > raiseThreshold && Math.random() < raiseChance && !nation.settlementIds.isEmpty()) {
          List<Integer> sids = new ArrayList<>(nation.settlementIds);
          int sid = sids.get((int) (Math.random() * sids.size()));
          String cheapest = nation.treasury > 600 ? "knight" : nation.treasury > 350 ? "swordsman" : "militia";
          raiseArmy(state, sid, cheapest);
        }
      }
    }

    for (Army army : state.armies.values()) {
      if (army.dead) continue;
      army.prevX = army.x; army.prevZ = army.z;
      army.strength = armyStrength(army);
      if (army.combatFlashTimer > 0) army.combatFlashTimer--;
      double tx = army.targetX, tz = army.targetZ;
      if (army.targetSettlementId != null) {
        Settlement s = state.settlements.get(army.targetSettlementId);
        if (s == null) { army.targetSettlementId = null; }
        else { tx = s.x + 0.5; tz = s.z + 0.5; }
      }
      army.targetX = tx; army.targetZ = tz;
      moveArmyToward(army, tx, tz, armySpeed(army));
    }

    resolveSieges(state);
    resolveFieldBattles(state);

    for (Integer id : new ArrayList<>(state.armies.keySet())) {
      Army a = state.armies.get(id);
      if (a.dead) state.armies.remove(id);
    }
  }

  private static void resolveSieges(GameState state) {
    for (Settlement settlement : state.settlements.values()) {
      if (settlement.abandoned) continue;
      List<Army> attackers = new ArrayList<>();
      for (Army army : state.armies.values()) {
        if (army.dead || army.nationId == settlement.nationId) continue;
        if (!state.diplomacy.getStatus(army.nationId, settlement.nationId).equals(Config.WAR)) continue;
        double d = Math.hypot(army.x - (settlement.x + 0.5), army.z - (settlement.z + 0.5));
        if (d <= ENGAGE_RANGE) attackers.add(army);
      }
      if (attackers.isEmpty()) {
        settlement.siegeProgress = Math.max(0, settlement.siegeProgress - 0.5);
        continue;
      }
      double defense = settlement.populationCount * 0.4 + 4;
      double attackTotal = 0;
      for (Army a : attackers) attackTotal += armyStrength(a);

      if (attackTotal > defense) {
        settlement.siegeProgress += (attackTotal - defense) * 0.05;
        for (Army a : attackers) applyDamage(state, a, defense * 0.09 * (armyStrength(a) / attackTotal));
      } else {
        settlement.siegeProgress = Math.max(0, settlement.siegeProgress - 1);
        for (Army a : attackers) applyDamage(state, a, defense * 0.1 / attackers.size());
      }

      if (settlement.siegeProgress >= 24) {
        Army winner = attackers.get(0);
        for (Army a : attackers) if (armyStrength(a) > armyStrength(winner)) winner = a;
        int oldNationId = settlement.nationId;
        Nation winnerNation = state.nations.get(winner.nationId);
        Nation loserNation = state.nations.get(oldNationId);
        Nation.transferSettlement(state, settlement, winner.nationId);
        settlement.siegeProgress = 0;
        winner.targetSettlementId = null;
        Diplomacy.onConquest(state, winner.nationId, oldNationId);
        EventLog.log(state, "war", (winnerNation != null ? winnerNation.name : "An unknown power")
            + " conquered " + settlement.name
            + (loserNation != null ? " from " + loserNation.name : ""));
      }
    }
  }

  private static void resolveFieldBattles(GameState state) {
    List<Army> armies = new ArrayList<>();
    for (Army a : state.armies.values()) if (!a.dead) armies.add(a);
    for (int i = 0; i < armies.size(); i++) {
      Army a = armies.get(i);
      if (a.dead) continue;
      for (int j = i + 1; j < armies.size(); j++) {
        Army b = armies.get(j);
        if (b.dead || a.nationId == b.nationId) continue;
        if (!state.diplomacy.getStatus(a.nationId, b.nationId).equals(Config.WAR)) continue;
        double d = Math.hypot(a.x - b.x, a.z - b.z);
        if (d > ENGAGE_RANGE) continue;
        double sa = armyStrength(a), sb = armyStrength(b);
        applyDamage(state, a, sb * 0.16 * (0.75 + Math.random() * 0.5));
        applyDamage(state, b, sa * 0.16 * (0.75 + Math.random() * 0.5));
      }
    }
  }
}
