package com.worldbox.sim;

import com.worldbox.config.Config;
import com.worldbox.world.VoxelWorld;
import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Fire/vegetation ambient dynamics, plus every one-shot "weird stuff" god
 * tool: meteor, nuke, earthquake, blessing, zombie outbreak, tornado and
 * the rampaging kaiju monster. */
public class Events {

  // ---- fire + vegetation: always running ----
  public static void igniteCell(WorldGrid grid, int x, int y, int life) {
    if (!grid.inBounds(x, y)) return;
    int i = grid.idx(x, y);
    if (grid.terrain[i] == Config.WATER || grid.terrain[i] == Config.STONE) return;
    if (!grid.burning[i] && (grid.terrain[i] == Config.GRASS || grid.resource[i] == Config.RES_FOREST)) {
      grid.burning[i] = true;
      grid.burnTimer[i] = life > 0 ? life : (int) (20 + Math.random() * 25);
      grid.burningCells.add(i);
      grid.markDirtyIdx(i);
    }
  }

  public static void igniteCell(WorldGrid grid, int x, int y) { igniteCell(grid, x, y, 0); }

  // a burning cell used to have a >1 expected number of neighbors it went
  // on to ignite over its lifetime (4 neighbors x 10%/tick x ~30 ticks),
  // which is an exponentially GROWING fire with no natural ceiling - on a
  // map where grass is no longer artificially broken up by center-biased
  // stone (see WorldGen), that meant one spark could eventually consume
  // every reachable field and forest in the world. A real wildfire still
  // spreads fast locally, but stops growing once it's already a big fire
  // - MAX_SIMULTANEOUS_FIRE acts as "the whole world's worth of bad luck
  // meeting itself", a hard ceiling on how much can be burning at once
  // regardless of how much fuel is still out there.
  private static final int MAX_SIMULTANEOUS_FIRE = 220;

  // a fire holding at the MAX_SIMULTANEOUS_FIRE cap for a long stretch
  // would otherwise just sit there indefinitely (spread blocked, but
  // individual cells still replaced by fresh ignitions as old ones burn
  // out) - a real wildfire eventually burns itself out, runs out of
  // easy fuel, or the weather turns on it, so a fire that's been raging
  // this big for this long starts picking up random forced-out cells
  // each tick, an escalating chance that guarantees it's fully dead
  // within a bounded (but not exactly predictable) further stretch.
  private static final int SIZABLE_FIRE = 40;
  private static final int BURNOUT_ONSET_TICKS = 300;

  private static void updateFire(GameState state) {
    WorldGrid grid = state.grid;
    if (grid.burningCells.isEmpty()) { grid.fireStreak = 0; return; }
    List<Integer> burningCells = new ArrayList<>(grid.burningCells);
    boolean canSpread = grid.burningCells.size() < MAX_SIMULTANEOUS_FIRE;

    if (grid.burningCells.size() >= SIZABLE_FIRE) grid.fireStreak++; else grid.fireStreak = 0;
    double burnoutChance = grid.fireStreak > BURNOUT_ONSET_TICKS
        ? Math.min(0.02, (grid.fireStreak - BURNOUT_ONSET_TICKS) * 0.00005) : 0;

    int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    for (int i : burningCells) {
      if (burnoutChance > 0 && Math.random() < burnoutChance) {
        grid.burning[i] = false;
        grid.burningCells.remove(i);
        grid.burnTimer[i] = 0;
        grid.markDirtyIdx(i);
        continue;
      }
      grid.burnTimer[i]--;
      if (grid.burnTimer[i] <= 0) {
        grid.burning[i] = false;
        grid.burningCells.remove(i);
        grid.resource[i] = Config.RES_NONE;
        if (grid.terrain[i] == Config.GRASS) {
          grid.terrain[i] = Config.DIRT;
          state.voxels.paintColumnSurface(i % grid.cols, i / grid.cols, VoxelWorld.DIRT);
        }
        grid.markDirtyIdx(i);
        continue;
      }
      if (!canSpread) continue;
      int x = i % grid.cols, y = i / grid.cols;
      for (int[] d : dirs) {
        int nx = x + d[0], ny = y + d[1];
        if (!grid.inBounds(nx, ny)) continue;
        int ni = grid.idx(nx, ny);
        if (grid.burning[ni]) continue;
        boolean flammable = grid.terrain[ni] == Config.GRASS || grid.resource[ni] == Config.RES_FOREST;
        if (flammable && Math.random() < 0.045) igniteCell(grid, nx, ny, (int) (20 + Math.random() * 25));
      }
    }
  }

  /** Puts fires out in a brush area without touching the terrain
   * underneath (unlike painting water over it) - a real firefighting
   * action, not a workaround. */
  public static void extinguish(WorldGrid grid, int cx, int cy, double radius) {
    grid.forEachInRadius(cx, cy, radius, (x, y, d) -> {
      int i = grid.idx(x, y);
      if (grid.burning[i]) {
        grid.burning[i] = false;
        grid.burnTimer[i] = 0;
        grid.burningCells.remove(i);
        grid.markDirtyIdx(i);
      }
    });
  }

  private static boolean hasGrassNeighbor(WorldGrid grid, int x, int y) {
    int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    for (int[] d : dirs) {
      int nx = x + d[0], ny = y + d[1];
      if (grid.inBounds(nx, ny) && grid.terrain[grid.idx(nx, ny)] == Config.GRASS) return true;
    }
    return false;
  }

  private static void updateVegetation(GameState state) {
    WorldGrid grid = state.grid;
    int samples = 250;
    for (int s = 0; s < samples; s++) {
      int x = (int) (Math.random() * grid.cols);
      int y = (int) (Math.random() * grid.rows);
      int i = grid.idx(x, y);
      if (grid.terrain[i] == Config.GRASS) {
        if (grid.resource[i] == Config.RES_FOREST) {
          if (grid.resourceAmount[i] < Config.RESOURCE_INFO.get(Config.RES_FOREST).yieldAmt * 8 && Math.random() < 0.05) {
            grid.resourceAmount[i] += 1;
          }
        } else if (grid.resource[i] == Config.RES_NONE && Math.random() < 0.004) {
          grid.resource[i] = Config.RES_FOREST;
          grid.resourceAmount[i] = Config.RESOURCE_INFO.get(Config.RES_FOREST).yieldAmt * 2;
          grid.markDirtyIdx(i);
        }
      } else if (grid.terrain[i] == Config.DIRT && !grid.burning[i] && hasGrassNeighbor(grid, x, y) && Math.random() < 0.02) {
        // grass slowly reclaims bare dirt (burn scars heal, cleared
        // patches soften) if it's got grass nearby to spread from
        grid.terrain[i] = Config.GRASS;
        state.voxels.paintColumnSurface(x, y, VoxelWorld.GRASS);
        grid.markDirtyIdx(i);
      }
    }
  }

  // ---- disaster / god-tool triggers (one-shot) ----
  public static void explode(GameState state, double cx, double cy, double radius, boolean crater) {
    WorldGrid grid = state.grid;
    java.util.Map<String, Integer> depositsDestroyed = new java.util.HashMap<>();
    grid.forEachInRadius(cx, cy, radius, (x, y, d) -> {
      int i = grid.idx(x, y);
      if (d < radius * 0.55) {
        byte res = grid.resource[i];
        if (res == Config.RES_STONE || res == Config.RES_IRON || res == Config.RES_GOLD) {
          depositsDestroyed.merge(Config.RESOURCE_INFO.get(res).key, 1, Integer::sum);
        }
        grid.resource[i] = Config.RES_NONE;
        grid.burning[i] = false;
        grid.burningCells.remove(i);
      } else {
        igniteCell(grid, x, y, (int) (25 + Math.random() * 25));
      }
      grid.markDirtyIdx(i);
    });

    // a real crater: carve a chunk of blocks out of the world instead of
    // just painting the terrain type, so the ground actually caves in
    if (crater) {
      int ix = (int) Math.floor(cx), iz = (int) Math.floor(cy);
      double groundY = grid.inBounds(ix, iz) ? grid.height[grid.idx(ix, iz)] : 0;
      VoxelWorld voxels = state.voxels;
      Set<Long> touched = new LinkedHashSet<>();
      voxels.carveSphere(cx, groundY, cy, radius * 0.75, touched);
      for (long packed : touched) {
        int x = (int) (packed % grid.cols), z = (int) (packed / grid.cols);
        voxels.resyncHeight(grid, x, z);
        int gi = grid.idx(x, z);
        if (grid.terrain[gi] != Config.WATER) grid.terrain[gi] = Config.STONE;
        grid.markDirtyIdx(gi);
      }
    }

    // supply shock: destroyed deposits mean less future supply, so the
    // market immediately reacts as if it's scarcer right now
    for (var e : depositsDestroyed.entrySet()) state.market.nudge(e.getKey(), 1, e.getValue() * 2.5);

    double r = radius;
    state.humans.removeIf(h -> Math.hypot(h.x - cx, h.z - cy) < r * 0.7);
    for (Settlement settlement : state.settlements.values()) {
      if (settlement.abandoned) continue;
      double d = Math.hypot(settlement.x - cx, settlement.z - cy);
      if (d < radius) {
        int loss = (int) Math.round((1 - d / radius) * settlement.populationCount * 0.4);
        settlement.stock.put("food", Math.max(0, settlement.stock.get("food") - loss * 5));
      }
    }
  }

  public static void earthquake(GameState state, double cx, double cy, double radius) {
    WorldGrid grid = state.grid;
    VoxelWorld voxels = state.voxels;
    grid.forEachInRadius(cx, cy, radius, (x, y, d) -> {
      int i = grid.idx(x, y);
      if (grid.terrain[i] == Config.WATER) return;
      double intensity = 1 - d / radius;
      int delta = (int) Math.round((Math.random() - 0.5) * 3 * intensity);
      if (delta > 0) {
        for (int k = 0; k < delta; k++) voxels.buildColumn(x, y, VoxelWorld.STONE);
      } else if (delta < 0) {
        for (int k = 0; k < -delta; k++) voxels.digColumn(x, y);
      }
      voxels.resyncHeight(grid, x, y);
      if (Math.random() < 0.08) {
        grid.terrain[i] = Config.STONE;
        voxels.paintColumnSurface(x, y, VoxelWorld.STONE);
      }
      grid.markDirtyIdx(i);
    });
    for (Settlement s : state.settlements.values()) {
      if (!s.abandoned && Math.hypot(s.x - cx, s.z - cy) < radius) s.siegeProgress = 0;
    }
    state.humans.removeIf(h -> {
      double d = Math.hypot(h.x - cx, h.z - cy);
      return d < radius * 0.5 && Math.random() < 0.3;
    });
  }

  public static void blessing(GameState state, double cx, double cy, double radius) {
    WorldGrid grid = state.grid;
    grid.forEachInRadius(cx, cy, radius, (x, y, d) -> {
      int i = grid.idx(x, y);
      if (grid.burning[i]) { grid.burning[i] = false; grid.burningCells.remove(i); grid.markDirtyIdx(i); }
    });
    for (Settlement s : state.settlements.values()) {
      if (!s.abandoned && Math.hypot(s.x - cx, s.z - cy) <= radius) {
        s.stock.merge("food", 60.0, Double::sum);
        Nation n = state.nations.get(s.nationId);
        if (n != null) n.treasury += 70;
      }
    }
  }

  public static void zombieOutbreak(GameState state, double cx, double cy, double radius, int count) {
    int converted = 0;
    for (Human h : state.humans) {
      if (converted >= count) break;
      if (h.nationId == Config.UNDEAD_NATION_ID) continue;
      if (Math.hypot(h.x - cx, h.z - cy) <= radius) {
        h.nationId = Config.UNDEAD_NATION_ID;
        h.settlementId = -1;
        h.job = null;
        h.carryingType = null;
        h.state = "wander";
        converted++;
      }
    }
    if (converted > 0) EventLog.log(state, "disaster", "A zombie outbreak turned " + converted + " " + nearbyPlaceDescription(state, cx, cy));
  }

  public static void spawnTornado(GameState state, double x, double z) {
    state.tornadoes.add(new Tornado(x, z, Math.random() * Math.PI * 2, Config.TORNADO_LIFETIME));
  }

  private static void updateTornadoes(GameState state) {
    WorldGrid grid = state.grid;
    List<Tornado> keep = new ArrayList<>();
    for (Tornado t : state.tornadoes) {
      t.angle += (Math.random() - 0.5) * 0.6;
      t.x += Math.cos(t.angle) * Config.TORNADO_SPEED * 0.1;
      t.z += Math.sin(t.angle) * Config.TORNADO_SPEED * 0.1;
      t.life--;
      state.humans.removeIf(h -> {
        double d = Math.hypot(h.x - t.x, h.z - t.z);
        return d < 1.6 && Math.random() < 0.12;
      });
      grid.forEachInRadius(t.x, t.z, 1.3, (x, y, d) -> {
        int i = grid.idx(x, y);
        if (grid.resource[i] == Config.RES_FOREST && Math.random() < 0.2) {
          grid.resource[i] = Config.RES_NONE;
          grid.markDirtyIdx(i);
        }
      });
      if (t.life > 0 && grid.inBounds((int) Math.floor(t.x), (int) Math.floor(t.z))) keep.add(t);
    }
    state.tornadoes = keep;
  }

  public static boolean spawnMonster(GameState state, double x, double z) {
    if (state.monster != null) return false;
    state.monster = new Monster(x, z, Config.MONSTER_HP, Config.MONSTER_LIFETIME);
    EventLog.log(state, "disaster", "A kaiju emerged " + nearbyPlaceDescription(state, x, z));
    return true;
  }

  private static void updateMonster(GameState state) {
    Monster m = state.monster;
    if (m == null) return;
    m.life--;

    Settlement target = null;
    double bestD = Double.MAX_VALUE;
    for (Settlement s : state.settlements.values()) {
      double d = Math.hypot(s.x - m.x, s.z - m.z);
      if (d < bestD) { bestD = d; target = s; }
    }
    // deliberately don't skip an abandoned (0-population) settlement here:
    // once a monster empties one it has nothing left to kill there and no
    // reason to move on, so it idles on the ruin instead of hunting down
    // every other settlement in the world one by one
    if (target != null && !target.abandoned) {
      double dx = target.x - m.x, dz = target.z - m.z;
      double dist = Math.hypot(dx, dz);
      if (dist > 1.4) {
        m.x += (dx / dist) * Config.MONSTER_SPEED;
        m.z += (dz / dist) * Config.MONSTER_SPEED;
      } else {
        List<Human> victims = new ArrayList<>();
        for (Human h : state.humans) if (h.settlementId == target.id) victims.add(h);
        for (int i = 0; i < 2 && !victims.isEmpty(); i++) {
          victims.get((int) (Math.random() * victims.size())).dead = true;
          DeathStats.monster++;
        }
        target.stock.put("food", Math.max(0, target.stock.get("food") - 15));
        target.stock.put("wood", Math.max(0, target.stock.get("wood") - 8));
        m.hp -= target.populationCount * 0.06;
      }
    }

    for (Army army : state.armies.values()) {
      if (army.dead) continue;
      double d = Math.hypot(army.x - m.x, army.z - m.z);
      if (d < 1.7) {
        m.hp -= Military.armyStrength(army) * 0.22;
        Military.damageArmy(state, army, Config.MONSTER_POWER * 0.4);
        Nation n = state.nations.get(army.nationId);
        if (n != null && m.hp <= 0) n.treasury += 200;
      }
    }

    state.humans.removeIf(h -> h.dead);
    if (m.hp <= 0 || m.life <= 0) state.monster = null;
  }

  public static void update(GameState state) {
    updateFire(state);
    updateVegetation(state);
    updateTornadoes(state);
    updateMonster(state);
    updateAutoDisasters(state);
  }

  /** A world with god tools but nothing happening on its own doesn't feel
   * alive - real earthquakes and lightning-strike wildfires happen without
   * the player lifting a finger, same as wars/revolts/booms and busts
   * already do. No meteors here on purpose - those stay a deliberate
   * player action, not something that falls out of the sky at random. */
  private static String nearbyPlaceDescription(GameState state, double x, double z) {
    Settlement best = null;
    double bestD = Double.MAX_VALUE;
    for (Settlement s : state.settlements.values()) {
      if (s.abandoned) continue;
      double d = Math.hypot(s.x - x, s.z - z);
      if (d < bestD) { bestD = d; best = s; }
    }
    if (best != null && bestD < 18) return "near " + best.name;
    return "in the wilderness";
  }

  private static void updateAutoDisasters(GameState state) {
    WorldGrid grid = state.grid;
    if (Math.random() < 0.0016) {
      int x = (int) (Math.random() * grid.cols), y = (int) (Math.random() * grid.rows);
      if (grid.inBounds(x, y) && grid.terrain[grid.idx(x, y)] != Config.WATER) {
        double cx = x + 0.5, cz = y + 0.5;
        String where = nearbyPlaceDescription(state, cx, cz);
        earthquake(state, cx, cz, 3 + Math.random() * 4);
        EventLog.log(state, "disaster", "An earthquake struck " + where);
      }
    }
    if (Math.random() < 0.0022) {
      for (int attempt = 0; attempt < 8; attempt++) {
        int x = (int) (Math.random() * grid.cols), y = (int) (Math.random() * grid.rows);
        int i = grid.idx(x, y);
        if (grid.inBounds(x, y) && (grid.terrain[i] == Config.GRASS || grid.resource[i] == Config.RES_FOREST)) {
          igniteCell(grid, x, y);
          EventLog.log(state, "disaster", "A wildfire broke out " + nearbyPlaceDescription(state, x + 0.5, y + 0.5));
          break;
        }
      }
    }
  }
}
