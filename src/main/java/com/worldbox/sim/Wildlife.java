package com.worldbox.sim;

import com.worldbox.config.Config;
import com.worldbox.util.Rng;
import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.List;

/** Ambient wildlife: no hunting/taming yet, just believable critters that
 * make each biome feel alive. Spawned once at world-gen (see spawn) and
 * given simple local-wander AI every tick (see update) - same organic
 * "turn gradually toward a target, then pick a new nearby one" movement
 * feel as Population's human wander, just without any of the economic/
 * settlement logic humans carry. */
public class Wildlife {
  private Wildlife() {}

  private static final double SPEED = 0.045;
  private static final int MAX_ANIMALS = 500;

  /** Which species (if any) live in a given biome - see Config.BIOME_*.
   * Mountain/ocean carry no land wildlife in this pass. */
  private static String[] speciesFor(byte biome) {
    if (biome == Config.BIOME_PLAINS) return new String[]{"cow", "sheep"};
    if (biome == Config.BIOME_FOREST) return new String[]{"deer", "wolf"};
    if (biome == Config.BIOME_DESERT) return new String[]{"camel"};
    if (biome == Config.BIOME_TUNDRA) return new String[]{"goat"};
    if (biome == Config.BIOME_WETLAND) return new String[]{"deer"};
    return null;
  }

  public static void spawn(GameState state, long seed) {
    WorldGrid grid = state.grid;
    Rng rng = new Rng(seed + 7777);
    // Eligible cells first (same "count then sample" approach WorldGen/
    // EntityRenderer use for trees) so a fixed animal budget still spreads
    // evenly across the whole map instead of only ever filling from
    // whichever region gets scanned first.
    List<Integer> eligible = new ArrayList<>();
    for (int y = 0; y < grid.rows; y++) {
      for (int x = 0; x < grid.cols; x++) {
        int i = grid.idx(x, y);
        if (!grid.isBuildable(i)) continue;
        if (speciesFor(grid.biome[i]) != null && rng.chance(0.02)) eligible.add(i);
      }
    }
    int spawnCount = Math.min(MAX_ANIMALS, eligible.size());
    for (int n = 0; n < spawnCount; n++) {
      int i = eligible.get(rng.intRange(0, eligible.size() - 1));
      int x = i % grid.cols, y = i / grid.cols;
      String[] species = speciesFor(grid.biome[i]);
      String sp = species[rng.intRange(0, species.length - 1)];
      state.animals.add(new Animal(sp, x + 0.5, y + 0.5));
    }
  }

  public static void update(GameState state) {
    WorldGrid grid = state.grid;
    for (Animal a : state.animals) {
      if (a.dead) continue;
      double dx = a.targetX - a.x, dz = a.targetZ - a.z;
      double dist = Math.hypot(dx, dz);
      if (dist < 0.05 || a.wanderTimer-- <= 0) {
        pickWanderTarget(grid, a);
        a.wanderTimer = 40 + (int) (Math.random() * 60);
        continue;
      }
      double desired = Math.atan2(dx, dz);
      double turn = Math.atan2(Math.sin(desired - a.heading), Math.cos(desired - a.heading));
      a.heading += Math.max(-0.2, Math.min(0.2, turn));
      a.walkPhase += 0.1 + (a.id % 5) * 0.01;
      double wobble = Math.sin(a.walkPhase) * 0.15;
      double moveAngle = a.heading + wobble;
      double step = Math.min(dist, SPEED);
      double nx = a.x + Math.sin(moveAngle) * step;
      double nz = a.z + Math.cos(moveAngle) * step;
      if (passable(grid, nx, nz)) { a.x = nx; a.z = nz; }
      else pickWanderTarget(grid, a);
    }
    // dead/fled animals (burned, drowned) are dropped rather than kept
    // around forever as inert dead weight in the list every tick has to walk
    state.animals.removeIf(a -> a.dead);
  }

  private static boolean passable(WorldGrid grid, double x, double z) {
    int gx = (int) Math.floor(x), gz = (int) Math.floor(z);
    if (!grid.inBounds(gx, gz)) return false;
    int i = grid.idx(gx, gz);
    return grid.isBuildable(i) && !grid.burning[i];
  }

  private static void pickWanderTarget(WorldGrid grid, Animal a) {
    for (int i = 0; i < 5; i++) {
      double nx = a.x + (Math.random() * 2 - 1) * 6;
      double nz = a.z + (Math.random() * 2 - 1) * 6;
      if (passable(grid, nx, nz)) { a.targetX = nx; a.targetZ = nz; return; }
    }
  }
}
