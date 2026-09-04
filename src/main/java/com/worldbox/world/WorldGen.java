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
    // stored (not just local to the classification loop) so the biome
    // pass below - which runs after rivers/islands have finished mutating
    // terrain - can still classify every cell using the same climate
    // fields the terrain itself was generated from.
    double[] moistureField = new double[n];
    double[] temperature = new double[n];
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

        // an independent, very-low-frequency field that occasionally
        // carves a broad strait/channel straight through what would
        // otherwise be one single landmass - without it every seed comes
        // out as one blob of continent no matter how the other noise
        // terms are tuned, since they only ever vary a blob's edge, never
        // split its interior. Only fires in a small top slice of the
        // field's own range, so it stays rare (a strait here or there,
        // not the map fractured into gravel).
        double strait = fbm.fbm(x * 0.008 + 15000, y * 0.008 + 15000, 3, 2, 0.5);
        if (strait > 0.72) elevation -= (strait - 0.72) * 3.0;

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
        moistureField[i] = moisture;

        // fertility: its own independent, broad low-frequency field (not
        // derived from moisture/elevation) so real fertile stretches and
        // poor ones each span a real region of the map instead of
        // following the same pattern grass/dirt already does - see
        // Settlement/Economy's farm food production, which reads this at
        // a settlement's own location.
        grid.fertility[i] = (float) fbm.fbm(x * 0.016 + 9000, y * 0.016 + 9000, 4, 2, 0.5);

        // temperature: a broad low-frequency noise field biased by
        // distance from the map's vertical center, so climate reads as
        // real cold/warm regions/bands (like real-world latitude) instead
        // of scattered patches - the noise term keeps the bands irregular
        // rather than a perfectly straight gradient.
        double latitude = 1.0 - Math.abs(ny) * 2; // 1 at the equatorial center, 0 at the poles
        double tempNoise = fbm.fbm(x * 0.02 + 11000, y * 0.02 + 11000, 3, 2, 0.5);
        temperature[i] = latitude * 0.65 + tempNoise * 0.35;

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

    scatterIslands(grid, fbm);
    carveRivers(grid, rng);
    classifyBiomes(grid, temperature, moistureField);

    for (int y = 0; y < rows; y++) {
      for (int x = 0; x < cols; x++) {
        int i = grid.idx(x, y);
        byte t = grid.terrain[i];
        if (t == Config.WATER) continue;

        if (t == Config.GRASS) {
          // lower frequency than the old 0.12 so forests clump into real
          // sprawling woods instead of a fine speckle of single trees.
          // Threshold shifts per biome (see forestThresholdFor) so a
          // wetland reads as dense woodland, a desert as near-treeless,
          // instead of every grass cell everywhere sharing one flat rate.
          double forestN = fbm.fbm(x * 0.07 + 900, y * 0.07 + 900, 3, 2, 0.5);
          if (forestN > forestThresholdFor(grid.biome[i]) && rng.chance(0.6)) {
            grid.resource[i] = Config.RES_FOREST;
            grid.resourceAmount[i] = Config.RESOURCE_INFO.get(Config.RES_FOREST).yieldAmt * 8;
          }
        } else if (t == Config.STONE) {
          // regional favorability: three independent broad noise fields
          // (not tied to elevation or each other) bias which mineral is
          // actually likely here, so the map ends up with a real "gold
          // country" and a real "iron country" instead of every mountain
          // range offering the same uniform blend of everything. Each
          // multiplier averages out to roughly 1x over the whole map (so
          // total world-wide deposit counts land close to the old flat
          // rates) but swings from 0.2x in an unfavorable region up to
          // 1.8x in a rich one - real scarcity depending on where a
          // nation actually is, never literally zero anywhere.
          double goldFavor = fbm.fbm(x * 0.012 + 5000, y * 0.012 + 5000, 3, 2, 0.5);
          double ironFavor = fbm.fbm(x * 0.012 + 6000, y * 0.012 + 6000, 3, 2, 0.5);
          double stoneFavor = fbm.fbm(x * 0.012 + 7500, y * 0.012 + 7500, 3, 2, 0.5);
          double goldP = 0.05 * (0.2 + goldFavor * 1.6);
          double ironP = 0.11 * (0.2 + ironFavor * 1.6);
          double stoneP = 0.24 * (0.2 + stoneFavor * 1.6);
          double roll = rng.next();
          if (roll < goldP) {
            grid.resource[i] = Config.RES_GOLD;
            grid.resourceAmount[i] = Config.RESOURCE_INFO.get(Config.RES_GOLD).yieldAmt * 30;
          } else if (roll < goldP + ironP) {
            grid.resource[i] = Config.RES_IRON;
            grid.resourceAmount[i] = Config.RESOURCE_INFO.get(Config.RES_IRON).yieldAmt * 30;
          } else if (roll < goldP + ironP + stoneP) {
            grid.resource[i] = Config.RES_STONE;
            grid.resourceAmount[i] = Config.RESOURCE_INFO.get(Config.RES_STONE).yieldAmt * 40;
          }
        }
      }
    }

    grid.dirty.clear();
    for (int i = 0; i < cols * rows; i++) grid.dirty.add(i);
  }

  /** Assigns every cell's biome (see Config.BIOME_*) from the same
   * temperature/moisture fields the terrain itself was generated from.
   * Runs after rivers/islands so water cells they add get tagged
   * BIOME_OCEAN too, and after the terrain pass so mountains (already
   * classified STONE by height) read as BIOME_MOUNTAIN regardless of
   * their local climate - a snowy peak and a sun-baked one are both
   * still "mountain", not "tundra"/"desert" with rocks in it. */
  private static void classifyBiomes(WorldGrid grid, double[] temperature, double[] moisture) {
    int cols = grid.cols, rows = grid.rows;
    for (int y = 0; y < rows; y++) {
      for (int x = 0; x < cols; x++) {
        int i = grid.idx(x, y);
        if (grid.terrain[i] == Config.WATER) { grid.biome[i] = Config.BIOME_OCEAN; continue; }
        if (grid.terrain[i] == Config.STONE) { grid.biome[i] = Config.BIOME_MOUNTAIN; continue; }
        double temp = temperature[i], moist = moisture[i];
        byte biome;
        if (temp < 0.32) {
          biome = Config.BIOME_TUNDRA;
        } else if (temp > 0.62 && moist < 0.4) {
          biome = Config.BIOME_DESERT;
        } else if (moist > 0.62) {
          // low-lying and soggy reads as wetland; the same moisture up on
          // higher ground is just a wet forest, not a swamp
          biome = grid.height[i] < 1.0f ? Config.BIOME_WETLAND : Config.BIOME_FOREST;
        } else if (moist > 0.45) {
          biome = Config.BIOME_FOREST;
        } else {
          biome = Config.BIOME_PLAINS;
        }
        grid.biome[i] = biome;
      }
    }
  }

  /** How dense forest resource placement is per biome - a flat rate
   * everywhere read as one uniform world regardless of climate. Lower
   * threshold = more forest (see the forestN > threshold check above). */
  private static float forestThresholdFor(byte biome) {
    if (biome == Config.BIOME_WETLAND) return 0.42f;
    if (biome == Config.BIOME_FOREST) return 0.48f;
    if (biome == Config.BIOME_TUNDRA) return 0.68f;
    if (biome == Config.BIOME_DESERT) return 0.9f;
    return 0.58f; // plains and anything else - the old flat rate
  }

  /** Scatters small offshore islands into open water - a separate,
   * higher-frequency noise field from the continent/hill/base terms
   * above, so an island's location has nothing to do with where the
   * mainland's own shape happens to be. Only touches cells that were
   * already water going in, so it can never eat into (or leave a stray
   * patch inside) the actual coastline. */
  private static void scatterIslands(WorldGrid grid, Noise fbm) {
    int cols = grid.cols, rows = grid.rows;
    for (int y = 0; y < rows; y++) {
      for (int x = 0; x < cols; x++) {
        int i = grid.idx(x, y);
        if (grid.terrain[i] != Config.WATER) continue;
        double nx = (double) x / cols - 0.5, ny = (double) y / rows - 0.5;
        double edgeDist = 0.5 - Math.max(Math.abs(nx), Math.abs(ny));
        if (edgeDist < 0.05) continue; // keep the outermost rim open ocean
        double islandN = fbm.fbm(x * 0.09 + 12000, y * 0.09 + 12000, 3, 2, 0.5);
        if (islandN > 0.82) {
          grid.terrain[i] = Config.GRASS;
          grid.height[i] = 1.2f;
        } else if (islandN > 0.76) {
          grid.terrain[i] = Config.SAND;
          grid.height[i] = 0.2f;
        }
      }
    }
  }

  private static final int[][] NEIGH4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private static final int[][] NEIGH8 = {
      {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

  /** Carves a handful of real rivers: each starts at a random point up in
   * the hills and walks steepest-descent (with a little noise so it
   * meanders instead of cutting a ruler-straight line) until it reaches
   * existing water or gets stuck in a local pit, turning every cell it
   * crosses into a narrow water channel with sand banks alongside. Runs
   * after the base terrain pass so it carves into the real generated
   * landscape rather than a separate abstract layer, and before resource
   * placement so a river cell can never end up double-booked as a forest
   * or ore deposit. */
  private static void carveRivers(WorldGrid grid, Rng rng) {
    int cols = grid.cols, rows = grid.rows;
    int riverCount = 4 + rng.intRange(0, 4);
    for (int r = 0; r < riverCount; r++) {
      int sx = -1, sy = -1;
      for (int tries = 0; tries < 60; tries++) {
        int x = rng.intRange(0, cols - 1), y = rng.intRange(0, rows - 1);
        int i = grid.idx(x, y);
        if (grid.terrain[i] == Config.WATER) continue;
        if (grid.height[i] < 3.0f) continue; // only start rivers up in high ground
        sx = x; sy = y;
        break;
      }
      if (sx < 0) continue;

      int x = sx, y = sy;
      java.util.Set<Long> visited = new java.util.HashSet<>();
      for (int step = 0; step < 400; step++) {
        long key = (long) x * rows + y;
        if (!visited.add(key)) break; // wandered back onto itself - stop rather than loop forever
        int i = grid.idx(x, y);
        if (grid.terrain[i] == Config.WATER) break; // reached the sea or a lake

        grid.terrain[i] = Config.WATER;
        grid.height[i] = Math.min(grid.height[i], -0.6f);
        grid.resource[i] = Config.RES_NONE;
        for (int[] d : NEIGH4) {
          int nx = x + d[0], ny = y + d[1];
          if (!grid.inBounds(nx, ny)) continue;
          int ni = grid.idx(nx, ny);
          if (grid.terrain[ni] != Config.WATER && grid.height[ni] < 2.5f) grid.terrain[ni] = Config.SAND;
        }

        int bestX = x, bestY = y;
        float bestH = grid.height[i];
        for (int[] d : NEIGH8) {
          int nx = x + d[0], ny = y + d[1];
          if (!grid.inBounds(nx, ny)) continue;
          float h = grid.height[grid.idx(nx, ny)] + (float) (rng.next() * 0.6 - 0.3);
          if (h < bestH) { bestH = h; bestX = nx; bestY = ny; }
        }
        if (bestX == x && bestY == y) break; // pooled - nowhere lower to flow
        x = bestX; y = bestY;
      }
    }
  }

  public static class Spot { public final int x, y; public Spot(int x, int y) { this.x = x; this.y = y; } }

  /** A settlement's suburb spiral, claimed-territory radius and roads all
   * grow outward from its center - founding one within this many cells of
   * the actual world edge left its far side permanently cut off against
   * the map boundary (empty void where the ocean/world just stops), and
   * read as "a village built at the edge of the world" rather than a real
   * settlement in the middle of usable land. A world border, in effect:
   * no settlement (new nation, or a colony/expansion) ever founds this
   * close to the edge in the first place. */
  private static final int WORLD_EDGE_MARGIN = 20;

  public static boolean tooCloseToWorldEdge(WorldGrid grid, int x, int y) {
    return x < WORLD_EDGE_MARGIN || y < WORLD_EDGE_MARGIN
        || x >= grid.cols - WORLD_EDGE_MARGIN || y >= grid.rows - WORLD_EDGE_MARGIN;
  }

  /** Finds a reasonable buildable land spot within `radius` cells of
   * (cx, cy) that also has enough nearby grass to actually farm - used for
   * settlement founding and colonization. Without the farmland check a
   * settlement could found on a sand spit or a dirt/plains patch with
   * nothing to eat nearby and starve out almost immediately. */
  public static Spot findLandSpot(WorldGrid grid, double cx, double cy, double radius, Rng rng) {
    for (int tries = 0; tries < 40; tries++) {
      int x = (int) Math.round(cx + (rng.next() * 2 - 1) * radius);
      int y = (int) Math.round(cy + (rng.next() * 2 - 1) * radius);
      if (!grid.inBounds(x, y) || tooCloseToWorldEdge(grid, x, y)) continue;
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
