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
  private static final ColorRGBA WATER_COLOR = new ColorRGBA(0.184f, 0.435f, 0.69f, 0.82f);
  private static final ColorRGBA FIRE_TINT = new ColorRGBA(1f, 0.48f, 0.1f, 1f);

  private static final float SHADE_TOP = 1.0f;
  private static final float SHADE_BOTTOM = 0.55f;
  private static final float SHADE_NS = 0.82f;
  private static final float SHADE_EW = 0.70f;

  private VoxelWorld world;
  private WorldGrid grid;
  private final NationColorLookup nationColor;
  public final Node solidNode = new Node("voxelSolid");
  public final Node waterNode = new Node("voxelWater");
  private final Geometry[] solidChunks;
  private final Geometry[] waterChunks;

  public VoxelChunkRenderer(VoxelWorld world, WorldGrid grid, AssetManager assets, NationColorLookup nationColor) {
    this.world = world;
    this.grid = grid;
    this.nationColor = nationColor;
    int n = world.chunksX * world.chunksZ;
    solidChunks = new Geometry[n];
    waterChunks = new Geometry[n];

    Material solidMat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
    solidMat.setBoolean("VertexColor", true);

    Material waterMat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
    waterMat.setBoolean("VertexColor", true);
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
        for (int y = 0; y < VoxelWorld.MAX_Y; y++) {
          byte b = world.get(x, y, z);
          if (b == VoxelWorld.AIR) continue;
          MeshBuilder mb = b == VoxelWorld.WATER ? water : solid;
          ColorRGBA color = b == VoxelWorld.WATER ? WATER_COLOR : BLOCK_COLOR.get(b);
          addVisibleFaces(mb, x, y, z, b, color);
        }
      }
    }

    solidChunks[ci].setMesh(solid.build());
    waterChunks[ci].setMesh(water.build());
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

  /** Blends fire glow and territory-owner tint into a top face's color,
   * matching the old smooth terrain's look. */
  private ColorRGBA topColor(int x, int z, ColorRGBA base) {
    int i = grid.idx(x, z);
    if (!grid.burning[i] && grid.ownerNation[i] < 0) return base;
    ColorRGBA c = base.clone();
    if (grid.burning[i]) c.interpolateLocal(FIRE_TINT, 0.55f);
    int owner = grid.ownerNation[i];
    if (owner >= 0 && nationColor != null) {
      ColorRGBA nc = nationColor.colorFor(owner);
      if (nc != null) c.interpolateLocal(nc, 0.22f);
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
  private static class MeshBuilder {
    final List<Float> pos = new ArrayList<>();
    final List<Float> col = new ArrayList<>();
    final List<Integer> idx = new ArrayList<>();

    void face(int bx, int by, int bz, Face f, ColorRGBA c, float shade) {
      float x = bx, y = by - VoxelWorld.Y_OFFSET, z = bz;
      float[][] verts;
      switch (f) {
        case TOP: verts = new float[][]{{x, y + 1, z}, {x, y + 1, z + 1}, {x + 1, y + 1, z + 1}, {x + 1, y + 1, z}}; break;
        case BOTTOM: verts = new float[][]{{x, y, z + 1}, {x, y, z}, {x + 1, y, z}, {x + 1, y, z + 1}}; break;
        case NORTH: verts = new float[][]{{x + 1, y, z}, {x, y, z}, {x, y + 1, z}, {x + 1, y + 1, z}}; break;
        case SOUTH: verts = new float[][]{{x, y, z + 1}, {x + 1, y, z + 1}, {x + 1, y + 1, z + 1}, {x, y + 1, z + 1}}; break;
        case EAST: verts = new float[][]{{x + 1, y, z + 1}, {x + 1, y, z}, {x + 1, y + 1, z}, {x + 1, y + 1, z + 1}}; break;
        default: verts = new float[][]{{x, y, z}, {x, y, z + 1}, {x, y + 1, z + 1}, {x, y + 1, z}}; break; // WEST
      }
      int base = pos.size() / 3;
      for (float[] v : verts) { pos.add(v[0]); pos.add(v[1]); pos.add(v[2]); }
      float r = c.r * shade, g = c.g * shade, b2 = c.b * shade;
      for (int i = 0; i < 4; i++) { col.add(r); col.add(g); col.add(b2); col.add(c.a); }
      idx.add(base); idx.add(base + 1); idx.add(base + 2);
      idx.add(base); idx.add(base + 2); idx.add(base + 3);
    }

    Mesh build() {
      Mesh m = new Mesh();
      float[] p = new float[pos.size()];
      for (int i = 0; i < p.length; i++) p[i] = pos.get(i);
      float[] c = new float[col.size()];
      for (int i = 0; i < c.length; i++) c[i] = col.get(i);
      int[] ix = new int[idx.size()];
      for (int i = 0; i < ix.length; i++) ix[i] = idx.get(i);
      m.setBuffer(VertexBuffer.Type.Position, 3, p);
      m.setBuffer(VertexBuffer.Type.Color, 4, c);
      m.setBuffer(VertexBuffer.Type.Index, 3, ix);
      m.setBuffer(VertexBuffer.Type.Normal, 3, new float[p.length]);
      m.updateBound();
      return m;
    }
  }
}
