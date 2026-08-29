package com.worldbox.render;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A building's real block-by-block layout - literally a list of unit
 * cubes, not a scaled/merged smooth box. A hollow rectangular wall ring
 * (with a door gap and a couple of window gaps) topped by a real peaked
 * (gable) roof - not a flat slab, which reads as a plain cube rather than
 * a house - plus optional corner towers for grander buildings. Generated
 * from a handful of dimensions in code rather than hand-typed layer-by-
 * layer ASCII art, so an odd building size can't silently end up with a
 * mismatched row/column count the way transcribed art risks.
 *
 * Cells are kept in construction order (bottom row first, walls before
 * roof) so a building's live construction progress can slice off a
 * prefix of the list and get a sensible "still rising" partial structure
 * - see buildStageMesh, and Building.progress/integrity in the sim layer. */
public class Blueprint {
  public enum Block { WALL, ROOF }

  private static final class Cell {
    final int x, y, z;
    final Block type;
    Cell(int x, int y, int z, Block type) { this.x = x; this.y = y; this.z = z; this.type = type; }
  }

  public final int width, depth, height;
  private final List<Cell> cells;

  private Blueprint(List<Cell> cells, int width, int depth, int height) {
    this.cells = cells;
    this.width = width;
    this.depth = depth;
    this.height = height;
  }

  /**
   * @param width, depth   footprint, in blocks (the roof's ridge runs the
   *                       length of `depth`, so the peak reads correctly
   *                       from any of the 4 spiral-placement rotations)
   * @param wallHeight     how many block-layers tall the walls are
   * @param corners        a single tower block poking up at each of the 4
   *                       corners, above the roof peak - a civic/grand cue
   */
  public static Blueprint building(int width, int depth, int wallHeight, boolean corners) {
    List<Cell> cells = new ArrayList<>();
    int doorX = width / 2;
    // a real house needs to actually look like one at a glance - windows
    // (small gaps in the two side walls, not the door wall) break up an
    // otherwise solid brick/plank box the same way a door does
    boolean windows = wallHeight >= 2 && depth >= 3;
    int windowY = wallHeight - 1, windowZ = depth / 2;
    for (int y = 0; y < wallHeight; y++) {
      for (int z = 0; z < depth; z++) {
        for (int x = 0; x < width; x++) {
          boolean perimeter = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
          if (!perimeter) continue;
          if (y == 0 && z == depth - 1 && x == doorX) continue; // the front door
          if (windows && y == windowY && z == windowZ && (x == 0 || x == width - 1)) continue;
          cells.add(new Cell(x, y, z, Block.WALL));
        }
      }
    }
    // a real peaked (gable) roof, not a flat slab - each layer steps in a
    // block from both sides while still running the FULL depth, giving an
    // actual triangular ridge profile the length of the building instead
    // of a flat-topped box. This is the single biggest "this reads as a
    // house, not a cube" cue a flat roof was missing.
    int gableLayers = (width + 1) / 2;
    for (int layer = 0; layer < gableLayers; layer++) {
      int y = wallHeight + layer;
      int x0 = layer, x1 = width - 1 - layer;
      if (x0 > x1) break;
      for (int z = 0; z < depth; z++) for (int x = x0; x <= x1; x++) cells.add(new Cell(x, y, z, Block.ROOF));
    }
    if (corners) {
      int y = wallHeight + gableLayers;
      for (int cx : new int[]{0, width - 1}) for (int cz : new int[]{0, depth - 1}) cells.add(new Cell(cx, y, cz, Block.WALL));
    }
    int height = wallHeight + gableLayers + (corners ? 1 : 0);
    return new Blueprint(cells, width, depth, height);
  }

  public int totalCells() { return cells.size(); }

  private static long key(int x, int y, int z) { return ((long) (x + 32) << 20) | ((long) (y + 32) << 10) | (z + 32); }

  /** A merged mesh of every visible face among the first `upTo` cells (in
   * construction order) of the given block type - only faces touching air
   * or the world outside the blueprint, so a sealed interior costs
   * nothing. Centered on its own footprint in x/z, floor at local y=0 -
   * the same ground-anchored convention every other prop template in this
   * renderer already uses, so placement math elsewhere needs no changes. */
  public Mesh buildStageMesh(int upTo, Block type) {
    upTo = Math.min(upTo, cells.size());
    Set<Long> present = new HashSet<>();
    for (int i = 0; i < upTo; i++) { Cell c = cells.get(i); present.add(key(c.x, c.y, c.z)); }

    float ox = width / 2f, oz = depth / 2f;
    List<Float> pos = new ArrayList<>();
    List<Float> norm = new ArrayList<>();
    List<Float> uv = new ArrayList<>();
    List<Integer> idx = new ArrayList<>();

    for (int i = 0; i < upTo; i++) {
      Cell c = cells.get(i);
      if (c.type != type) continue;
      float x = c.x - ox, y = c.y, z = c.z - oz;
      if (!present.contains(key(c.x, c.y + 1, c.z))) face(pos, norm, uv, idx, x, y, z, 0, 1, 0);
      if (!present.contains(key(c.x, c.y - 1, c.z))) face(pos, norm, uv, idx, x, y, z, 0, -1, 0);
      if (!present.contains(key(c.x + 1, c.y, c.z))) face(pos, norm, uv, idx, x, y, z, 1, 0, 0);
      if (!present.contains(key(c.x - 1, c.y, c.z))) face(pos, norm, uv, idx, x, y, z, -1, 0, 0);
      if (!present.contains(key(c.x, c.y, c.z + 1))) face(pos, norm, uv, idx, x, y, z, 0, 0, 1);
      if (!present.contains(key(c.x, c.y, c.z - 1))) face(pos, norm, uv, idx, x, y, z, 0, 0, -1);
    }

    Mesh m = new Mesh();
    if (idx.isEmpty()) {
      // an empty stage (e.g. no roof cells yet at a low construction
      // stage) still needs a valid, boundable mesh - a single degenerate
      // triangle does the job without ever actually drawing anything
      m.setBuffer(VertexBuffer.Type.Position, 3, new float[]{0, 0, 0});
      m.setBuffer(VertexBuffer.Type.Normal, 3, new float[]{0, 1, 0});
      m.setBuffer(VertexBuffer.Type.TexCoord, 2, new float[]{0, 0});
      m.setBuffer(VertexBuffer.Type.Index, 3, new int[]{0, 0, 0});
      m.updateBound();
      return m;
    }
    float[] p = new float[pos.size()];
    for (int i = 0; i < p.length; i++) p[i] = pos.get(i);
    float[] n = new float[norm.size()];
    for (int i = 0; i < n.length; i++) n[i] = norm.get(i);
    float[] t = new float[uv.size()];
    for (int i = 0; i < t.length; i++) t[i] = uv.get(i);
    int[] ix = new int[idx.size()];
    for (int i = 0; i < ix.length; i++) ix[i] = idx.get(i);
    m.setBuffer(VertexBuffer.Type.Position, 3, p);
    m.setBuffer(VertexBuffer.Type.Normal, 3, n);
    m.setBuffer(VertexBuffer.Type.TexCoord, 2, t);
    m.setBuffer(VertexBuffer.Type.Index, 3, ix);
    m.updateBound();
    return m;
  }

  /** One unit-cube face, one block wide/tall, full 0..1 UV - a blueprint's
   * wall/roof texture is its own standalone image (see TerrainTextures),
   * not an atlas slice, so no UV offset math is needed here. */
  private static void face(List<Float> pos, List<Float> norm, List<Float> uv, List<Integer> idx,
      float x, float y, float z, int nx, int ny, int nz) {
    float[][] v;
    if (ny == 1) v = new float[][]{{x, y + 1, z}, {x, y + 1, z + 1}, {x + 1, y + 1, z + 1}, {x + 1, y + 1, z}};
    else if (ny == -1) v = new float[][]{{x, y, z + 1}, {x, y, z}, {x + 1, y, z}, {x + 1, y, z + 1}};
    else if (nz == -1) v = new float[][]{{x + 1, y, z}, {x, y, z}, {x, y + 1, z}, {x + 1, y + 1, z}};
    else if (nz == 1) v = new float[][]{{x, y, z + 1}, {x + 1, y, z + 1}, {x + 1, y + 1, z + 1}, {x, y + 1, z + 1}};
    else if (nx == 1) v = new float[][]{{x + 1, y, z + 1}, {x + 1, y, z}, {x + 1, y + 1, z}, {x + 1, y + 1, z + 1}};
    else v = new float[][]{{x, y, z}, {x, y, z + 1}, {x, y + 1, z + 1}, {x, y + 1, z}};

    int base = pos.size() / 3;
    for (float[] p : v) { pos.add(p[0]); pos.add(p[1]); pos.add(p[2]); }
    for (int i = 0; i < 4; i++) { norm.add((float) nx); norm.add((float) ny); norm.add((float) nz); }
    uv.add(0f); uv.add(0f);
    uv.add(1f); uv.add(0f);
    uv.add(1f); uv.add(1f);
    uv.add(0f); uv.add(1f);
    idx.add(base); idx.add(base + 1); idx.add(base + 2);
    idx.add(base); idx.add(base + 2); idx.add(base + 3);
  }
}
