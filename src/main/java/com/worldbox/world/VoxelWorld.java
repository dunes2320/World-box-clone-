package com.worldbox.world;

import com.worldbox.config.Config;

import java.util.LinkedHashSet;
import java.util.Set;

/** A real 3D block grid, generated from the existing 2D heightmap/terrain
 * data and independently destructible - meteors, digging, earthquakes etc.
 * carve actual blocks out rather than just editing a height number.
 *
 * The 2D WorldGrid stays the authoritative layer for simulation (pathing,
 * settlement placement, resource yields, territory); after every voxel
 * edit the affected column's WorldGrid.height is resynced from the
 * topmost solid block (see Simulation/GameApp), so every existing
 * gameplay system keeps working unchanged against a slightly blockier
 * surface. */
public class VoxelWorld {
  public static final byte AIR = 0;
  public static final byte GRASS = 1;
  public static final byte DIRT = 2;
  public static final byte SAND = 3;
  public static final byte STONE = 4;
  public static final byte WATER = 5;

  /** World-space height 0 sits at this block layer, so negative terrain
   * heights (seabeds, valleys) still have a valid non-negative index. */
  public static final int Y_OFFSET = 9;
  public static final int MAX_Y = 30;
  public static final int WATER_LEVEL = Y_OFFSET - 1;
  public static final int CHUNK_SIZE = 16;

  public final int cols, rows;
  public final int chunksX, chunksZ;
  private final byte[] blocks;
  public final Set<Integer> dirtyChunks = new LinkedHashSet<>();

  public VoxelWorld(WorldGrid grid) {
    this.cols = grid.cols;
    this.rows = grid.rows;
    this.chunksX = (cols + CHUNK_SIZE - 1) / CHUNK_SIZE;
    this.chunksZ = (rows + CHUNK_SIZE - 1) / CHUNK_SIZE;
    this.blocks = new byte[cols * rows * MAX_Y];
    generate(grid);
  }

  private int index(int x, int y, int z) { return (z * cols + x) * MAX_Y + y; }

  public byte get(int x, int y, int z) {
    if (x < 0 || z < 0 || x >= cols || z >= rows || y < 0 || y >= MAX_Y) return AIR;
    return blocks[index(x, y, z)];
  }

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

  /** Topmost solid (non-air, non-water) block in a column, or 0 if none. */
  public int columnTopY(int x, int z) {
    for (int y = MAX_Y - 1; y >= 0; y--) {
      byte b = get(x, y, z);
      if (b != AIR && b != WATER) return y;
    }
    return 0;
  }

  /** World-space Y of the top face of a column's surface - what the old
   * smooth WorldGrid.height used to mean, now quantized to whole blocks. */
  public float heightWorld(int x, int z) {
    return columnTopY(x, z) + 1 - Y_OFFSET;
  }

  private void generate(WorldGrid grid) {
    for (int z = 0; z < rows; z++) {
      for (int x = 0; x < cols; x++) {
        int i = grid.idx(x, z);
        byte terrain = grid.terrain[i];
        int h = Math.round(grid.height[i]) + Y_OFFSET;
        h = Math.max(1, Math.min(MAX_Y - 2, h));
        if (terrain == Config.WATER) {
          int seabed = Math.min(h, WATER_LEVEL - 1);
          for (int y = 0; y <= seabed; y++) setRaw(x, y, z, y >= seabed - 1 ? SAND : STONE);
          for (int y = seabed + 1; y <= WATER_LEVEL; y++) setRaw(x, y, z, WATER);
        } else {
          byte surfaceBlock = blockForTerrain(terrain);
          for (int y = 0; y < h; y++) {
            byte b = y >= h - 1 ? surfaceBlock : (y >= h - 3 ? DIRT : STONE);
            setRaw(x, y, z, b);
          }
        }
      }
    }
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
   * working against the new blocky surface. */
  public void resyncHeight(WorldGrid grid, int x, int z) {
    grid.height[grid.idx(x, z)] = heightWorld(x, z);
  }

  /** Removes (sets to air) every non-water block within `radius` blocks of
   * a world-space point - meteors, nukes, earthquakes, the dig tool. */
  public void carveSphere(double wx, double wyWorld, double wz, double radius, Set<Long> touchedColumns) {
    int cxBlock = (int) Math.round(wx);
    int czBlock = (int) Math.round(wz);
    int cyBlock = (int) Math.round(wyWorld) + Y_OFFSET;
    int r = (int) Math.ceil(radius);
    for (int dz = -r; dz <= r; dz++) {
      int z = czBlock + dz;
      if (z < 0 || z >= rows) continue;
      for (int dx = -r; dx <= r; dx++) {
        int x = cxBlock + dx;
        if (x < 0 || x >= cols) continue;
        double horizD = Math.hypot(dx, dz);
        if (horizD > radius) continue;
        boolean touched = false;
        for (int dy = -r; dy <= r; dy++) {
          int y = cyBlock + dy;
          if (y < 0 || y >= MAX_Y) continue;
          double d = Math.sqrt(horizD * horizD + dy * dy);
          if (d > radius) continue;
          if (get(x, y, z) == WATER || get(x, y, z) == AIR) continue;
          set(x, y, z, AIR);
          touched = true;
        }
        if (touched && touchedColumns != null) touchedColumns.add((long) z * cols + x);
      }
    }
  }

  /** Digs straight down one block from the current surface of a column. */
  public byte digColumn(int x, int z) {
    int top = columnTopY(x, z);
    if (top <= 1) return AIR;
    byte removed = get(x, top, z);
    set(x, top, z, AIR);
    return removed;
  }

  /** Places one block on top of the current surface. */
  public void buildColumn(int x, int z, byte type) {
    int top = columnTopY(x, z);
    if (top + 1 >= MAX_Y) return;
    set(x, top + 1, z, type);
  }

  /** Repaints a column's surface block - used by the terrain-paint tools
   * (grass/sand/dirt/stone) so they still visibly change the ground. */
  public void paintColumnSurface(int x, int z, byte type) {
    int top = columnTopY(x, z);
    set(x, top, z, type);
  }

  /** Turns a column into a small lake: clears anything above sea level and
   * fills down to it with water. */
  public void paintWaterColumn(int x, int z) {
    int seabed = Math.max(0, Math.min(columnTopY(x, z), WATER_LEVEL - 1));
    for (int y = 0; y <= seabed; y++) {
      if (get(x, y, z) == AIR) set(x, y, z, y >= seabed - 1 ? SAND : STONE);
    }
    for (int y = seabed + 1; y <= WATER_LEVEL; y++) set(x, y, z, WATER);
    for (int y = WATER_LEVEL + 1; y < MAX_Y; y++) {
      if (get(x, y, z) != AIR) set(x, y, z, AIR);
    }
  }

  /** Turns a water column back into solid land up to sea level - used when
   * a terrain-paint tool draws over what used to be water. */
  public void fillColumnSolid(int x, int z, byte surfaceType) {
    int top = WATER_LEVEL;
    for (int y = 0; y <= top; y++) set(x, y, z, y == top ? surfaceType : (y >= top - 2 ? DIRT : STONE));
    for (int y = top + 1; y < MAX_Y; y++) {
      if (get(x, y, z) != AIR) set(x, y, z, AIR);
    }
  }
}
