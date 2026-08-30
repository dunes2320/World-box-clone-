package com.worldbox.render;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A building's real block-by-block layout - literally a list of unit
 * cubes, not a scaled/merged smooth box. Modeled on how real Minecraft
 * survival houses are actually built (see the class-level research this
 * was based on): a cobblestone foundation course a block wider than the
 * walls, a hollow wall ring (door + window gaps, with a log/quartz-style
 * TRIM accent at the corners and framing the openings) topped by a real
 * peaked (gable) roof with a proper overhang - not a flat slab flush with
 * the walls, which reads as a plain cube rather than a house - plus
 * optional corner towers for grander buildings. Generated from a handful
 * of dimensions in code rather than
 * hand-typed layer-by-layer ASCII art, so an odd building size can't
 * silently end up with a mismatched row/column count the way transcribed
 * art risks.
 *
 * Cells are kept in construction order (foundation first, then walls,
 * then roof) so a building's live construction progress can slice off a
 * prefix of the list and get a sensible "still rising" partial structure
 * - see buildStageMesh, and Building.progress/integrity in the sim layer. */
public class Blueprint {
  /** Each is rendered as its own separate mesh with its own material (see
   * EntityRenderer) - FOUNDATION is the cobblestone plinth, TRIM is the
   * corner posts and window/door framing (log on a house, quartz on a
   * civic building), DOOR is a real paneled door block filling the
   * doorway, and WINDOW is real (transparent) glass filling a window
   * opening - not bare gaps either one used to be. */
  public enum Block { WALL, ROOF, FOUNDATION, TRIM, DOOR, WINDOW }

  /** World-unit size of one block cube - matches VoxelWorld.FINE's terrain
   * block size (1/FINE) so a building's own blocks read as the same
   * "small block" unit as the ground it stands on, instead of looking
   * like it's built from chunkier Lego next to finer terrain. Callers
   * double their width/depth/wallHeight arguments (see EntityRenderer)
   * to keep a building's overall physical footprint the same as before -
   * more, smaller blocks, not a smaller building. */
  public static final float SCALE = 1f / com.worldbox.world.VoxelWorld.FINE;

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
  /** Evenly spaced interior positions along a wall of this length, never
   * right in a corner (that's where TRIM's own corner post already goes),
   * one in the middle plus more every 3 cells outward as the wall gets
   * long enough to fit them - a short wall gets exactly one window, a long
   * one gets several real repeating windows instead of a single opening
   * lost in a lot of blank wall. */
  private static Set<Integer> windowPositions(int length) {
    Set<Integer> pos = new java.util.LinkedHashSet<>();
    int lo = 1, hi = length - 2;
    if (lo > hi) return pos;
    int mid = (lo + hi) / 2;
    pos.add(mid);
    for (int d = 3; mid - d >= lo; d += 3) pos.add(mid - d);
    for (int d = 3; mid + d <= hi; d += 3) pos.add(mid + d);
    return pos;
  }

  /** Same as windowPositions, but drops anything within excludeRadius of
   * excludeCenter - used for the door wall so a window never lands on top
   * of (or immediately beside) the doorway itself. */
  private static Set<Integer> windowPositionsExcluding(int length, int excludeCenter, int excludeRadius) {
    Set<Integer> pos = windowPositions(length);
    pos.removeIf(x -> Math.abs(x - excludeCenter) <= excludeRadius);
    return pos;
  }

  public static Blueprint building(int width, int depth, int wallHeight, boolean corners) {
    List<Cell> cells = new ArrayList<>();
    int doorX = width / 2;

    // a cobblestone plinth a block wider than the walls on every side -
    // every real-build tutorial this was modeled on starts the same way
    // (lay the foundation out past the wall line, then build the walls on
    // top of it), and it's what stops the building from reading as walls
    // simply starting flush out of the bare ground
    for (int z = -1; z <= depth; z++) {
      for (int x = -1; x <= width; x++) cells.add(new Cell(x, 0, z, Block.FOUNDATION));
    }

    // a real house needs to actually look like one at a glance, not one
    // lone window per side - real windows repeat along a wall's length,
    // and a genuinely two-story building (a tall wallHeight) has a
    // separate row for each floor, not one row jammed up under the eave.
    // windowPositions spaces them out along whichever wall they sit on,
    // symmetric and never right in a corner; door/back/front walls each
    // get their own set so the door wall's windows never land on top of
    // the doorway itself. Both openings are real blocks now (Block.DOOR/
    // Block.WINDOW), not bare gaps - see EntityRenderer for their own
    // dedicated door/glass textures and materials.
    boolean windows = wallHeight >= 2 && depth >= 3;
    Set<Integer> windowYs = new java.util.LinkedHashSet<>();
    if (windows) {
      windowYs.add(wallHeight);
      if (wallHeight >= 6) windowYs.add(Math.max(3, wallHeight / 2)); // a real second-floor row on taller builds
    }
    Set<Integer> sideWinZ = windows ? windowPositions(depth) : java.util.Collections.emptySet();
    Set<Integer> frontWinX = windows ? windowPositionsExcluding(width, doorX, 1) : java.util.Collections.emptySet();
    Set<Integer> backWinX = windows ? windowPositions(width) : java.util.Collections.emptySet();
    for (int y = 1; y <= wallHeight; y++) {
      for (int z = 0; z < depth; z++) {
        for (int x = 0; x < width; x++) {
          boolean perimeter = x == 0 || x == width - 1 || z == 0 || z == depth - 1;
          if (!perimeter) continue;
          boolean corner = (x == 0 || x == width - 1) && (z == 0 || z == depth - 1);
          boolean isDoor = y == 1 && z == depth - 1 && x == doorX;
          boolean isDoorFrame = z == depth - 1 && !corner && (x == doorX - 1 || x == doorX + 1);
          boolean isSideWindow = windowYs.contains(y) && (x == 0 || x == width - 1) && sideWinZ.contains(z);
          boolean isSideWindowFrame = windowYs.contains(y + 1) && (x == 0 || x == width - 1) && sideWinZ.contains(z);
          boolean isFrontWindow = windowYs.contains(y) && z == depth - 1 && frontWinX.contains(x);
          boolean isFrontWindowFrame = windowYs.contains(y + 1) && z == depth - 1 && frontWinX.contains(x);
          boolean isBackWindow = windowYs.contains(y) && z == 0 && backWinX.contains(x);
          boolean isBackWindowFrame = windowYs.contains(y + 1) && z == 0 && backWinX.contains(x);
          boolean isWindow = isSideWindow || isFrontWindow || isBackWindow;
          boolean isWindowFrame = isSideWindowFrame || isFrontWindowFrame || isBackWindowFrame;
          if (isDoor) { cells.add(new Cell(x, y, z, Block.DOOR)); continue; }
          if (isWindow) { cells.add(new Cell(x, y, z, Block.WINDOW)); continue; }
          // corner posts (like real log corner framing) and the trim
          // bordering each opening read as a deliberate frame rather than
          // one flat wall material everywhere
          cells.add(new Cell(x, y, z, corner || isDoorFrame || isWindowFrame ? Block.TRIM : Block.WALL));
        }
      }
    }

    // a real peaked (gable) roof with a proper overhang - the eave (the
    // very first/lowest layer) sticks out a full block past the wall line
    // on every side, then each layer above steps in from the two long
    // sides while still running the FULL depth (plus its own 1-block
    // overhang past the gable ends), giving an actual triangular ridge
    // profile the length of the building. Roof starting flush with the
    // wall face (no overhang at all) was the single biggest remaining
    // "this reads as a plain box" cue once the walls themselves got trim.
    int roofBaseY = wallHeight + 1;
    int gableLayers = (width + 1) / 2 + 1;
    int lastRoofLayer = 0;
    for (int layer = 0; layer < gableLayers; layer++) {
      int inset = layer - 1; // -1 on the eave layer, 0, 1, 2... tapering up to the ridge
      int x0 = inset, x1 = width - 1 - inset;
      if (x0 > x1) break;
      int y = roofBaseY + layer;
      lastRoofLayer = layer;
      for (int z = -1; z <= depth; z++) for (int x = x0; x <= x1; x++) cells.add(new Cell(x, y, z, Block.ROOF));
    }

    if (corners) {
      int y = roofBaseY + lastRoofLayer + 1;
      for (int cx : new int[]{0, width - 1}) for (int cz : new int[]{0, depth - 1}) cells.add(new Cell(cx, y, cz, Block.TRIM));
    }

    // no chimney - every version of one (floating clear of the house,
    // then flush against the wall) still read as a small stray block
    // stuck onto the building rather than a deliberate feature, so it's
    // gone entirely rather than re-tuned again.

    int height = roofBaseY + lastRoofLayer + 1 + (corners ? 1 : 0);
    return new Blueprint(cells, width, depth, height);
  }

  public int totalCells() { return cells.size(); }

  /** Real world-space half-extent of this blueprint's FULL footprint -
   * every cell it actually contains, not just width/depth/2 - measured
   * from its own center out to the furthest edge on either horizontal
   * axis. Corner towers on grander buildings sit well outside the plain
   * wall footprint, so a caller that only terraforms a pad sized off
   * width/depth leaves them standing on whatever unleveled ground
   * happened to already be there. +SCALE at the end accounts for a
   * cell's own width past its near corner, not just the corner itself. */
  public float footprintHalfExtent() {
    float ox = width / 2f, oz = depth / 2f;
    float maxR = 0;
    for (Cell c : cells) {
      maxR = Math.max(maxR, Math.abs(c.x - ox));
      maxR = Math.max(maxR, Math.abs(c.z - oz));
    }
    return maxR * SCALE + SCALE;
  }

  /** Every distinct (x, z) column, as a world-space offset from this
   * blueprint's own center, that any of its cells actually occupies - the
   * foundation slab, each a real spot on the ground something is actually
   * built on. Lets a caller (see Settlement.terraformFootprint) flatten
   * ground that hugs the building's true silhouette instead of
   * footprintHalfExtent's single circle sized off whichever one point
   * sits furthest from center - a circle that size wastefully flattens a
   * lot of empty ground the building doesn't actually stand on, which is
   * what was reading as an oversized, unnaturally flat "chunk taken out
   * of the land" around every house. */
  public java.util.List<float[]> footprintColumns() {
    float ox = width / 2f, oz = depth / 2f;
    Set<Long> seen = new HashSet<>();
    List<float[]> out = new ArrayList<>();
    for (Cell c : cells) {
      long k = key(c.x, 0, c.z);
      if (!seen.add(k)) continue;
      out.add(new float[]{(c.x - ox) * SCALE, (c.z - oz) * SCALE});
    }
    return out;
  }

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
      float x = (c.x - ox) * SCALE, y = c.y * SCALE, z = (c.z - oz) * SCALE;
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

  /** One cube face, SCALE wide/tall (x/y/z already came in pre-scaled from
   * buildStageMesh), full 0..1 UV - a blueprint's wall/roof texture is its
   * own standalone image (see TerrainTextures), not an atlas slice, so no
   * UV offset math is needed here. */
  private static void face(List<Float> pos, List<Float> norm, List<Float> uv, List<Integer> idx,
      float x, float y, float z, int nx, int ny, int nz) {
    float s = SCALE;
    float[][] v;
    if (ny == 1) v = new float[][]{{x, y + s, z}, {x, y + s, z + s}, {x + s, y + s, z + s}, {x + s, y + s, z}};
    else if (ny == -1) v = new float[][]{{x, y, z + s}, {x, y, z}, {x + s, y, z}, {x + s, y, z + s}};
    else if (nz == -1) v = new float[][]{{x + s, y, z}, {x, y, z}, {x, y + s, z}, {x + s, y + s, z}};
    else if (nz == 1) v = new float[][]{{x, y, z + s}, {x + s, y, z + s}, {x + s, y + s, z + s}, {x, y + s, z + s}};
    else if (nx == 1) v = new float[][]{{x + s, y, z + s}, {x + s, y, z}, {x + s, y + s, z}, {x + s, y + s, z + s}};
    else v = new float[][]{{x, y, z}, {x, y, z + s}, {x, y + s, z + s}, {x, y + s, z}};

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
