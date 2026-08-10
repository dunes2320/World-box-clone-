package com.worldbox.world;

import com.worldbox.config.Config;
import com.worldbox.util.Noise;
import com.worldbox.util.Rng;

/** Builds a continent-ish island with beaches, plains, forests, hills and a
 * mountain spine, then scatters resource deposits. Deterministic per seed. */
public class WorldGen {

  public static void generate(WorldGrid grid, long seed) {
    Noise fbm = new Noise((int) seed);
    Rng rng = new Rng(seed * 7 + 3);
    int cols = grid.cols, rows = grid.rows;

    for (int y = 0; y < rows; y++) {
      for (int x = 0; x < cols; x++) {
        int i = grid.idx(x, y);
        double nx = (double) x / cols - 0.5;
        double ny = (double) y / rows - 0.5;
        double radial = Math.sqrt(nx * nx + ny * ny) * 2;

        double base = fbm.fbm(x * 0.06, y * 0.06, 5, 2, 0.5);
        double warp = fbm.fbm(x * 0.02 + 50, y * 0.02 + 50, 3, 2, 0.5);
        double elevation = base * 0.75 + warp * 0.25 - radial * 0.55;

        double h = elevation * 14;
        grid.height[i] = (float) h;

        // a separate moisture field breaks up what would otherwise be one
        // uniform grass biome into real plains/dirt patches, so the map
        // reads as varied terrain instead of a flat green blob
        double moisture = fbm.fbm(x * 0.045 + 400, y * 0.045 + 400, 4, 2, 0.5);

        if (h < -1.0) {
          grid.terrain[i] = Config.WATER;
        } else if (h < -0.15) {
          grid.terrain[i] = Config.SAND;
        } else if (h > 5.2) {
          grid.terrain[i] = Config.STONE;
        } else if (h > 3.6 && fbm.fbm(x * 0.1 + 200, y * 0.1 + 200, 2, 2, 0.5) > 0.55) {
          grid.terrain[i] = Config.STONE;
        } else if (moisture < -0.18) {
          grid.terrain[i] = Config.DIRT;
        } else {
          grid.terrain[i] = Config.GRASS;
        }
      }
    }

    for (int y = 0; y < rows; y++) {
      for (int x = 0; x < cols; x++) {
        int i = grid.idx(x, y);
        byte t = grid.terrain[i];
        if (t == Config.WATER) continue;

        if (t == Config.GRASS) {
          double forestN = fbm.fbm(x * 0.12 + 900, y * 0.12 + 900, 3, 2, 0.5);
          if (forestN > 0.62 && rng.chance(0.55)) {
            grid.resource[i] = Config.RES_FOREST;
            grid.resourceAmount[i] = Config.RESOURCE_INFO.get(Config.RES_FOREST).yieldAmt * 8;
          }
        } else if (t == Config.STONE) {
          double roll = rng.next();
          if (roll < 0.05) {
            grid.resource[i] = Config.RES_GOLD;
            grid.resourceAmount[i] = Config.RESOURCE_INFO.get(Config.RES_GOLD).yieldAmt * 30;
          } else if (roll < 0.16) {
            grid.resource[i] = Config.RES_IRON;
            grid.resourceAmount[i] = Config.RESOURCE_INFO.get(Config.RES_IRON).yieldAmt * 30;
          } else if (roll < 0.4) {
            grid.resource[i] = Config.RES_STONE;
            grid.resourceAmount[i] = Config.RESOURCE_INFO.get(Config.RES_STONE).yieldAmt * 40;
          }
        }
      }
    }

    grid.dirty.clear();
    for (int i = 0; i < cols * rows; i++) grid.dirty.add(i);
  }

  public static class Spot { public final int x, y; public Spot(int x, int y) { this.x = x; this.y = y; } }

  /** Finds a reasonable buildable land spot within `radius` cells of
   * (cx, cy). Used for settlement founding and colonization. */
  public static Spot findLandSpot(WorldGrid grid, double cx, double cy, double radius, Rng rng) {
    for (int tries = 0; tries < 40; tries++) {
      int x = (int) Math.round(cx + (rng.next() * 2 - 1) * radius);
      int y = (int) Math.round(cy + (rng.next() * 2 - 1) * radius);
      if (!grid.inBounds(x, y)) continue;
      int i = grid.idx(x, y);
      if (grid.isBuildable(i) && grid.slopeAt(x, y) < 1.2) return new Spot(x, y);
    }
    return null;
  }
}
