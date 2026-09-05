package com.game.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.game.sim.SimConfig;
import com.game.sim.TileType;
import com.game.sim.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Ray picking is pure geometry - {@link PerspectiveCamera} and {@link
 * com.badlogic.gdx.math.collision.Ray} need no GL context - so the trickiest
 * part of the input path can be tested without opening a window.
 */
class TilePickerTest {

    private static final int VIEW_WIDTH = 1280;
    private static final int VIEW_HEIGHT = 800;

    /**
     * Matrix4's projection maths is backed by native code, so even the
     * headless, window-free parts of libGDX need the native library loaded
     * before a Camera can be constructed.
     */
    @BeforeAll
    static void loadNatives() {
        GdxNativesLoader.load();
    }

    private static World flatWorld(float height) {
        World world = new World();
        float fertility = 0.0f;
        for (int i = 0; i < world.tileCount; i++) {
            world.height[i] = height;
            world.fertility[i] = fertility;
            world.tileType[i] = TileType.fromTerrain(height, fertility);
        }
        return world;
    }

    /** A camera hanging directly above {@code (targetX, targetZ)} looking straight down. */
    private static PerspectiveCamera overheadCamera(float targetX, float targetZ, float altitude) {
        PerspectiveCamera camera = new PerspectiveCamera(60f, VIEW_WIDTH, VIEW_HEIGHT);
        camera.near = 0.5f;
        camera.far = 600f;
        camera.position.set(targetX, altitude, targetZ);
        // Looking straight down, so "up" has to be a horizontal axis or the
        // view matrix is degenerate.
        camera.up.set(0f, 0f, -1f);
        camera.lookAt(targetX, 0f, targetZ);
        camera.update();
        return camera;
    }

    @Test
    void pickingScreenCentreHitsTheTileUnderTheCamera() {
        World world = flatWorld(2.0f);
        PerspectiveCamera camera = overheadCamera(64.5f, 64.5f, 60f);
        TilePicker picker = new TilePicker();

        assertTrue(picker.pick(camera, world, VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f), "centre ray should hit the ground");
        assertEquals(64, picker.getTileX());
        assertEquals(64, picker.getTileZ());
    }

    @Test
    void pickingFollowsTheCursorAcrossTheScreen() {
        World world = flatWorld(0.5f);
        PerspectiveCamera camera = overheadCamera(64.5f, 64.5f, 60f);
        TilePicker picker = new TilePicker();

        assertTrue(picker.pick(camera, world, VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f));
        int centreX = picker.getTileX();
        int centreZ = picker.getTileZ();

        // Moving the cursor right must move the picked tile in +x, and moving
        // it down the screen must move it in +z for this camera orientation.
        assertTrue(picker.pick(camera, world, VIEW_WIDTH / 2f + 200f, VIEW_HEIGHT / 2f));
        assertTrue(picker.getTileX() > centreX, "cursor moved right, picked tile should too");
        assertEquals(centreZ, picker.getTileZ(), "moving horizontally should not change z");

        assertTrue(picker.pick(camera, world, VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f + 150f));
        assertTrue(picker.getTileZ() > centreZ, "cursor moved down-screen, picked tile should move in +z");
    }

    @Test
    void pickLandsOnRaisedGroundNotTheSeabedBelowIt() {
        // A tall column: a ray coming down must stop at its top face, and the
        // reported tile must be the column itself.
        World world = flatWorld(0.0f);
        for (int z = 60; z <= 68; z++) {
            for (int x = 60; x <= 68; x++) {
                world.setHeight(x, z, 9.0f);
            }
        }
        PerspectiveCamera camera = overheadCamera(64.5f, 64.5f, 60f);
        TilePicker picker = new TilePicker();

        assertTrue(picker.pick(camera, world, VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f));
        assertEquals(64, picker.getTileX());
        assertEquals(64, picker.getTileZ());
    }

    @Test
    void pickingWaterUsesTheVisibleSurfaceNotTheSeabed() {
        // Water renders as a sheet at sea level, so a click on a lake must
        // resolve against that sheet - otherwise clicks land on tiles the
        // player cannot see.
        World world = flatWorld(-4.0f);
        assertTrue(TileType.isWater(world.typeAt(64, 64)));

        PerspectiveCamera camera = overheadCamera(64.5f, 64.5f, 60f);
        TilePicker picker = new TilePicker();

        assertTrue(picker.pick(camera, world, VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f));
        assertEquals(64, picker.getTileX());
        assertEquals(64, picker.getTileZ());
    }

    @Test
    void rayAimedAwayFromTheWorldReportsAMiss() {
        World world = flatWorld(1.0f);
        PerspectiveCamera camera = new PerspectiveCamera(60f, VIEW_WIDTH, VIEW_HEIGHT);
        camera.near = 0.5f;
        camera.far = 600f;
        camera.position.set(64.5f, 30f, 64.5f);
        camera.up.set(Vector3.Y);
        // Pointing at the sky: the ray climbs forever and never meets terrain.
        camera.lookAt(64.5f, 200f, 300f);
        camera.update();

        TilePicker picker = new TilePicker();
        assertFalse(picker.pick(camera, world, VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f),
            "a ray pointing at the sky must not report a hit");
    }

    @Test
    void pickOffTheEdgeOfTheIslandReportsAMiss() {
        World world = flatWorld(1.0f);
        // Park the camera outside the map looking further outward, so the ray
        // never crosses the grid at all.
        PerspectiveCamera camera = new PerspectiveCamera(60f, VIEW_WIDTH, VIEW_HEIGHT);
        camera.near = 0.5f;
        camera.far = 600f;
        camera.position.set(-60f, 20f, -60f);
        camera.up.set(Vector3.Y);
        camera.lookAt(-260f, 18f, -260f);
        camera.update();

        TilePicker picker = new TilePicker();
        assertFalse(picker.pick(camera, world, VIEW_WIDTH / 2f, VIEW_HEIGHT / 2f),
            "a ray leaving the world must not report a hit");
    }

    @Test
    void everyPickedTileIsInBounds() {
        World world = flatWorld(1.0f);
        PerspectiveCamera camera = overheadCamera(64.5f, 64.5f, 90f);
        TilePicker picker = new TilePicker();

        // Sweep the whole screen; any hit at all must be a legal tile index,
        // since callers index straight into the world arrays with it.
        for (int sy = 0; sy < VIEW_HEIGHT; sy += 37) {
            for (int sx = 0; sx < VIEW_WIDTH; sx += 37) {
                if (picker.pick(camera, world, sx, sy)) {
                    assertTrue(world.inBounds(picker.getTileX(), picker.getTileZ()),
                        "pick at (" + sx + ", " + sy + ") returned out-of-bounds tile "
                            + picker.getTileX() + ", " + picker.getTileZ());
                }
            }
        }
    }

    @Test
    void picksRemainStableForTheSameInput() {
        World world = flatWorld(3.0f);
        PerspectiveCamera camera = overheadCamera(40.5f, 90.5f, 45f);
        TilePicker picker = new TilePicker();

        assertTrue(picker.pick(camera, world, 700f, 300f));
        int x = picker.getTileX();
        int z = picker.getTileZ();
        for (int i = 0; i < 5; i++) {
            assertTrue(picker.pick(camera, world, 700f, 300f));
            assertEquals(x, picker.getTileX(), "repeated picks must agree");
            assertEquals(z, picker.getTileZ(), "repeated picks must agree");
        }
        assertTrue(SimConfig.WORLD_SIZE > 0);
    }
}
