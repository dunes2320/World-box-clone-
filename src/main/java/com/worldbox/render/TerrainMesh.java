package com.worldbox.render;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.worldbox.config.Config;
import com.worldbox.world.WorldGrid;

import java.util.HashSet;
import java.util.Set;

/** One continuous mesh for the whole terrain: a (cols+1)x(rows+1) vertex
 * grid, height-displaced per cell, vertex-colored by biome + territory tint
 * + fire glow. A separate transparent quad represents the sea. */
public class TerrainMesh {
  private static final float WATER_LEVEL = -1.0f;
  private static final ColorRGBA FIRE_TINT = new ColorRGBA(1f, 0.48f, 0.1f, 1f);

  private static final java.util.Map<Byte, ColorRGBA> BIOME_COLOR = new java.util.HashMap<>();
  static {
    BIOME_COLOR.put(Config.WATER, new ColorRGBA(0.184f, 0.31f, 0.478f, 1f));
    BIOME_COLOR.put(Config.SAND, new ColorRGBA(0.851f, 0.773f, 0.541f, 1f));
    BIOME_COLOR.put(Config.GRASS, new ColorRGBA(0.310f, 0.604f, 0.267f, 1f));
    BIOME_COLOR.put(Config.DIRT, new ColorRGBA(0.478f, 0.357f, 0.227f, 1f));
    BIOME_COLOR.put(Config.STONE, new ColorRGBA(0.545f, 0.561f, 0.588f, 1f));
  }

  private WorldGrid grid;
  private final NationColorLookup nationColor;
  private final int vCols, vRows;
  private final float[] positions;
  private final float[] colors;
  private final float[] normals;
  private final int[] indices;
  private final Mesh mesh;
  public final Geometry geometry;
  public final Geometry waterGeometry;

  public TerrainMesh(WorldGrid grid, AssetManager assets, NationColorLookup nationColor) {
    this.grid = grid;
    this.nationColor = nationColor;
    int cols = grid.cols, rows = grid.rows;
    vCols = cols + 1;
    vRows = rows + 1;

    positions = new float[vCols * vRows * 3];
    colors = new float[vCols * vRows * 4];
    normals = new float[vCols * vRows * 3];
    indices = new int[cols * rows * 6];

    for (int gy = 0; gy < vRows; gy++) {
      for (int gx = 0; gx < vCols; gx++) {
        int v = gy * vCols + gx;
        positions[v * 3] = gx;
        positions[v * 3 + 1] = 0;
        positions[v * 3 + 2] = gy;
      }
    }
    int ii = 0;
    for (int y = 0; y < rows; y++) {
      for (int x = 0; x < cols; x++) {
        int a = y * vCols + x;
        int b = a + 1;
        int c = a + vCols;
        int d = c + 1;
        // wound so the +Y face is up given jME's default CCW front-face
        indices[ii++] = a; indices[ii++] = c; indices[ii++] = b;
        indices[ii++] = b; indices[ii++] = c; indices[ii++] = d;
      }
    }

    mesh = new Mesh();
    mesh.setBuffer(VertexBuffer.Type.Position, 3, positions);
    mesh.setBuffer(VertexBuffer.Type.Color, 4, colors);
    mesh.setBuffer(VertexBuffer.Type.Normal, 3, normals);
    mesh.setBuffer(VertexBuffer.Type.Index, 3, indices);
    mesh.setStreamed();

    // Unshaded + VertexColor: guarantees the actual per-vertex biome colors
    // show up. (Lighting.j3md's UseMaterialColors path multiplies vertex
    // color through a lit response that saturates to white under a bright
    // directional + ambient light combo - not worth fighting for a
    // stylized/flat look.)
    Material mat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
    mat.setBoolean("VertexColor", true);

    geometry = new Geometry("Terrain", mesh);
    geometry.setMaterial(mat);
    geometry.setQueueBucket(RenderQueue.Bucket.Opaque);

    Mesh waterMesh = buildFlatQuad(-3, -3, cols + 3, rows + 3, WATER_LEVEL);
    Material waterMat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
    waterMat.setColor("Color", new ColorRGBA(0.184f, 0.435f, 0.69f, 0.85f));
    waterMat.setTransparent(true);
    waterMat.setFloat("AlphaDiscardThreshold", 0f);
    waterMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
    waterMat.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);
    waterGeometry = new Geometry("Water", waterMesh);
    waterGeometry.setMaterial(waterMat);
    waterGeometry.setQueueBucket(RenderQueue.Bucket.Transparent);

    fullRebuild();
  }

  private static Mesh buildFlatQuad(float x0, float z0, float x1, float z1, float y) {
    Mesh m = new Mesh();
    float[] pos = {x0, y, z0, x1, y, z0, x0, y, z1, x1, y, z1};
    float[] norm = {0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0};
    int[] idx = {0, 1, 2, 1, 3, 2};
    m.setBuffer(VertexBuffer.Type.Position, 3, pos);
    m.setBuffer(VertexBuffer.Type.Normal, 3, norm);
    m.setBuffer(VertexBuffer.Type.Index, 3, idx);
    m.updateBound();
    return m;
  }

  public void setGrid(WorldGrid grid) {
    this.grid = grid;
    fullRebuild();
  }

  private float heightAt(int ix, int iy) {
    int i = grid.idx(ix, iy);
    float h = grid.height[i];
    if (grid.terrain[i] == Config.WATER) h = Math.min(h, WATER_LEVEL - 0.15f);
    return h;
  }

  private void colorAt(int ix, int iy, ColorRGBA out) {
    int i = grid.idx(ix, iy);
    out.set(BIOME_COLOR.get(grid.terrain[i]));
    if (grid.burning[i]) out.interpolateLocal(FIRE_TINT, 0.55f);
    int owner = grid.ownerNation[i];
    if (owner >= 0 && nationColor != null) {
      ColorRGBA nc = nationColor.colorFor(owner);
      if (nc != null) out.interpolateLocal(nc, 0.22f);
    }
  }

  private final ColorRGBA tmp = new ColorRGBA();

  private void updateVertex(int gx, int gy) {
    int cols = grid.cols, rows = grid.rows;
    int cx = Math.min(gx, cols - 1);
    int cy = Math.min(gy, rows - 1);
    int v = gy * vCols + gx;
    positions[v * 3 + 1] = heightAt(cx, cy);
    colorAt(cx, cy, tmp);
    colors[v * 4] = tmp.r;
    colors[v * 4 + 1] = tmp.g;
    colors[v * 4 + 2] = tmp.b;
    colors[v * 4 + 3] = 1f;
  }

  public void fullRebuild() {
    for (int gy = 0; gy < vRows; gy++) {
      for (int gx = 0; gx < vCols; gx++) updateVertex(gx, gy);
    }
    pushBuffers();
  }

  public void flushDirty() {
    if (grid.dirty.isEmpty()) return;
    int cols = grid.cols;
    Set<Integer> touched = new HashSet<>();
    for (int i : grid.dirty) {
      int x = i % cols, y = i / cols;
      touched.add(y * vCols + x);
      touched.add(y * vCols + x + 1);
      touched.add((y + 1) * vCols + x);
      touched.add((y + 1) * vCols + x + 1);
    }
    for (int v : touched) {
      int gx = v % vCols, gy = v / vCols;
      updateVertex(gx, gy);
    }
    pushBuffers();
    grid.dirty.clear();
  }

  private void computeNormals() {
    java.util.Arrays.fill(normals, 0f);
    for (int t = 0; t < indices.length; t += 3) {
      int ia = indices[t] * 3, ib = indices[t + 1] * 3, ic = indices[t + 2] * 3;
      float abx = positions[ib] - positions[ia], aby = positions[ib + 1] - positions[ia + 1], abz = positions[ib + 2] - positions[ia + 2];
      float acx = positions[ic] - positions[ia], acy = positions[ic + 1] - positions[ia + 1], acz = positions[ic + 2] - positions[ia + 2];
      float nx = aby * acz - abz * acy;
      float ny = abz * acx - abx * acz;
      float nz = abx * acy - aby * acx;
      normals[ia] += nx; normals[ia + 1] += ny; normals[ia + 2] += nz;
      normals[ib] += nx; normals[ib + 1] += ny; normals[ib + 2] += nz;
      normals[ic] += nx; normals[ic + 1] += ny; normals[ic + 2] += nz;
    }
    for (int v = 0; v < normals.length; v += 3) {
      float nx = normals[v], ny = normals[v + 1], nz = normals[v + 2];
      float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
      if (len > 1e-6f) { normals[v] = nx / len; normals[v + 1] = ny / len; normals[v + 2] = nz / len; }
      else { normals[v] = 0; normals[v + 1] = 1; normals[v + 2] = 0; }
    }
  }

  // GL calls need a *direct* NIO buffer; FloatBuffer.wrap(array) is a heap
  // buffer and crashes native code if handed to updateData(). Write into
  // the mesh's own direct buffer in place instead.
  private void writeInto(VertexBuffer.Type type, float[] data) {
    java.nio.FloatBuffer buf = mesh.getFloatBuffer(type);
    buf.rewind();
    buf.put(data);
    buf.rewind();
    mesh.getBuffer(type).updateData(buf);
  }

  private void pushBuffers() {
    computeNormals();
    writeInto(VertexBuffer.Type.Position, positions);
    writeInto(VertexBuffer.Type.Color, colors);
    writeInto(VertexBuffer.Type.Normal, normals);
    mesh.updateBound();
  }
}
