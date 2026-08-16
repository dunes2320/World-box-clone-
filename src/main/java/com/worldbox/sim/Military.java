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
    int before = armyUnitCount(army);
    for (String key : army.units.keySet()) {
      army.units.put(key, Math.max(0, (int) Math.floor(army.units.get(key) * (1 - ratio))));
    }
    int lost = before - armyUnitCount(army);
    // every unit this battle actually cost is a specific real person, not
    // just a number - kill that many of this army's own roster (real
    // combat casualties, counted the same way a siege/sack death is)
    // rather than letting the roster silently drift out of sync with the
    // unit-count math above.
    if (lost > 0 && !army.memberHumanIds.isEmpty()) {
      java.util.Set<Integer> toKill = new java.util.HashSet<>();
      for (int i = 0; i < lost && !army.memberHumanIds.isEmpty(); i++) {
        int idx = (int) (Math.random() * army.memberHumanIds.size());
        toKill.add(army.memberHumanIds.remove(idx));
      }
      for (Human h : state.humans) {
        if (toKill.contains(h.id)) { h.dead = true; DeathStats.war++; }
      }
    }
    army.strength = armyStrength(army);
    army.combatFlashTimer = 18;
    if (armyUnitCount(army) <= 0) killArmy(state, army);
  }

  public static void damageArmy(GameState state, Army army, double dmg) { applyDamage(state, army, dmg); }

  /** Disbanding an army for any reason (wiped out, or a nation that fielded
   * it collapsing entirely) must never just leave real people stuck with
   * role=="soldier" forever with no army left to belong to - anyone still
   * on the roster at this point survived and goes back to being an
   * ordinary civilian. Actual combat deaths already happened in
   * applyDamage above; this is only ever a safety net for the (normally
   * empty) remainder. */
  public static void killArmy(GameState state, Army army) {
    if (army.dead) return;
    army.dead = true;
    Nation nation = state.nations.get(army.nationId);
    if (nation != null) nation.armyIds.remove(army.id);
    if (!army.memberHumanIds.isEmpty()) {
      java.util.Set<Integer> remaining = new java.util.HashSet<>(army.memberHumanIds);
      for (Human h : state.humans) {
        if (remaining.contains(h.id)) h.role = null;
      }
      army.memberHumanIds.clear();
    }
  }

  public static class RaiseResult {
    public final boolean ok;
    public final String reason;
    public final int count;
    RaiseResult(boolean ok, String reason, int count) { this.ok = ok; this.reason = reason; this.count = count; }
  }

  /** Picks who actually gets recruited out of a settlement's eligible
   * civilians, per the nation's recruitmentPolicy: a "volunteer" nation's
   * army fills up with the most ambitious/least settled people (higher
   * ambition, lower wisdom - the ones likeliest to actually choose this),
   * weighted-random rather than a hard cutoff so it's a strong lean, not
   * an absolute rule. A "conscription" nation just presses whoever's
   * available, unweighted - nobody gets a personality-driven say in it. */
  private static List<Human> pickRecruits(List<Human> eligible, String policy, int count) {
    List<Human> pool = new ArrayList<>(eligible);
    List<Human> picked = new ArrayList<>();
    boolean volunteer = "volunteer".equals(policy);
    for (int i = 0; i < count && !pool.isEmpty(); i++) {
      int idx;
      if (volunteer) {
        idx = 0;
        double bestScore = -1;
        for (int j = 0; j < pool.size(); j++) {
          Human h = pool.get(j);
          double score = (h.personality.ambition * 0.7 + (1 - h.personality.wisdom) * 0.3) * (0.5 + Math.random());
          if (score > bestScore) { bestScore = score; idx = j; }
        }
      } else {
        idx = (int) (Math.random() * pool.size());
      }
      picked.add(pool.remove(idx));
    }
    return picked;
  }

  /** Pulls real, named villagers out of a settlement's own population and
   * enlists them - chosen (volunteer) or pressed (conscription) per the
   * nation's recruitmentPolicy - funded by the settlement's raw stock +
   * the nation's gold treasury. They keep their identity (Human.role
   * flips to "soldier" instead of being deleted) and are tracked by id on
   * the army's roster; see applyDamage for what happens to them in
   * combat and demobilize for how they come home. */
  public static RaiseResult raiseArmy(GameState state, int settlementId, String unitType) {
    Settlement settlement = state.settlements.get(settlementId);
    if (settlement == null) return new RaiseResult(false, "no settlement", 0);
    Nation nation = state.nations.get(settlement.nationId);
    if (nation == null) return new RaiseResult(false, "no nation", 0);
    Config.UnitSpec spec = Config.UNIT_TYPES.get(unitType);
    if (spec == null) return new RaiseResult(false, "bad unit", 0);

    List<Human> civilians = new ArrayList<>();
    for (Human h : state.humans) if (h.settlementId == settlementId && h.role == null) civilians.add(h);
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

    List<Human> recruits = pickRecruits(civilians, nation.recruitmentPolicy, count);
    for (Human h : recruits) {
      h.role = "soldier";
      army.memberHumanIds.add(h.id);
    }
    army.units.merge(unitType, recruits.size(), Integer::sum);
    army.strength = armyStrength(army);
    return new RaiseResult(true, null, recruits.size());
  }

  /** Idle armies (not marching, not fighting, not besieging anything)
   * slowly release their people back to civilian life instead of serving
   * forever - a real standing army only makes sense while there's an
   * actual reason for one. Released soldiers just resume ordinary life
   * (role=null); Population.update picks their routine back up on its
   * own the next tick, same as any other civilian. */
  public static void demobilize(GameState state) {
    for (Army army : state.armies.values()) {
      if (army.dead || army.memberHumanIds.isEmpty()) continue;
      // not "state==idle": state only ever flips forward to "marching" and
      // never back (see update() below), so an army that ever marched
      // once would otherwise never be eligible again. Having no live
      // target and not being at war is what "nothing to do right now"
      // actually means.
      if (army.targetSettlementId != null || army.targetArmyId != null) continue;
      Nation nation = state.nations.get(army.nationId);
      boolean atWar = false;
      if (nation != null) {
        for (DiplomacyManager.PairInfo p : state.diplomacy.pairsInvolving(nation.id)) {
          if (p.relation.status.equals(Config.WAR)) { atWar = true; break; }
        }
      }
      if (atWar) continue;
      // was 0.02 - fast enough that a 6-person militia (Config.RAISE_BATCH)
      // fully dissolved back to civilian life in well under a year of
      // peace, roughly as fast as raiseArmy's own peacetime raise cadence
      // (Military.update's 35%-chance-every-12-ticks check) could refill
      // it. Net effect: standing armies never actually accumulated no
      // matter how rich or populous a nation got - "no one builds a
      // military." A real standing army should take years of sustained
      // peace to fully stand down, not months.
      if (Math.random() >= 0.003) continue;

      int idx = (int) (Math.random() * army.memberHumanIds.size());
      int humanId = army.memberHumanIds.remove(idx);
      for (Human h : state.humans) {
        if (h.id == humanId) { h.role = null; break; }
      }
      // shrink whichever unit type still has a count to shrink, to keep
      // the abstract unit map in sync with the real roster size
      for (Map.Entry<String, Integer> e : army.units.entrySet()) {
        if (e.getValue() > 0) { e.setValue(e.getValue() - 1); break; }
      }
      army.strength = armyStrength(army);
      if (armyUnitCount(army) <= 0) killArmy(state, army);
    }
  }

  /** The strongest unit type this nation's era unlocks that it can
   * comfortably afford a full raise batch of - falls back to the weakest
   * unlocked unit (still gated by era, never below it) so an AI nation's
   * armies naturally escalate from spears through guns to tanks as it
   * ages instead of raising knights forever. */
  private static String pickRaiseUnit(GameState state, Nation nation) {
    int era = Nation.era(state, nation);
    String choice = "militia";
    for (Map.Entry<String, Config.UnitSpec> e : Config.UNIT_TYPES.entrySet()) {
      Config.UnitSpec spec = e.getValue();
      if (spec.era > era) continue;
      double goldCost = spec.cost.getOrDefault("gold", 0.0) * Config.RAISE_BATCH;
      if (nation.treasury > goldCost * 1.6) choice = e.getKey();
    }
    return choice;
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
    demobilize(state);
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
          raiseArmy(state, sid, pickRaiseUnit(state, nation));
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

  /** How much defender manpower a settlement fields once attacked - scales
   * with population like the old flat "defense" stat used to, but now
   * persists across ticks as real manpower that gets worn down instead of
   * being recomputed fresh every tick. */
  public static double garrisonMax(Settlement settlement) {
    return settlement.populationCount * 0.5 + 6;
  }

  /** A city is taken by killing or routing everyone defending it, not by a
   * detached siege-progress number: while any garrison remains the two
   * sides just grind each other down, and only once the last defender is
   * dead or has fled does the city actually start to fall (siegeProgress
   * then tracks that final capture, ticking up fast since there's no one
   * left to stop it). */
  private static void resolveSieges(GameState state) {
    for (Settlement settlement : state.settlements.values()) {
      if (settlement.abandoned) continue;
      double maxGarrison = garrisonMax(settlement);
      if (settlement.garrisonHp < 0) settlement.garrisonHp = maxGarrison;

      List<Army> attackers = new ArrayList<>();
      for (Army army : state.armies.values()) {
        if (army.dead || army.nationId == settlement.nationId) continue;
        if (!state.diplomacy.getStatus(army.nationId, settlement.nationId).equals(Config.WAR)) continue;
        double d = Math.hypot(army.x - (settlement.x + 0.5), army.z - (settlement.z + 0.5));
        if (d <= ENGAGE_RANGE) attackers.add(army);
      }
      if (attackers.isEmpty()) {
        // no one at the gates: the garrison slowly reforms and any
        // in-progress capture stalls back out
        settlement.garrisonHp = Math.min(maxGarrison, settlement.garrisonHp + maxGarrison * 0.015);
        settlement.siegeProgress = Math.max(0, settlement.siegeProgress - 3);
        continue;
      }

      double attackTotal = 0;
      for (Army a : attackers) attackTotal += armyStrength(a);

      if (settlement.garrisonHp > 0) {
        double defense = settlement.garrisonHp;
        double ratio = attackTotal / Math.max(1, defense);
        if (ratio >= 2.0) {
          // a nation too weak to actually contest this doesn't get a slow
          // grind - the defense just breaks. "If a nation is too weak to
          // fight, it falls, end of story."
          settlement.garrisonHp = 0;
          for (Army a : attackers) applyDamage(state, a, defense * 0.04 * (armyStrength(a) / attackTotal));
          inflictWarDamage(state, settlement, 0.15);
          EventLog.log(state, "war", "The defenders of " + settlement.name + " were completely overwhelmed and broke");
        } else if (attackTotal > defense) {
          settlement.garrisonHp = Math.max(0, defense - (defense * 0.4 + (attackTotal - defense) * 0.25));
          for (Army a : attackers) applyDamage(state, a, defense * 0.1 * (armyStrength(a) / attackTotal));
          // an overwhelmed defense means the fighting is spilling into the
          // streets - real, visible cost, not just an abstract number
          inflictWarDamage(state, settlement, 0.08);
        } else {
          settlement.garrisonHp = Math.max(0, defense - attackTotal * 0.1);
          for (Army a : attackers) applyDamage(state, a, defense * 0.16 / attackers.size());
          inflictWarDamage(state, settlement, 0.03);
        }
        // a badly mauled garrison can break outright rather than fight to
        // its literal last defender - covers the "or ran away" half of
        // "all the soldiers in the city are dead, or ran away"
        if (settlement.garrisonHp > 0 && settlement.garrisonHp < maxGarrison * 0.25 && Math.random() < 0.15) {
          settlement.garrisonHp = 0;
          EventLog.log(state, "war", "The defenders of " + settlement.name + " broke and fled");
        }
      }

      if (settlement.garrisonHp <= 0) {
        // once the defense is actually gone the city falls fast - no
        // reason for a defenseless settlement to hold out another dozen
        // ticks once nobody's left to stop the attackers walking in
        settlement.siegeProgress += 30;
        inflictWarDamage(state, settlement, 0.15);
      }

      if (settlement.siegeProgress >= 100) {
        Army winner = attackers.get(0);
        for (Army a : attackers) if (armyStrength(a) > armyStrength(winner)) winner = a;
        int oldNationId = settlement.nationId;
        Nation winnerNation = state.nations.get(winner.nationId);
        Nation loserNation = state.nations.get(oldNationId);
        int sacked = sackSettlement(state, settlement);
        Nation.transferSettlement(state, settlement, winner.nationId);
        settlement.siegeProgress = 0;
        settlement.garrisonHp = -1;
        winner.targetSettlementId = null;
        Diplomacy.onConquest(state, winner.nationId, oldNationId);
        EventLog.log(state, "war", (winnerNation != null ? winnerNation.name : "An unknown power")
            + " conquered " + settlement.name
            + (loserNation != null ? " from " + loserNation.name : "")
            + (sacked > 0 ? " - " + sacked + " killed in the sack" : ""));
      }
    }
  }

  /** Real, visible cost of a siege while it's under way - not just a
   * number quietly ticking - a chance each tick (scaled by how hard the
   * fighting is right now) of a civilian casualty, some of the
   * settlement's stock/housing actually being wrecked, buildings actually
   * catching fire, and the ground itself getting torn up - a siege has to
   * look like a siege, not a number silently draining. */
  private static void inflictWarDamage(GameState state, Settlement settlement, double severity) {
    if (Math.random() < severity) {
      List<Human> residents = new ArrayList<>();
      for (Human h : state.humans) if (h.settlementId == settlement.id) residents.add(h);
      if (!residents.isEmpty()) {
        residents.get((int) (Math.random() * residents.size())).dead = true;
        DeathStats.war++;
      }
    }
    if (Math.random() < severity * 0.4 && settlement.housingStock > 1) {
      settlement.housingStock = Math.max(1, settlement.housingStock - 1);
    }
    for (String key : new String[]{"food", "wood", "stone"}) {
      double have = settlement.stock.getOrDefault(key, 0.0);
      if (have > 0) settlement.stock.put(key, have - have * severity * 0.5);
    }
    // an actual burning city, not an abstract number: a fire catching
    // somewhere in the settlement, real terrain damage where a building
    // just went down
    if (Math.random() < severity * 0.5) {
      int fx = settlement.x + (int) (Math.random() * 7 - 3);
      int fz = settlement.z + (int) (Math.random() * 7 - 3);
      Events.igniteCell(state.grid, fx, fz, (int) (15 + Math.random() * 20));
    }
    if (Math.random() < severity * 0.35) {
      int dx = settlement.x + (int) (Math.random() * 5 - 2);
      int dz = settlement.z + (int) (Math.random() * 5 - 2);
      if (state.grid.inBounds(dx, dz)) {
        state.voxels.digColumn(dx, dz);
        state.voxels.resyncHeight(state.grid, dx, dz);
        state.grid.markDirtyIdx(state.grid.idx(dx, dz));
      }
    }
  }

  /** The final toll when a city actually falls - a real sack, not a quiet
   * flag flip: a chunk of whoever's left dies or scatters and a chunk of
   * the housing is left in ruins for the new owner to rebuild. Returns
   * how many died, for the conquest log line. */
  private static int sackSettlement(GameState state, Settlement settlement) {
    List<Human> residents = new ArrayList<>();
    for (Human h : state.humans) if (h.settlementId == settlement.id) residents.add(h);
    int killCount = (int) Math.round(residents.size() * (0.12 + Math.random() * 0.18));
    for (int i = 0; i < killCount && !residents.isEmpty(); i++) {
      residents.remove((int) (Math.random() * residents.size())).dead = true;
      DeathStats.war++;
    }
    state.humans.removeIf(h -> h.dead);
    settlement.housingStock = Math.max(1, settlement.housingStock * 0.55);
    return killCount;
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
        // bumped from 0.16 - the player asked for wars that feel/look
        // devastating, and a field clash that barely dents either side
        // read as a stalemate rather than a real fight
        double sa = armyStrength(a), sb = armyStrength(b);
        applyDamage(state, a, sb * 0.22 * (0.75 + Math.random() * 0.5));
        applyDamage(state, b, sa * 0.22 * (0.75 + Math.random() * 0.5));
        // a field battle used to have zero effect on the world around it -
        // real soldiers dying somewhere the map never showed. The
        // battlefield itself (the ground between the two armies) now
        // actually burns and scars, real and visible, not just two
        // strength numbers quietly shrinking.
        double midX = (a.x + b.x) / 2, midZ = (a.z + b.z) / 2;
        if (Math.random() < 0.4) {
          int fx = (int) Math.round(midX + (Math.random() * 5 - 2.5));
          int fz = (int) Math.round(midZ + (Math.random() * 5 - 2.5));
          Events.igniteCell(state.grid, fx, fz, (int) (10 + Math.random() * 15));
        }
        if (Math.random() < 0.25) {
          int dx = (int) Math.round(midX + (Math.random() * 3 - 1.5));
          int dz = (int) Math.round(midZ + (Math.random() * 3 - 1.5));
          if (state.grid.inBounds(dx, dz)) {
            state.voxels.digColumn(dx, dz);
            state.voxels.resyncHeight(state.grid, dx, dz);
            state.grid.markDirtyIdx(state.grid.idx(dx, dz));
          }
        }
      }
    }
  }
}
