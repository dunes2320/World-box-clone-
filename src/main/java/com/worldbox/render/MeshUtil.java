package com.worldbox.render;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;

import java.nio.FloatBuffer;

public class MeshUtil {
  /** jME's Cylinder/Cone-like primitives are built along the Z axis; this
   * rotates a template's position+normal buffers in place so "up" becomes
   * +Y, matching how we place things on the terrain. */
  public static void reorientZToY(Mesh mesh) {
    Quaternion rot = new Quaternion().fromAngleAxis(-com.jme3.math.FastMath.HALF_PI, Vector3f.UNIT_X);
    for (VertexBuffer.Type type : new VertexBuffer.Type[]{VertexBuffer.Type.Position, VertexBuffer.Type.Normal}) {
      FloatBuffer buf = mesh.getFloatBuffer(type);
      if (buf == null) continue;
      Vector3f v = new Vector3f();
      for (int i = 0; i < buf.limit() / 3; i++) {
        v.set(buf.get(i * 3), buf.get(i * 3 + 1), buf.get(i * 3 + 2));
        rot.multLocal(v);
        buf.put(i * 3, v.x);
        buf.put(i * 3 + 1, v.y);
        buf.put(i * 3 + 2, v.z);
      }
    }
    mesh.updateBound();
  }

  private static float[] computeSmoothNormals(float[] positions, int[] indices) {
    float[] normals = new float[positions.length];
    for (int t = 0; t < indices.length; t += 3) {
      int ia = indices[t] * 3, ib = indices[t + 1] * 3, ic = indices[t + 2] * 3;
      float abx = positions[ib] - positions[ia], aby = positions[ib + 1] - positions[ia + 1], abz = positions[ib + 2] - positions[ia + 2];
      float acx = positions[ic] - positions[ia], acy = positions[ic + 1] - positions[ia + 1], acz = positions[ic + 2] - positions[ia + 2];
      float nx = aby * acz - abz * acy, ny = abz * acx - abx * acz, nz = abx * acy - aby * acx;
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
    return normals;
  }

  /** A small 4-sided bipyramid ("gem") - a distinct silhouette from the
   * cones/boxes used elsewhere, for ore/crystal deposits. */
  public static Mesh buildGem(float radius, float height) {
    float[] positions = {
        0, height / 2, 0,     // 0 top
        0, -height / 2, 0,    // 1 bottom
        radius, 0, 0,          // 2
        0, 0, radius,           // 3
        -radius, 0, 0,           // 4
        0, 0, -radius,            // 5
    };
    int[] indices = {
        0, 3, 2, 0, 4, 3, 0, 5, 4, 0, 2, 5, // top faces (outward winding)
        1, 2, 3, 1, 3, 4, 1, 4, 5, 1, 5, 2, // bottom faces
    };
    Mesh m = new Mesh();
    m.setBuffer(VertexBuffer.Type.Position, 3, positions);
    m.setBuffer(VertexBuffer.Type.Normal, 3, computeSmoothNormals(positions, indices));
    m.setBuffer(VertexBuffer.Type.Index, 3, indices);
    m.updateBound();
    return m;
  }

  /** Rotates a mesh's position+normal buffers in place around Y - lets a
   * primitive built axis-aligned (like Box, which can't be built rotated)
   * get jumbled into an irregular cluster afterward. */
  public static void rotateYInPlace(Mesh mesh, float angle) {
    Quaternion rot = new Quaternion().fromAngleAxis(angle, Vector3f.UNIT_Y);
    for (VertexBuffer.Type type : new VertexBuffer.Type[]{VertexBuffer.Type.Position, VertexBuffer.Type.Normal}) {
      FloatBuffer buf = mesh.getFloatBuffer(type);
      if (buf == null) continue;
      Vector3f v = new Vector3f();
      for (int i = 0; i < buf.limit() / 3; i++) {
        v.set(buf.get(i * 3), buf.get(i * 3 + 1), buf.get(i * 3 + 2));
        rot.multLocal(v);
        buf.put(i * 3, v.x);
        buf.put(i * 3 + 1, v.y);
        buf.put(i * 3 + 2, v.z);
      }
    }
    mesh.updateBound();
  }

  /** A jumbled boulder cluster - three overlapping, differently-sized and
   * -rotated boxes instead of one smooth "gem", so a stone deposit reads
   * as a rough rock pile instead of a polished blob. Bottom sits at y=0
   * so ground placement works the same as any other origin-centered
   * template (lift by half its overall height). */
  public static Mesh buildRockCluster(float baseRadius) {
    Mesh big = new com.jme3.scene.shape.Box(baseRadius * 0.5f, baseRadius * 0.38f, baseRadius * 0.46f);
    rotateYInPlace(big, 0.35f);
    Mesh bigP = translatedCopy(big, 0, baseRadius * 0.38f, 0);

    Mesh mid = new com.jme3.scene.shape.Box(baseRadius * 0.36f, baseRadius * 0.3f, baseRadius * 0.32f);
    rotateYInPlace(mid, -0.7f);
    Mesh midP = translatedCopy(mid, baseRadius * 0.32f, baseRadius * 0.28f, -baseRadius * 0.18f);

    Mesh small = new com.jme3.scene.shape.Box(baseRadius * 0.26f, baseRadius * 0.22f, baseRadius * 0.24f);
    rotateYInPlace(small, 1.1f);
    Mesh smallP = translatedCopy(small, -baseRadius * 0.3f, baseRadius * 0.2f, baseRadius * 0.22f);

    return mergeMeshes(mergeMeshes(bigP, midP), smallP);
  }

  /** A single angular crystal spike - a low-radial-segment cone reads as
   * faceted/gem-like rather than smoothly round. Bottom sits at local
   * y=0, tip at y=height. */
  private static Mesh spike(float radius, float height, int sides) {
    Mesh cone = new com.jme3.scene.shape.Cylinder(2, sides, radius, 0.001f, height, true, false);
    reorientZToY(cone);
    return translatedCopy(cone, 0, height / 2, 0);
  }

  /** A cluster of jutting angular spikes at different heights/radii - a
   * mineral crystal formation, not a single smooth polished "gem". Used
   * for iron/gold ore so they read as raw crystal rather than a blobby
   * lump; each instance's own per-placement Y rotation (see
   * EntityRenderer) varies which facets face the camera between
   * deposits even though the cluster shape itself is fixed. */
  public static Mesh buildCrystalCluster(float baseRadius) {
    Mesh a = spike(baseRadius * 0.4f, baseRadius * 1.6f, 5);
    Mesh b = translatedCopy(spike(baseRadius * 0.28f, baseRadius * 1.1f, 5), baseRadius * 0.35f, 0, baseRadius * 0.15f);
    Mesh c = translatedCopy(spike(baseRadius * 0.22f, baseRadius * 0.85f, 5), -baseRadius * 0.3f, 0, -baseRadius * 0.2f);
    Mesh d = translatedCopy(spike(baseRadius * 0.18f, baseRadius * 0.6f, 6), baseRadius * 0.08f, 0, -baseRadius * 0.38f);
    return mergeMeshes(mergeMeshes(a, b), mergeMeshes(c, d));
  }

  /** A single thin double-sided blade - a flat triangle tapering from
   * `width` at the base to a near-point tip, leaning over by `bend` along
   * local Z. Built as two separate triangles (not a shared-vertex quad) so
   * front and back faces each get their own flat normal instead of
   * averaging to a degenerate near-zero normal. */
  private static Mesh bladeShape(float width, float height, float bend) {
    float hw = width / 2f, tw = width * 0.12f;
    float[] front = {
        -hw, 0, 0,
        hw, 0, 0,
        bend, height, 0,
    };
    float[] positions = new float[18];
    System.arraycopy(front, 0, positions, 0, 9);
    System.arraycopy(front, 0, positions, 9, 9);
    // widen the tip slightly off dead-center so the silhouette isn't a
    // perfectly straight spike
    positions[6] = tw + bend; positions[15] = tw + bend;
    int[] indices = {0, 1, 2, 5, 4, 3};
    Mesh m = new Mesh();
    m.setBuffer(VertexBuffer.Type.Position, 3, positions);
    m.setBuffer(VertexBuffer.Type.Normal, 3, computeSmoothNormals(positions, indices));
    m.setBuffer(VertexBuffer.Type.Index, 3, indices);
    m.updateBound();
    return m;
  }

  /** A little clump of 7 fanned-out blades around a shared base - reads as
   * an actual tuft of grass/garden greenery from any angle instead of the
   * old two-crossed-boxes "stick" (which was both too short and too boxy
   * to read as a plant at all). Bottom sits at y=0, like the rock/crystal
   * clusters, so ground placement just needs the usual per-instance
   * height/scale from EntityRenderer - no extra lift here. */
  public static Mesh buildGrassTuft(float baseHeight) {
    Mesh tuft = null;
    int blades = 7;
    for (int i = 0; i < blades; i++) {
      float angle = (i / (float) blades) * 6.2832f + (i * 0.53f);
      float h = baseHeight * (0.78f + 0.11f * (i % 3));
      float w = baseHeight * 0.3f;
      Mesh blade = bladeShape(w, h, w * 1.1f);
      rotateYInPlace(blade, angle);
      tuft = (tuft == null) ? blade : mergeMeshes(tuft, blade);
    }
    return tuft;
  }

  /** A slim pillar with a flared cap - used for a nation's bank/vault. */
  public static Mesh buildPillar(float baseRadius, float capRadius, float height) {
    Mesh shaft = new com.jme3.scene.shape.Cylinder(2, 5, baseRadius, baseRadius * 0.8f, height, true, false);
    reorientZToY(shaft);
    Mesh cap = new com.jme3.scene.shape.Cylinder(2, 5, capRadius, 0.001f, height * 0.35f, true, false);
    reorientZToY(cap);
    java.util.List<PropBatcher.Placement> parts = new java.util.ArrayList<>();
    com.jme3.math.ColorRGBA white = com.jme3.math.ColorRGBA.White;
    parts.add(new PropBatcher.Placement(0, 0, 0, 0, 1, white));
    Mesh baseMesh = PropBatcher.bake(shaft, parts);
    parts.clear();
    parts.add(new PropBatcher.Placement(0, height * 0.68f, 0, 0, 1, white));
    Mesh capMesh = PropBatcher.bake(cap, parts);
    return mergeMeshes(baseMesh, capMesh);
  }

  /** Clones a mesh's position buffer shifted by (dx,dy,dz); used to stack
   * simple primitives into a composite shape before merging them. */
  public static Mesh translatedCopy(Mesh mesh, float dx, float dy, float dz) {
    Mesh clone = mesh.deepClone();
    FloatBuffer pos = clone.getFloatBuffer(VertexBuffer.Type.Position);
    for (int i = 0; i < pos.limit() / 3; i++) {
      pos.put(i * 3, pos.get(i * 3) + dx);
      pos.put(i * 3 + 1, pos.get(i * 3 + 1) + dy);
      pos.put(i * 3 + 2, pos.get(i * 3 + 2) + dz);
    }
    clone.updateBound();
    return clone;
  }

  public static Mesh mergeMeshes(Mesh a, Mesh b) {
    FloatBuffer aPos = a.getFloatBuffer(VertexBuffer.Type.Position);
    FloatBuffer bPos = b.getFloatBuffer(VertexBuffer.Type.Position);
    FloatBuffer aNorm = a.getFloatBuffer(VertexBuffer.Type.Normal);
    FloatBuffer bNorm = b.getFloatBuffer(VertexBuffer.Type.Normal);
    FloatBuffer aCol = a.getFloatBuffer(VertexBuffer.Type.Color);
    FloatBuffer bCol = b.getFloatBuffer(VertexBuffer.Type.Color);
    com.jme3.scene.mesh.IndexBuffer aIdx = a.getIndicesAsList();
    com.jme3.scene.mesh.IndexBuffer bIdx = b.getIndicesAsList();
    int aVerts = a.getVertexCount(), bVerts = b.getVertexCount();

    float[] pos = new float[(aVerts + bVerts) * 3];
    float[] norm = new float[(aVerts + bVerts) * 3];
    float[] col = new float[(aVerts + bVerts) * 4];
    int[] idx = new int[aIdx.size() + bIdx.size()];

    for (int i = 0; i < aVerts * 3; i++) { pos[i] = aPos.get(i); norm[i] = aNorm.get(i); }
    for (int i = 0; i < bVerts * 3; i++) { pos[aVerts * 3 + i] = bPos.get(i); norm[aVerts * 3 + i] = bNorm.get(i); }
    if (aCol != null) for (int i = 0; i < aVerts * 4; i++) col[i] = aCol.get(i);
    else java.util.Arrays.fill(col, 0, aVerts * 4, 1f);
    if (bCol != null) for (int i = 0; i < bVerts * 4; i++) col[aVerts * 4 + i] = bCol.get(i);
    else java.util.Arrays.fill(col, aVerts * 4, (aVerts + bVerts) * 4, 1f);

    for (int i = 0; i < aIdx.size(); i++) idx[i] = aIdx.get(i);
    for (int i = 0; i < bIdx.size(); i++) idx[aIdx.size() + i] = bIdx.get(i) + aVerts;

    Mesh m = new Mesh();
    m.setBuffer(VertexBuffer.Type.Position, 3, pos);
    m.setBuffer(VertexBuffer.Type.Normal, 3, norm);
    m.setBuffer(VertexBuffer.Type.Color, 4, col);
    m.setBuffer(VertexBuffer.Type.Index, 3, idx);
    m.updateBound();
    return m;
  }
}
