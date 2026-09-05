package com.game;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.InputMultiplexer;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.game.render.RtsCamera;
import com.game.render.TerrainRenderer;
import com.game.render.TilePicker;
import com.game.sim.SimClock;
import com.game.sim.SimConfig;
import com.game.sim.Simulation;
import com.game.sim.Terraform;
import java.nio.ByteBuffer;

/**
 * Top-level game loop. Owns the simulation, drives it at a fixed rate through
 * {@link SimClock}, and renders whatever state the simulation currently holds.
 *
 * <p>The dependency runs strictly one way: this class reads simulation state to
 * draw it, and sends god-tool commands in. The simulation never calls back.
 */
public final class GodGame extends ApplicationAdapter {

    private final long seed;

    private Simulation simulation;
    private SimClock clock;

    private RtsCamera camera;
    private TerrainRenderer terrainRenderer;
    private TilePicker picker;
    private ModelBatch modelBatch;
    private Environment environment;

    private int brushRadius = 4;

    // Smoke-test hooks. There is no window manager or human in CI, so the only
    // way to prove the renderer actually draws something is to run a fixed
    // number of frames, dump the framebuffer, and exit.
    private int autoExitFrames;
    private String screenshotPath;
    private int frameCounter;

    public GodGame(long seed) {
        this.seed = seed;
    }

    /**
     * Runs {@code frames} frames, optionally writes the last one to
     * {@code pngPath}, then quits. Used for automated verification only.
     */
    public void enableSmokeTest(int frames, String pngPath) {
        this.autoExitFrames = frames;
        this.screenshotPath = pngPath;
    }

    @Override
    public void create() {
        simulation = new Simulation(seed);
        clock = new SimClock();

        camera = new RtsCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        terrainRenderer = new TerrainRenderer(simulation.getWorld());
        picker = new TilePicker();
        modelBatch = new ModelBatch();

        environment = new Environment();
        // Bright ambient plus one strong key light: enough contrast for the
        // stepped terrain to read, without any face going fully black.
        environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.55f, 0.56f, 0.60f, 1f));
        environment.add(new DirectionalLight().set(
            new Color(1.0f, 0.97f, 0.88f, 1f), -0.55f, -0.75f, -0.35f));

        Gdx.input.setInputProcessor(new InputMultiplexer(new WorldInput()));

        // Mesh the whole world before the first frame so the player never sees
        // the map pop in chunk by chunk on startup.
        while (!terrainRenderer.isFullyBuilt()) {
            terrainRenderer.update();
        }
    }

    @Override
    public void render() {
        float delta = Gdx.graphics.getDeltaTime();

        handleHeldKeys(delta);

        int ticks = clock.advance(delta);
        for (int i = 0; i < ticks; i++) {
            simulation.tick();
        }

        terrainRenderer.update();

        Gdx.gl.glClearColor(0.52f, 0.70f, 0.86f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        modelBatch.begin(camera.getCamera());
        terrainRenderer.render(modelBatch, environment);
        modelBatch.end();

        if (autoExitFrames > 0) {
            runSmokeTestFrame();
        }
    }

    private void runSmokeTestFrame() {
        frameCounter++;
        if (frameCounter < autoExitFrames) {
            return;
        }
        if (screenshotPath != null) {
            writeScreenshot(screenshotPath);
        }
        System.out.println("SMOKE_OK frames=" + frameCounter
            + " seed=" + seed
            + " ticks=" + simulation.getTickCount()
            + " chunksBuilt=" + terrainRenderer.isFullyBuilt());
        Gdx.app.exit();
    }

    private void writeScreenshot(String path) {
        int width = Gdx.graphics.getBackBufferWidth();
        int height = Gdx.graphics.getBackBufferHeight();
        Pixmap pixmap = Pixmap.createFromFrameBuffer(0, 0, width, height);
        // The framebuffer arrives bottom-up relative to how PNGs are stored,
        // so flip it row by row before writing or the image comes out upside
        // down - which would make a screenshot actively misleading.
        ByteBuffer pixels = pixmap.getPixels();
        int stride = width * 4;
        byte[] rowA = new byte[stride];
        byte[] rowB = new byte[stride];
        for (int y = 0; y < height / 2; y++) {
            int top = y * stride;
            int bottom = (height - 1 - y) * stride;
            pixels.position(top);
            pixels.get(rowA);
            pixels.position(bottom);
            pixels.get(rowB);
            pixels.position(top);
            pixels.put(rowB);
            pixels.position(bottom);
            pixels.put(rowA);
        }
        pixels.position(0);
        PixmapIO.writePNG(Gdx.files.absolute(path), pixmap);
        pixmap.dispose();
    }

    private void handleHeldKeys(float delta) {
        float forward = 0f;
        float right = 0f;
        if (Gdx.input.isKeyPressed(Input.Keys.W)) {
            forward += 1f;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.S)) {
            forward -= 1f;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.D)) {
            right += 1f;
        }
        if (Gdx.input.isKeyPressed(Input.Keys.A)) {
            right -= 1f;
        }
        camera.panKeys(forward, right, delta);
    }

    @Override
    public void resize(int width, int height) {
        if (width > 0 && height > 0) {
            camera.resize(width, height);
        }
    }

    @Override
    public void dispose() {
        modelBatch.dispose();
        terrainRenderer.dispose();
    }

    /**
     * Mouse and keyboard handling.
     *
     * <p>The brief asked for left-drag to rotate the camera and for god tools to
     * be applied by clicking and dragging on the world - which are the same
     * gesture. Since this is a god game, the bare left button is given to the
     * tool (the thing the player does constantly) and camera rotation moves to
     * the middle button or Alt+left.
     */
    private final class WorldInput extends InputAdapter {

        private int lastX;
        private int lastY;
        private boolean rotating;
        private boolean panning;
        private boolean painting;

        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            lastX = screenX;
            lastY = screenY;

            boolean altHeld = Gdx.input.isKeyPressed(Input.Keys.ALT_LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.ALT_RIGHT);

            if (button == Input.Buttons.MIDDLE || (button == Input.Buttons.LEFT && altHeld)) {
                rotating = true;
                return true;
            }
            if (button == Input.Buttons.RIGHT) {
                panning = true;
                return true;
            }
            if (button == Input.Buttons.LEFT) {
                painting = true;
                applyTool(screenX, screenY);
                return true;
            }
            return false;
        }

        @Override
        public boolean touchDragged(int screenX, int screenY, int pointer) {
            int deltaX = screenX - lastX;
            int deltaY = screenY - lastY;
            lastX = screenX;
            lastY = screenY;

            if (rotating) {
                camera.rotate(deltaX, deltaY);
                return true;
            }
            if (panning) {
                camera.pan(deltaX, deltaY);
                return true;
            }
            if (painting) {
                applyTool(screenX, screenY);
                return true;
            }
            return false;
        }

        @Override
        public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            rotating = false;
            panning = false;
            painting = false;
            return true;
        }

        @Override
        public boolean scrolled(float amountX, float amountY) {
            camera.zoom(amountY);
            return true;
        }

        @Override
        public boolean keyDown(int keycode) {
            switch (keycode) {
                case Input.Keys.LEFT_BRACKET:
                    brushRadius = Terraform.clampRadius(brushRadius - 1);
                    return true;
                case Input.Keys.RIGHT_BRACKET:
                    brushRadius = Terraform.clampRadius(brushRadius + 1);
                    return true;
                case Input.Keys.SPACE:
                    clock.setSpeed(clock.isPaused() ? 1 : 0);
                    return true;
                case Input.Keys.ESCAPE:
                    Gdx.app.exit();
                    return true;
                default:
                    return false;
            }
        }

        /**
         * Phase 1's single tool: raise terrain, or lower it with shift held.
         * The tool palette that selects between all the brushes arrives in
         * phase 2.
         */
        private void applyTool(int screenX, int screenY) {
            if (!picker.pick(camera.getCamera(), simulation.getWorld(), screenX, screenY)) {
                return;
            }
            boolean shiftHeld = Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT)
                || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);

            // Scaled by frame time so holding the brush still deforms at a
            // predictable rate on a fast machine rather than a faster one.
            float strength = SimConfig.TERRAFORM_STRENGTH * Math.min(Gdx.graphics.getDeltaTime() * 12f, 1.5f);
            if (shiftHeld) {
                Terraform.lower(simulation.getWorld(), picker.getTileX(), picker.getTileZ(), brushRadius, strength);
            } else {
                Terraform.raise(simulation.getWorld(), picker.getTileX(), picker.getTileZ(), brushRadius, strength);
            }
        }
    }
}
