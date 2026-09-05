package com.game.render;

import com.badlogic.gdx.graphics.Camera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.game.sim.SimConfig;
import com.game.sim.TileType;
import com.game.sim.World;

/**
 * Turns a screen position into the tile under it.
 *
 * <p>Marches the camera ray forward in small steps and stops at the first
 * sample that has sunk below the terrain surface, then bisects between the last
 * two samples to land near the true intersection. Analytic ray/heightfield
 * intersection is possible but fiddly across stepped tiles with vertical walls;
 * a march is short, obviously correct, and cheap enough to run per click.
 */
public final class TilePicker {

    /** Coarse march step, in world units. Tiles are 1 unit, so this samples several times per tile. */
    private static final float STEP = 0.35f;
    private static final int REFINE_ITERATIONS = 12;

    private final Ray ray = new Ray();
    private final Vector3 point = new Vector3();
    private final Vector3 near = new Vector3();
    private final Vector3 far = new Vector3();

    /** Tile coordinates from the last successful pick. */
    private int tileX = -1;
    private int tileZ = -1;

    public int getTileX() {
        return tileX;
    }

    public int getTileZ() {
        return tileZ;
    }

    /**
     * Picks the tile under a screen position.
     *
     * @return true if the ray hit the world, leaving the result in
     *         {@link #getTileX()} / {@link #getTileZ()}
     */
    public boolean pick(Camera camera, World world, float screenX, float screenY) {
        buildPickRay(camera, screenX, screenY);

        float maxDistance = camera.far;
        float previousDistance = 0f;
        boolean previousAbove = isAboveSurface(world, ray, 0f);

        // A ray that starts underground (camera clipped into a hill) has
        // nothing sensible to report.
        if (!previousAbove) {
            return false;
        }

        for (float d = STEP; d < maxDistance; d += STEP) {
            boolean above = isAboveSurface(world, ray, d);
            if (!above) {
                float hit = bisect(world, ray, previousDistance, d);
                ray.getEndPoint(point, hit);
                return storeTile(world, point);
            }
            previousDistance = d;

            // Once the ray is below the world floor and still has not hit
            // anything, it never will - stop rather than marching to the far
            // plane every miss.
            ray.getEndPoint(point, d);
            if (point.y < SimConfig.MIN_HEIGHT - 5f) {
                return false;
            }
        }
        return false;
    }

    /**
     * Unprojects a screen position into a world-space ray using only the
     * camera's own matrices and viewport.
     *
     * <p>Deliberately not {@code Camera.getPickRay}: every overload of that
     * method, including the one taking an explicit viewport, flips the y axis
     * using the global {@code Gdx.graphics.getHeight()}. That couples picking
     * to the window rather than to the camera doing the looking, and makes the
     * whole input path untestable without a live application. The maths below
     * is the same unprojection, sourced entirely from the camera.
     */
    private void buildPickRay(Camera camera, float screenX, float screenY) {
        // Screen coordinates run +y downward from the top-left; normalised
        // device coordinates run +y upward from the centre.
        float ndcX = 2f * screenX / camera.viewportWidth - 1f;
        float ndcY = 1f - 2f * screenY / camera.viewportHeight;

        near.set(ndcX, ndcY, -1f).prj(camera.invProjectionView);
        far.set(ndcX, ndcY, 1f).prj(camera.invProjectionView);

        ray.origin.set(near);
        ray.direction.set(far).sub(near).nor();
    }

    /** Narrows an above/below bracket down to the crossing point. */
    private float bisect(World world, Ray ray, float aboveDistance, float belowDistance) {
        float lo = aboveDistance;
        float hi = belowDistance;
        for (int i = 0; i < REFINE_ITERATIONS; i++) {
            float mid = (lo + hi) * 0.5f;
            if (isAboveSurface(world, ray, mid)) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    private boolean isAboveSurface(World world, Ray ray, float distance) {
        ray.getEndPoint(point, distance);
        return point.y > surfaceHeightAt(world, point.x, point.z);
    }

    /**
     * The visible surface height at a world position. Outside the map this
     * returns the sea floor level so the ray keeps travelling instead of
     * hitting an invisible wall at the border.
     */
    private static float surfaceHeightAt(World world, float worldX, float worldZ) {
        int x = (int) Math.floor(worldX);
        int z = (int) Math.floor(worldZ);
        if (!world.inBounds(x, z)) {
            return SimConfig.MIN_HEIGHT - 100f;
        }
        byte type = world.typeAt(x, z);
        // Matches ChunkMesh: water is drawn as a sheet at sea level, so clicks
        // on a lake must land on the surface the player can actually see.
        return TileType.isWater(type) ? SimConfig.SEA_LEVEL : world.heightAt(x, z);
    }

    private boolean storeTile(World world, Vector3 hit) {
        int x = (int) Math.floor(hit.x);
        int z = (int) Math.floor(hit.z);
        if (!world.inBounds(x, z)) {
            return false;
        }
        tileX = x;
        tileZ = z;
        return true;
    }
}
