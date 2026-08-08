package com.worldbox.sim;

import com.worldbox.config.Config;
import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.List;

/** Individual villagers. Food is handled abstractly at the settlement level
 * (territory + population -> auto food production/consumption), so a
 * human's only job is to gather wood/stone/iron and haul it home, or flee
 * danger. When drafted, a human is removed from the list and folded into an
 * Army's unit counts (see Military). */
public class Population {
  private static final double SPEED = 0.34;
  private static final int GATHER_TICKS = 5;

  public static Human createHuman(double x, double z, int nationId, int settlementId) {
    return new Human(x, z, nationId, settlementId);
  }

  private static boolean passable(WorldGrid grid, double x, double z) {
    int gx = (int) Math.floor(x), gz = (int) Math.floor(z);
    if (!grid.inBounds(gx, gz)) return false;
    return grid.terrain[grid.idx(gx, gz)] != Config.WATER;
  }

  private static void pickWanderTarget(WorldGrid grid, Human h) {
    for (int i = 0; i < 5; i++) {
      double nx = h.x + (Math.random() * 2 - 1) * 5;
      double nz = h.z + (Math.random() * 2 - 1) * 5;
      if (passable(grid, nx, nz)) { h.targetX = nx; h.targetZ = nz; return; }
    }
  }

  private static int[] findResourceCell(WorldGrid grid, double cx, double cz, byte resourceType, double radius) {
    int best = -1, bestX = -1, bestY = -1;
    double bestD = Double.MAX_VALUE;
    int r = (int) Math.ceil(radius);
    int cxi = (int) Math.floor(cx), czi = (int) Math.floor(cz);
    for (int dy = -r; dy <= r; dy++) {
      for (int dx = -r; dx <= r; dx++) {
        int x = cxi + dx, y = czi + dy;
        if (!grid.inBounds(x, y)) continue;
        int i = grid.idx(x, y);
        if (grid.resource[i] == resourceType && grid.resourceAmount[i] > 0) {
          double d = (double) dx * dx + (double) dy * dy;
          if (d < bestD) { bestD = d; bestX = x; bestY = y; best = i; }
        }
      }
    }
    if (best < 0) return null;
    return new int[]{bestX, bestY};
  }

  private static byte jobResource(String job) {
    switch (job) {
      case "wood": return Config.RES_FOREST;
      case "stone": return Config.RES_STONE;
      case "iron": return Config.RES_IRON;
      default: return Config.RES_NONE;
    }
  }

  private static void assignJob(GameState state, Human h) {
    Settlement settlement = state.settlements.get(h.settlementId);
    if (settlement == null) { h.job = null; return; }
    String[] keys = {"wood", "stone", "iron"};
    // pick whichever tracked resource is scarcest relative to a healthy buffer
    String[] sorted = keys.clone();
    java.util.Arrays.sort(sorted, (a, b) -> Double.compare(settlement.stock.get(a), settlement.stock.get(b)));
    for (String k : sorted) {
      int[] cell = findResourceCell(state.grid, settlement.x, settlement.z, jobResource(k), 14);
      if (cell != null) {
        h.job = k;
        h.gatherX = cell[0];
        h.gatherY = cell[1];
        h.targetX = cell[0] + 0.5;
        h.targetZ = cell[1] + 0.5;
        h.state = "gather";
        return;
      }
    }
    h.job = null;
    h.state = "wander";
  }

  private static double moveToward(WorldGrid grid, Human h, double speed) {
    double dx = h.targetX - h.x, dz = h.targetZ - h.z;
    double dist = Math.hypot(dx, dz);
    if (dist < 0.05) return dist;
    double step = Math.min(dist, speed);
    double nx = h.x + (dx / dist) * step;
    double nz = h.z + (dz / dist) * step;
    if (passable(grid, nx, nz)) { h.x = nx; h.z = nz; }
    else pickWanderTarget(grid, h);
    return dist;
  }

  private static boolean nearbyFire(WorldGrid grid, double x, double z) {
    int gx = (int) Math.floor(x), gz = (int) Math.floor(z);
    for (int dy = -1; dy <= 1; dy++) {
      for (int dx = -1; dx <= 1; dx++) {
        int nx = gx + dx, ny = gz + dy;
        if (grid.inBounds(nx, ny) && grid.burning[grid.idx(nx, ny)]) return true;
      }
    }
    return false;
  }

  private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
  private static int clampCoord(int v, int max) { return Math.max(0, Math.min(max - 1, v)); }

  public static void update(GameState state) {
    WorldGrid grid = state.grid;
    List<Human> next = new ArrayList<>(state.humans.size());
    for (Human h : state.humans) {
      if (h.dead) continue;
      h.prevX = h.x; h.prevZ = h.z;
      h.age++;

      if (h.nationId == Config.UNDEAD_NATION_ID) {
        updateZombie(state, h);
        next.add(h);
        continue;
      }

      int ci = grid.idx(clampCoord((int) Math.floor(h.x), grid.cols), clampCoord((int) Math.floor(h.z), grid.rows));
      if (grid.burning[ci] && Math.random() < 0.35) continue; // burned to death

      if (grid.burning[ci] || nearbyFire(grid, h.x, h.z)) {
        h.state = "flee";
        h.fleeTimer = 25;
        double away = Math.atan2(h.z - grid.rows / 2.0, h.x - grid.cols / 2.0) + (Math.random() - 0.5);
        h.targetX = clamp(h.x + Math.cos(away) * 6, 0, grid.cols - 1);
        h.targetZ = clamp(h.z + Math.sin(away) * 6, 0, grid.rows - 1);
      }

      if (h.state.equals("flee")) {
        moveToward(grid, h, SPEED * 1.4);
        h.fleeTimer--;
        if (h.fleeTimer <= 0) h.state = "wander";
        next.add(h);
        continue;
      }

      if (h.state.equals("gather")) {
        double dist = moveToward(grid, h, SPEED);
        if (dist < 0.15) {
          h.gatherTimer++;
          if (h.gatherTimer >= GATHER_TICKS) {
            int gi = grid.idx(h.gatherX, h.gatherY);
            Config.ResourceInfo info = Config.RESOURCE_INFO.get(grid.resource[gi]);
            if (info != null && grid.resourceAmount[gi] > 0) {
              int amt = Math.min(info.yieldAmt, grid.resourceAmount[gi]);
              grid.resourceAmount[gi] -= amt;
              if (grid.resourceAmount[gi] <= 0 && !info.respawns) {
                grid.resource[gi] = Config.RES_NONE;
                grid.markDirtyIdx(gi);
              }
              h.carryingType = info.key;
              h.carryingAmount = amt;
              Settlement settlement = state.settlements.get(h.settlementId);
              if (settlement != null) { h.targetX = settlement.x + 0.5; h.targetZ = settlement.z + 0.5; }
              h.state = "haul";
            } else {
              assignJob(state, h);
            }
            h.gatherTimer = 0;
          }
        }
      } else if (h.state.equals("haul")) {
        Settlement settlement = state.settlements.get(h.settlementId);
        if (settlement == null) {
          h.state = "wander";
        } else {
          double dist = moveToward(grid, h, SPEED);
          if (dist < 0.2) {
            double cur = settlement.stock.getOrDefault(h.carryingType, 0.0);
            settlement.stock.put(h.carryingType, cur + h.carryingAmount);
            h.carryingType = null;
            assignJob(state, h);
          }
        }
      } else {
        if (Math.random() < 0.02 || moveToward(grid, h, SPEED) < 0.1) pickWanderTarget(grid, h);
        if (Math.random() < 0.05) assignJob(state, h);
      }

      next.add(h);
    }
    next.removeIf(h -> h.dead);
    state.humans = next;
  }

  private static void updateZombie(GameState state, Human h) {
    WorldGrid grid = state.grid;
    Human target = null;
    double bestD = 64;
    for (Human other : state.humans) {
      if (other == h || other.dead || other.nationId == Config.UNDEAD_NATION_ID) continue;
      double d = (other.x - h.x) * (other.x - h.x) + (other.z - h.z) * (other.z - h.z);
      if (d < bestD) { bestD = d; target = other; }
    }
    if (target != null) {
      h.targetX = target.x; h.targetZ = target.z;
      double dist = moveToward(grid, h, SPEED * 0.8);
      if (dist < 0.5) {
        if (Math.random() < 0.5) {
          target.nationId = Config.UNDEAD_NATION_ID;
          target.settlementId = -1;
          target.job = null;
          target.carryingType = null;
          target.state = "wander";
        } else {
          target.dead = true;
        }
      }
    } else if (Math.random() < 0.03 || moveToward(grid, h, SPEED * 0.5) < 0.1) {
      pickWanderTarget(grid, h);
    }
  }
}
