package com.worldbox;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.system.AppSettings;
import com.simsilica.lemur.GuiGlobals;
import com.worldbox.config.Config;
import com.worldbox.render.EntityRenderer;
import com.worldbox.render.NationColorLookup;
import com.worldbox.render.Picking;
import com.worldbox.render.VoxelChunkRenderer;
import com.worldbox.save.SaveManager;
import com.worldbox.sim.Army;
import com.worldbox.sim.GameState;
import com.worldbox.sim.Nation;
import com.worldbox.sim.Simulation;
import com.worldbox.tools.GodTools;
import com.worldbox.ui.GameHud;
import com.worldbox.ui.HudContext;

/** Top-level jME application: scene, camera, input, HUD and the fixed-step
 * simulation loop all live here. */
public class GameApp extends SimpleApplication implements HudContext, ActionListener, AnalogListener {

  private final int screenWidth, screenHeight;

  private GameState state;
  private VoxelChunkRenderer voxelRenderer;
  private EntityRenderer entityRenderer;
  private GameHud hud;
  private DirectionalLight sun;
  /** Follows the camera every frame (see simpleUpdate) since a directional
   * light has no real world position of its own - the filter needs a
   * screen-space anchor to radiate its light shafts from, so this is
   * synthesized as "far away, opposite the sun's direction, relative to
   * wherever the camera currently is." */
  private com.jme3.post.filters.LightScatteringFilter lightScatteringFilter;

  private String tool = "select";
  private int brushSize = 3;
  private GameState.Selection selection;
  private int speed = 1;

  private double simTime;
  /** A separate clock from simTime, driving every purely cosmetic,
   * continuous-real-time animation (water shimmer, fire flicker, smoke,
   * rain, clouds, sparkles, and a walking villager's step-bounce/arm-
   * swing) - simTime itself can't be paused (it also paces the sim-tick
   * schedule, see maybeTick/alpha below), but this only ever advances
   * while speed > 0, so pausing the game (speed=0) now actually freezes
   * every ambient animation instead of only stopping the simulation
   * while the world visibly keeps flickering/rippling/swinging around it. */
  private double animClock;
  private double lastTickTime;
  private boolean leftDown, rotating, panning;
  private boolean moveFwd, moveBack, moveLeft, moveRight;
  private Picking.CellHit lastCell;

  private final Vector3f camTarget = new Vector3f();
  private float camYaw = 0.5f, camPitch = 0.75f, camDistance = 55f, camDistanceTarget = 55f;
  /** Player-configurable scroll-wheel zoom speed multiplier, set from the
   * settings panel. */
  private float zoomSensitivity = 1.0f;

  // headless verification mode: F12, or -Dworldbox.testMode=true to
  // auto-screenshot and exit (used by automated smoke tests / CI, no effect
  // on normal play)
  private ScreenshotAppState screenshotState;
  private boolean testMode;
  private double testModeExitAt = -1;
  private int combatShotsTaken = 0;
  private double lastCombatCheck = -999;

  public GameApp(int width, int height) {
    this.screenWidth = width;
    this.screenHeight = height;
  }

  @Override
  public void simpleInitApp() {
    setDisplayFps(false);
    setDisplayStatView(false);
    flyCam.setEnabled(false);
    inputManager.setCursorVisible(true);

    state = Simulation.createInitialState();
    camTarget.set(Config.COLS / 2f, 0, Config.ROWS / 2f);

    viewPort.setBackgroundColor(new ColorRGBA(0.56f, 0.78f, 0.91f, 1f));
    // Lighting.j3md just adds ambient+diffuse and clamps at 1.0 with no
    // tonemapping, so a bright ambient+directional combo saturates every
    // surface to white regardless of its own color - that headroom is why
    // this stays modest rather than a naive "just make it brighter". The
    // actual fix for "lighting reads as flat" is contrast, not brightness:
    // pulling the ambient floor down (0.4 -> 0.3) leaves much more of the
    // 0-1 range for the directional term to actually carve out, so a lit
    // block and a shadowed block read as visibly different instead of
    // both sitting close to the same washed-out ambient-dominated value.
    //
    // That went too far the other way for a face with NO direct light at
    // all (pointing away from the sun): its color is ambient-only to
    // begin with, and once the cast-shadow modulate and SSAO's occlusion
    // term both multiply that same already-dim value further, a small
    // isolated block's away-facing side (a dug hole's rim, a lone beach
    // mound) crushed all the way to true black - reading as "the water
    // just isn't there" rather than a shaded face. Splitting the
    // difference (0.3 -> 0.34) keeps most of that contrast while giving
    // the darkest, ambient-only faces enough of a floor that stacking
    // shadow and AO on top of them can no longer reach actual zero.
    rootNode.addLight(new AmbientLight(new ColorRGBA(0.30f, 0.32f, 0.36f, 1f)));
    sun = new DirectionalLight();
    // a lower, more grazing angle than before (-1 on Y was near-noon
    // overhead) throws longer, more dramatic shadows across the terrain -
    // the single biggest cue that reads as "a shader pack is on" in every
    // Minecraft shader screenshot, where the sun is rarely straight up.
    sun.setDirection(new Vector3f(-0.55f, -0.72f, -0.45f).normalizeLocal());
    sun.setColor(new ColorRGBA(0.95f, 0.86f, 0.68f, 1f));
    rootNode.addLight(sun);

    rootNode.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.CastAndReceive);
    // was 2048/3 splits - three full extra scene passes at that resolution
    // was the single heaviest thing in this filter stack and the main
    // reason frame rate tanked once a settlement's worth of geometry was
    // on screen. 1024/2 still reads as real directional shadow at this
    // camera's usual distance, for close to a quarter of the render cost.
    com.jme3.shadow.DirectionalLightShadowRenderer shadowRenderer =
        new com.jme3.shadow.DirectionalLightShadowRenderer(assetManager, 1024, 2);
    shadowRenderer.setLight(sun);
    // was 0.72 - strong enough that a small isolated block (a dug hole's
    // rim, a lone beach mound) facing away from the sun could go solid
    // black once its own cast shadow stacked on top of already having no
    // direct light: Blend Modulate multiplies the existing color by
    // (1 - intensity), so at 0.72 even a lit neighboring face's spill
    // barely survived, and a face with none to begin with crushed to
    // near-zero. That read as "the water just isn't there" rather than a
    // shading effect. Lower still reads as real directional shadow
    // (this scene's whole "shader pack" look leans on long dramatic
    // shadows on purpose) without being able to push anything to true black.
    shadowRenderer.setShadowIntensity(0.3f);
    shadowRenderer.setEdgeFilteringMode(com.jme3.shadow.EdgeFilteringMode.Bilinear);
    viewPort.addProcessor(shadowRenderer);

    // Genre peers this style draws from (Townscaper, Kingdoms and Castles,
    // WorldBox's own newer look) all lean on the same two cheap tricks to
    // read as "a considered art style" rather than "flat untextured
    // blocks": soft contact shadows in every crevice (ambient occlusion),
    // and a gentle glow on the brightest surfaces instead of a hard clip
    // at white. Neither needs any actual texture work, and both are cheap
    // screen-space post effects.
    com.jme3.post.FilterPostProcessor fpp = new com.jme3.post.FilterPostProcessor(assetManager);
    // intensity was 8.0 - jME's own SSAOFilter default is ~1.2, so this
    // was nearly 7x stronger than a normal AO pass and crushed every
    // block seam/crevice to near-black instead of a soft contact shadow.
    // Bias bumped up too, to soften self-shadowing on flat faces.
    com.jme3.post.ssao.SSAOFilter ssao = new com.jme3.post.ssao.SSAOFilter(3.2f, 1.4f, 0.2f, 0.15f);
    // approximates surface normals from the depth buffer instead of
    // sampling a separate normal buffer - a real cost cut for a filter
    // that's already the most expensive thing running every frame, at a
    // softness difference too small to notice at this camera's distance.
    ssao.setApproximateNormals(true);
    fpp.addFilter(ssao);

    // light shafts through the trees/hills toward the sun - the other
    // instantly-recognizable "shader pack" cue alongside long shadows.
    // Kept modest (low light density, few samples - was 40, halved again
    // since this is a purely decorative full-screen pass and the extra
    // samples bought very little visible smoothness over this one) since
    // this camera looks down at the world far more than it looks toward
    // the horizon, so the shafts should read as a soft glow near the sun
    // rather than a heavy, distracting streak across the whole screen.
    lightScatteringFilter = new com.jme3.post.filters.LightScatteringFilter();
    lightScatteringFilter.setLightDensity(0.8f);
    lightScatteringFilter.setNbSamples(16);
    lightScatteringFilter.setBlurStart(0.05f);
    lightScatteringFilter.setBlurWidth(0.6f);
    fpp.addFilter(lightScatteringFilter);

    com.jme3.post.filters.BloomFilter bloom = new com.jme3.post.filters.BloomFilter(com.jme3.post.filters.BloomFilter.GlowMode.Scene);
    bloom.setBloomIntensity(0.9f);
    bloom.setExposurePower(4.2f);
    bloom.setExposureCutOff(0.25f);
    bloom.setBlurScale(1.1f);
    fpp.addFilter(bloom);

    // a soft, sky-matched haze on distant terrain - real depth instead of
    // the map's far edge just clipping into flat color, and it also
    // softens the harder edges of long-distance voxel geometry the same
    // way a shader pack's volumetric fog does.
    com.jme3.post.filters.FogFilter fog = new com.jme3.post.filters.FogFilter();
    fog.setFogColor(new ColorRGBA(0.62f, 0.8f, 0.92f, 1f));
    fog.setFogDistance(190f);
    fog.setFogDensity(1.1f);
    fpp.addFilter(fog);

    // filmic highlight rolloff - Lighting.j3md has no tonemapping of its
    // own (see the ambient-light comment above), so a bright bloom source
    // still clips hard to flat white right at its core; this rounds that
    // off into a softer, HDR-glow-like falloff instead.
    com.jme3.post.filters.ToneMapFilter toneMap = new com.jme3.post.filters.ToneMapFilter();
    toneMap.setWhitePoint(new Vector3f(11f, 11f, 11f));
    fpp.addFilter(toneMap);

    fpp.addFilter(new com.jme3.post.filters.FXAAFilter());
    viewPort.addProcessor(fpp);

    NationColorLookup nationColors = new NationColorLookup() {
      @Override public ColorRGBA colorFor(int nationId) { return nationColorFor(nationId); }
      @Override public ColorRGBA borderColorFor(int nationId) { return nationBorderColorFor(nationId); }
    };
    voxelRenderer = new VoxelChunkRenderer(state.voxels, state.grid, assetManager, nationColors);
    voxelRenderer.rebuildAll();
    rootNode.attachChild(voxelRenderer.solidNode);
    rootNode.attachChild(voxelRenderer.waterNode);
    // a wobbling shadow cast through translucent water looks broken more
    // often than it looks good - keep water shadow-free
    voxelRenderer.waterNode.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);

    entityRenderer = new EntityRenderer(rootNode, assetManager, state.grid, nationColors);
    entityRenderer.rebuildStatics(state);

    GuiGlobals.initialize(this);
    hud = new GameHud(guiNode, assetManager, screenWidth, screenHeight, this);

    setupInput();
    camTarget.y = terrainHeightAt(camTarget.x, camTarget.z);
    updateCamera(1f);

    screenshotState = new ScreenshotAppState(System.getProperty("java.io.tmpdir") + "/", "worldbox");
    stateManager.attach(screenshotState);

    testMode = "true".equals(System.getProperty("worldbox.testMode"));
  }

  private ColorRGBA nationColorFor(int nationId) {
    Nation n = state.nations.get(nationId);
    if (n == null) return null;
    return intToColor(n.color);
  }

  private ColorRGBA nationBorderColorFor(int nationId) {
    Nation n = state.nations.get(nationId);
    if (n == null) return null;
    return intToColor(n.borderColor);
  }

  private static ColorRGBA intToColor(int rgb) {
    return new ColorRGBA(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f);
  }

  private void setupInput() {
    // SimpleApplication binds ESCAPE to its own built-in "quit immediately"
    // action by default (INPUT_MAPPING_EXIT) - that mapping was still live
    // alongside the custom "Escape" handling below, so every Escape press
    // closed a popup (or opened Settings) AND quit the app in the same
    // keystroke. Removing the default mapping leaves Escape entirely to
    // the close-popup/open-Settings-with-its-own-Quit-button handling.
    inputManager.deleteMapping(INPUT_MAPPING_EXIT);
    inputManager.addMapping("Paint", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
    inputManager.addMapping("RotateCam", new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
    inputManager.addMapping("PanCam", new MouseButtonTrigger(MouseInput.BUTTON_MIDDLE));
    inputManager.addMapping("MouseXPos", new MouseAxisTrigger(MouseInput.AXIS_X, false));
    inputManager.addMapping("MouseXNeg", new MouseAxisTrigger(MouseInput.AXIS_X, true));
    inputManager.addMapping("MouseYPos", new MouseAxisTrigger(MouseInput.AXIS_Y, false));
    inputManager.addMapping("MouseYNeg", new MouseAxisTrigger(MouseInput.AXIS_Y, true));
    inputManager.addMapping("WheelUp", new MouseAxisTrigger(MouseInput.AXIS_WHEEL, false));
    inputManager.addMapping("WheelDown", new MouseAxisTrigger(MouseInput.AXIS_WHEEL, true));
    inputManager.addMapping("MoveForward", new KeyTrigger(KeyInput.KEY_W), new KeyTrigger(KeyInput.KEY_UP));
    inputManager.addMapping("MoveBack", new KeyTrigger(KeyInput.KEY_S), new KeyTrigger(KeyInput.KEY_DOWN));
    inputManager.addMapping("MoveLeft", new KeyTrigger(KeyInput.KEY_A), new KeyTrigger(KeyInput.KEY_LEFT));
    inputManager.addMapping("MoveRight", new KeyTrigger(KeyInput.KEY_D), new KeyTrigger(KeyInput.KEY_RIGHT));
    inputManager.addMapping("ToggleFullscreen", new KeyTrigger(KeyInput.KEY_F11));
    inputManager.addMapping("Escape", new KeyTrigger(KeyInput.KEY_ESCAPE));
    inputManager.addListener(this, "Paint", "RotateCam", "PanCam",
        "MouseXPos", "MouseXNeg", "MouseYPos", "MouseYNeg", "WheelUp", "WheelDown",
        "MoveForward", "MoveBack", "MoveLeft", "MoveRight", "ToggleFullscreen", "Escape");
  }

  /** F11: toggles between the windowed resolution and exclusive fullscreen
   * at the desktop's native resolution. The HUD/camera keep working
   * unchanged since it re-applies the same logical layout after restart. */
  private void toggleFullscreen() {
    try {
      AppSettings s = new AppSettings(true);
      s.copyFrom(settings);
      if (s.isFullscreen()) {
        s.setResolution(screenWidth, screenHeight);
        s.setFullscreen(false);
      } else {
        java.awt.DisplayMode dm = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getDefaultScreenDevice().getDisplayMode();
        s.setResolution(dm.getWidth(), dm.getHeight());
        s.setFullscreen(true);
      }
      setSettings(s);
      restart();
    } catch (Exception e) {
      System.err.println("Fullscreen toggle failed: " + e.getMessage());
    }
  }

  @Override
  public void onAction(String name, boolean isPressed, float tpf) {
    switch (name) {
      case "RotateCam": rotating = isPressed; break;
      case "PanCam": panning = isPressed; break;
      case "Paint":
        leftDown = isPressed;
        if (isPressed) { lastCell = null; handlePick(); }
        break;
      case "MoveForward": moveFwd = isPressed; break;
      case "MoveBack": moveBack = isPressed; break;
      case "MoveLeft": moveLeft = isPressed; break;
      case "MoveRight": moveRight = isPressed; break;
      case "ToggleFullscreen": if (isPressed) toggleFullscreen(); break;
      case "Escape": if (isPressed) hud.handleEscape(); break;
      default: break;
    }
  }

  @Override
  public void onAnalog(String name, float value, float tpf) {
    if (rotating) {
      if (name.equals("MouseXPos")) camYaw -= value * 3f;
      else if (name.equals("MouseXNeg")) camYaw += value * 3f;
      else if (name.equals("MouseYPos")) camPitch = clamp(camPitch + value * 3f, 0.15f, 1.45f);
      else if (name.equals("MouseYNeg")) camPitch = clamp(camPitch - value * 3f, 0.15f, 1.45f);
    }
    if (panning) {
      Vector3f fwd = cam.getDirection().clone().setY(0).normalizeLocal();
      Vector3f right = cam.getLeft().negate().setY(0).normalizeLocal();
      float panSpeed = camDistance * 0.9f;
      if (name.equals("MouseXPos")) camTarget.addLocal(right.mult(-value * panSpeed));
      else if (name.equals("MouseXNeg")) camTarget.addLocal(right.mult(value * panSpeed));
      else if (name.equals("MouseYPos")) camTarget.addLocal(fwd.mult(value * panSpeed));
      else if (name.equals("MouseYNeg")) camTarget.addLocal(fwd.mult(-value * panSpeed));
    }
    // wheel input only nudges a target distance - updateCamera() eases
    // camDistance toward it each frame, turning discrete wheel notches into
    // a smooth scroll instead of an instant jump
    if (name.equals("WheelUp")) camDistanceTarget = clamp(camDistanceTarget - value * 7f * zoomSensitivity, 8f, 140f);
    else if (name.equals("WheelDown")) camDistanceTarget = clamp(camDistanceTarget + value * 7f * zoomSensitivity, 8f, 140f);
  }

  private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

  private void applyKeyboardMovement(float tpf) {
    if (!moveFwd && !moveBack && !moveLeft && !moveRight) return;
    Vector3f fwd = cam.getDirection().clone().setY(0);
    if (fwd.lengthSquared() > 1e-6f) fwd.normalizeLocal();
    Vector3f right = cam.getLeft().negate().setY(0);
    if (right.lengthSquared() > 1e-6f) right.normalizeLocal();
    float camSpeed = (18f + camDistance) * tpf;
    if (moveFwd) camTarget.addLocal(fwd.mult(camSpeed));
    if (moveBack) camTarget.addLocal(fwd.mult(-camSpeed));
    if (moveRight) camTarget.addLocal(right.mult(camSpeed));
    if (moveLeft) camTarget.addLocal(right.mult(-camSpeed));
  }

  private float terrainHeightAt(float wx, float wz) {
    int gx = (int) Math.max(0, Math.min(Config.COLS - 1, Math.floor(wx)));
    int gz = (int) Math.max(0, Math.min(Config.ROWS - 1, Math.floor(wz)));
    return state.grid.height[state.grid.idx(gx, gz)];
  }

  private void updateCamera(float tpf) {
    camTarget.x = clamp(camTarget.x, -15f, Config.COLS + 15f);
    camTarget.z = clamp(camTarget.z, -15f, Config.ROWS + 15f);

    // ease toward the wheel's target distance rather than snapping to it
    camDistance += (camDistanceTarget - camDistance) * Math.min(1f, tpf * 10f);

    // keep the orbit pivot glued to the ground under it, not a stale
    // height from wherever the camera happened to start - otherwise
    // zooming in over a hill drops the camera below the terrain surface
    camTarget.y += (terrainHeightAt(camTarget.x, camTarget.z) - camTarget.y) * Math.min(1f, tpf * 6f);

    float x = camDistance * FastMath.cos(camPitch) * FastMath.sin(camYaw);
    float y = camDistance * FastMath.sin(camPitch);
    float z = camDistance * FastMath.cos(camPitch) * FastMath.cos(camYaw);
    Vector3f camPos = camTarget.add(x, y, z);

    // belt and suspenders: never let the camera itself end up under the
    // ground it's flying over, no matter how close/shallow the angle
    float minY = terrainHeightAt(camPos.x, camPos.z) + 1.2f;
    if (camPos.y < minY) camPos.y = minY;

    cam.setLocation(camPos);
    cam.lookAt(camTarget, Vector3f.UNIT_Y);
  }

  private void handlePick() {
    Vector2f cursor = inputManager.getCursorPosition();
    if (hud.isOverUi(cursor.x, cursor.y)) return;

    if (tool.equals("select")) {
      Integer sid = Picking.pickPoolId(cam, entityRenderer.getSettlementsNode(), cursor, "settlementId");
      if (sid != null) { setSelection(new GameState.Selection("settlement", sid)); return; }
      // a soldier is a real, specific person drawn from the population
      // (see Military.raiseArmy) - rendered through the exact same
      // humansNode pool as any civilian (see EntityRenderer.updateHumans'
      // soldier branch), so clicking one already opens their own info
      // panel here, same as any civilian, with no separate lookup needed.
      Integer hid = Picking.pickPoolId(cam, entityRenderer.getHumansNode(), cursor, "humanId");
      if (hid != null) { setSelection(new GameState.Selection("human", hid)); return; }
      // nothing specific under the cursor - if it's inside a nation's
      // territory, clicking anywhere on the ground pulls up that nation
      Picking.CellHit territoryCell = Picking.pickTerrainCell(cam, voxelRenderer.solidNode, state.grid, cursor);
      if (territoryCell != null) {
        int owner = state.grid.ownerNation[state.grid.idx(territoryCell.x, territoryCell.z)];
        if (owner >= 0) { setSelection(new GameState.Selection("nation", owner)); return; }
      }
      setSelection(null);
      return;
    }

    Picking.CellHit cell = Picking.pickTerrainCell(cam, voxelRenderer.solidNode, state.grid, cursor);
    if (cell == null) return;
    lastCell = cell;
    GodTools.apply(state, tool, cell.x, cell.z, brushSize);
  }

  // Real wall-clock frame time (this call to the next one) - distinct
  // from PROF's avgTickMs/avgRebuildMs, which only measure sim-tick and
  // statics-rebuild CPU cost and say nothing about actual render cost
  // (draw calls, geometry count). This is what "will doubling the
  // per-person geometry count tank the frame rate" actually has to be
  // measured against, not guessed at.
  private long lastFrameNanos = 0;
  private double frameTimeAccumMs = 0;
  private int frameCount = 0;
  private double lastFpsLogAt = 0;

  private void profFrame() {
    long now = System.nanoTime();
    if (lastFrameNanos != 0) {
      frameTimeAccumMs += (now - lastFrameNanos) / 1e6;
      frameCount++;
    }
    lastFrameNanos = now;
    if (testMode && simTime - lastFpsLogAt >= 5.0 && frameCount > 0) {
      lastFpsLogAt = simTime;
      double avgMs = frameTimeAccumMs / frameCount;
      System.out.println(String.format("PROF_FPS avgFrameMs=%.3f fps=%.1f frames=%d humans=%d",
          avgMs, 1000.0 / avgMs, frameCount, state.humans.size()));
      frameTimeAccumMs = 0; frameCount = 0;
    }
  }

  @Override
  public void simpleUpdate(float tpf) {
    profFrame();
    simTime += tpf;
    if (speed > 0) animClock += tpf;
    maybeTick();
    applyKeyboardMovement(tpf);
    updateCamera(tpf);
    // the god-ray filter needs a screen-space anchor for its light shafts;
    // a directional light has no real position, so this fakes one far
    // away opposite the sun's direction, relative to the camera's current
    // spot, so the shafts stay correctly aimed as the camera moves/zooms
    lightScatteringFilter.setLightPosition(cam.getLocation().subtract(sun.getDirection().mult(400f)));

    if (leftDown && GodTools.CONTINUOUS_TOOLS.contains(tool)) {
      Vector2f cursor = inputManager.getCursorPosition();
      if (!hud.isOverUi(cursor.x, cursor.y)) {
        Picking.CellHit cell = Picking.pickTerrainCell(cam, voxelRenderer.solidNode, state.grid, cursor);
        if (cell != null && (lastCell == null || lastCell.x != cell.x || lastCell.z != cell.z)) {
          lastCell = cell;
          GodTools.apply(state, tool, cell.x, cell.z, brushSize);
        }
      }
    }

    updateWarHoverFlash(tpf);
    voxelRenderer.flushDirty();
    float alpha = speed > 0 ? (float) Math.min(1.0, (simTime - lastTickTime) / (Config.TICK_MS / 1000.0 / speed)) : 1f;
    entityRenderer.update(state, alpha, (float) animClock, camTarget);
    updateSelectionRing();
    updateBrushIndicator();
    updateNationLabel();

    hud.update(tpf, simTime);

    if (testMode) runTestMode();
  }

  // Scripted checkpoints for automated verification (no real mouse/keyboard
  // available under Xvfb). Each entry fires once when simTime crosses it.
  private final java.util.TreeMap<Double, Runnable> testScript = new java.util.TreeMap<>();
  private void runTestMode() {
    double duration = Double.parseDouble(System.getProperty("worldbox.testDuration", "12"));
    if (testModeExitAt < 0) {
      testModeExitAt = duration;
      speed = 4;
      boolean skipDisasters = "true".equals(System.getProperty("worldbox.skipScript"));
      testScript.put(0.3, () -> {
        // find a shoreline cell (sand next to water) for a close-up debug
        // shot, and aim the camera - looking from the land side toward
        // the water side - at the actual shared edge, not just near it,
        // since a top-down or misaimed shot can hide a real gap
        outer:
        for (int y = 0; y < state.grid.rows; y++) {
          for (int x = 0; x < state.grid.cols; x++) {
            int i = state.grid.idx(x, y);
            if (state.grid.terrain[i] != Config.SAND) continue;
            boolean waterEast = x + 1 < state.grid.cols && state.grid.terrain[state.grid.idx(x + 1, y)] == Config.WATER;
            boolean waterSouth = y + 1 < state.grid.rows && state.grid.terrain[state.grid.idx(x, y + 1)] == Config.WATER;
            if (waterEast || waterSouth) {
              // stay on the sand cell itself, not the water cell - the
              // camera's ground-follow logic re-samples terrain height at
              // camTarget's xz every frame, and sampling into the water
              // column pulls the pivot down to the seabed instead of the
              // beach, tilting the shot away from the water surface
              float h = state.grid.height[i];
              camTarget.set(x + 0.5f, h, y + 0.5f);
              camYaw = waterEast ? -FastMath.HALF_PI : FastMath.PI;
              camDistance = camDistanceTarget = 6f;
              camPitch = 0.35f; // low, near player-eye angle - a top-down shot can hide a real vertical gap
              break outer;
            }
          }
        }
      });
      testScript.put(0.5, () -> { tool = "dig"; brushSize = 4; });
      testScript.put(0.8, () -> screenshotState.takeScreenshot());
      testScript.put(1.0, () -> screenshotState.takeScreenshot());
      testScript.put(1.2, () -> tool = "select");
      testScript.put(2.0, () -> {
        if (!skipDisasters) {
          GodTools.apply(state, "monster", Config.COLS / 2, Config.ROWS / 2, 3);
          GodTools.apply(state, "fire", Config.COLS / 2 + 8, Config.ROWS / 2 + 8, 4);
        }
        if (!state.settlements.isEmpty()) {
          int firstId = state.settlements.keySet().iterator().next();
          setSelection(new GameState.Selection("settlement", firstId));
        }
      });
      testScript.put(3.0, () -> screenshotState.takeScreenshot());
      testScript.put(4.0, () -> hud.debugSetPanelMode("nationsList"));
      testScript.put(4.5, () -> screenshotState.takeScreenshot());
      testScript.put(5.0, () -> hud.debugSetPanelMode("market"));
      testScript.put(5.5, () -> screenshotState.takeScreenshot());
      testScript.put(6.5, () -> hud.debugSetPanelMode("settings"));
      testScript.put(7.0, () -> screenshotState.takeScreenshot());
      testScript.put(7.2, () -> hud.debugSetPanelMode("log"));
      testScript.put(7.4, () -> screenshotState.takeScreenshot());
      testScript.put(7.5, () -> hud.debugSetPanelMode(null));
      testScript.put(8.0, () -> {
        if (!state.humans.isEmpty()) setSelection(new GameState.Selection("human", state.humans.get(0).id));
      });
      testScript.put(8.5, () -> screenshotState.takeScreenshot());
      testScript.put(9.0, () -> {
        if (!state.nations.isEmpty()) {
          int firstNationId = state.nations.keySet().iterator().next();
          setSelection(new GameState.Selection("nation", firstNationId));
          Nation n = state.nations.get(firstNationId);
          var capital = n != null ? state.settlements.get(n.capitalSettlementId) : null;
          if (capital != null) {
            float h = state.grid.height[state.grid.idx(capital.x, capital.z)];
            camTarget.set(capital.x + 0.5f, h, capital.z + 0.5f);
            camDistance = camDistanceTarget = 10f;
            camPitch = 0.35f;
          }
        }
      });
      testScript.put(9.5, () -> screenshotState.takeScreenshot());
      testScript.put(10.0, () -> setSelection(null));
      double midway = Math.max(15.0, duration * 0.5);
      int[] graphNationId = {-1};
      testScript.put(midway, () -> {
        if (!state.nations.isEmpty()) {
          int firstNationId = state.nations.keySet().iterator().next();
          graphNationId[0] = firstNationId;
          setSelection(new GameState.Selection("nation", firstNationId));
        }
      });
      testScript.put(midway + 0.5, () -> screenshotState.takeScreenshot());
      testScript.put(midway + 1.0, () -> hud.debugShowGraph("nation", graphNationId[0]));
      testScript.put(midway + 1.5, () -> screenshotState.takeScreenshot());
      testScript.put(midway + 1.8, () -> hud.debugSetGraphMetric("unemployment"));
      testScript.put(midway + 2.0, () -> screenshotState.takeScreenshot());
      testScript.put(midway + 2.2, () -> hud.debugSetGraphMetric("gdp"));
      testScript.put(midway + 2.4, () -> screenshotState.takeScreenshot());
      testScript.put(midway + 2.6, () -> hud.debugSetGraphMetric("currency"));
      testScript.put(midway + 2.8, () -> screenshotState.takeScreenshot());
      testScript.put(midway + 3.0, () -> hud.debugSetGraphMetric("inflation"));
      testScript.put(midway + 3.2, () -> screenshotState.takeScreenshot());
      testScript.put(midway + 3.4, () -> hud.debugShowGraph("world", -1));
      testScript.put(midway + 3.9, () -> screenshotState.takeScreenshot());
      // popups now pause the sim while open (see GameHud.refreshSidePanel) -
      // close this one out or the rest of the script would run against a
      // frozen simulation for the remainder of the test
      testScript.put(midway + 4.4, () -> hud.debugSetPanelMode(null));
      // a screenshot right after closing, with nothing else changed, so a
      // chart that's still stuck on screen (previously: refreshSidePanel's
      // mode==null branch returned early and skipped hiding it) actually
      // shows up in the test output instead of being masked by whatever
      // panel gets opened next
      testScript.put(midway + 4.6, () -> screenshotState.takeScreenshot());
      testScript.put(duration - 4.0, () -> {
        if (!state.settlements.isEmpty()) {
          var s = state.settlements.values().iterator().next();
          int mx = Math.min(Config.COLS - 1, s.x + 6), mz = Math.min(Config.ROWS - 1, s.z);
          int dx = Math.max(0, mx - 6);
          float beforeMeteor = state.grid.height[state.grid.idx(mx, mz)];
          float beforeDig = state.grid.height[state.grid.idx(dx, mz)];
          GodTools.apply(state, "meteor", mx, mz, 3);
          GodTools.apply(state, "dig", dx, mz, 1);
          GodTools.apply(state, "dig", dx, mz, 1);
          float afterDig = state.grid.height[state.grid.idx(dx, mz)];
          GodTools.apply(state, "build", dx, mz, 1);
          float afterBuild = state.grid.height[state.grid.idx(dx, mz)];
          System.out.println("TESTMODE_VOXEL_CHECK meteorHeight=" + beforeMeteor
              + "->" + state.grid.height[state.grid.idx(mx, mz)]
              + " digHeight=" + beforeDig + "->" + afterDig
              + " buildHeight=" + afterDig + "->" + afterBuild);
          float h = state.grid.height[state.grid.idx(mx, mz)];
          camTarget.set(mx, h, mz);
          camDistance = camDistanceTarget = 16f;
          camPitch = 0.5f;
        }
      });
      testScript.put(duration - 3.7, () -> screenshotState.takeScreenshot());
      testScript.put(duration - 3.5, () -> {
        // exercise the save/load round trip end to end: save the live
        // world, then immediately load it straight back and confirm the
        // reload actually put a real world back (not an empty/broken one)
        int humansBefore = state.humans.size(), tickBefore = state.tick;
        boolean saveOk = saveToSlot(5);
        boolean loadOk = loadFromSlot(5);
        System.out.println("TESTMODE_SAVELOAD saveOk=" + saveOk + " loadOk=" + loadOk
            + " humansBefore=" + humansBefore + " humansAfter=" + state.humans.size()
            + " tickBefore=" + tickBefore + " tickAfter=" + state.tick
            + " nationsAfter=" + state.nations.size() + " settlementsAfter=" + state.settlements.size());
        deleteSlot(5);
      });
      testScript.put(duration - 2.0, () -> {
        if (!state.settlements.isEmpty()) {
          com.worldbox.sim.Settlement biggest = null;
          for (var s : state.settlements.values()) {
            if (biggest == null || s.populationCount > biggest.populationCount) biggest = s;
          }
          float h = state.grid.height[state.grid.idx(biggest.x, biggest.z)];
          camTarget.set(biggest.x + 0.5f, h, biggest.z + 0.5f);
        }
        camDistance = camDistanceTarget = 14f;
        camPitch = 0.55f;
      });
      testScript.put(duration - 1.7, () -> screenshotState.takeScreenshot());
      testScript.put(duration - 1.65, () -> {
        // a tight close-up on one specific villager - the settlement-wide
        // shot above is too far out to actually verify per-person detail
        // (skin tone vs. clothing color, hair, gear) at a glance
        if (!state.humans.isEmpty()) {
          com.worldbox.sim.Human h = state.humans.get(0);
          float hh = state.grid.height[state.grid.idx(
              Math.max(0, Math.min(Config.COLS - 1, (int) h.x)),
              Math.max(0, Math.min(Config.ROWS - 1, (int) h.z)))];
          camTarget.set((float) h.x, hh, (float) h.z);
          camDistance = camDistanceTarget = 2.2f;
          camPitch = 0.15f;
        }
      });
      testScript.put(duration - 1.62, () -> screenshotState.takeScreenshot());
      testScript.put(duration - 1.6, () -> {
        int fx = -1, fz = -1;
        search:
        for (int y = 0; y < state.grid.rows; y++) {
          for (int x = 0; x < state.grid.cols; x++) {
            if (state.grid.resource[state.grid.idx(x, y)] == Config.RES_FOREST) { fx = x; fz = y; break search; }
          }
        }
        if (fx >= 0) {
          GodTools.apply(state, "fire", fx, fz, 3);
          entityRenderer.rebuildStatics(state);
          float h = state.grid.height[state.grid.idx(fx, fz)];
          camTarget.set(fx + 0.5f, h, fz + 0.5f);
          camDistance = camDistanceTarget = 12f;
          camPitch = 0.55f;
        }
      });
      testScript.put(duration - 1.2, () -> screenshotState.takeScreenshot());
      testScript.put(duration - 1.0, () -> screenshotState.takeScreenshot());
      testScript.put(duration - 0.3, () -> {
        System.out.println("TESTMODE_FINAL_STATS tick=" + state.tick
            + " humans=" + state.humans.size()
            + " nations=" + state.nations.size()
            + " settlements=" + state.settlements.size()
            + " armies=" + state.armies.size()
            + " monsterAlive=" + (state.monster != null));
      });
    }

    // Wars break out organically at an unpredictable in-game year, so the
    // fixed checkpoints above can't know when to aim at one - this instead
    // polls every couple seconds for a settlement actively falling (best)
    // or an army mid-fight (fallback), swings the camera in close, and
    // queues a screenshot a moment later once the camera's actually
    // settled there. DEBUG/temporary: for capturing combat screenshots on
    // request, not part of the normal smoke-test checklist.
    if ("true".equals(System.getProperty("worldbox.chaseCombat")) && combatShotsTaken < 6
        && simTime - lastCombatCheck > 3.0 && simTime < duration - 1.5) {
      lastCombatCheck = simTime;
      com.worldbox.sim.Settlement fallingCity = null;
      for (var s : state.settlements.values()) {
        if (!s.abandoned && s.siegeProgress > 3) { fallingCity = s; break; }
      }
      Army fightingArmy = null;
      for (Army a : state.armies.values()) {
        if (!a.dead && a.combatFlashTimer > 0) { fightingArmy = a; break; }
      }
      if (fightingArmy == null) {
        // fall back to a marching army, but only one currently, actually
        // at war with its target - a targetSettlementId can linger from a
        // war that's since gone to truce, which used to burn the whole
        // shot budget stalking a harmless peacetime march
        for (Army a : state.armies.values()) {
          if (a.dead || a.targetSettlementId == null) continue;
          var target = state.settlements.get(a.targetSettlementId);
          if (target == null) continue;
          if (!state.diplomacy.getStatus(a.nationId, target.nationId).equals(Config.WAR)) continue;
          fightingArmy = a;
          break;
        }
      }
      if (fallingCity != null) {
        var s = fallingCity;
        float h = state.grid.height[state.grid.idx(s.x, s.z)];
        camTarget.set(s.x + 0.5f, h, s.z + 0.5f);
        camDistance = camDistanceTarget = 10f;
        camPitch = 0.4f;
        combatShotsTaken++;
        System.out.println("TESTMODE_CHASE falling city=" + s.name + " siegeProgress=" + s.siegeProgress);
        testScript.put(simTime + 0.5, () -> screenshotState.takeScreenshot());
      } else if (fightingArmy != null) {
        Army a = fightingArmy;
        int gx = Math.max(0, Math.min(state.grid.cols - 1, (int) a.x));
        int gz = Math.max(0, Math.min(state.grid.rows - 1, (int) a.z));
        float h = state.grid.height[state.grid.idx(gx, gz)];
        camTarget.set((float) a.x, h, (float) a.z);
        camDistance = camDistanceTarget = 9f;
        camPitch = 0.4f;
        combatShotsTaken++;
        System.out.println("TESTMODE_CHASE army id=" + a.id + " state=" + a.state
            + " combatFlashTimer=" + a.combatFlashTimer + " units=" + a.units);
        testScript.put(simTime + 0.5, () -> screenshotState.takeScreenshot());
      }
    }

    for (var entry : new java.util.ArrayList<>(testScript.entrySet())) {
      if (simTime > entry.getKey()) {
        entry.getValue().run();
        testScript.remove(entry.getKey());
      }
    }
    if (simTime > testModeExitAt) stop();
  }

  private long profTickNanos = 0, profRebuildNanos = 0;
  private int profTickCount = 0, profRebuildCount = 0;

  private void maybeTick() {
    if (speed <= 0) { lastTickTime = simTime; return; }
    double interval = Config.TICK_MS / 1000.0 / speed;
    int iterations = 0;
    while (simTime - lastTickTime > interval && iterations < 8) {
      long t0 = System.nanoTime();
      Simulation.tick(state);
      long t1 = System.nanoTime();
      profTickNanos += (t1 - t0);
      profTickCount++;
      if (state.tick % 20 == 0) {
        entityRenderer.rebuildStatics(state);
        long t2 = System.nanoTime();
        profRebuildNanos += (t2 - t1);
        profRebuildCount++;
      }
      if (testMode && state.tick % com.worldbox.util.Calendar.DAYS_PER_YEAR == 0) {
        logEconomicHealth();
        if (profTickCount > 0) {
          System.out.println(String.format(
              "PROF avgTickMs=%.3f avgRebuildMs=%.3f humans=%d nations=%d settlements=%d armies=%d businesses=%d",
              profTickNanos / 1e6 / profTickCount, profRebuildCount > 0 ? profRebuildNanos / 1e6 / profRebuildCount : 0,
              state.humans.size(), state.nations.size(), state.settlements.size(), state.armies.size(),
              state.businesses.size()));
        }
        profTickNanos = 0; profRebuildNanos = 0; profTickCount = 0; profRebuildCount = 0;
      }
      lastTickTime += interval;
      iterations++;
    }
    if (iterations >= 8) lastTickTime = simTime;
  }

  /** Yearly economic snapshot, only printed in test mode - lets a long
   * soak-test run be checked for realistic growth/collapse after the fact
   * from console output instead of only the tiny final-tick summary. */
  private void logEconomicHealth() {
    double years = state.tick / (double) com.worldbox.util.Calendar.DAYS_PER_YEAR;
    int homeless = 0;
    double totalWealth = 0, totalDebt = 0;
    for (com.worldbox.sim.Human h : state.humans) {
      if (!h.hasHouse) homeless++;
      totalWealth += h.wealth;
      totalDebt += h.debt;
    }
    int n = Math.max(1, state.humans.size());
    int atWar = 0, allied = 0;
    for (var r : state.diplomacy.relations.values()) {
      if (com.worldbox.config.Config.WAR.equals(r.status)) atWar++;
      else if (com.worldbox.config.Config.ALLIANCE.equals(r.status)) allied++;
    }
    int discoveries = 0;
    String lastDiscovery = null;
    for (var e : state.eventLog) {
      if ("discovery".equals(e.category)) { discoveries++; lastDiscovery = e.message; }
    }
    System.out.println(String.format(
        "SOAK year=%.1f tick=%d pop=%d nationsAlive=%d nationsFounded=%d settlements=%d homeless=%d avgWealth=%.1f avgDebt=%.1f atWar=%d allied=%d armies=%d discoveries=%d deaths[%s]",
        years, state.tick, state.humans.size(), state.nations.size(), Nation.totalFounded(),
        state.settlements.size(), homeless, totalWealth / n, totalDebt / n, atWar, allied, state.armies.size(),
        discoveries, com.worldbox.sim.DeathStats.summary()));
    if (lastDiscovery != null) System.out.println("  latest discovery: " + lastDiscovery);
    com.worldbox.sim.DeathStats.reset();
    for (Nation nation : state.nations.values()) {
      if (!nation.alive) continue;
      int pop = 0;
      double food = 0; int farmCells = 0; int starveTicks = 0;
      for (int sid : nation.settlementIds) {
        com.worldbox.sim.Settlement s = state.settlements.get(sid);
        if (s != null) { pop += s.populationCount; food += s.stock.get("food"); farmCells += s.farmCells; starveTicks += s.starveTicks; }
      }
      System.out.println(String.format(
          "  NATION %s pop=%d gov=%s treasury=%.0f bankReserves=%.0f bankLoans=%.0f moneySupply=%.0f gdp=%.0f unemployment=%.2f inflation=%.3f exchangeRate=%.2f collapsed=%b stability=%.0f food=%.0f farmCells=%d starveTicks=%d landValue=%.2f exportTax=%.2f importTax=%.2f",
          nation.name, pop, nation.government, nation.treasury, nation.bank.reserves, nation.bank.loans,
          nation.moneySupply, nation.gdpHistory.isEmpty() ? 0 : nation.gdpHistory.peekLast(), nation.unemploymentRate,
          nation.inflationRate, nation.exchangeRate, nation.currencyCollapsed, nation.stability, food, farmCells, starveTicks,
          nation.landValueIndex, nation.exportTaxRate, nation.importTaxRate));
      StringBuilder sectors = new StringBuilder("  SECTORS " + nation.name);
      for (String sector : com.worldbox.config.Config.SECTORS) {
        var hist = nation.sectorHistory.get(sector);
        sectors.append(String.format(" %s=%.0f", sector, hist == null || hist.isEmpty() ? 0 : hist.peekLast()));
      }
      StringBuilder stock = new StringBuilder("  STOCKPILE " + nation.name);
      for (String key : com.worldbox.sim.GlobalMarket.keys()) {
        stock.append(String.format(" %s=%.0f", key, nation.stockpile.getOrDefault(key, 0.0)));
      }
      System.out.println(sectors);
      System.out.println(stock);
    }
  }

  private void updateBrushIndicator() {
    if (tool.equals("select")) { entityRenderer.setBrushIndicator(0, 0, 0, 0, false); return; }
    Vector2f cursor = inputManager.getCursorPosition();
    if (hud.isOverUi(cursor.x, cursor.y)) { entityRenderer.setBrushIndicator(0, 0, 0, 0, false); return; }
    Picking.CellHit cell = Picking.pickTerrainCell(cam, voxelRenderer.solidNode, state.grid, cursor);
    if (cell == null) { entityRenderer.setBrushIndicator(0, 0, 0, 0, false); return; }
    float h = state.grid.height[state.grid.idx(cell.x, cell.z)];
    float radius = (float) GodTools.brushRadius(brushSize);
    entityRenderer.setBrushIndicator(cell.x + 0.5f, cell.z + 0.5f, h, radius, true);
  }

  /** Hovering the cursor over a nation's territory flashes red on whoever
   * it's currently at war with, so a war reads on the map itself instead
   * of only in the log/panel. Only recomputes the (usually empty) target
   * set when the hovered nation actually changes - the flash blink
   * cadence itself is handled every frame by VoxelChunkRenderer. */
  private void updateWarHoverFlash(float tpf) {
    Vector2f cursor = inputManager.getCursorPosition();
    int hoveredNation = -1;
    if (!hud.isOverUi(cursor.x, cursor.y)) {
      Picking.CellHit cell = Picking.pickTerrainCell(cam, voxelRenderer.solidNode, state.grid, cursor);
      if (cell != null) hoveredNation = state.grid.ownerNation[state.grid.idx(cell.x, cell.z)];
    }
    java.util.Set<Integer> warTargets = java.util.Collections.emptySet();
    if (hoveredNation >= 0) {
      warTargets = new java.util.HashSet<>();
      for (com.worldbox.sim.DiplomacyManager.PairInfo p : state.diplomacy.pairsInvolving(hoveredNation)) {
        if (p.relation.status.equals(Config.WAR)) warTargets.add(p.other);
      }
    }
    voxelRenderer.setWarFlashTargets(warTargets);
    voxelRenderer.updateWarFlash(tpf);
  }

  private void updateSelectionRing() {
    if (selection != null && selection.type.equals("settlement")) {
      var s = state.settlements.get(selection.id);
      if (s != null) {
        float h = state.grid.height[state.grid.idx(s.x, s.z)];
        entityRenderer.setSelection(s.x + 0.5f, s.z + 0.5f, h, true);
        return;
      }
    } else if (selection != null && selection.type.equals("nation")) {
      // clicking a nation now highlights its capital with the same ring
      // used for a settlement selection, on top of the bigger/brighter
      // floating name label (see updateNationLabel below) - previously a
      // nation selection showed only the label, nothing on the ground
      Nation n = state.nations.get(selection.id);
      var capital = n != null ? state.settlements.get(n.capitalSettlementId) : null;
      if (capital != null) {
        float h = state.grid.height[state.grid.idx(capital.x, capital.z)];
        entityRenderer.setSelection(capital.x + 0.5f, capital.z + 0.5f, h, true);
        return;
      }
    }
    entityRenderer.setSelection(0, 0, 0, false);
  }

  private void updateNationLabel() {
    int selectedNationId = (selection != null && selection.type.equals("nation")) ? selection.id : -1;
    entityRenderer.updateNationLabels(state, selectedNationId, camDistance);
  }

  // ---- HudContext ----
  @Override public GameState getState() { return state; }
  @Override public String getTool() { return tool; }
  @Override public void setTool(String id) { tool = id; }
  @Override public int getBrushSize() { return brushSize; }
  @Override public void setBrushSize(int n) { brushSize = n; }
  @Override public GameState.Selection getSelection() { return selection; }
  @Override public void setSelection(GameState.Selection sel) {
    selection = sel;
    if (hud != null) hud.notifySelectionChanged();
  }
  @Override public int getGameSpeed() { return speed; }
  @Override public void setGameSpeed(int n) { speed = n; }
  @Override public float getZoomSensitivity() { return zoomSensitivity; }
  @Override public void setZoomSensitivity(float v) { zoomSensitivity = v; }
  @Override public void quitGame() { stop(); }

  @Override
  public void resetWorld() {
    state = Simulation.createInitialState();
    voxelRenderer.rebind(state.voxels, state.grid);
    entityRenderer.setGrid(state);
    selection = null;
    lastTickTime = simTime;
    hud.notifySelectionChanged();
  }

  @Override public SaveManager.SlotInfo[] listSaveSlots() { return SaveManager.listSlots(); }

  @Override
  public boolean saveToSlot(int slot) {
    try {
      SaveManager.save(state, slot);
      com.worldbox.sim.EventLog.log(state, "economy", "World saved to slot " + slot);
      return true;
    } catch (java.io.IOException | RuntimeException e) {
      com.worldbox.sim.EventLog.log(state, "economy", "Save failed: " + e);
      return false;
    }
  }

  @Override
  public boolean loadFromSlot(int slot) {
    GameState loaded;
    try {
      loaded = SaveManager.load(slot);
    } catch (java.io.IOException | ClassNotFoundException | RuntimeException e) {
      // couldn't load - leave the current world running rather than
      // crash out of the game over a bad or missing save file, but still
      // say so instead of a load button that silently does nothing
      com.worldbox.sim.EventLog.log(state, "economy", "Load failed: " + e);
      return false;
    }
    state = loaded;
    voxelRenderer.rebind(state.voxels, state.grid);
    entityRenderer.setGrid(state);
    selection = null;
    lastTickTime = simTime;
    hud.notifySelectionChanged();
    return true;
  }

  @Override public void deleteSlot(int slot) { SaveManager.delete(slot); }
}
