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
        // domain-warp the radial falloff with slow noise so the coastline
        // comes out as bays/peninsulas instead of one clean circular blob
        double coastWarpX = fbm.fbm(x * 0.015 + 700, y * 0.015 + 700, 3, 2, 0.5) - 0.5;
        double coastWarpY = fbm.fbm(x * 0.015 + 900, y * 0.015 + 900, 3, 2, 0.5) - 0.5;
        double radial = Math.sqrt(
            (nx + coastWarpX * 0.35) * (nx + coastWarpX * 0.35)
                + (ny + coastWarpY * 0.35) * (ny + coastWarpY * 0.35)) * 2;

        double base = fbm.fbm(x * 0.06, y * 0.06, 5, 2, 0.5);
        double warp = fbm.fbm(x * 0.02 + 50, y * 0.02 + 50, 3, 2, 0.5);
        double elevation = base * 0.75 + warp * 0.25 - radial * 0.55;

        double h = elevation * 14;
        grid.height[i] = (float) h;

        // a separate moisture field breaks up what would otherwise be one
        // uniform grass biome into real plains/dirt patches, so the map
        // reads as varied terrain instead of a flat green blob. Noise.fbm
        // returns 0..1 (not -1..1), so the threshold has to sit inside
        // that range - comparing against a negative number here silently
        // never fires, which is why dirt patches weren't actually showing
        // up despite this field existing.
        double moisture = fbm.fbm(x * 0.045 + 400, y * 0.045 + 400, 4, 2, 0.5);

        // VoxelWorld voxelizes a land column's height as
        // round(grid.height[i]) + Y_OFFSET, and water is a *fixed* plane
        // at WATER_LEVEL = Y_OFFSET - 1. A land cell only comes out flush
        // with that fixed water surface when its own height rounds to 0
        // (i.e. sits in [-0.5, 0.5)) - the old thresholds here (water
        // below -1.0, sand below -0.15) classified "dry" sand across a
        // band that was still well below that flush point, so nearly
        // every beach tile voxelized as fully submerged, one block under
        // the water surface, instead of meeting it at the shoreline.
        if (h < -0.5) {
          grid.terrain[i] = Config.WATER;
        } else if (h < 0.5) {
          grid.terrain[i] = Config.SAND;
        } else if (h > 5.2) {
          grid.terrain[i] = Config.STONE;
        } else if (h > 3.6 && fbm.fbm(x * 0.1 + 200, y * 0.1 + 200, 2, 2, 0.5) > 0.55) {
          grid.terrain[i] = Config.STONE;
        } else if (moisture < 0.32) {
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
          // lower frequency than the old 0.12 so forests clump into real
          // sprawling woods instead of a fine speckle of single trees
          double forestN = fbm.fbm(x * 0.07 + 900, y * 0.07 + 900, 3, 2, 0.5);
          if (forestN > 0.58 && rng.chance(0.6)) {
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
   * (cx, cy) that also has enough nearby grass to actually farm - used for
   * settlement founding and colonization. Without the farmland check a
   * settlement could found on a sand spit or a dirt/plains patch with
   * nothing to eat nearby and starve out almost immediately. */
  public static Spot findLandSpot(WorldGrid grid, double cx, double cy, double radius, Rng rng) {
    for (int tries = 0; tries < 40; tries++) {
      int x = (int) Math.round(cx + (rng.next() * 2 - 1) * radius);
      int y = (int) Math.round(cy + (rng.next() * 2 - 1) * radius);
      if (!grid.inBounds(x, y)) continue;
      int i = grid.idx(x, y);
      if (grid.isBuildable(i) && grid.slopeAt(x, y) < 1.2 && hasNearbyGrass(grid, x, y, 4, 6)) return new Spot(x, y);
    }
    return null;
  }

  private static boolean hasNearbyGrass(WorldGrid grid, int cx, int cy, int radius, int minCount) {
    int count = 0;
    for (int dy = -radius; dy <= radius; dy++) {
      for (int dx = -radius; dx <= radius; dx++) {
        int x = cx + dx, y = cy + dy;
        if (!grid.inBounds(x, y)) continue;
        if (grid.terrain[grid.idx(x, y)] == Config.GRASS && ++count >= minCount) return true;
      }
    }
    return false;
  }
}
