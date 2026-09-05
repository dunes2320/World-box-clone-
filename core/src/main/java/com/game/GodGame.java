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
import com.game.ui.Hud;
import com.game.ui.ToolState;
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
    private ToolState toolState;

    private RtsCamera camera;
    private TerrainRenderer terrainRenderer;
    private TilePicker picker;
    private ModelBatch modelBatch;
    private Environment environment;
    private Hud hud;

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
        toolState = new ToolState();

        camera = new RtsCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        terrainRenderer = new TerrainRenderer(simulation.getWorld());
        picker = new TilePicker();
        modelBatch = new ModelBatch();
        hud = new Hud(toolState, clock);

        environment = new Environment();
        // Bright ambient plus one strong key light: enough contrast for the
        // stepped terrain to read, without any face going fully black.
        environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.55f, 0.56f, 0.60f, 1f));
        environment.add(new DirectionalLight().set(
            new Color(1.0f, 0.97f, 0.88f, 1f), -0.55f, -0.75f, -0.35f));

        // The stage goes first so clicks on the palette never reach the world.
        Gdx.input.setInputProcessor(new InputMultiplexer(hud.getStage(), new WorldInput()));

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
        hud.update(simulation, clock, delta);

        Gdx.gl.glClearColor(0.52f, 0.70f, 0.86f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        modelBatch.begin(camera.getCamera());
        terrainRenderer.render(modelBatch, environment);
        modelBatch.end();

        // Depth testing has to come off before the 2D overlay, or panels get
        // z-rejected against terrain that was drawn closer to the camera.
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
        hud.render();

        if (autoExitFrames > 0) {
            runSmokeTestFrame();
        }
    }

    private void runSmokeTestFrame() {
        frameCounter++;
        // Partway through, drive the tool path the way a player would: carve
        // some terrain and select a tile. Without this the smoke screenshot
        // only ever proves that an untouched world renders, and says nothing
        // about whether the brushes or the inspector actually work.
        if (frameCounter == autoExitFrames / 2) {
            runToolDemo();
        }
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

    /** Exercises every terraform brush and the inspector, for the smoke test. */
    private void runToolDemo() {
        var world = simulation.getWorld();
        int centre = SimConfig.WORLD_SIZE / 2;

        // A mountain and a crater, side by side, so both directions are visible.
        for (int i = 0; i < 14; i++) {
            Terraform.raise(world, centre - 14, centre, 7, 0.9f);
            Terraform.lower(world, centre + 14, centre, 7, 0.9f);
        }
        for (int i = 0; i < 12; i++) {
            Terraform.addWater(world, centre + 14, centre, 6, 0.6f);
        }
        Terraform.addForest(world, centre, centre + 20, 9);

        toolState.setTool(ToolState.Tool.RAISE);
        toolState.setRadius(7);
        hud.getInspector().select(centre - 14, centre);

        System.out.println("DEMO_APPLIED"
            + " raisedHeight=" + String.format("%.2f", world.heightAt(centre - 14, centre))
            + " craterHeight=" + String.format("%.2f", world.heightAt(centre + 14, centre))
            + " craterType=" + com.game.sim.TileType.name(world.typeAt(centre + 14, centre)));
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
            hud.resize(width, height);
        }
    }

    @Override
    public void dispose() {
        modelBatch.dispose();
        terrainRenderer.dispose();
        hud.dispose();
    }

    /**
     * Mouse and keyboard handling for the world beneath the HUD.
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
                applyTool(screenX, screenY, true);
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
                applyTool(screenX, screenY, false);
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
                    toolState.setRadius(toolState.getRadius() - 1);
                    return true;
                case Input.Keys.RIGHT_BRACKET:
                    toolState.setRadius(toolState.getRadius() + 1);
                    return true;
                case Input.Keys.SPACE:
                    clock.setSpeed(clock.isPaused() ? 1 : 0);
                    return true;
                case Input.Keys.ESCAPE:
                    // Dismiss the inspector first; only quit once there is
                    // nothing left to back out of, so Escape is never a
                    // surprise one-way trip out of the game.
                    if (hud.getInspector().hasSelection()) {
                        hud.getInspector().clear();
                    } else {
                        Gdx.app.exit();
                    }
                    return true;
                default:
                    return selectToolByNumber(keycode);
            }
        }

        /** Number keys 1-5 arm the tools in palette order. */
        private boolean selectToolByNumber(int keycode) {
            int index = keycode - Input.Keys.NUM_1;
            ToolState.Tool[] tools = ToolState.Tool.values();
            if (index < 0 || index >= tools.length) {
                return false;
            }
            toolState.setTool(tools[index]);
            return true;
        }

        private void applyTool(int screenX, int screenY, boolean initialPress) {
            ToolState.Tool tool = toolState.getTool();
            // Inspect is a discrete pick, not a stroke - dragging across the
            // map should not fire it on every tile in the path.
            if (!initialPress && !tool.isContinuous()) {
                return;
            }
            if (!picker.pick(camera.getCamera(), simulation.getWorld(), screenX, screenY)) {
                return;
            }

            int x = picker.getTileX();
            int z = picker.getTileZ();
            int radius = toolState.getRadius();

            // Scaled by frame time so holding a brush deforms at a predictable
            // rate rather than a faster one on a faster machine.
            float strength = SimConfig.TERRAFORM_STRENGTH
                * Math.min(Gdx.graphics.getDeltaTime() * 12f, 1.5f);

            switch (tool) {
                case INSPECT -> hud.getInspector().select(x, z);
                case RAISE -> Terraform.raise(simulation.getWorld(), x, z, radius, strength);
                case LOWER -> Terraform.lower(simulation.getWorld(), x, z, radius, strength);
                case WATER -> Terraform.addWater(simulation.getWorld(), x, z, radius, strength);
                case FOREST -> Terraform.addForest(simulation.getWorld(), x, z, radius);
                default -> { }
            }
        }
    }
}
