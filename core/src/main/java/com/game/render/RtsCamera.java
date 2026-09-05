package com.game.render;

import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.game.sim.SimConfig;

/**
 * An RTS-style orbit camera: it looks at a point on the ground and swings
 * around it. Pitch is clamped away from both the horizon and straight-down, so
 * the camera can never slip under the terrain or gimbal-flip overhead.
 */
public final class RtsCamera {

    private static final float MIN_PITCH = 12.0f;
    private static final float MAX_PITCH = 82.0f;
    private static final float MIN_DISTANCE = 6.0f;
    private static final float MAX_DISTANCE = 220.0f;

    private static final float ROTATE_SENSITIVITY = 0.35f;
    private static final float ZOOM_SENSITIVITY = 0.12f;
    private static final float PAN_SENSITIVITY = 0.06f;
    private static final float KEY_PAN_SPEED = 32.0f;

    private final PerspectiveCamera camera;
    private final Vector3 target = new Vector3();
    private final Vector3 scratch = new Vector3();

    private float yawDegrees = 45.0f;
    private float pitchDegrees = 50.0f;
    private float distance = 110.0f;

    public RtsCamera(int viewportWidth, int viewportHeight) {
        camera = new PerspectiveCamera(60f, viewportWidth, viewportHeight);
        camera.near = 0.5f;
        camera.far = 600f;
        target.set(SimConfig.WORLD_SIZE * 0.5f, 0f, SimConfig.WORLD_SIZE * 0.5f);
        update();
    }

    public PerspectiveCamera getCamera() {
        return camera;
    }

    public Vector3 getTarget() {
        return target;
    }

    public float getDistance() {
        return distance;
    }

    public void resize(int width, int height) {
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        update();
    }

    /** Left-drag (or the configured rotate binding) swings the camera around its target. */
    public void rotate(float deltaX, float deltaY) {
        yawDegrees += deltaX * ROTATE_SENSITIVITY;
        pitchDegrees = MathUtils.clamp(pitchDegrees + deltaY * ROTATE_SENSITIVITY, MIN_PITCH, MAX_PITCH);
        update();
    }

    /**
     * Drags the world under the cursor. Pan speed scales with zoom distance so
     * a drag moves roughly the same number of on-screen pixels whether the
     * camera is close in or pulled right back.
     */
    public void pan(float deltaX, float deltaY) {
        float scale = PAN_SENSITIVITY * (distance / 50.0f);
        panWorld(-deltaX * scale, -deltaY * scale);
    }

    /** Keyboard panning, in world units per second, aligned to the current heading. */
    public void panKeys(float forward, float right, float deltaSeconds) {
        if (forward == 0f && right == 0f) {
            return;
        }
        float speed = KEY_PAN_SPEED * deltaSeconds * (distance / 80.0f + 0.35f);
        panWorld(right * speed, -forward * speed);
    }

    /**
     * Moves the target across the ground plane in camera-relative directions:
     * {@code strafe} runs along the camera's right vector, {@code forward}
     * along its heading flattened onto the ground.
     */
    private void panWorld(float strafe, float forward) {
        float yawRadians = yawDegrees * MathUtils.degreesToRadians;
        float sin = MathUtils.sin(yawRadians);
        float cos = MathUtils.cos(yawRadians);

        target.x += strafe * cos - forward * sin;
        target.z += strafe * sin + forward * cos;

        // Keep the focus point over the map, with a little slack so the coast
        // can be centred without the camera escaping to empty space.
        float slack = 20f;
        target.x = MathUtils.clamp(target.x, -slack, SimConfig.WORLD_SIZE + slack);
        target.z = MathUtils.clamp(target.z, -slack, SimConfig.WORLD_SIZE + slack);
        update();
    }

    /** Scroll wheel. Positive amount zooms out. */
    public void zoom(float amount) {
        // Proportional to current distance, so zooming feels equally paced when
        // dialled right in and when pulled right out.
        distance = MathUtils.clamp(distance * (1f + amount * ZOOM_SENSITIVITY), MIN_DISTANCE, MAX_DISTANCE);
        update();
    }

    /** Recomputes the camera position from yaw/pitch/distance around the target. */
    public void update() {
        float yawRadians = yawDegrees * MathUtils.degreesToRadians;
        float pitchRadians = pitchDegrees * MathUtils.degreesToRadians;

        float horizontal = MathUtils.cos(pitchRadians) * distance;
        scratch.set(
            target.x + MathUtils.sin(yawRadians) * horizontal,
            target.y + MathUtils.sin(pitchRadians) * distance,
            target.z + MathUtils.cos(yawRadians) * horizontal);

        camera.position.set(scratch);
        camera.up.set(Vector3.Y);
        camera.lookAt(target);
        camera.update();
    }
}
