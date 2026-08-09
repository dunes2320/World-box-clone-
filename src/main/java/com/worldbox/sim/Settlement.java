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

  private static final String[] NAME_A = {"Oak", "Stone", "River", "North", "South", "Iron", "Gold", "Sun", "Wind", "Hill", "White", "Black", "Green", "Silver", "Amber"};
  private static final String[] NAME_B = {"ford", "haven", "burg", "shire", "port", "hold", "watch", "mere", "vale", "reach", "crest", "fell"};

  public static String randomSettlementName(Rng rng) {
    return rng.pick(NAME_A) + rng.pick(NAME_B);
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
  public final int founded;

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
      state.humans.add(Population.createHuman(hx, hz, nationId, settlement.id));
    }

    claimTerritory(state, settlement);
    return settlement;
  }

  public static void claimTerritory(GameState state, Settlement settlement) {
    WorldGrid grid = state.grid;
    grid.forEachInRadius(settlement.x, settlement.z, settlement.radius, (x, y, d) -> {
      int i = grid.idx(x, y);
      if (grid.terrain[i] == Config.WATER) return;
      grid.ownerNation[i] = settlement.nationId;
      grid.markDirtyIdx(i);
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

  public static void update(GameState state) {
    for (Settlement s : state.settlements.values()) s.populationCount = 0;
    for (Human h : state.humans) {
      Settlement s = state.settlements.get(h.settlementId);
      if (s != null) s.populationCount++;
    }

    for (Settlement settlement : state.settlements.values()) {
      if (state.tick % 25 == 0) {
        settlement.radius = Math.min(11, 3.5 + Math.sqrt(settlement.populationCount) * 0.85);
        settlement.farmCells = countFarmCells(state, settlement);
        claimTerritory(state, settlement);
      }

      // small passive trickle (foraging) so a settlement that hits 0
      // population from disaster/famine can still recover eventually,
      // instead of being stuck at 0 food -> 0 production -> 0 growth forever
      double production = Math.min(settlement.populationCount, settlement.farmCells) * FOOD_PER_WORKER
          + Math.min(settlement.farmCells, 3) * 0.08;
      double consumption = settlement.populationCount * FOOD_PER_POP;
      settlement.stock.merge("food", production - consumption, Double::sum);

      if (settlement.stock.get("food") < 0) {
        settlement.starveTicks++;
        settlement.stock.put("food", Math.max(settlement.stock.get("food"), -30));
        if (settlement.starveTicks > 10 && settlement.populationCount > 0) {
          List<Human> victims = new ArrayList<>();
          for (Human h : state.humans) if (h.settlementId == settlement.id) victims.add(h);
          int killCount = Math.min(victims.size(), 1 + (int) (Math.random() * 2));
          for (int i = 0; i < killCount && !victims.isEmpty(); i++) {
            victims.get((int) (Math.random() * victims.size())).dead = true;
          }
          state.humans.removeIf(h -> h.dead);
          settlement.starveTicks = 0;
          settlement.stock.put("food", 0.0);
        }
      } else {
        settlement.starveTicks = 0;
      }

      if (settlement.stock.get("food") > Config.SETTLEMENT_BUFFER
          && settlement.populationCount < POP_CAP_PER_SETTLEMENT
          && state.humans.size() < Config.MAX_HUMANS) {
        settlement.growthAccum += 0.015;
        if (settlement.growthAccum >= 1) {
          settlement.growthAccum -= 1;
          settlement.stock.merge("food", -18.0, Double::sum);
          double ang = Math.random() * Math.PI * 2;
          state.humans.add(Population.createHuman(
              settlement.x + 0.5 + Math.cos(ang) * 1.5,
              settlement.z + 0.5 + Math.sin(ang) * 1.5,
              settlement.nationId, settlement.id));
        }
      }
    }
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
