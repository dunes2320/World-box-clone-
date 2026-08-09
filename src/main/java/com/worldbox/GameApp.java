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
import com.simsilica.lemur.GuiGlobals;
import com.worldbox.config.Config;
import com.worldbox.render.EntityRenderer;
import com.worldbox.render.NationColorLookup;
import com.worldbox.render.Picking;
import com.worldbox.render.TerrainMesh;
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
  private TerrainMesh terrainMesh;
  private EntityRenderer entityRenderer;
  private GameHud hud;

  private String tool = "select";
  private int brushSize = 3;
  private GameState.Selection selection;
  private int speed = 1;

  private double simTime;
  private double lastTickTime;
  private boolean leftDown, rotating, panning;
  private boolean moveFwd, moveBack, moveLeft, moveRight;
  private Picking.CellHit lastCell;

  private final Vector3f camTarget = new Vector3f();
  private float camYaw = 0.5f, camPitch = 0.75f, camDistance = 55f;

  // headless verification mode: F12, or -Dworldbox.testMode=true to
  // auto-screenshot and exit (used by automated smoke tests / CI, no effect
  // on normal play)
  private ScreenshotAppState screenshotState;
  private boolean testMode;
  private double testModeExitAt = -1;

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
    rootNode.addLight(new AmbientLight(new ColorRGBA(0.35f, 0.38f, 0.45f, 1f)));
    DirectionalLight sun = new DirectionalLight();
    sun.setDirection(new Vector3f(-0.5f, -1f, -0.4f).normalizeLocal());
    sun.setColor(new ColorRGBA(1f, 0.96f, 0.86f, 1f));
    rootNode.addLight(sun);

    terrainMesh = new TerrainMesh(state.grid, assetManager, this::nationColorFor);
    rootNode.attachChild(terrainMesh.geometry);
    rootNode.attachChild(terrainMesh.waterGeometry);

    entityRenderer = new EntityRenderer(rootNode, assetManager, state.grid, this::nationColorFor);
    entityRenderer.rebuildStatics();

    GuiGlobals.initialize(this);
    hud = new GameHud(guiNode, assetManager, screenWidth, screenHeight, this);

    setupInput();
    updateCamera();

    screenshotState = new ScreenshotAppState(System.getProperty("java.io.tmpdir") + "/", "worldbox");
    stateManager.attach(screenshotState);

    testMode = "true".equals(System.getProperty("worldbox.testMode"));
  }

  private ColorRGBA nationColorFor(int nationId) {
    Nation n = state.nations.get(nationId);
    if (n == null) return null;
    return intToColor(n.color);
  }

  private static ColorRGBA intToColor(int rgb) {
    return new ColorRGBA(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, 1f);
  }

  private void setupInput() {
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
    inputManager.addListener(this, "Paint", "RotateCam", "PanCam",
        "MouseXPos", "MouseXNeg", "MouseYPos", "MouseYNeg", "WheelUp", "WheelDown",
        "MoveForward", "MoveBack", "MoveLeft", "MoveRight");
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
    if (name.equals("WheelUp")) camDistance = clamp(camDistance - value * 20f, 8f, 140f);
    else if (name.equals("WheelDown")) camDistance = clamp(camDistance + value * 20f, 8f, 140f);
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

  private void updateCamera() {
    camTarget.x = clamp(camTarget.x, -15f, Config.COLS + 15f);
    camTarget.z = clamp(camTarget.z, -15f, Config.ROWS + 15f);
    float x = camDistance * FastMath.cos(camPitch) * FastMath.sin(camYaw);
    float y = camDistance * FastMath.sin(camPitch);
    float z = camDistance * FastMath.cos(camPitch) * FastMath.cos(camYaw);
    cam.setLocation(camTarget.add(x, y, z));
    cam.lookAt(camTarget, Vector3f.UNIT_Y);
  }

  private void handlePick() {
    Vector2f cursor = inputManager.getCursorPosition();
    if (hud.isOverUi(cursor.x, cursor.y)) return;

    if (tool.equals("select")) {
      Integer sid = Picking.pickPoolId(cam, entityRenderer.getSettlementsNode(), cursor, "settlementId");
      if (sid != null) { setSelection(new GameState.Selection("settlement", sid)); return; }
      Integer aid = Picking.pickPoolId(cam, entityRenderer.getArmiesNode(), cursor, "armyId");
      if (aid != null) {
        Army army = state.armies.get(aid);
        if (army != null) { setSelection(new GameState.Selection("nation", army.nationId)); return; }
      }
      setSelection(null);
      return;
    }

    Picking.CellHit cell = Picking.pickTerrainCell(cam, terrainMesh.geometry, state.grid, cursor);
    if (cell == null) return;
    lastCell = cell;
    GodTools.apply(state, tool, cell.x, cell.z, brushSize);
  }

  @Override
  public void simpleUpdate(float tpf) {
    simTime += tpf;
    maybeTick();
    applyKeyboardMovement(tpf);
    updateCamera();

    if (leftDown && GodTools.CONTINUOUS_TOOLS.contains(tool)) {
      Vector2f cursor = inputManager.getCursorPosition();
      if (!hud.isOverUi(cursor.x, cursor.y)) {
        Picking.CellHit cell = Picking.pickTerrainCell(cam, terrainMesh.geometry, state.grid, cursor);
        if (cell != null && (lastCell == null || lastCell.x != cell.x || lastCell.z != cell.z)) {
          lastCell = cell;
          GodTools.apply(state, tool, cell.x, cell.z, brushSize);
        }
      }
    }

    terrainMesh.flushDirty();
    float alpha = speed > 0 ? (float) Math.min(1.0, (simTime - lastTickTime) / (Config.TICK_MS / 1000.0 / speed)) : 1f;
    entityRenderer.update(state, alpha);
    updateSelectionRing();

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
      testScript.put(1.0, () -> screenshotState.takeScreenshot());
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
      testScript.put(6.0, () -> hud.debugSetPanelMode(null));
      double midway = Math.max(15.0, duration * 0.5);
      testScript.put(midway, () -> {
        if (!state.nations.isEmpty()) {
          int firstNationId = state.nations.keySet().iterator().next();
          setSelection(new GameState.Selection("nation", firstNationId));
        }
      });
      testScript.put(midway + 0.5, () -> screenshotState.takeScreenshot());
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
    for (var entry : new java.util.ArrayList<>(testScript.entrySet())) {
      if (simTime > entry.getKey()) {
        entry.getValue().run();
        testScript.remove(entry.getKey());
      }
    }
    if (simTime > testModeExitAt) stop();
  }

  private void maybeTick() {
    if (speed <= 0) { lastTickTime = simTime; return; }
    double interval = Config.TICK_MS / 1000.0 / speed;
    int iterations = 0;
    while (simTime - lastTickTime > interval && iterations < 8) {
      Simulation.tick(state);
      if (state.tick % 20 == 0) entityRenderer.rebuildStatics();
      lastTickTime += interval;
      iterations++;
    }
    if (iterations >= 8) lastTickTime = simTime;
  }

  private void updateSelectionRing() {
    if (selection == null || !selection.type.equals("settlement")) {
      entityRenderer.setSelection(0, 0, 0, false);
      return;
    }
    var s = state.settlements.get(selection.id);
    if (s == null) { entityRenderer.setSelection(0, 0, 0, false); return; }
    float h = state.grid.height[state.grid.idx(s.x, s.z)];
    entityRenderer.setSelection(s.x + 0.5f, s.z + 0.5f, h, true);
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

  @Override
  public void resetWorld() {
    state = Simulation.createInitialState();
    terrainMesh.setGrid(state.grid);
    entityRenderer.setGrid(state.grid);
    selection = null;
    lastTickTime = simTime;
    hud.notifySelectionChanged();
  }
}
