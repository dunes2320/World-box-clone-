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
import com.game.render.EffectRenderer;
import com.game.render.RtsCamera;
import com.game.render.TerrainRenderer;
import com.game.render.TilePicker;
import com.game.render.UnitRenderer;
import com.game.render.VillageRenderer;
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
    private UnitRenderer unitRenderer;
    private VillageRenderer villageRenderer;
    private EffectRenderer effectRenderer;
    private TilePicker picker;
    private ModelBatch modelBatch;
    private Environment environment;
    private Hud hud;

    // Smoke-test hooks. There is no window manager or human in CI, so the only
    // way to prove the renderer actually draws something is to run a fixed
    // number of frames, dump the framebuffer, and exit.
    private int autoExitFrames;
    private String screenshotPath;
    private boolean closeUp;
    private int stressUnits;
    private int fastForwardTicks;
    private boolean forceWar;
    private boolean forceDisasters;
    private boolean firestorm;
    private long rebuildNanos;
    private int rebuildCount;
    private long flameRebuildNanos;
    private int flameRebuildCount;
    private int peakFlames;
    private int frameCounter;
    /** Set when something other than a tick changed the units - a spawn, a cull. */
    private boolean unitsDirty = true;

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

    /** Drops the smoke-test camera down among the units instead of map-wide. */
    public void enableCloseUp() {
        this.closeUp = true;
    }

    /** Spawns this many units in the smoke test, to measure the render cost at scale. */
    public void setStressUnits(int units) {
        this.stressUnits = units;
    }

    /** Runs the simulation forward inside the smoke test, so slow emergent
     * behaviour (villages, territory) has actually happened by screenshot time. */
    public void setFastForwardTicks(int ticks) {
        this.fastForwardTicks = ticks;
    }

    /**
     * Forces one pair into a war during the smoke test. Wars arrive on their
     * own schedule, so without this a screenshot only catches one by luck -
     * and a screenshot that happens to be taken in peacetime proves nothing
     * about whether combat renders.
     */
    public void enableForcedWar() {
        this.forceWar = true;
    }

    /** Unleashes every disaster during the smoke test, for a screenshot of the aftermath. */
    public void enableForcedDisasters() {
        this.forceDisasters = true;
    }

    /** Sets the entire island alight, to measure the worst case for fire. */
    public void enableFirestorm() {
        this.firestorm = true;
    }

    @Override
    public void create() {
        simulation = new Simulation(seed);
        clock = new SimClock();
        toolState = new ToolState();

        camera = new RtsCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        terrainRenderer = new TerrainRenderer(simulation.getWorld(), simulation.getVillages());
        unitRenderer = new UnitRenderer();
        villageRenderer = new VillageRenderer();
        effectRenderer = new EffectRenderer();
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
        // Units move on the fixed tick, not per frame, so rebuilding their
        // geometry every frame would redraw half a million vertices to put
        // everything back exactly where it already was.
        if (ticks > 0 || unitsDirty) {
            long start = System.nanoTime();
            unitRenderer.rebuild(simulation.getWorld(), simulation.getUnits());
            rebuildNanos += System.nanoTime() - start;
            rebuildCount++;
            unitsDirty = false;

            // Villages change far more slowly than units; rebuild their markers
            // only when the set actually differs from what is on screen.
            int liveVillages = simulation.getVillages().getLiveCount();
            if (liveVillages != villageRenderer.getVisibleVillages()) {
                villageRenderer.rebuild(simulation.getWorld(), simulation.getVillages());
            }
        }

        // Flames rebuild off the fire's own generation counter rather than the
        // tick, so a still world with a fire in it does the work and a still
        // world without one does none.
        long flameStart = System.nanoTime();
        if (effectRenderer.rebuildIfChanged(
            simulation.getWorld(), simulation.getDisasters().getFireGeneration())) {
            flameRebuildNanos += System.nanoTime() - flameStart;
            flameRebuildCount++;
            peakFlames = Math.max(peakFlames, effectRenderer.getVisibleFlames());
        }

        terrainRenderer.update();
        hud.update(simulation, clock, delta);

        Gdx.gl.glClearColor(0.52f, 0.70f, 0.86f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        modelBatch.begin(camera.getCamera());
        terrainRenderer.render(modelBatch, environment);
        unitRenderer.render(modelBatch, environment);
        villageRenderer.render(modelBatch, environment);
        effectRenderer.render(modelBatch, environment);
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
            + " units=" + simulation.getUnits().getLiveCount()
            + " drawnUnits=" + unitRenderer.getVisibleUnits()
            + " villages=" + simulation.getVillages().getLiveCount()
            + " warsDeclared=" + simulation.getRelations().getWarsDeclared()
            + " warDead=" + simulation.getWarCasualties()
            + " fighting=" + countFighting()
            + " burning=" + simulation.getDisasters().getBurningTiles()
            + " flames=" + effectRenderer.getVisibleFlames()
            + " sick=" + simulation.getDisasters().getInfectedUnits()
            + " chunksBuilt=" + terrainRenderer.isFullyBuilt()
            + " peakFlames=" + peakFlames
            + String.format(" avgUnitRebuildMs=%.3f", rebuildCount == 0 ? 0.0
                : rebuildNanos / 1e6 / rebuildCount)
            + String.format(" avgFlameRebuildMs=%.3f", flameRebuildCount == 0 ? 0.0
                : flameRebuildNanos / 1e6 / flameRebuildCount));
        Gdx.app.exit();
    }

    /**
     * Declares a war and stages a battle for it, so a smoke screenshot is
     * evidence rather than luck.
     *
     * <p>Declaring the war alone is not enough. Two species at war across a
     * 128x128 map only actually fight where they happen to meet, so a capture
     * taken at an arbitrary frame kept landing on a lull - measured at zero
     * units fighting on a run whose war was very much still on. Putting two
     * crowds on the same ground guarantees there is a battle to photograph.
     */
    private void forceBattle(com.game.sim.World world) {
        simulation.getRelations().set(com.game.sim.Species.HUMAN, com.game.sim.Species.ORC, -1f);
        for (int i = 0; i < SimConfig.RELATION_UPDATE_INTERVAL; i++) {
            simulation.tick();
        }

        int centre = SimConfig.WORLD_SIZE / 2;
        for (int radius = 0; radius < centre; radius++) {
            int x = centre + radius;
            if (x < world.size && com.game.sim.TileType.isWalkable(world.typeAt(x, centre))) {
                simulation.spawnUnits(x, centre, 3, com.game.sim.Species.HUMAN, 40);
                simulation.spawnUnits(x, centre, 3, com.game.sim.Species.ORC, 40);
                break;
            }
        }
        // Long enough for the combat pass to mark them, short enough that the
        // battle is still in progress when the frame is captured.
        for (int i = 0; i < 6; i++) {
            simulation.tick();
        }
    }

    /**
     * Drops all six disasters on populated ground and lets them run.
     *
     * <p>Spread across separate spots rather than stacked, so the screenshot
     * shows a crater, a burning forest and a drowned coast at once instead of
     * one very thoroughly destroyed tile.
     */
    private void forceDisasters(com.game.sim.World world) {
        com.game.sim.Disaster[] kinds = com.game.sim.Disaster.values();
        int placed = 0;
        // Aim at ground somebody actually lives on: an unpopulated disaster
        // proves the terrain edits work but says nothing about the casualties.
        var units = simulation.getUnits();
        for (int i = 0; i < units.getHighWater() && placed < kinds.length; i += 17) {
            if (!units.alive[i]) {
                continue;
            }
            int x = (int) units.x[i];
            int z = (int) units.z[i];
            if (!world.inBounds(x, z)) {
                continue;
            }
            simulation.strike(kinds[placed++], x, z, 7);
        }

        // Let the fire spread and the plague take hold before the capture.
        for (int i = 0; i < 40; i++) {
            simulation.tick();
        }
        System.out.println("DEMO_DISASTERS struck=" + placed
            + " burning=" + simulation.getDisasters().getBurningTiles()
            + " sick=" + simulation.getDisasters().getInfectedUnits());
    }

    /**
     * Forests the whole island and sets light to all of it - the worst case the
     * fire code and the flame renderer will ever see.
     *
     * <p>A natural map only has a few hundred forest tiles, so lighting one is
     * no test of anything. This turns every scrap of open ground into fuel
     * first, which is how "holds frame rate under a full-map fire" gets
     * measured rather than assumed.
     */
    private void forceFirestorm(com.game.sim.World world) {
        int step = SimConfig.MAX_BRUSH_RADIUS * 2;
        for (int z = 0; z < world.size; z += step) {
            for (int x = 0; x < world.size; x += step) {
                Terraform.addForest(world, x, z, SimConfig.MAX_BRUSH_RADIUS);
            }
        }
        for (int z = 0; z < world.size; z += step) {
            for (int x = 0; x < world.size; x += step) {
                simulation.strike(com.game.sim.Disaster.FIRE, x, z, SimConfig.MAX_BRUSH_RADIUS);
            }
        }
        System.out.println("DEMO_FIRESTORM burning=" + simulation.getDisasters().getBurningTiles());
    }

    /** Units currently in a fight - the number the combat tint is drawn for. */
    private int countFighting() {
        var units = simulation.getUnits();
        int fighting = 0;
        for (int i = 0; i < units.getHighWater(); i++) {
            if (units.alive[i] && units.state[i] == com.game.sim.Units.STATE_FIGHT) {
                fighting++;
            }
        }
        return fighting;
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

        // Seed all four species so the stats panel and the per-species colours
        // are both evidenced by the screenshot.
        int perSpecies = Math.max(1, (stressUnits > 0 ? stressUnits : 880) / com.game.sim.Species.COUNT);
        int spawned = 0;
        for (byte species = 0; species < com.game.sim.Species.COUNT; species++) {
            spawned += simulation.spawnUnits(centre - 20 + species * 13, centre - 18,
                stressUnits > 0 ? 22 : 11, species, perSpecies);
        }
        unitsDirty = true;

        toolState.setTool(ToolState.Tool.SPAWN);
        toolState.setRadius(7);
        System.out.println("DEMO_SPAWNED units=" + spawned);

        // Villages take thousands of ticks to establish. Running the sim
        // forward here lets one screenshot show settled territory rather than
        // a freshly seeded crowd standing around.
        for (int i = 0; i < fastForwardTicks; i++) {
            simulation.tick();
        }
        if (forceWar) {
            forceBattle(world);
        }
        if (forceDisasters) {
            forceDisasters(world);
        }
        if (firestorm) {
            forceFirestorm(world);
        }
        unitsDirty = true;
        villageRenderer.rebuild(world, simulation.getVillages());

        // Select a settled tile so the inspector shows a real owner.
        int inspectX = centre;
        int inspectZ = centre;
        for (int i = 0; i < world.tileCount; i++) {
            if (world.ownerVillage[i] != com.game.sim.World.NO_OWNER) {
                inspectX = i % world.size;
                inspectZ = i / world.size;
                break;
            }
        }
        hud.getInspector().select(inspectX, inspectZ);
        if (closeUp) {
            // Point the close-up at a battle if there is one. Aiming it at an
            // arbitrary settled tile made the screenshot prove nothing about
            // combat: the fighting is a small fraction of the map, and the
            // camera kept landing on quiet coastline.
            int focusX = inspectX;
            int focusZ = inspectZ;
            var units = simulation.getUnits();
            for (int i = 0; i < units.getHighWater(); i++) {
                if (units.alive[i] && units.state[i] == com.game.sim.Units.STATE_FIGHT) {
                    focusX = (int) units.x[i];
                    focusZ = (int) units.z[i];
                    break;
                }
            }
            camera.focusOn(focusX, focusZ, 34f);
        }

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
        unitRenderer.dispose();
        villageRenderer.dispose();
        effectRenderer.dispose();
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
                    return selectToolByKey(keycode);
            }
        }

        /**
         * Number keys 1-6 arm the shaping tools in palette order; letters arm
         * the disasters.
         *
         * <p>The disasters get letters rather than continuing the numbers
         * because twelve tools do not fit on the number row without running
         * into keys nobody would guess. M, L, F, Q, V and P at least name the
         * thing they drop, and none of them collide with the WASD pan.
         */
        private boolean selectToolByKey(int keycode) {
            ToolState.Tool disaster = switch (keycode) {
                case Input.Keys.M -> ToolState.Tool.METEOR;
                case Input.Keys.L -> ToolState.Tool.LIGHTNING;
                case Input.Keys.F -> ToolState.Tool.FIRE;
                case Input.Keys.Q -> ToolState.Tool.QUAKE;
                case Input.Keys.V -> ToolState.Tool.FLOOD;
                case Input.Keys.P -> ToolState.Tool.PLAGUE;
                default -> null;
            };
            if (disaster != null) {
                toolState.setTool(disaster);
                return true;
            }

            int index = keycode - Input.Keys.NUM_1;
            ToolState.Tool[] tools = ToolState.Tool.values();
            if (index < 0 || index >= tools.length || tools[index].isDisaster()) {
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
                case SPAWN -> {
                    simulation.spawnUnits(x, z, radius,
                        toolState.getSpawnSpecies(), toolState.spawnCountForRadius());
                    unitsDirty = true;
                }
                default -> {
                    if (tool.isDisaster()) {
                        simulation.strike(tool.disaster(), x, z, radius);
                        unitsDirty = true;
                    }
                }
            }

            // Terraforming can drown or bury whoever was standing there, so
            // anything left somewhere impossible is removed straight away
            // rather than waiting for a tick that may be paused. Disasters
            // move the ground too, and are exactly the case where the player is
            // watching for the consequences.
            if (tool.isDisaster() || tool == ToolState.Tool.WATER
                || tool == ToolState.Tool.RAISE || tool == ToolState.Tool.LOWER) {
                if (simulation.cullStrandedUnits() > 0) {
                    unitsDirty = true;
                }
            }
        }
    }
}
