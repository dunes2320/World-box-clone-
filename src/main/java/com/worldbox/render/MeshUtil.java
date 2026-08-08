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
}
