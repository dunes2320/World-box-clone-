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
import com.worldbox.world.VoxelWorld;
import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Renders a VoxelWorld as one low-poly mesh per chunk, only emitting faces
 * that touch air (or, for water, air/land) so fully-buried blocks cost
 * nothing. Each face is tinted by block type and darkened a bit by facing
 * direction - a cheap fake-shading trick so the blocky world reads with
 * some depth without needing real lighting. */
public class VoxelChunkRenderer {
  private static final Map<Byte, ColorRGBA> BLOCK_COLOR = new HashMap<>();
  static {
    BLOCK_COLOR.put(VoxelWorld.GRASS, new ColorRGBA(0.310f, 0.604f, 0.267f, 1f));
    BLOCK_COLOR.put(VoxelWorld.DIRT, new ColorRGBA(0.478f, 0.357f, 0.227f, 1f));
    BLOCK_COLOR.put(VoxelWorld.SAND, new ColorRGBA(0.851f, 0.773f, 0.541f, 1f));
    BLOCK_COLOR.put(VoxelWorld.STONE, new ColorRGBA(0.545f, 0.561f, 0.588f, 1f));
  }
  private static final ColorRGBA WATER_COLOR = new ColorRGBA(0.130f, 0.380f, 0.620f, 0.80f);
  private static final ColorRGBA FOAM_COLOR = new ColorRGBA(0.72f, 0.85f, 0.88f, 0.85f);
  private static final ColorRGBA FIRE_TINT = new ColorRGBA(1f, 0.48f, 0.1f, 1f);
  private static final ColorRGBA FARMLAND_TINT = new ColorRGBA(0.62f, 0.48f, 0.26f, 1f);
  private static final ColorRGBA ROAD_TINT = new ColorRGBA(0.68f, 0.63f, 0.53f, 1f);

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
  private final Geometry[] solidChunks;
  private final Geometry[] waterChunks;
  /** Pre-wave vertex positions per water chunk, captured at mesh-build
   * time so the per-frame animation always displaces from a stable base
   * instead of compounding drift onto itself. */
  private final Map<Integer, float[]> waterBasePositions = new HashMap<>();

  public VoxelChunkRenderer(VoxelWorld world, WorldGrid grid, AssetManager assets, NationColorLookup nationColor) {
    this.world = world;
    this.grid = grid;
    this.nationColor = nationColor;
    int n = world.chunksX * world.chunksZ;
    solidChunks = new Geometry[n];
    waterChunks = new Geometry[n];

    Material solidMat = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
    solidMat.setBoolean("UseVertexColor", true);
    solidMat.setColor("Specular", ColorRGBA.Black);
    solidMat.setFloat("Shininess", 1f);

    Material waterMat = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
    waterMat.setBoolean("UseVertexColor", true);
    waterMat.setColor("Specular", ColorRGBA.Black);
    waterMat.setFloat("Shininess", 1f);
    waterMat.setTransparent(true);
    waterMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
    waterMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

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
        int x = i % world.cols, z = i / world.cols;
        world.dirtyChunks.add((z / VoxelWorld.CHUNK_SIZE) * world.chunksX + (x / VoxelWorld.CHUNK_SIZE));
      }
      grid.dirty.clear();
    }
    if (world.dirtyChunks.isEmpty()) return;
    for (int ci : world.dirtyChunks) rebuildChunk(ci);
    world.dirtyChunks.clear();
  }

  public void rebuildAll() {
    for (int ci = 0; ci < solidChunks.length; ci++) rebuildChunk(ci);
    world.dirtyChunks.clear();
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
        boolean shoreline = false;
        for (int y = 0; y < VoxelWorld.MAX_Y; y++) {
          byte b = world.get(x, y, z);
          if (b == VoxelWorld.AIR) continue;
          MeshBuilder mb = b == VoxelWorld.WATER ? water : solid;
          ColorRGBA color;
          if (b == VoxelWorld.WATER) {
            if (y == VoxelWorld.WATER_LEVEL) shoreline = isShoreline(x, z);
            color = shoreline ? FOAM_COLOR : WATER_COLOR;
          } else {
            color = BLOCK_COLOR.get(b);
          }
          addVisibleFaces(mb, x, y, z, b, color);
        }
      }
    }

    solidChunks[ci].setMesh(solid.build());
    Mesh waterMesh = water.build();
    waterChunks[ci].setMesh(waterMesh);
    if (waterMesh.getVertexCount() > 0) {
      java.nio.FloatBuffer wpos = waterMesh.getFloatBuffer(VertexBuffer.Type.Position);
      float[] base = new float[wpos.limit()];
      wpos.rewind();
      wpos.get(base);
      waterBasePositions.put(ci, base);
    } else {
      waterBasePositions.remove(ci);
    }
  }

  /** A water column counts as shoreline if any orthogonal neighbor is dry
   * land near the waterline - used to tint a light foam ring around
   * coasts instead of one flat color for the whole ocean. */
  private boolean isShoreline(int x, int z) {
    return isLandNear(x + 1, z) || isLandNear(x - 1, z) || isLandNear(x, z + 1) || isLandNear(x, z - 1);
  }

  private boolean isLandNear(int x, int z) {
    if (x < 0 || z < 0 || x >= world.cols || z >= world.rows) return false;
    return world.get(x, VoxelWorld.WATER_LEVEL, z) != VoxelWorld.WATER
        && world.columnTopY(x, z) >= VoxelWorld.WATER_LEVEL - 1;
  }

  /** Nudges every water chunk's vertices with a couple of overlapping sine
   * waves each frame - a cheap CPU-side ripple instead of a flat static
   * plane, since Unshaded/Lighting materials have no time-based shader
   * uniform to animate this on the GPU side. */
  public void updateWaterAnimation(float time) {
    for (Map.Entry<Integer, float[]> e : waterBasePositions.entrySet()) {
      Geometry g = waterChunks[e.getKey()];
      Mesh mesh = g.getMesh();
      float[] base = e.getValue();
      float[] out = new float[base.length];
      for (int i = 0; i < base.length; i += 3) {
        float x = base[i], y = base[i + 1], z = base[i + 2];
        float wave = (float) (Math.sin((x + z) * 0.6 + time * 1.3) * 0.05
            + Math.sin((x - z) * 0.9 + time * 0.8) * 0.03);
        out[i] = x;
        out[i + 1] = y + wave;
        out[i + 2] = z;
      }
      java.nio.FloatBuffer buf = mesh.getFloatBuffer(VertexBuffer.Type.Position);
      buf.rewind();
      buf.put(out);
      buf.rewind();
      mesh.getBuffer(VertexBuffer.Type.Position).updateData(buf);
      mesh.updateBound();
    }
  }

  /** True if a face between a block of `selfType` and a neighbor of
   * `neighborType` should be skipped (fully occluded). */
  private boolean faceHidden(byte neighborType, byte selfType) {
    if (neighborType == VoxelWorld.AIR) return false;
    if (selfType == VoxelWorld.WATER) return true; // water only shows its face against air
    if (neighborType == VoxelWorld.WATER) return false; // solid ground still shows through water
    return true; // solid against solid: buried, never seen
  }

  private void addVisibleFaces(MeshBuilder mb, int x, int y, int z, byte type, ColorRGBA color) {
    if (!faceHidden(world.get(x, y + 1, z), type)) {
      ColorRGBA c = type == VoxelWorld.WATER ? color : topColor(x, z, color);
      mb.face(x, y, z, Face.TOP, c, SHADE_TOP);
    }
    if (!faceHidden(world.get(x, y - 1, z), type)) mb.face(x, y, z, Face.BOTTOM, color, SHADE_BOTTOM);
    if (!faceHidden(world.get(x + 1, y, z), type)) mb.face(x, y, z, Face.EAST, color, SHADE_EW);
    if (!faceHidden(world.get(x - 1, y, z), type)) mb.face(x, y, z, Face.WEST, color, SHADE_EW);
    if (!faceHidden(world.get(x, y, z + 1), type)) mb.face(x, y, z, Face.SOUTH, color, SHADE_NS);
    if (!faceHidden(world.get(x, y, z - 1), type)) mb.face(x, y, z, Face.NORTH, color, SHADE_NS);
  }

  /** Blends fire glow, territory-owner tint, and road/farmland overlays
   * into a top face's color, matching the old smooth terrain's look.
   * Territory itself gets only a faint wash so it doesn't wash out the
   * underlying terrain, but the actual border - where ownership changes,
   * including at the map edge - gets a strong accent (a brightened tint of
   * the nation's own color) so borders are actually visible from a normal
   * play camera distance instead of needing to zoom in to spot them. */
  private ColorRGBA topColor(int x, int z, ColorRGBA base) {
    int i = grid.idx(x, z);
    if (!grid.burning[i] && grid.ownerNation[i] < 0 && !grid.isRoad[i] && !grid.isFarmland[i]) return base;
    ColorRGBA c = base.clone();
    if (grid.isFarmland[i]) c.interpolateLocal(FARMLAND_TINT, 0.5f);
    if (grid.isRoad[i]) c.interpolateLocal(ROAD_TINT, 0.75f);
    if (grid.burning[i]) c.interpolateLocal(FIRE_TINT, 0.55f);
    int owner = grid.ownerNation[i];
    if (owner >= 0 && nationColor != null) {
      ColorRGBA nc = nationColor.colorFor(owner);
      if (nc != null) {
        if (isBorderCell(x, z, owner)) {
          ColorRGBA accent = new ColorRGBA(
              Math.min(1f, nc.r * 1.6f + 0.12f), Math.min(1f, nc.g * 1.6f + 0.12f), Math.min(1f, nc.b * 1.6f + 0.12f), 1f);
          c.interpolateLocal(accent, 0.88f);
        } else {
          c.interpolateLocal(nc, 0.16f);
        }
      }
    }
    return c;
  }

  private boolean borderNeighborDiffers(int x, int z, int owner) {
    if (!grid.inBounds(x, z)) return true;
    return grid.ownerNation[grid.idx(x, z)] != owner;
  }

  private boolean isBorderCell(int x, int z, int owner) {
    return borderNeighborDiffers(x - 1, z, owner) || borderNeighborDiffers(x + 1, z, owner)
        || borderNeighborDiffers(x, z - 1, owner) || borderNeighborDiffers(x, z + 1, owner);
  }

  private enum Face { TOP, BOTTOM, NORTH, SOUTH, EAST, WEST }

  /** Accumulates one chunk's faces. Block-space y is converted to
   * world-space by subtracting VoxelWorld.Y_OFFSET, matching the old
   * smooth heightmap's coordinate space so everything else (camera,
   * entities, picking) needs no changes. Winding is CCW as seen from the
   * direction each face's normal points, matching jME's default
   * front-face convention. */
  private static class MeshBuilder {
    final List<Float> pos = new ArrayList<>();
    final List<Float> col = new ArrayList<>();
    final List<Float> norm = new ArrayList<>();
    final List<Integer> idx = new ArrayList<>();

    void face(int bx, int by, int bz, Face f, ColorRGBA c, float shade) {
      float x = bx, y = by - VoxelWorld.Y_OFFSET, z = bz;
      float[][] verts;
      float nx, ny, nz;
      switch (f) {
        case TOP: verts = new float[][]{{x, y + 1, z}, {x, y + 1, z + 1}, {x + 1, y + 1, z + 1}, {x + 1, y + 1, z}}; nx = 0; ny = 1; nz = 0; break;
        case BOTTOM: verts = new float[][]{{x, y, z + 1}, {x, y, z}, {x + 1, y, z}, {x + 1, y, z + 1}}; nx = 0; ny = -1; nz = 0; break;
        case NORTH: verts = new float[][]{{x + 1, y, z}, {x, y, z}, {x, y + 1, z}, {x + 1, y + 1, z}}; nx = 0; ny = 0; nz = -1; break;
        case SOUTH: verts = new float[][]{{x, y, z + 1}, {x + 1, y, z + 1}, {x + 1, y + 1, z + 1}, {x, y + 1, z + 1}}; nx = 0; ny = 0; nz = 1; break;
        case EAST: verts = new float[][]{{x + 1, y, z + 1}, {x + 1, y, z}, {x + 1, y + 1, z}, {x + 1, y + 1, z + 1}}; nx = 1; ny = 0; nz = 0; break;
        default: verts = new float[][]{{x, y, z}, {x, y, z + 1}, {x, y + 1, z + 1}, {x, y + 1, z}}; nx = -1; ny = 0; nz = 0; break; // WEST
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
      idx.add(base); idx.add(base + 1); idx.add(base + 2);
      idx.add(base); idx.add(base + 2); idx.add(base + 3);
    }

    Mesh build() {
      Mesh m = new Mesh();
      float[] p = new float[pos.size()];
      for (int i = 0; i < p.length; i++) p[i] = pos.get(i);
      float[] c = new float[col.size()];
      for (int i = 0; i < c.length; i++) c[i] = col.get(i);
      float[] n = new float[norm.size()];
      for (int i = 0; i < n.length; i++) n[i] = norm.get(i);
      int[] ix = new int[idx.size()];
      for (int i = 0; i < ix.length; i++) ix[i] = idx.get(i);
      m.setBuffer(VertexBuffer.Type.Position, 3, p);
      m.setBuffer(VertexBuffer.Type.Color, 4, c);
      m.setBuffer(VertexBuffer.Type.Index, 3, ix);
      m.setBuffer(VertexBuffer.Type.Normal, 3, n);
      m.updateBound();
      return m;
    }
  }
}
