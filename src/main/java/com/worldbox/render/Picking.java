package com.worldbox.render;

import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.math.Ray;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.worldbox.world.WorldGrid;

public class Picking {

  public static class CellHit {
    public final int x, z;
    public CellHit(int x, int z) { this.x = x; this.z = z; }
  }

  private static Ray rayFromCursor(Camera cam, Vector2f screenPos) {
    Vector3f origin = cam.getWorldCoordinates(screenPos, 0f);
    Vector3f target = cam.getWorldCoordinates(screenPos, 1f);
    Vector3f dir = target.subtractLocal(origin).normalizeLocal();
    return new Ray(origin, dir);
  }

  public static CellHit pickTerrainCell(Camera cam, Spatial terrain, WorldGrid grid, Vector2f screenPos) {
    Ray ray = rayFromCursor(cam, screenPos);
    CollisionResults results = new CollisionResults();
    terrain.collideWith(ray, results);
    if (results.size() == 0) return null;
    Vector3f p = results.getClosestCollision().getContactPoint();
    int x = (int) Math.floor(p.x), z = (int) Math.floor(p.z);
    if (!grid.inBounds(x, z)) return null;
    return new CellHit(x, z);
  }

  /** Returns the Integer stored under `userDataKey` on the first hit
   * geometry with cull hint not "Always", or null if nothing was hit. */
  public static Integer pickPoolId(Camera cam, Spatial poolRoot, Vector2f screenPos, String userDataKey) {
    Ray ray = rayFromCursor(cam, screenPos);
    CollisionResults results = new CollisionResults();
    poolRoot.collideWith(ray, results);
    for (CollisionResult r : results) {
      Geometry g = r.getGeometry();
      if (g.getCullHint() == Spatial.CullHint.Always) continue;
      Object id = g.getUserData(userDataKey);
      if (id instanceof Integer) return (Integer) id;
    }
    return null;
  }
}
