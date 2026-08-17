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
    int n = cols * rows;

    // Pass 1: raw multi-scale elevation, no bias applied yet. The
    // dominant term is low-frequency ("continents"), and a map this size
    // only spans a handful of its wavelengths, so its average value
    // drifts noticeably from seed to seed - without correcting for that
    // one seed could come out almost all ocean and another almost all
    // mountain. Measuring this map's own actual mean first and
    // calibrating against it keeps land/water proportions consistent
    // regardless of seed.
    double[] rawElevation = new double[n];
    double sum = 0;
    for (int y = 0; y < rows; y++) {
      for (int x = 0; x < cols; x++) {
        int i = grid.idx(x, y);
        double continents = fbm.fbm(x * 0.01 + 1000, y * 0.01 + 1000, 4, 2, 0.5);
        // a mid-frequency term between the broad "continents" shape and the
        // fine "base" bumps - without it the map only ever reads as one
        // smooth landmass with a bit of surface noise on top; this is what
        // actually breaks it into distinct rolling hill regions at a scale
        // you notice while walking the camera across it, not just from orbit
        double hills = fbm.fbm(x * 0.025 + 2000, y * 0.025 + 2000, 4, 2, 0.5);
        double base = fbm.fbm(x * 0.06, y * 0.06, 5, 2, 0.5);
        double warp = fbm.fbm(x * 0.02 + 50, y * 0.02 + 50, 3, 2, 0.5);
        double e = continents * 1.3 + hills * 0.7 + base * 0.45 + warp * 0.25;
        rawElevation[i] = e;
        sum += e;
      }
    }
    double mean = sum / n;

    for (int y = 0; y < rows; y++) {
      for (int x = 0; x < cols; x++) {
        int i = grid.idx(x, y);
        double nx = (double) x / cols - 0.5;
        double ny = (double) y / rows - 0.5;

        // only the outer ~8% of the map tapers toward water, so the
        // border reads as a coastline instead of terrain cutting off -
        // unlike the old radial term this doesn't touch anywhere else,
        // so mountains/forests can appear anywhere in the interior
        double edgeDist = 0.5 - Math.max(Math.abs(nx), Math.abs(ny));
        double edgeFade = Math.min(1.0, edgeDist / 0.08);

        // +0.12 relative to this map's own mean keeps land somewhat more
        // common than water without depending on absolute noise output,
        // which is what made the water/land split swing wildly by seed
        double elevation = (rawElevation[i] - mean) + 0.28;
        elevation = elevation * edgeFade - (1 - edgeFade) * 1.2;

        // Ridged noise (folding fbm's usual rolling peaks into sharp
        // valleys-turned-ridges via |2v-1|) sharpens whatever's already
        // trending uphill into a real jagged mountain spine instead of the
        // one smooth blob "elevation" alone produces - masked by how high
        // elevation already is here so ridges crown existing highlands
        // rather than poking up at random across the plains.
        if (elevation > 0.15) {
          double ridgeN = fbm.fbm(x * 0.045 + 3000, y * 0.045 + 3000, 4, 2.1, 0.5);
          double ridge = 1.0 - Math.abs(ridgeN * 2 - 1);
          double mountainMask = Math.min(1.0, (elevation - 0.15) / 0.5);
          elevation += ridge * ridge * mountainMask * 0.5;
        }

        double h = elevation * 14;

        // A separate, much lower-frequency field than every other term here
        // picks out a handful of broad interior basins - landing on open,
        // low-to-mid ground away from both the mountains and the coastline,
        // it carves a real inland lake instead of the map only ever having
        // water at its ocean-fringed border.
        double basin = fbm.fbm(x * 0.018 + 7000, y * 0.018 + 7000, 3, 2, 0.5);
        if (basin > 0.7 && edgeFade > 0.35 && h > -0.5 && h < 5) {
          h = -1.2;
        }

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
        } else if (h > 8.5) {
          grid.terrain[i] = Config.STONE;
        } else if (h > 6.5 && fbm.fbm(x * 0.1 + 200, y * 0.1 + 200, 2, 2, 0.5) > 0.6) {
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
