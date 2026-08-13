package com.worldbox.world;

import com.worldbox.config.Config;

import java.util.LinkedHashSet;
import java.util.Set;

/** The "physical layer" of the world: elevation, terrain type, resource
 * deposits, fire state and territory ownership. Everything else
 * (population, settlements, nations) reads/writes into this shared layer. */
public class WorldGrid {
  public final int cols = Config.COLS;
  public final int rows = Config.ROWS;
  private final int n = cols * rows;

  public final float[] height = new float[n];
  public final byte[] terrain = new byte[n];
  public final byte[] resource = new byte[n];
  public final int[] resourceAmount = new int[n];
  public final boolean[] burning = new boolean[n];
  public final int[] burnTimer = new int[n];
  public final int[] ownerNation = new int[n];
  public final int[] settlementAt = new int[n];
  /** Tilled farmland (recomputed alongside a settlement's farmCells) and
   * road cells (recomputed alongside territory) - purely a rendering
   * overlay, blended into the terrain's top-face color. */
  public final boolean[] isFarmland = new boolean[n];
  public final boolean[] isRoad = new boolean[n];

  /** Cell indices whose terrain/resource/color changed since the renderer
   * last flushed them. */
  public final Set<Integer> dirty = new LinkedHashSet<>();

  /** Cell indices currently on fire, maintained incrementally as cells
   * ignite/extinguish - lets fire spread be driven by however many cells
   * are actually burning instead of a full cols*rows scan every tick,
   * which mattered a lot once the map got a lot bigger. */
  public final Set<Integer> burningCells = new LinkedHashSet<>();
  /** How many consecutive ticks a large fire has been sustained - once a
   * wildfire has been raging a while, see Events.updateFire, its odds of
   * burning itself out start climbing so it's guaranteed to eventually
   * stop at some (unpredictable) point instead of just holding steady
   * against its spread cap forever. */
  public int fireStreak = 0;

  public WorldGrid() {
    java.util.Arrays.fill(terrain, Config.GRASS);
    java.util.Arrays.fill(ownerNation, -1);
    java.util.Arrays.fill(settlementAt, -1);
  }

  public int idx(int x, int y) { return y * cols + x; }
  public boolean inBounds(int x, int y) { return x >= 0 && y >= 0 && x < cols && y < rows; }

  public void markDirty(int x, int y) { dirty.add(idx(x, y)); }
  public void markDirtyIdx(int i) { dirty.add(i); }

  public boolean isLand(int i) { return terrain[i] != Config.WATER; }
  public boolean isBuildable(int i) {
    return terrain[i] == Config.GRASS || terrain[i] == Config.SAND || terrain[i] == Config.DIRT;
  }

  public double slopeAt(int x, int y) {
    int c = idx(x, y);
    double maxDiff = 0;
    int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    for (int[] d : dirs) {
      int nx = x + d[0], ny = y + d[1];
      if (!inBounds(nx, ny)) continue;
      maxDiff = Math.max(maxDiff, Math.abs(height[c] - height[idx(nx, ny)]));
    }
    return maxDiff;
  }

  public void setTerrain(int x, int y, byte t) {
    int i = idx(x, y);
    terrain[i] = t;
    if (t == Config.WATER) {
      resource[i] = Config.RES_NONE;
      resourceAmount[i] = 0;
    }
    burning[i] = false;
    burnTimer[i] = 0;
    markDirtyIdx(i);
  }

  public interface CellVisitor { void visit(int x, int y, double dist); }

  public void forEachInRadius(double cx, double cy, double radius, CellVisitor fn) {
    double r = Math.max(0, radius);
    int minY = (int) Math.floor(cy - r), maxY = (int) Math.ceil(cy + r);
    int minX = (int) Math.floor(cx - r), maxX = (int) Math.ceil(cx + r);
    for (int y = minY; y <= maxY; y++) {
      for (int x = minX; x <= maxX; x++) {
        if (!inBounds(x, y)) continue;
        double d = Math.hypot(x - cx, y - cy);
        if (d <= r) fn.visit(x, y, d);
      }
    }
  }
}
