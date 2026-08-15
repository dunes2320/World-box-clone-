package com.worldbox.sim;

import com.worldbox.config.Config;
import com.worldbox.util.Rng;
import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Settlement {
  private static int nextId = 1;

  private static final String[] NAME_A = {
      "Oak", "Stone", "River", "North", "South", "East", "West", "Iron", "Gold", "Sun", "Wind", "Hill",
      "White", "Black", "Green", "Silver", "Amber", "Copper", "Elm", "Birch", "Willow", "Thorn", "Ash",
      "Frost", "Shadow", "Bright", "Clear", "Deep", "Fair", "High", "Low", "Old", "New", "Red", "Grey"
  };
  private static final String[] NAME_B = {
      "ford", "haven", "burg", "shire", "port", "hold", "watch", "mere", "vale", "reach", "crest", "fell",
      "wood", "field", "brook", "gate", "hollow", "wick", "moor", "dale", "worth", "stead", "cross", "bridge"
  };
  private static final String[] NAME_MID = {"", "", "", "en", "in", "on", "el"};

  /** Two or occasionally three phoneme pieces, matching the trick used for
   * nation names - keeps hundreds of settlements from repeating. */
  public static String randomSettlementName(Rng rng) {
    String mid = rng.next() < 0.25 ? rng.pick(NAME_MID) : "";
    return rng.pick(NAME_A) + mid + rng.pick(NAME_B);
  }

  public final int id;
  public int nationId;
  public final int x, z;
  public String name;
  public final Map<String, Double> stock = new HashMap<>();
  public int populationCount = 0;
  public int farmCells = 6;
  public double radius = 4;
  public double growthAccum = 0;
  public int starveTicks = 0;
  public double siegeProgress = 0;
  /** current defender manpower - depletes as attackers fight through it.
   * -1 means "not yet initialized", lazily set on first contact (see
   * Military.resolveSieges) so a settlement's garrison always reflects
   * its population/era at the moment it's actually attacked. Once this
   * hits 0 every defender is dead or has fled and the city starts to
   * fall (siegeProgress then measures the physical capture, not combat). */
  public double garrisonHp = -1;
  public final int founded;
  /** True once population has hit 0 - the settlement leaves its nation
   * and territory but its structures stay standing as a visible ruin. */
  public boolean abandoned = false;
  public double housingStock = 5;

  private Settlement(int x, int z, int nationId, String name, int foundedTick) {
    this.id = nextId++;
    this.x = x; this.z = z;
    this.nationId = nationId;
    this.name = name;
    this.founded = foundedTick;
    stock.put("food", 60.0);
    stock.put("wood", 30.0);
    stock.put("stone", 10.0);
    stock.put("iron", 0.0);
    stock.put("gold_ore", 0.0);
  }

  public static Settlement create(GameState state, int x, int z, int nationId, String name) {
    Settlement settlement = new Settlement(x, z, nationId, name != null ? name : "Settlement", state.tick);
    if (name == null) settlement.name = "Settlement " + settlement.id;
    state.settlements.put(settlement.id, settlement);
    state.grid.settlementAt[state.grid.idx(x, z)] = settlement.id;

    int startPop = 5;
    for (int i = 0; i < startPop; i++) {
      if (state.humans.size() >= Config.MAX_HUMANS) break;
      double ang = (i / (double) startPop) * Math.PI * 2;
      double hx = x + 0.5 + Math.cos(ang) * 1.5;
      double hz = z + 0.5 + Math.sin(ang) * 1.5;
      Human founder = Population.createAdult(hx, hz, nationId, settlement.id);
      // a fresh settlement's starting housingStock (5 houses, 20 capacity)
      // comfortably covers its 5 founders - they move into real houses on
      // day one instead of just existing without one
      founder.hasHouse = i < settlement.housingStock * PEOPLE_PER_HOUSE;
      state.humans.add(founder);
    }

    claimTerritory(state, settlement);
    return settlement;
  }

  /** Whether this settlement currently has an unoccupied house to give a
   * new or joining resident - counts actual housed residents rather than
   * just populationCount, since a settlement can carry homeless citizens
   * who are still waiting on one. */
  public static boolean hasHouseRoom(GameState state, Settlement settlement) {
    double capacity = settlement.housingStock * PEOPLE_PER_HOUSE;
    int housed = 0;
    for (Human h : state.humans) if (h.settlementId == settlement.id && h.hasHouse) housed++;
    return housed < capacity;
  }

  /** Claims cells in range that are either already ours or genuinely
   * unclaimed wilderness - contested by "claim strength" (population +
   * treasury + military power, falling off with distance) among rival
   * claims on open land, same as before. Once a cell is held by another
   * LIVING nation, ordinary peaceful growth can no longer take it no
   * matter how much richer/stronger this settlement is - that's what
   * war is for now (see Nation.transferSettlement's forced reclaim on
   * conquest, and Settlement.abandon releasing a dead nation's cells
   * back to unclaimed). */
  public static void claimTerritory(GameState state, Settlement settlement) {
    claimTerritory(state, settlement, false);
  }

  /** Unconditionally hands this settlement's surrounding cells to its
   * current nation regardless of who held them before - used right after
   * a conquest (see Nation.transferSettlement) so the captured city's own
   * land changes hands immediately instead of staying painted the
   * defeated nation's color until the next peaceful claim pass silently
   * refuses to touch it. */
  public static void forceClaimTerritory(GameState state, Settlement settlement) {
    claimTerritory(state, settlement, true);
  }

  private static void claimTerritory(GameState state, Settlement settlement, boolean force) {
    WorldGrid grid = state.grid;
    Nation nation = state.nations.get(settlement.nationId);
    double treasury = nation != null ? Math.max(0, nation.treasury) : 0;
    double power = nation != null ? Nation.totalMilitaryPower(state, nation.id) : 0;
    float strength = (float) (settlement.populationCount + treasury * 0.01 + power * 3);
    grid.forEachInRadius(settlement.x, settlement.z, settlement.radius, (x, y, d) -> {
      int i = grid.idx(x, y);
      if (grid.terrain[i] == Config.WATER) return;
      boolean alreadyOurs = grid.ownerNation[i] == settlement.nationId;
      if (!force && !alreadyOurs && grid.ownerNation[i] >= 0) return;
      float effective = strength - (float) d * 4f;
      if (force || alreadyOurs || effective >= grid.claimStrength[i]) {
        grid.ownerNation[i] = settlement.nationId;
        grid.claimStrength[i] = effective;
        grid.markDirtyIdx(i);
      }
    });
  }

  private static int countFarmCells(GameState state, Settlement settlement) {
    WorldGrid grid = state.grid;
    // the visible farm plot is a smaller ring right around the village
    // center, distinct from the wider (untilled) territory radius
    double plotRadius = Math.min(4.5, 2.2 + Math.sqrt(settlement.populationCount) * 0.25);
    int[] n = {0};
    grid.forEachInRadius(settlement.x, settlement.z, settlement.radius, (x, y, d) -> {
      int i = grid.idx(x, y);
      boolean owned = grid.terrain[i] == Config.GRASS && grid.ownerNation[i] == settlement.nationId;
      if (owned) n[0]++;
      boolean plot = owned && d <= plotRadius;
      if (grid.isFarmland[i] != plot) { grid.isFarmland[i] = plot; grid.markDirtyIdx(i); }
    });
    return n[0];
  }

  private static final double FOOD_PER_WORKER = 0.55;
  private static final double FOOD_PER_POP = 0.42;
  private static final int POP_CAP_PER_SETTLEMENT = 70;
  public static final double PEOPLE_PER_HOUSE = 4.0;
  private static final double HOUSE_WOOD_COST = 12.0;

  public static void update(GameState state) {
    for (Settlement s : state.settlements.values()) s.populationCount = 0;
    // one pass over every human to figure out which settlements actually
    // have a mature male and a mature female resident - growth below only
    // fires for settlements with a real pair, not just enough stored food
    Map<Integer, Boolean> hasMatureMale = new HashMap<>();
    Map<Integer, Boolean> hasMatureFemale = new HashMap<>();
    for (Human h : state.humans) {
      Settlement s = state.settlements.get(h.settlementId);
      if (s != null) s.populationCount++;
      if (h.age >= Config.MATURE_AGE) {
        if (h.female) hasMatureFemale.put(h.settlementId, true);
        else hasMatureMale.put(h.settlementId, true);
      }
    }

    List<Settlement> toAbandon = null;
    for (Settlement settlement : state.settlements.values()) {
      if (!settlement.abandoned && settlement.populationCount == 0) {
        if (toAbandon == null) toAbandon = new ArrayList<>();
        toAbandon.add(settlement);
      }
    }
    if (toAbandon != null) for (Settlement settlement : toAbandon) abandon(state, settlement);

    for (Settlement settlement : state.settlements.values()) {
      if (settlement.abandoned) continue;

      if (state.tick % 25 == 0) {
        // territory used to be population-only and capped at a modest 11 -
        // a nation's wealth and military never mattered, and even a
        // flourishing empire topped out claiming a small patch around each
        // town, leaving most of the map permanently unclaimed wilderness.
        // Now a rich, powerful settlement genuinely presses outward
        // further than a poor, defenseless one, and claimTerritory (below)
        // lets that actually win contested cells from a weaker neighbor.
        Nation homeNation = state.nations.get(settlement.nationId);
        double wealthBonus = homeNation != null ? Math.sqrt(Math.max(0, homeNation.treasury)) * 0.11 : 0;
        double powerBonus = homeNation != null ? Math.sqrt(Nation.totalMilitaryPower(state, homeNation.id)) * 0.6 : 0;
        settlement.radius = Math.min(24, 3.5 + Math.sqrt(settlement.populationCount) * 0.85 + wealthBonus + powerBonus);
        settlement.farmCells = countFarmCells(state, settlement);
        claimTerritory(state, settlement);

        // public housing backstop: housingStock is built to track total
        // population, not how many residents can actually afford to buy
        // back in after a repossession (see Population.maybeBuyHouse), so
        // a rising share could end up permanently homeless right next to
        // a rising number of genuinely empty houses - "most homes go
        // vacant" alongside a homelessness crisis, at the same time. If
        // this settlement has any vacancy, hand one over to a resident
        // still without a roof - gradual (one per cycle), not instant,
        // but no longer stuck behind an unreachable savings bar either.
        double houseRoom = settlement.housingStock * PEOPLE_PER_HOUSE;
        int housedCount = 0;
        for (Human h : state.humans) if (h.settlementId == settlement.id && h.hasHouse) housedCount++;
        if (housedCount < houseRoom) {
          for (Human h : state.humans) {
            if (h.settlementId == settlement.id && !h.hasHouse) { h.hasHouse = true; break; }
          }
        }
      }

      int farmWorkers = Math.min(settlement.populationCount, settlement.farmCells);
      // a small unconditional foraging trickle, independent of farmland -
      // a settlement founded on a grass-poor spot (sand, or boxed in by
      // mountains) would otherwise produce exactly zero food forever and
      // starve out immediately, which used to be harmless (a dead
      // settlement just sat inert) but now genuinely deletes the whole
      // nation if it was that settlement's only one - this buys a real
      // settlement enough time to trade, expand, or be reinforced instead
      double production = farmWorkers * FOOD_PER_WORKER + Math.min(settlement.farmCells, 3) * 0.08 + 1.2;
      double consumption = settlement.populationCount * FOOD_PER_POP;
      settlement.stock.merge("food", production - consumption, Double::sum);

      // a farm needs a hired hand to run, and that hand is paid a
      // government-set daily wage - the same wagePolicy lever that governs
      // every other job, so an underpaying government shows up here too.
      // Unlike a haul-wage this isn't backed by a matching market sale, so
      // it's kept modest - a subsidy the treasury carries, not a 1:1 cost.
      if (farmWorkers > 0) {
        Nation farmNation = state.nations.get(settlement.nationId);
        if (farmNation != null) {
          double foodPrice = state.market.prices.getOrDefault("food", 1.0);
          farmNation.treasury -= farmWorkers * FOOD_PER_WORKER * foodPrice * farmNation.wagePolicy * 0.35;
        }
      }

      if (settlement.stock.get("food") < 0) {
        settlement.starveTicks++;
        settlement.stock.put("food", Math.max(settlement.stock.get("food"), -30));
        if (settlement.starveTicks > 10 && settlement.populationCount > 0) {
          List<Human> victims = new ArrayList<>();
          for (Human h : state.humans) if (h.settlementId == settlement.id) victims.add(h);
          int killCount = Math.min(victims.size(), 1 + (int) (Math.random() * 2));
          for (int i = 0; i < killCount && !victims.isEmpty(); i++) {
            victims.get((int) (Math.random() * victims.size())).dead = true;
            DeathStats.starve++;
          }
          state.humans.removeIf(h -> h.dead);
          settlement.starveTicks = 0;
          settlement.stock.put("food", 0.0);
        }
      } else {
        settlement.starveTicks = 0;
      }

      // every citizen needs a roof: build houses out of spare wood whenever
      // the settlement is running low on space for its current population
      double houseCapacity = settlement.housingStock * PEOPLE_PER_HOUSE;
      if (settlement.populationCount >= houseCapacity - 2
          && settlement.stock.get("wood") > HOUSE_WOOD_COST + Config.SETTLEMENT_BUFFER * 0.5) {
        settlement.stock.merge("wood", -HOUSE_WOOD_COST, Double::sum);
        settlement.housingStock += 1;
        houseCapacity = settlement.housingStock * PEOPLE_PER_HOUSE;
        // the house that was just built goes to whoever in this
        // settlement still doesn't have one
        for (Human h : state.humans) {
          if (h.settlementId == settlement.id && !h.hasHouse) { h.hasHouse = true; break; }
        }
      }

      // a hard requirement (zero growth without a same-settlement pair)
      // turned any settlement that randomly skewed to one gender - common
      // once war/starvation shrinks a population down to a few people -
      // into a death spiral with no way back, since it could then never
      // grow again on its own. A real pair still grows the settlement far
      // faster, but a settlement without one isn't stuck at zero forever.
      boolean canReproduce = hasMatureMale.getOrDefault(settlement.id, false)
          && hasMatureFemale.getOrDefault(settlement.id, false);
      // growth used to only check the food buffer at this exact instant,
      // not whether the settlement could actually go on FEEDING the new
      // mouth - a settlement with little or no farmland would grow past
      // what its passive trickle production could sustain, go negative,
      // and get its population violently cut back down by the starvation
      // kill below, then grow right back into the same wall. Capping
      // growth at what farmCells can support (plus the trickle's own
      // small headroom) keeps population growth a steady climb instead of
      // that boom-and-starve sawtooth.
      if (settlement.stock.get("food") > Config.SETTLEMENT_BUFFER
          && settlement.populationCount < POP_CAP_PER_SETTLEMENT
          && settlement.populationCount < houseCapacity
          && settlement.populationCount < settlement.farmCells + 4
          && state.humans.size() < Config.MAX_HUMANS) {
        settlement.growthAccum += canReproduce ? 0.02 : 0.003;
        if (settlement.growthAccum >= 1) {
          settlement.growthAccum -= 1;
          settlement.stock.merge("food", -18.0, Double::sum);
          double ang = Math.random() * Math.PI * 2;
          // a birth only happens when populationCount < houseCapacity
          // above, so there's guaranteed to be room for this one
          Human baby = Population.createHuman(
              settlement.x + 0.5 + Math.cos(ang) * 1.5,
              settlement.z + 0.5 + Math.sin(ang) * 1.5,
              settlement.nationId, settlement.id);
          baby.hasHouse = true;
          state.humans.add(baby);
        }
      }
    }
  }

  /** A settlement that just lost its last citizen leaves its nation and
   * releases its territory/farmland back to no-man's-land; its structures
   * stay physically standing (settlementAt is left alone, permanently
   * blocking a new settlement from founding on the exact same spot) but
   * the record itself is removed from state.settlements below - nothing
   * else ever looks a settlement up by ID expecting a ruin to still be
   * there, and leaving thousands of dead entries around over a long game
   * both inflated every "settlements" count in the UI/logs and leaked
   * memory that never gets reclaimed. */
  private static void abandon(GameState state, Settlement settlement) {
    settlement.abandoned = true;
    // zero out (rather than clear) so the many call sites that assume the
    // standard keys are always present don't NPE on a missing entry
    for (String key : settlement.stock.keySet()) settlement.stock.put(key, 0.0);

    int oldNationId = settlement.nationId;
    Nation nation = state.nations.get(oldNationId);
    if (nation != null) {
      nation.settlementIds.remove(Integer.valueOf(settlement.id));
      if (nation.capitalSettlementId == settlement.id) {
        nation.capitalSettlementId = nation.settlementIds.isEmpty() ? -1 : nation.settlementIds.iterator().next();
      }
    }
    settlement.nationId = -1;

    WorldGrid grid = state.grid;
    grid.forEachInRadius(settlement.x, settlement.z, settlement.radius, (x, y, d) -> {
      int i = grid.idx(x, y);
      if (grid.ownerNation[i] == oldNationId) {
        grid.ownerNation[i] = -1;
        // otherwise this settlement's last claim strength lingers forever
        // and can block a neighbor from ever claiming the cell, even
        // though nothing is actually holding it anymore
        grid.claimStrength[i] = 0;
        grid.markDirtyIdx(i);
      }
      if (grid.isFarmland[i]) { grid.isFarmland[i] = false; grid.markDirtyIdx(i); }
    });

    List<Integer> deadBusinesses = new ArrayList<>();
    for (Business b : state.businesses.values()) if (b.settlementId == settlement.id) deadBusinesses.add(b.id);
    for (int id : deadBusinesses) state.businesses.remove(id);

    state.settlements.remove(settlement.id);
  }

  /** Full re-claim pass across every settlement, nearest-wins. Cheap enough
   * to run every few dozen ticks rather than continuously. */
  public static void recomputeTerritory(GameState state) {
    WorldGrid grid = state.grid;
    List<Settlement> settlements = new ArrayList<>(state.settlements.values());
    java.util.Arrays.fill(grid.ownerNation, -1);
    if (settlements.isEmpty()) return;
    for (int y = 0; y < grid.rows; y++) {
      for (int x = 0; x < grid.cols; x++) {
        int i = grid.idx(x, y);
        if (grid.terrain[i] == Config.WATER) continue;
        Settlement best = null;
        double bestD = Double.MAX_VALUE;
        for (Settlement s : settlements) {
          double d = Math.hypot(x - s.x, y - s.z);
          if (d <= s.radius && d < bestD) { bestD = d; best = s; }
        }
        if (best != null) grid.ownerNation[i] = best.nationId;
      }
    }
    for (int i = 0; i < grid.cols * grid.rows; i++) grid.markDirtyIdx(i);
  }
}
