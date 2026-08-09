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
