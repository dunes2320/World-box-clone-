package com.worldbox.render;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Texture2D;
import com.worldbox.config.Config;
import com.worldbox.world.VoxelWorld;
import com.worldbox.world.WorldGrid;

import java.util.HashMap;
import java.util.Map;

/** Renders a VoxelWorld as one low-poly mesh per chunk, only emitting faces
 * that touch air (or, for water, air/land) so fully-buried blocks cost
 * nothing. Each face is tinted by block type and darkened a bit by facing
 * direction - a cheap fake-shading trick so the blocky world reads with
 * some depth without needing real lighting. */
public class VoxelChunkRenderer {
  // Bright, saturated cartoon palette - was a much muted, earthy set of
  // tones (part of the earlier "realistic shader pack" look); pushed
  // toward punchier, more distinct hues so each terrain type reads as a
  // clear, vivid color rather than a desaturated approximation of a real
  // material.
  private static final Map<Byte, ColorRGBA> BLOCK_COLOR = new HashMap<>();
  static {
    BLOCK_COLOR.put(VoxelWorld.GRASS, new ColorRGBA(0.373f, 0.796f, 0.290f, 1f));
    BLOCK_COLOR.put(VoxelWorld.DIRT, new ColorRGBA(0.612f, 0.416f, 0.243f, 1f));
    BLOCK_COLOR.put(VoxelWorld.SAND, new ColorRGBA(0.949f, 0.851f, 0.541f, 1f));
    BLOCK_COLOR.put(VoxelWorld.STONE, new ColorRGBA(0.624f, 0.651f, 0.690f, 1f));
    // a built path/road cell - a warm gravel tone (was the old ROAD_TINT
    // color-wash applied over whatever terrain happened to be there) on
    // the same fractured-rock stone tile, so a real path visibly reads
    // as "laid gravel", not just grass painted a different color
    BLOCK_COLOR.put(VoxelWorld.PATH, new ColorRGBA(0.78f, 0.72f, 0.60f, 1f));
  }
  // grass's base color, replaced per-biome so plains/forest/desert/tundra/
  // wetland each read as a real, distinct region instead of one uniform
  // green everywhere - mountain/ocean biomes need no entry since their
  // terrain is already STONE/WATER (colored above), never GRASS.
  private static final Map<Byte, ColorRGBA> BIOME_GRASS_COLOR = new HashMap<>();
  static {
    BIOME_GRASS_COLOR.put(Config.BIOME_PLAINS, BLOCK_COLOR.get(VoxelWorld.GRASS));
    BIOME_GRASS_COLOR.put(Config.BIOME_FOREST, new ColorRGBA(0.204f, 0.635f, 0.267f, 1f));
    BIOME_GRASS_COLOR.put(Config.BIOME_DESERT, new ColorRGBA(0.867f, 0.741f, 0.376f, 1f));
    BIOME_GRASS_COLOR.put(Config.BIOME_TUNDRA, new ColorRGBA(0.831f, 0.878f, 0.851f, 1f));
    BIOME_GRASS_COLOR.put(Config.BIOME_WETLAND, new ColorRGBA(0.298f, 0.541f, 0.322f, 1f));
  }
  private static final ColorRGBA WATER_COLOR = new ColorRGBA(0.180f, 0.612f, 0.839f, 0.85f);
  private static final ColorRGBA FIRE_TINT = new ColorRGBA(1f, 0.48f, 0.1f, 1f);
  private static final ColorRGBA FARMLAND_TINT = new ColorRGBA(0.72f, 0.56f, 0.30f, 1f);
  private static final ColorRGBA ROAD_TINT = new ColorRGBA(0.78f, 0.72f, 0.60f, 1f);
  private static final ColorRGBA WAR_FLASH_COLOR = new ColorRGBA(0.95f, 0.1f, 0.08f, 1f);
  // purely a rendering-layer touch so the mountain spine reads as a real
  // peak instead of the same flat grey stone all the way up - no new block
  // type or terrain byte involved, just a height-based tint on stone tops.
  private static final ColorRGBA SNOW_COLOR = new ColorRGBA(0.95f, 0.96f, 0.98f, 1f);
  // measured actual terrain heights only reach ~7-10 above sea level at
  // their tallest, so the snow line has to sit well below that or it
  // would never render on any peak
  private static final int SNOW_LINE = VoxelWorld.Y_OFFSET + 6 * VoxelWorld.FINE;

  // Gentler now that real dynamic sun lighting also shades faces by
  // direction - this only needs to add a light baked-AO hint underneath.
  private static final float SHADE_TOP = 1.0f;
  private static final float SHADE_BOTTOM = 0.8f;
  private static final float SHADE_NS = 0.94f;
  private static final float SHADE_EW = 0.88f;

  private VoxelWorld world;
  private WorldGrid grid;
  private final NationColorLookup nationColor;
  public final Node solidNode = new Node("voxelSolid");
  public final Node waterNode = new Node("voxelWater");
  /** Which nation(s) to flash red on the map right now (the nations the
   * currently-hovered one is at war with - see GameApp's hover pick) and
   * whether this blink cycle is currently "on". Empty set = no flash. */
  private java.util.Set<Integer> warFlashNations = java.util.Collections.emptySet();
  private boolean warFlashOn = false;
  private float warFlashTimer = 0f;
  private static final float WAR_FLASH_INTERVAL = 0.35f;
  private final Geometry[] solidChunks;
  private final Geometry[] waterChunks;

  public VoxelChunkRenderer(VoxelWorld world, WorldGrid grid, AssetManager assets, NationColorLookup nationColor) {
    this.world = world;
    this.grid = grid;
    this.nationColor = nationColor;
    int n = world.chunksX * world.chunksZ;
    solidChunks = new Geometry[n];
    waterChunks = new Geometry[n];

    // Specular was pinned to black everywhere (pure diffuse+ambient, no
    // highlight at all) - technically "lit" but with nothing that visibly
    // responds to view angle, which reads as flat/painted-on rather than
    // actually lit. A soft sheen on terrain and a real glint on water
    // give the sun something to visibly bounce off of.
    // a small, classic-resolution atlas (grass/dirt/stone/sand/water),
    // painted procedurally at startup - see TerrainTextures - instead of
    // the old flat per-block vertex color. DiffuseMap multiplies with
    // UseVertexColor in Lighting.j3md, so every existing tint (territory
    // color, farmland, roads, fire, snow, foam - see topColor below) still
    // works exactly as before, just modulating a textured base now
    // instead of a flat one.
    Texture2D atlas = TerrainTextures.buildAtlas();

    Material solidMat = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
    solidMat.setBoolean("UseVertexColor", true);
    solidMat.setTexture("DiffuseMap", atlas);
    solidMat.setColor("Specular", new ColorRGBA(0.16f, 0.16f, 0.15f, 1f));
    solidMat.setFloat("Shininess", 10f);

    Material waterMat = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
    waterMat.setBoolean("UseVertexColor", true);
    waterMat.setTexture("DiffuseMap", atlas);
    // was 0.85-0.93 (near-white) at Shininess 80 - a tight, bright
    // highlight plus a still, unrippled surface (see the wave-animation
    // removal above) read as a sheet of glass/a mirror rather than water.
    // Dimmer, less saturated specular and a much lower shininess spread
    // the same sun-glint out into a broad, soft sheen instead of a hard
    // point highlight, which is what actually reads as "water" at a
    // glance rather than "polished floor".
    waterMat.setColor("Specular", new ColorRGBA(0.45f, 0.5f, 0.55f, 1f));
    waterMat.setFloat("Shininess", 18f);
    waterMat.setTransparent(true);
    waterMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
    waterMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
    // a real (if cheap - see TerrainTextures.buildSkyCubeMap) sky
    // reflection instead of water just being lit with no reflection at
    // all - but a subtle glossy sheen, not a mirror. 0.85 scale at power 2
    // washed the whole surface white; 0.22 at power 4.5 still read as a
    // clear mirrored sky image rather than a soft gloss; 0.09 at 5.5 was
    // still legible enough as an actual sky reflection (combined with the
    // brighter specular above) to read as a mirror rather than water.
    // Cut once more so it only shows as a faint brightening/sky tint at
    // real grazing angles - the same way a glossy (not mirror-finish)
    // surface behaves - rather than anything resembling a reflected image.
    waterMat.setTexture("EnvMap", TerrainTextures.buildSkyCubeMap());
    waterMat.setVector3("FresnelParams", new com.jme3.math.Vector3f(0.01f, 0.045f, 5.5f));

    for (int ci = 0; ci < n; ci++) {
      Geometry sg = new Geometry("chunk_solid_" + ci, new Mesh());
      sg.setMaterial(solidMat);
      sg.setQueueBucket(RenderQueue.Bucket.Opaque);
      solidNode.attachChild(sg);
      solidChunks[ci] = sg;

      Geometry wg = new Geometry("chunk_water_" + ci, new Mesh());
      wg.setMaterial(waterMat);
      wg.setQueueBucket(RenderQueue.Bucket.Transparent);
      waterNode.attachChild(wg);
      waterChunks[ci] = wg;

      world.dirtyChunks.add(ci);
    }
  }

  /** Rebinds to a freshly generated world (e.g. after "Reset World") and
   * re-meshes every chunk. Assumes the world dimensions never change at
   * runtime, so the existing chunk geometry pool is reused as-is. */
  public void rebind(VoxelWorld newWorld, WorldGrid newGrid) {
    this.world = newWorld;
    this.grid = newGrid;
    rebuildAll();
  }

  public void flushDirty() {
    // fire/territory changes don't touch block data, so they only show up
    // in WorldGrid.dirty - fold those into the chunk-dirty set too.
    if (!grid.dirty.isEmpty()) {
      for (int i : grid.dirty) {
        // grid.dirty holds COARSE WorldGrid indices - each one covers a
        // FINE x FINE patch of real fine columns (see VoxelWorld's class
        // comment), which - since CHUNK_SIZE is a multiple of FINE - can
        // only ever fall in one or two chunks, so mark both corners.
        int gx = i % grid.cols, gz = i / grid.cols;
        int fx0 = gx * VoxelWorld.FINE, fz0 = gz * VoxelWorld.FINE;
        int fx1 = fx0 + VoxelWorld.FINE - 1, fz1 = fz0 + VoxelWorld.FINE - 1;
        world.dirtyChunks.add((fz0 / VoxelWorld.CHUNK_SIZE) * world.chunksX + (fx0 / VoxelWorld.CHUNK_SIZE));
        world.dirtyChunks.add((fz1 / VoxelWorld.CHUNK_SIZE) * world.chunksX + (fx1 / VoxelWorld.CHUNK_SIZE));
      }
      grid.dirty.clear();
    }
    if (world.dirtyChunks.isEmpty()) return;
    // rebuilding a chunk got noticeably heavier once each one covers
    // FINE x FINE as many real blocks (see VoxelWorld's class comment) -
    // a single frame that happens to dirty a big batch of chunks at once
    // (a wide dig-tool stroke, a territory shift after a battle, a whole
    // settlement's worth of new construction) used to rebuild every one
    // of them synchronously in that one frame, reading as a stutter/hang.
    // Capping how many rebuild per call spreads that same total work
    // across the next several frames instead - each one still gets
    // rebuilt (nothing is ever silently skipped, see the iterator below),
    // just not all in the same frame. Ordinary single-chunk edits (one
    // dig, one build) are already well under this cap, so normal play
    // resolves in the very next frame exactly as before.
    java.util.Iterator<Integer> it = world.dirtyChunks.iterator();
    int budget = MAX_CHUNK_REBUILDS_PER_FRAME;
    while (it.hasNext() && budget-- > 0) {
      rebuildChunk(it.next());
      it.remove();
    }
  }

  private static final int MAX_CHUNK_REBUILDS_PER_FRAME = 24;

  public void rebuildAll() {
    for (int ci = 0; ci < solidChunks.length; ci++) rebuildChunk(ci);
    world.dirtyChunks.clear();
  }

  /** Sets which nation(s) should flash red on the map (called whenever
   * the cursor moves onto a different nation's territory) - only touches
   * the map at all, via a targeted remesh, when the actual target set
   * changes, not every frame the mouse merely moves within it. */
  public void setWarFlashTargets(java.util.Set<Integer> nationIds) {
    if (nationIds.equals(warFlashNations)) return;
    java.util.Set<Integer> affected = new java.util.HashSet<>(warFlashNations);
    affected.addAll(nationIds);
    warFlashNations = nationIds;
    markNationsDirty(affected);
  }

  /** Advances the blink timer - only actually touches the map (a targeted
   * remesh of the flashing nations' cells) on the rare tick the blink
   * state flips, not continuously. */
  public void updateWarFlash(float tpf) {
    // clearing the flash entirely is already handled by setWarFlashTargets
    // (it remeshes whatever was flashing back to normal the moment the
    // target set goes empty) - this only needs to manage the actual blink
    // cadence while there's something to flash
    if (warFlashNations.isEmpty()) { warFlashOn = false; warFlashTimer = 0f; return; }
    warFlashTimer += tpf;
    if (warFlashTimer >= WAR_FLASH_INTERVAL) {
      warFlashTimer -= WAR_FLASH_INTERVAL;
      warFlashOn = !warFlashOn;
      markNationsDirty(warFlashNations);
    }
  }

  private void markNationsDirty(java.util.Set<Integer> nationIds) {
    if (nationIds.isEmpty()) return;
    for (int i = 0; i < grid.ownerNation.length; i++) {
      if (nationIds.contains(grid.ownerNation[i])) grid.markDirtyIdx(i);
    }
  }

  private void rebuildChunk(int ci) {
    int cx = ci % world.chunksX, cz = ci / world.chunksX;
    int x0 = cx * VoxelWorld.CHUNK_SIZE, z0 = cz * VoxelWorld.CHUNK_SIZE;
    int x1 = Math.min(world.cols, x0 + VoxelWorld.CHUNK_SIZE);
    int z1 = Math.min(world.rows, z0 + VoxelWorld.CHUNK_SIZE);

    MeshBuilder solid = new MeshBuilder();
    MeshBuilder water = new MeshBuilder();

    for (int z = z0; z < z1; z++) {
      for (int x = x0; x < x1; x++) {
        // most of a column's height is fully buried - solid on every
        // side, above, and below - and can never contribute a visible
        // face, which got a lot more expensive to keep scanning past
        // once every WorldGrid cell became FINE x FINE real columns (4x
        // the columns, each still walking the full MAX_Y range). A real
        // cliff's exposed side faces never reach deeper than the LOWEST
        // of this column's own top and its 4 orthogonal neighbors' tops,
        // so anything further down is guaranteed hidden on every side -
        // safe to start the scan there instead of from bedrock. Clamped
        // to never skip past the water line, since columnTopY treats
        // water as non-solid (a water column's "top" is its seabed), so
        // this can't accidentally cut a shoreline/underwater column off
        // before its own water blocks.
        int loTop = Math.min(world.columnTopY(x, z), Math.min(
            Math.min(world.columnTopY(x - 1, z), world.columnTopY(x + 1, z)),
            Math.min(world.columnTopY(x, z - 1), world.columnTopY(x, z + 1))));
        int scanFrom = Math.max(0, Math.min(loTop - 1, VoxelWorld.WATER_LEVEL - 2));
        for (int y = scanFrom; y < VoxelWorld.MAX_Y; y++) {
          byte b = world.get(x, y, z);
          if (b == VoxelWorld.AIR) continue;
          MeshBuilder mb = b == VoxelWorld.WATER ? water : solid;
          ColorRGBA base = b == VoxelWorld.GRASS ? biomeGrassColor(x, z) : BLOCK_COLOR.get(b);
          ColorRGBA color = b == VoxelWorld.WATER ? WATER_COLOR : mottle(base, x, y, z);
          addVisibleFaces(mb, x, y, z, b, color);
        }
      }
    }

    solidChunks[ci].setMesh(solid.build());
    waterChunks[ci].setMesh(water.build());
  }

  /** No texture atlas/UV mapping in this renderer, so every block of a
   * given type used to be one perfectly flat, identical color across an
   * entire mountain or plain - which is what read as "stone looks dumb,
   * add some texture". A cheap deterministic per-block hash gives each
   * individual block its own small brightness offset instead, breaking
   * the flatness up into a mottled, textured-looking surface for free -
   * same block position always gets the same offset, so it doesn't
   * shimmer or change between frames. */
  private static ColorRGBA mottle(ColorRGBA base, int x, int y, int z) {
    int h = x * 374761393 + y * 668265263 + z * 2147483647;
    h = (h ^ (h >>> 13)) * 1274126177;
    h = h ^ (h >>> 16);
    float t = (h & 0xFFFF) / 65535f; // 0..1, deterministic per block
    float offset = (t - 0.5f) * 0.16f;
    return new ColorRGBA(
        clamp01(base.r + offset), clamp01(base.g + offset), clamp01(base.b + offset), base.a);
  }

  private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

  /** Grass's base color for the coarse WorldGrid cell a fine column sits
   * in, keyed by that cell's biome (see WorldGrid.biome/Config.BIOME_*). */
  private ColorRGBA biomeGrassColor(int fx, int fz) {
    int i = grid.idx(fx / VoxelWorld.FINE, fz / VoxelWorld.FINE);
    ColorRGBA c = BIOME_GRASS_COLOR.get(grid.biome[i]);
    return c != null ? c : BLOCK_COLOR.get(VoxelWorld.GRASS);
  }

  /** True if a face between a block of `selfType` and a neighbor of
   * `neighborType` should be skipped (fully occluded). */
  private boolean faceHidden(byte neighborType, byte selfType) {
    if (neighborType == VoxelWorld.AIR) return false;
    if (selfType == VoxelWorld.WATER) return true; // water only shows its face against air
    if (neighborType == VoxelWorld.WATER) return false; // solid ground still shows through water
    return true; // solid against solid: buried, never seen
  }

  /** Which atlas tile (see TerrainTextures) a block's face should sample -
   * every face the same for dirt/sand/stone/water, but grass shows its
   * green top tile, a grass/dirt transition tile on its sides, and plain
   * dirt underneath, same as the block it's modeled after. */
  private static int tileFor(byte type, Face f) {
    switch (type) {
      case VoxelWorld.GRASS:
        return f == Face.TOP ? TerrainTextures.GRASS_TOP : f == Face.BOTTOM ? TerrainTextures.DIRT : TerrainTextures.GRASS_SIDE;
      case VoxelWorld.DIRT: return TerrainTextures.DIRT;
      case VoxelWorld.SAND: return TerrainTextures.SAND;
      case VoxelWorld.STONE: return TerrainTextures.STONE;
      case VoxelWorld.PATH: return TerrainTextures.STONE;
      default: return TerrainTextures.WATER;
    }
  }

  private void addVisibleFaces(MeshBuilder mb, int x, int y, int z, byte type, ColorRGBA color) {
    if (!faceHidden(world.get(x, y + 1, z), type)) {
      ColorRGBA c = type == VoxelWorld.WATER ? color : topColor(x, z, color);
      if (type == VoxelWorld.STONE && y >= SNOW_LINE) {
        float t = Math.min(1f, (y - SNOW_LINE) / (4f * VoxelWorld.FINE));
        c = c.clone().interpolateLocal(SNOW_COLOR, 0.4f + t * 0.5f);
      }
      mb.face(x, y, z, Face.TOP, c, SHADE_TOP, tileFor(type, Face.TOP));
    }
    if (!faceHidden(world.get(x, y - 1, z), type)) mb.face(x, y, z, Face.BOTTOM, color, SHADE_BOTTOM, tileFor(type, Face.BOTTOM));
    if (!faceHidden(world.get(x + 1, y, z), type)) mb.face(x, y, z, Face.EAST, color, SHADE_EW, tileFor(type, Face.EAST));
    if (!faceHidden(world.get(x - 1, y, z), type)) mb.face(x, y, z, Face.WEST, color, SHADE_EW, tileFor(type, Face.WEST));
    if (!faceHidden(world.get(x, y, z + 1), type)) mb.face(x, y, z, Face.SOUTH, color, SHADE_NS, tileFor(type, Face.SOUTH));
    if (!faceHidden(world.get(x, y, z - 1), type)) mb.face(x, y, z, Face.NORTH, color, SHADE_NS, tileFor(type, Face.NORTH));
  }

  /** Blends fire glow, territory-owner tint, and road/farmland overlays
   * into a top face's color, matching the old smooth terrain's look. No
   * separate border-accent pass anymore (see git history) - just a flat
   * interior tint for every cell a nation owns. */
  private ColorRGBA topColor(int fx, int fz, ColorRGBA base) {
    // territory/road/farmland/fire are all COARSE WorldGrid data - a fine
    // column's tint always comes from whichever coarse cell it sits in
    int x = fx / VoxelWorld.FINE, z = fz / VoxelWorld.FINE;
    int i = grid.idx(x, z);
    if (!grid.burning[i] && grid.ownerNation[i] < 0 && !grid.isRoad[i] && !grid.isFarmland[i]) return base;
    ColorRGBA c = base.clone();
    if (grid.isFarmland[i]) c.interpolateLocal(FARMLAND_TINT, 0.5f);
    if (grid.isRoad[i]) c.interpolateLocal(ROAD_TINT, 0.75f);
    if (grid.burning[i]) c.interpolateLocal(FIRE_TINT, 0.55f);
    int owner = grid.ownerNation[i];
    if (owner >= 0 && warFlashOn && warFlashNations.contains(owner)) {
      // hovering over a nation that's at war highlights whoever it's
      // fighting - a hard, unmissable red pulse that overrides the
      // normal interior coloring entirely while it's "on"
      c.interpolateLocal(WAR_FLASH_COLOR, 0.75f);
      return c;
    }
    if (owner >= 0 && nationColor != null) {
      ColorRGBA nc = nationColor.colorFor(owner);
      if (nc != null) {
        // a genuinely darker interior, not just a faint wash of the
        // full-brightness color - reads as "this ground belongs to
        // someone" without competing with the border for attention.
        // The old 0.3 blend of an already-halved color was reading as
        // "practically invisible" against real terrain/lighting - this
        // is a clearly visible tint at a normal play camera distance
        // without fully overpowering the underlying terrain texture.
        ColorRGBA darker = new ColorRGBA(nc.r * 0.62f, nc.g * 0.62f, nc.b * 0.62f, 1f);
        c.interpolateLocal(darker, 0.5f);
      }
    }
    return c;
  }

  private enum Face { TOP, BOTTOM, NORTH, SOUTH, EAST, WEST }

  /** Accumulates one chunk's faces. Block-space y is converted to
   * world-space by subtracting VoxelWorld.Y_OFFSET, matching the old
   * smooth heightmap's coordinate space so everything else (camera,
   * entities, picking) needs no changes. Winding is CCW as seen from the
   * direction each face's normal points, matching jME's default
   * front-face convention. */
  /** A plain growable float array - SUBDIV's subdivided faces push this
   * builder's vertex counts several times higher than the old one-quad-
   * per-face version, and List&lt;Float&gt;/List&lt;Integer&gt;'s per-element
   * boxing (millions of Float/Integer allocations on a full-map rebuild)
   * was slow enough there to noticeably stall startup - a plain float[]
   * that doubles its own capacity costs nothing per element instead. */
  private static final class FloatArr {
    float[] a = new float[4096];
    int n = 0;
    void add(float v) { if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2); a[n++] = v; }
    int size() { return n; }
    float[] toArray() { return java.util.Arrays.copyOf(a, n); }
  }

  private static final class IntArr {
    int[] a = new int[2048];
    int n = 0;
    void add(int v) { if (n == a.length) a = java.util.Arrays.copyOf(a, a.length * 2); a[n++] = v; }
    int[] toArray() { return java.util.Arrays.copyOf(a, n); }
  }

  private static class MeshBuilder {
    final FloatArr pos = new FloatArr();
    final FloatArr col = new FloatArr();
    final FloatArr norm = new FloatArr();
    final FloatArr uv = new FloatArr();
    final IntArr idx = new IntArr();

    /** bx/by/bz are fine-column coordinates - every axis, Y included, is
     * really only 1/VoxelWorld.FINE world units per step (see FINE's own
     * comment on VoxelWorld - Y used to stay a full unscaled world unit
     * per step while X/Z were subdivided, which rendered every terrain
     * block as a 0.5x1.0x0.5 slab instead of a real cube), so the quad
     * this emits is scaled down on every axis accordingly. */
    void face(int bx, int by, int bz, Face f, ColorRGBA c, float shade, int tile) {
      float s = 1f / VoxelWorld.FINE;
      float x = bx * s, y = (by - VoxelWorld.Y_OFFSET) * s, z = bz * s;
      float[][] verts;
      float nx, ny, nz;
      switch (f) {
        case TOP: verts = new float[][]{{x, y + s, z}, {x, y + s, z + s}, {x + s, y + s, z + s}, {x + s, y + s, z}}; nx = 0; ny = 1; nz = 0; break;
        case BOTTOM: verts = new float[][]{{x, y, z + s}, {x, y, z}, {x + s, y, z}, {x + s, y, z + s}}; nx = 0; ny = -1; nz = 0; break;
        case NORTH: verts = new float[][]{{x + s, y, z}, {x, y, z}, {x, y + s, z}, {x + s, y + s, z}}; nx = 0; ny = 0; nz = -1; break;
        case SOUTH: verts = new float[][]{{x, y, z + s}, {x + s, y, z + s}, {x + s, y + s, z + s}, {x, y + s, z + s}}; nx = 0; ny = 0; nz = 1; break;
        case EAST: verts = new float[][]{{x + s, y, z + s}, {x + s, y, z}, {x + s, y + s, z}, {x + s, y + s, z + s}}; nx = 1; ny = 0; nz = 0; break;
        default: verts = new float[][]{{x, y, z}, {x, y, z + s}, {x, y + s, z + s}, {x, y + s, z}}; nx = -1; ny = 0; nz = 0; break; // WEST
      }
      int base = pos.size() / 3;
      for (float[] v : verts) { pos.add(v[0]); pos.add(v[1]); pos.add(v[2]); }
      // a mild baked-AO tint layered under the real dynamic sun lighting -
      // keeps cliffs/undersides readable even when the sun angle alone
      // wouldn't shade them
      float r = c.r * shade, g = c.g * shade, b2 = c.b * shade;
      for (int i = 0; i < 4; i++) {
        col.add(r); col.add(g); col.add(b2); col.add(c.a);
        norm.add(nx); norm.add(ny); norm.add(nz);
      }
      // each quad's own corner maps 0,0 / 1,0 / 1,1 / 0,1 (matching the
      // vertex winding above), scaled into just this block type's slice of
      // the shared terrain atlas (see TerrainTextures)
      float u0 = TerrainTextures.u0(tile), u1 = TerrainTextures.u1(tile);
      uv.add(u0); uv.add(0f);
      uv.add(u1); uv.add(0f);
      uv.add(u1); uv.add(1f);
      uv.add(u0); uv.add(1f);
      idx.add(base); idx.add(base + 1); idx.add(base + 2);
      idx.add(base); idx.add(base + 2); idx.add(base + 3);
    }

    Mesh build() {
      Mesh m = new Mesh();
      m.setBuffer(VertexBuffer.Type.Position, 3, pos.toArray());
      m.setBuffer(VertexBuffer.Type.Color, 4, col.toArray());
      m.setBuffer(VertexBuffer.Type.Index, 3, idx.toArray());
      m.setBuffer(VertexBuffer.Type.Normal, 3, norm.toArray());
      m.setBuffer(VertexBuffer.Type.TexCoord, 2, uv.toArray());
      m.updateBound();
      return m;
    }
  }
}
