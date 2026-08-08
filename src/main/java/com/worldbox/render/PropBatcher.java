package com.worldbox.render;

import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.mesh.IndexBuffer;

import java.nio.FloatBuffer;
import java.util.List;

/** Bakes many placements of a small template shape (a tree cone, a rock
 * cube, ...) into one merged static Mesh. Rebuilt periodically rather than
 * per-frame, which keeps hundreds/thousands of props to a single draw call
 * without needing true GPU instancing. */
public class PropBatcher {

  public static class Placement {
    public final float x, y, z, rotY, scale;
    public final ColorRGBA color;
    public Placement(float x, float y, float z, float rotY, float scale, ColorRGBA color) {
      this.x = x; this.y = y; this.z = z; this.rotY = rotY; this.scale = scale; this.color = color;
    }
  }

  public static Mesh bake(Mesh template, List<Placement> placements) {
    FloatBuffer tPos = template.getFloatBuffer(VertexBuffer.Type.Position);
    FloatBuffer tNorm = template.getFloatBuffer(VertexBuffer.Type.Normal);
    IndexBuffer tIdx = template.getIndicesAsList();
    int vertsPerInstance = template.getVertexCount();
    int idxPerInstance = tIdx.size();

    int count = Math.max(1, placements.size());
    float[] pos = new float[vertsPerInstance * count * 3];
    float[] norm = new float[vertsPerInstance * count * 3];
    float[] col = new float[vertsPerInstance * count * 4];
    int[] idx = new int[idxPerInstance * count];

    Quaternion q = new Quaternion();
    Vector3f p = new Vector3f();
    Vector3f n = new Vector3f();

    int vOff = 0, iOff = 0;
    for (Placement placement : placements) {
      q.fromAngleAxis(placement.rotY, Vector3f.UNIT_Y);
      for (int v = 0; v < vertsPerInstance; v++) {
        p.set(tPos.get(v * 3), tPos.get(v * 3 + 1), tPos.get(v * 3 + 2));
        p.multLocal(placement.scale);
        q.multLocal(p);
        p.addLocal(placement.x, placement.y, placement.z);
        int pi = (vOff + v) * 3;
        pos[pi] = p.x; pos[pi + 1] = p.y; pos[pi + 2] = p.z;

        n.set(tNorm.get(v * 3), tNorm.get(v * 3 + 1), tNorm.get(v * 3 + 2));
        q.multLocal(n);
        norm[pi] = n.x; norm[pi + 1] = n.y; norm[pi + 2] = n.z;

        int ci = (vOff + v) * 4;
        col[ci] = placement.color.r; col[ci + 1] = placement.color.g; col[ci + 2] = placement.color.b; col[ci + 3] = 1f;
      }
      for (int t = 0; t < idxPerInstance; t++) idx[iOff + t] = vOff + tIdx.get(t);
      vOff += vertsPerInstance;
      iOff += idxPerInstance;
    }

    if (placements.isEmpty()) {
      java.util.Arrays.fill(idx, 0); // degenerate triangle at the shared single instance slot
    }

    Mesh m = new Mesh();
    m.setBuffer(VertexBuffer.Type.Position, 3, pos);
    m.setBuffer(VertexBuffer.Type.Normal, 3, norm);
    m.setBuffer(VertexBuffer.Type.Color, 4, col);
    m.setBuffer(VertexBuffer.Type.Index, 3, idx);
    m.updateBound();
    return m;
  }
}
