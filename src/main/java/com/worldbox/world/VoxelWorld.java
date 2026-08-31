package com.worldbox.world;

import com.worldbox.config.Config;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** A real 3D block grid, generated from the existing 2D heightmap/terrain
 * data and independently destructible - meteors, digging, earthquakes etc.
 * carve actual blocks out rather than just editing a height number.
 *
 * Horizontally this is genuinely higher-resolution than the 2D WorldGrid it's
 * built from: each WorldGrid cell becomes a real FINE x FINE block of
 * independently-editable columns (not a cosmetic render-time subdivision -
 * every one of those columns is its own byte in the block array, diggable
 * and buildable on its own), so the world actually reads as built from
 * smaller blocks instead of one shader trick over the same coarse data.
 * The WorldGrid's own resolution never changes - a prior test found just
 * 1.5x its dimensions collapsing the simulation tick rate (fire spread,
 * grass regrowth, territory, pathing all scan it every tick), so all of
 * that stays cheap. Every public method below except get/set/columnTopY/
 * heightWorld takes the SAME coarse WorldGrid-cell coordinates every
 * existing caller (GodTools, Events, Settlement, Picking) already uses -
 * they just now affect a FINE x FINE patch of real columns under the hood,
 * so none of those callers needed to change at all.
 *
 * The 2D WorldGrid stays the authoritative layer for simulation (pathing,
 * settlement placement, resource yields, territory); after every voxel
 * edit the affected column's WorldGrid.height is resynced from the
 * topmost solid block (see Simulation/GameApp), so every existing
 * gameplay system keeps working unchanged against a slightly blockier
 * surface. */
public class VoxelWorld implements java.io.Serializable {
  public static final byte AIR = 0;
  public static final byte GRASS = 1;
  public static final byte DIRT = 2;
  public static final byte SAND = 3;
  public static final byte STONE = 4;
  public static final byte WATER = 5;
  public static final byte PATH = 6;

  /** How many real fine columns each WorldGrid cell becomes, per
   * horizontal axis - the actual "smaller blocks" knob. Also used as the
   * vertical step divisor (see Y_OFFSET/MAX_Y below): every block, terrain
   * or building, is exactly 1/FINE world units on every axis - a real
   * cube, not a slab. It used to only subdivide X/Z while Y stayed a full
   * world-unit step, which made every terrain block 0.5 wide x 1.0 tall x
   * 0.5 deep - visibly a tall narrow slab instead of a cube next to a
   * building's own already-cubic Blueprint blocks (Blueprint.SCALE applies
   * the same 1/FINE to x, y, and z alike). */
  public static final int FINE = 2;

  /** World-space height 0 sits at the TOP FACE of block layer Y_OFFSET-1,
   * so negative terrain heights (seabeds, valleys) still have a valid
   * non-negative index. Scaled by FINE (same as MAX_Y/WATER_LEVEL below)
   * since each index step is now 1/FINE world units instead of a full
   * one - this keeps the same real amount of below/above-sea-level
   * headroom in world units as before, just expressed in twice as many,
   * twice as fine, steps. */
  public static final int Y_OFFSET = 9 * FINE;
  public static final int MAX_Y = 30 * FINE;
  public static final int WATER_LEVEL = Y_OFFSET - 1;
  public static final int CHUNK_SIZE = 16 * FINE;

  /** The WorldGrid's own (coarse) dimensions - what every sim-facing
   * method below takes its x/z parameters in. */
  public final int coarseCols, coarseRows;
  /** Real block-grid extent, in fine columns - what get/set/columnTopY/
   * heightWorld and the renderer operate in. */
  public final int cols, rows;
  public final int chunksX, chunksZ;
  private final byte[] blocks;
  public final Set<Integer> dirtyChunks = new LinkedHashSet<>();

  public VoxelWorld(WorldGrid grid) {
    this.coarseCols = grid.cols;
    this.coarseRows = grid.rows;
    this.cols = grid.cols * FINE;
    this.rows = grid.rows * FINE;
    this.chunksX = (cols + CHUNK_SIZE - 1) / CHUNK_SIZE;
    this.chunksZ = (rows + CHUNK_SIZE - 1) / CHUNK_SIZE;
    this.blocks = new byte[cols * rows * MAX_Y];
    generate(grid);
  }

  private int index(int x, int y, int z) { return (z * cols + x) * MAX_Y + y; }

  /** Fine-column coordinates (0..cols-1, 0..rows-1). */
  public byte get(int x, int y, int z) {
    if (x < 0 || z < 0 || x >= cols || z >= rows || y < 0 || y >= MAX_Y) return AIR;
    return blocks[index(x, y, z)];
  }

  /** Fine-column coordinates (0..cols-1, 0..rows-1). */
  public void set(int x, int y, int z, byte type) {
    if (x < 0 || z < 0 || x >= cols || z >= rows || y < 0 || y >= MAX_Y) return;
    blocks[index(x, y, z)] = type;
    markChunkDirty(x, z);
  }

  private void setRaw(int x, int y, int z, byte type) {
    if (y < 0 || y >= MAX_Y) return;
    blocks[index(x, y, z)] = type;
  }

  private void markChunkDirty(int x, int z) {
    int cx = x / CHUNK_SIZE, cz = z / CHUNK_SIZE;
    dirtyChunks.add(cz * chunksX + cx);
    // an edit right at a chunk boundary changes the neighbor chunk's
    // face-culling too, so it needs a re-mesh as well
    if (x % CHUNK_SIZE == 0 && cx > 0) dirtyChunks.add(cz * chunksX + (cx - 1));
    if (x % CHUNK_SIZE == CHUNK_SIZE - 1 && cx < chunksX - 1) dirtyChunks.add(cz * chunksX + (cx + 1));
    if (z % CHUNK_SIZE == 0 && cz > 0) dirtyChunks.add((cz - 1) * chunksX + cx);
    if (z % CHUNK_SIZE == CHUNK_SIZE - 1 && cz < chunksZ - 1) dirtyChunks.add((cz + 1) * chunksX + cx);
  }

  /** Topmost solid (non-air, non-water) block in a column, or 0 if none.
   * Fine-column coordinates. */
  public int columnTopY(int x, int z) {
    for (int y = MAX_Y - 1; y >= 0; y--) {
      byte b = get(x, y, z);
      if (b != AIR && b != WATER) return y;
    }
    return 0;
  }

  /** World-space Y of the top face of a column's surface - what the old
   * smooth WorldGrid.height used to mean, now quantized to whole blocks
   * (1/FINE world units each - see FINE's own comment). Fine-column
   * coordinates. */
  public float heightWorld(int x, int z) {
    return (columnTopY(x, z) + 1 - Y_OFFSET) / (float) FINE;
  }

  private void generate(WorldGrid grid) {
    for (int z = 0; z < rows; z++) {
      for (int x = 0; x < cols; x++) {
        int gx = x / FINE, gz = z / FINE;
        int i = grid.idx(gx, gz);
        byte terrain = grid.terrain[i];
        int h = Math.round(grid.height[i] * FINE) + Y_OFFSET;
        // a sparse, deterministic +-1 FINE-STEP jitter per fine column
        // (not every one - most stay flush with their coarse cell) so
        // upsampling to real smaller blocks actually shows new
        // bumps/weathering at the finer scale instead of just re-tiling
        // the exact same flat surface at a higher block count. Excludes
        // water and sand: sand is the shoreline fringe, and jittering it
        // undoes the rounding above that keeps it flush with the fixed
        // WATER_LEVEL plane, showing up as a ragged, "sunken" coastline.
        if (terrain != Config.WATER && terrain != Config.SAND) {
          int jitter = fineHash(x, z);
          if (jitter == 0) h -= 1;
          else if (jitter == 1) h += 1;
        }
        h = Math.max(1, Math.min(MAX_Y - 2, h));
        if (terrain == Config.WATER) {
          int seabed = Math.min(h, WATER_LEVEL - 1);
          for (int y = 0; y <= seabed; y++) setRaw(x, y, z, y >= seabed - 1 ? SAND : STONE);
          for (int y = seabed + 1; y <= WATER_LEVEL; y++) setRaw(x, y, z, WATER);
        } else {
          byte surfaceBlock = blockForTerrain(terrain);
          int dirtDepth = 3 * FINE;
          for (int y = 0; y < h; y++) {
            byte b = y >= h - 1 ? surfaceBlock : (y >= h - dirtDepth ? DIRT : STONE);
            setRaw(x, y, z, b);
          }
        }
      }
    }
  }

  /** Deterministic per-fine-column roll: ~12% chance of -1, ~12% of +1,
   * rest flat (0 meaning "no jitter" isn't itself a valid return other
   * than falling through both checks above). */
  private static int fineHash(int x, int z) {
    int h = x * 374761393 + z * 668265263;
    h = (h ^ (h >>> 13)) * 1274126177;
    h = h ^ (h >>> 16);
    int bucket = Math.floorMod(h, 100);
    if (bucket < 12) return 0;
    if (bucket < 24) return 1;
    return -1;
  }

  public static byte blockForTerrain(byte terrain) {
    if (terrain == Config.SAND) return SAND;
    if (terrain == Config.STONE) return STONE;
    if (terrain == Config.DIRT) return DIRT;
    return GRASS;
  }

  /** WorldGrid.height used to mean "the smooth surface elevation"; after a
   * voxel edit, callers resync it from the block data so pathing,
   * buildability, and every entity that stands "on the ground" keep
   * working against the new blocky surface. Coarse (WorldGrid) x/z -
   * averages the FINE x FINE sub-columns under this cell so gameplay
   * reads one representative height even once fine edits (or the
   * generation-time jitter above) have made them diverge slightly. */
  public void resyncHeight(WorldGrid grid, int x, int z) {
    double sum = 0;
    boolean allWater = true;
    for (int dz = 0; dz < FINE; dz++) {
      for (int dx = 0; dx < FINE; dx++) {
        int fx = x * FINE + dx, fz = z * FINE + dz;
        sum += heightWorld(fx, fz);
        if (get(fx, WATER_LEVEL, fz) != WATER) allWater = false;
      }
    }
    grid.height[grid.idx(x, z)] = (float) (sum / (FINE * FINE));
    int gi = grid.idx(x, z);
    // flowWaterInto (see its own comment) can flood a cell's voxels that
    // was dry land at worldgen time - WorldGrid's own coarse terrain
    // classification needs to catch up too, or gameplay (isBuildable,
    // farmland, house placement) would keep treating a now-flooded cell
    // as dry land and immediately try to terraform the water back out
    if (allWater) {
      grid.terrain[gi] = Config.WATER;
    } else if (grid.terrain[gi] == Config.WATER) {
      // the reverse: building/filling has reclaimed this cell from water
      // (buildColumn simply overwrites whatever was there, water
      // included) - it needs a real land classification too, not to stay
      // marked WATER forever just because it once was
      grid.terrain[gi] = Config.DIRT;
    }
  }

  /** Removes (sets to air) every non-water block within `radius` WORLD
   * UNITS of a world-space point - meteors, nukes, earthquakes, the dig
   * tool. `touchedColumns` still comes back as packed COARSE (x,z) - see
   * Events.earthquake/crater for the unpack + resyncHeight loop this
   * feeds, unchanged by the finer grid underneath. */
  public void carveSphere(double wx, double wyWorld, double wz, double radius, Set<Long> touchedColumns) {
    int cxBlock = (int) Math.round(wx * FINE);
    int czBlock = (int) Math.round(wz * FINE);
    int cyBlock = (int) Math.round(wyWorld * FINE) + Y_OFFSET;
    int rFine = (int) Math.ceil(radius * FINE);
    int rY = (int) Math.ceil(radius * FINE);
    for (int dz = -rFine; dz <= rFine; dz++) {
      int z = czBlock + dz;
      if (z < 0 || z >= rows) continue;
      for (int dx = -rFine; dx <= rFine; dx++) {
        int x = cxBlock + dx;
        if (x < 0 || x >= cols) continue;
        double horizD = Math.hypot(dx / (double) FINE, dz / (double) FINE);
        if (horizD > radius) continue;
        boolean touched = false;
        for (int dy = -rY; dy <= rY; dy++) {
          int y = cyBlock + dy;
          if (y < 0 || y >= MAX_Y) continue;
          double d = Math.sqrt(horizD * horizD + (dy / (double) FINE) * (dy / (double) FINE));
          if (d > radius) continue;
          if (get(x, y, z) == WATER || get(x, y, z) == AIR) continue;
          set(x, y, z, AIR);
          touched = true;
        }
        if (touched) {
          flowWaterInto(x, z);
          if (touchedColumns != null) touchedColumns.add((long) (z / FINE) * coarseCols + (x / FINE));
        }
      }
    }
  }

  /** Digs straight down one block from the current surface of every fine
   * sub-column under this coarse cell. Coarse (WorldGrid) x/z. Returns
   * the block type removed from the first sub-column actually touched
   * (existing callers only check it against AIR to mean "nothing dug"). */
  public byte digColumn(int x, int z) {
    byte removed = AIR;
    for (int dz = 0; dz < FINE; dz++) {
      for (int dx = 0; dx < FINE; dx++) {
        int fx = x * FINE + dx, fz = z * FINE + dz;
        int top = columnTopY(fx, fz);
        if (top <= 1) continue;
        byte r = get(fx, top, fz);
        set(fx, top, fz, AIR);
        if (removed == AIR) removed = r;
        flowWaterInto(fx, fz);
      }
    }
    return removed;
  }

  /** Places one block on top of the current surface of every fine
   * sub-column under this coarse cell. Coarse (WorldGrid) x/z. */
  public void buildColumn(int x, int z, byte type) {
    for (int dz = 0; dz < FINE; dz++) {
      for (int dx = 0; dx < FINE; dx++) {
        int fx = x * FINE + dx, fz = z * FINE + dz;
        int top = columnTopY(fx, fz);
        if (top + 1 >= MAX_Y) continue;
        set(fx, top + 1, fz, type);
      }
    }
  }

  /** Sets one FINE column's actual surface to exactly targetTop (a real
   * block-layer index, not a WorldGrid height) - digs down or builds up
   * from wherever that column's own current top really is. This is NOT
   * the same as calling digColumn/buildColumn a fixed number of times:
   * those only ever apply the same DELTA to every column, which merely
   * shifts each one by the same amount and preserves whatever height
   * differences it already had with its neighbors (generation's sparse
   * per-fine-column jitter - see generate() - means neighboring columns
   * routinely don't start at the same height) - not actually flat, which
   * is what a building's site needs to be so its single flat blueprint
   * mesh doesn't clip into (or float above) a lot that only reads as
   * flat at the coarse WorldGrid level. Fine-column coordinates. */
  public void levelColumn(int fx, int fz, int targetTop, byte fillType) {
    int top = columnTopY(fx, fz);
    while (top < targetTop) { set(fx, ++top, fz, fillType); }
    while (top > targetTop) { set(fx, top, fz, AIR); top--; }
    flowWaterInto(fx, fz);
  }

  /** Pending cells to check for water flow (see tickWaterFlow) - a real,
   * ongoing fluid simulation instead of a one-shot flood resolved
   * instantly (and only partially - see below) at the moment of a single
   * edit. Transient: not worth persisting through a save, a reload just
   * re-settles from whatever's actually still air, which happens within
   * a few ticks anyway. */
  private transient ArrayDeque<Long> waterFrontier;

  private ArrayDeque<Long> waterFrontier() {
    if (waterFrontier == null) waterFrontier = new ArrayDeque<>();
    return waterFrontier;
  }

  private long voxelKey(int x, int y, int z) {
    return ((long) z * cols + x) * MAX_Y + y;
  }

  /** Queues this column's exposed-below-sea-level air to be checked by
   * tickWaterFlow. After digging/terraforming exposes empty space below
   * WATER_LEVEL, water needs to actually flow into it the way a real hole
   * dug at the water's edge floods - not stay a dry pit just because
   * nothing painted it as a lake at worldgen time. Fine-column
   * coordinates. */
  public void flowWaterInto(int fx, int fz) {
    int top = columnTopY(fx, fz);
    if (top >= WATER_LEVEL) return;
    for (int y = top + 1; y <= WATER_LEVEL; y++) {
      if (get(fx, y, fz) == AIR) waterFrontier().add(voxelKey(fx, y, fz));
    }
  }

  private static final int[][] NEIGHBORS_6 = {
      {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
  };

  /** The actual fluid rule, run incrementally: any AIR block at or below
   * sea level that's directly touching a WATER block becomes water
   * itself, and since it's now water, it can flood ITS OWN air neighbors
   * in turn on a later call - a real flood spreading outward from
   * wherever an edit exposed new air, through every connected air block
   * below sea level, not stopping at some arbitrary point partway through
   * a large connected cavity the way a single bounded pass used to (that
   * read as an invisible wall - water flooding in for a while and then
   * just stopping dead with nothing obviously different about the ground
   * on the other side of it). Spread across ticks (`budget` cells per
   * call, see Simulation.tick) instead of resolved all at once so a big
   * flood visibly takes a moment to fill in, the way real water does,
   * rather than teleporting to its final shape in one frame.
   *
   * Still deliberately does not flood an air pocket that never actually
   * reaches a real water block: plenty of ordinary dry land (a low beach,
   * a marshy hollow, a sealed basement dug below sea level) legitimately
   * sits below WATER_LEVEL with nothing around it, on purpose. A cell only
   * becomes water once ONE of its neighbors already is - a pocket with no
   * water anywhere in it never gets a first cell to start from, so it
   * just sits in the frontier queue, gets discarded once checked, and
   * stays dry, same as before. Returns every COARSE (WorldGrid) cell that
   * actually changed, so the caller can resync WorldGrid's own terrain/
   * height classification for them (see resyncHeight). */
  public Set<Long> tickWaterFlow(int budget) {
    Set<Long> touchedCoarse = null;
    ArrayDeque<Long> q = waterFrontier();
    for (int steps = 0; steps < budget && !q.isEmpty(); steps++) {
      long key = q.poll();
      int y = (int) (key % MAX_Y);
      long rest = key / MAX_Y;
      int x = (int) (rest % cols);
      int z = (int) (rest / cols);
      if (get(x, y, z) != AIR) continue; // already resolved since it was queued

      boolean touchesWater = false;
      for (int[] d : NEIGHBORS_6) {
        int nx = x + d[0], ny = y + d[1], nz = z + d[2];
        if (nx < 0 || nz < 0 || nx >= cols || nz >= rows || ny < 0 || ny > WATER_LEVEL) continue;
        byte b = get(nx, ny, nz);
        if (b == WATER) touchesWater = true;
        else if (b == AIR) q.add(voxelKey(nx, ny, nz));
      }
      if (touchesWater) {
        set(x, y, z, WATER);
        if (touchedCoarse == null) touchedCoarse = new HashSet<>();
        touchedCoarse.add((long) (z / FINE) * coarseCols + (x / FINE));
      }
    }
    return touchedCoarse == null ? java.util.Collections.emptySet() : touchedCoarse;
  }

  /** Repaints every fine sub-column's surface block under this coarse
   * cell - used by the terrain-paint tools (grass/sand/dirt/stone) so
   * they still visibly change the ground. Coarse (WorldGrid) x/z. */
  public void paintColumnSurface(int x, int z, byte type) {
    for (int dz = 0; dz < FINE; dz++) {
      for (int dx = 0; dx < FINE; dx++) {
        int fx = x * FINE + dx, fz = z * FINE + dz;
        set(fx, columnTopY(fx, fz), fz, type);
      }
    }
  }

  /** Turns a coarse cell into a small lake: clears anything above sea
   * level and fills down to it with water, across every fine sub-column.
   * Coarse (WorldGrid) x/z. */
  public void paintWaterColumn(int x, int z) {
    for (int dz = 0; dz < FINE; dz++) {
      for (int dx = 0; dx < FINE; dx++) {
        int fx = x * FINE + dx, fz = z * FINE + dz;
        int seabed = Math.max(0, Math.min(columnTopY(fx, fz), WATER_LEVEL - 1));
        for (int y = 0; y <= seabed; y++) {
          if (get(fx, y, fz) == AIR) set(fx, y, fz, y >= seabed - 1 ? SAND : STONE);
        }
        for (int y = seabed + 1; y <= WATER_LEVEL; y++) set(fx, y, fz, WATER);
        for (int y = WATER_LEVEL + 1; y < MAX_Y; y++) {
          if (get(fx, y, fz) != AIR) set(fx, y, fz, AIR);
        }
      }
    }
  }

  /** Turns a water coarse cell back into solid land up to sea level -
   * used when a terrain-paint tool draws over what used to be water.
   * Coarse (WorldGrid) x/z. */
  public void fillColumnSolid(int x, int z, byte surfaceType) {
    for (int dz = 0; dz < FINE; dz++) {
      for (int dx = 0; dx < FINE; dx++) {
        int fx = x * FINE + dx, fz = z * FINE + dz;
        int top = WATER_LEVEL;
        for (int y = 0; y <= top; y++) set(fx, y, fz, y == top ? surfaceType : (y >= top - 2 ? DIRT : STONE));
        for (int y = top + 1; y < MAX_Y; y++) {
          if (get(fx, y, fz) != AIR) set(fx, y, fz, AIR);
        }
      }
    }
  }
}
