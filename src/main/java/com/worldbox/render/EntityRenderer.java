package com.worldbox.render;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Cylinder;
import com.jme3.scene.shape.Torus;
import com.worldbox.config.Config;
import com.worldbox.sim.Army;
import com.worldbox.sim.GameState;
import com.worldbox.sim.Human;
import com.worldbox.sim.Military;
import com.worldbox.sim.Monster;
import com.worldbox.sim.Nation;
import com.worldbox.sim.Settlement;
import com.worldbox.sim.Tornado;
import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns every non-terrain visual: batched trees/deposits, pooled
 * settlements/humans/armies, and the one-off monster/tornado/selection
 * markers. */
public class EntityRenderer {
  private static final ColorRGBA ZOMBIE_COLOR = new ColorRGBA(0.353f, 0.353f, 0.431f, 1f);
  private static final Map<Byte, ColorRGBA> DEPOSIT_COLORS = new HashMap<>();
  static {
    DEPOSIT_COLORS.put(Config.RES_STONE, new ColorRGBA(0.604f, 0.627f, 0.659f, 1f));
    DEPOSIT_COLORS.put(Config.RES_IRON, new ColorRGBA(0.690f, 0.416f, 0.290f, 1f));
    DEPOSIT_COLORS.put(Config.RES_GOLD, new ColorRGBA(0.902f, 0.773f, 0.247f, 1f));
  }
  private static final ColorRGBA TREE_COLOR = new ColorRGBA(0.137f, 0.361f, 0.157f, 1f);

  private static final int TREE_CAP_SAMPLE = 2600;
  private static final int DEPOSIT_CAP_SAMPLE = 900;
  private static final int SETTLEMENT_CAP = 48;
  private static final int ARMY_CAP = 96;

  private final AssetManager assets;
  private final Node root;
  private final NationColorLookup nationColor;
  private WorldGrid grid;

  private final Mesh treeTemplate, depositTemplate, settlementTemplate, humanTemplate, armyTemplate;

  private final Geometry treesGeom, depositsGeom;

  private final Node settlementsNode = new Node("settlements");
  private final Node armiesNode = new Node("armies");
  private final Node humansNode = new Node("humans");
  private final Geometry[] settlementPool = new Geometry[SETTLEMENT_CAP];
  private final Geometry[] armyPool = new Geometry[ARMY_CAP];
  private final Geometry[] humanPool = new Geometry[Config.MAX_HUMANS];

  private final Geometry monsterGeom;
  private final List<Geometry> tornadoGeoms = new ArrayList<>();
  private final Geometry selectionRing;

  public EntityRenderer(Node root, AssetManager assets, WorldGrid grid, NationColorLookup nationColor) {
    this.root = root;
    this.assets = assets;
    this.grid = grid;
    this.nationColor = nationColor;

    treeTemplate = new Cylinder(2, 6, 0.38f, 0.001f, 0.95f, true, false);
    MeshUtil.reorientZToY(treeTemplate);
    depositTemplate = new Box(0.28f, 0.28f, 0.28f);
    settlementTemplate = new Cylinder(2, 4, 0.55f, 0.001f, 1.0f, true, false);
    MeshUtil.reorientZToY(settlementTemplate);
    humanTemplate = new Cylinder(2, 6, 0.13f, 0.13f, 0.5f, true, false);
    MeshUtil.reorientZToY(humanTemplate);
    armyTemplate = new Cylinder(2, 4, 0.32f, 0.001f, 0.6f, true, false);
    MeshUtil.reorientZToY(armyTemplate);

    treesGeom = new Geometry("Trees", treeTemplate.deepClone());
    treesGeom.setMaterial(vertexColorMaterial());
    root.attachChild(treesGeom);

    depositsGeom = new Geometry("Deposits", depositTemplate.deepClone());
    depositsGeom.setMaterial(vertexColorMaterial());
    root.attachChild(depositsGeom);

    for (int i = 0; i < SETTLEMENT_CAP; i++) {
      Geometry g = new Geometry("Settlement" + i, settlementTemplate);
      g.setMaterial(soloColorMaterial(ColorRGBA.Gray));
      g.setCullHint(Spatial.CullHint.Always);
      settlementsNode.attachChild(g);
      settlementPool[i] = g;
    }
    root.attachChild(settlementsNode);

    for (int i = 0; i < ARMY_CAP; i++) {
      Geometry g = new Geometry("Army" + i, armyTemplate);
      g.setMaterial(soloColorMaterial(ColorRGBA.White));
      g.setCullHint(Spatial.CullHint.Always);
      armiesNode.attachChild(g);
      armyPool[i] = g;
    }
    root.attachChild(armiesNode);

    for (int i = 0; i < Config.MAX_HUMANS; i++) {
      Geometry g = new Geometry("Human" + i, humanTemplate);
      g.setMaterial(soloColorMaterial(ColorRGBA.White));
      g.setCullHint(Spatial.CullHint.Always);
      humansNode.attachChild(g);
      humanPool[i] = g;
    }
    root.attachChild(humansNode);

    Box monsterBox = new Box(1.3f, 1.3f, 1.3f);
    monsterGeom = new Geometry("Monster", monsterBox);
    Material monsterMat = soloColorMaterial(new ColorRGBA(0.29f, 0.06f, 0.19f, 1f));
    monsterGeom.setMaterial(monsterMat);
    monsterGeom.setCullHint(Spatial.CullHint.Always);
    root.attachChild(monsterGeom);

    Torus ringMesh = new Torus(24, 6, 0.06f, 0.8f);
    selectionRing = new Geometry("Selection", ringMesh);
    Material ringMat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
    ringMat.setColor("Color", ColorRGBA.White);
    selectionRing.setMaterial(ringMat);
    selectionRing.setLocalRotation(new Quaternion().fromAngleAxis((float) Math.PI / 2, Vector3f.UNIT_X));
    selectionRing.setCullHint(Spatial.CullHint.Always);
    root.attachChild(selectionRing);
  }

  private Material vertexColorMaterial() {
    Material mat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
    mat.setBoolean("VertexColor", true);
    return mat;
  }

  private Material soloColorMaterial(ColorRGBA c) {
    Material mat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
    mat.setColor("Color", c);
    return mat;
  }

  public void setGrid(WorldGrid grid) {
    this.grid = grid;
    for (Geometry g : tornadoGeoms) g.removeFromParent();
    tornadoGeoms.clear();
    monsterGeom.setCullHint(Spatial.CullHint.Always);
    rebuildStatics();
  }

  private ColorRGBA nationOrFallback(int nationId, ColorRGBA fallback) {
    if (nationId == Config.UNDEAD_NATION_ID) return ZOMBIE_COLOR;
    if (nationColor != null) {
      ColorRGBA c = nationColor.colorFor(nationId);
      if (c != null) return c;
    }
    return fallback;
  }

  /** Trees/deposits barely move; rebuild their instance lists on a slow
   * cadence instead of every frame. */
  public void rebuildStatics() {
    List<PropBatcher.Placement> trees = new ArrayList<>();
    List<PropBatcher.Placement> deposits = new ArrayList<>();
    for (int y = 0; y < grid.rows && (trees.size() < TREE_CAP_SAMPLE || deposits.size() < DEPOSIT_CAP_SAMPLE); y++) {
      for (int x = 0; x < grid.cols; x++) {
        int i = grid.idx(x, y);
        byte res = grid.resource[i];
        if (res == Config.RES_FOREST && trees.size() < TREE_CAP_SAMPLE) {
          float scale = 0.6f + Math.min(1f, grid.resourceAmount[i] / 48f) * 0.6f;
          float rotY = (float) ((x * 7 + y * 13) % 6.28);
          trees.add(new PropBatcher.Placement(x + 0.5f, grid.height[i], y + 0.5f, rotY, scale, TREE_COLOR));
        } else if (res != Config.RES_NONE && res != Config.RES_FOREST && deposits.size() < DEPOSIT_CAP_SAMPLE) {
          float rotY = (float) ((x * 3 + y * 5) % 6.28);
          ColorRGBA c = DEPOSIT_COLORS.getOrDefault(res, ColorRGBA.White);
          deposits.add(new PropBatcher.Placement(x + 0.5f, grid.height[i] + 0.22f, y + 0.5f, rotY, 1f, c));
        }
      }
    }
    treesGeom.setMesh(PropBatcher.bake(treeTemplate, trees));
    treesGeom.setCullHint(trees.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
    depositsGeom.setMesh(PropBatcher.bake(depositTemplate, deposits));
    depositsGeom.setCullHint(deposits.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
  }

  public void update(GameState state, float alpha) {
    updateSettlements(state);
    updateArmies(state, alpha);
    updateHumans(state, alpha);
    updateMonster(state);
    updateTornadoes(state);
  }

  private void updateSettlements(GameState state) {
    int i = 0;
    for (Settlement s : state.settlements.values()) {
      if (i >= SETTLEMENT_CAP) break;
      Geometry g = settlementPool[i];
      float h = grid.height[grid.idx(s.x, s.z)];
      float scale = (float) (0.55 + Math.sqrt(Math.max(1, s.populationCount)) * 0.13);
      g.setLocalTranslation(s.x + 0.5f, h, s.z + 0.5f);
      g.setLocalScale(scale);
      Nation nation = state.nations.get(s.nationId);
      ColorRGBA c = nation != null ? nationOrFallback(nation.id, ColorRGBA.Gray) : ColorRGBA.Gray;
      g.getMaterial().setColor("Color", c);
      g.setUserData("settlementId", s.id);
      g.setCullHint(Spatial.CullHint.Inherit);
      i++;
    }
    for (; i < SETTLEMENT_CAP; i++) settlementPool[i].setCullHint(Spatial.CullHint.Always);
  }

  private void updateArmies(GameState state, float alpha) {
    int i = 0;
    for (Army a : state.armies.values()) {
      if (i >= ARMY_CAP || a.dead) continue;
      Geometry g = armyPool[i];
      double x = a.prevX + (a.x - a.prevX) * alpha;
      double z = a.prevZ + (a.z - a.prevZ) * alpha;
      int gx = clampIdx((int) Math.floor(x), grid.cols), gz = clampIdx((int) Math.floor(z), grid.rows);
      float h = grid.height[grid.idx(gx, gz)];
      float scale = (float) (0.5 + Math.min(1.4, (a.strength > 0 ? a.strength : 1) / 40));
      g.setLocalTranslation((float) x, h + 0.6f, (float) z);
      g.setLocalScale(scale);
      g.setLocalRotation(new Quaternion().fromAngleAxis(state.tick * 0.05f, Vector3f.UNIT_Y));
      Nation nation = state.nations.get(a.nationId);
      ColorRGBA c = nation != null ? nationOrFallback(nation.id, ColorRGBA.White) : ColorRGBA.White;
      g.getMaterial().setColor("Color", c);
      g.setUserData("armyId", a.id);
      g.setCullHint(Spatial.CullHint.Inherit);
      i++;
    }
    for (; i < ARMY_CAP; i++) armyPool[i].setCullHint(Spatial.CullHint.Always);
  }

  private void updateHumans(GameState state, float alpha) {
    List<Human> humans = state.humans;
    int n = Math.min(humans.size(), Config.MAX_HUMANS);
    for (int i = 0; i < n; i++) {
      Human h = humans.get(i);
      Geometry g = humanPool[i];
      double x = h.prevX + (h.x - h.prevX) * alpha;
      double z = h.prevZ + (h.z - h.prevZ) * alpha;
      int gx = clampIdx((int) Math.floor(x), grid.cols), gz = clampIdx((int) Math.floor(z), grid.rows);
      float hgt = grid.height[grid.idx(gx, gz)];
      g.setLocalTranslation((float) x, hgt + 0.25f, (float) z);
      double dx = h.x - h.prevX, dz = h.z - h.prevZ;
      if (Math.abs(dx) > 1e-5 || Math.abs(dz) > 1e-5) {
        float yaw = (float) Math.atan2(dx, dz);
        g.setLocalRotation(new Quaternion().fromAngleAxis(yaw, Vector3f.UNIT_Y));
      }
      ColorRGBA c = h.nationId == Config.UNDEAD_NATION_ID
          ? ZOMBIE_COLOR
          : nationOrFallback(h.nationId, new ColorRGBA(0.6f, 0.6f, 0.65f, 1f));
      g.getMaterial().setColor("Color", c);
      g.setCullHint(Spatial.CullHint.Inherit);
    }
    for (int i = n; i < Config.MAX_HUMANS; i++) humanPool[i].setCullHint(Spatial.CullHint.Always);
  }

  private void updateMonster(GameState state) {
    Monster m = state.monster;
    if (m == null) { monsterGeom.setCullHint(Spatial.CullHint.Always); return; }
    monsterGeom.setCullHint(Spatial.CullHint.Inherit);
    int gx = clampIdx((int) Math.floor(m.x), grid.cols), gz = clampIdx((int) Math.floor(m.z), grid.rows);
    float h = grid.height[grid.idx(gx, gz)];
    float scale = (float) (0.7 + (m.hp / m.maxHp) * 0.6);
    monsterGeom.setLocalTranslation((float) m.x, h + 1.6f * scale, (float) m.z);
    monsterGeom.setLocalScale(scale);
    monsterGeom.rotate(0, 0.02f, 0);
  }

  private void updateTornadoes(GameState state) {
    while (tornadoGeoms.size() < state.tornadoes.size()) {
      Cylinder cone = new Cylinder(6, 10, 1.1f, 0.001f, 3.4f, true, false);
      MeshUtil.reorientZToY(cone);
      Geometry g = new Geometry("Tornado", cone);
      Material mat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
      mat.setColor("Color", new ColorRGBA(0.75f, 0.77f, 0.8f, 0.55f));
      mat.setTransparent(true);
      mat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
      g.setMaterial(mat);
      g.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
      root.attachChild(g);
      tornadoGeoms.add(g);
    }
    while (tornadoGeoms.size() > state.tornadoes.size()) {
      tornadoGeoms.remove(tornadoGeoms.size() - 1).removeFromParent();
    }
    for (int i = 0; i < state.tornadoes.size(); i++) {
      Tornado t = state.tornadoes.get(i);
      Geometry g = tornadoGeoms.get(i);
      int gx = clampIdx((int) Math.floor(t.x), grid.cols), gz = clampIdx((int) Math.floor(t.z), grid.rows);
      float h = grid.height[grid.idx(gx, gz)];
      g.setLocalTranslation((float) t.x, h + 1.7f, (float) t.z);
      g.rotate(0, 0.35f, 0);
    }
  }

  public void setSelection(float x, float z, float h, boolean visible) {
    selectionRing.setCullHint(visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
    if (visible) selectionRing.setLocalTranslation(x, h + 0.08f, z);
  }

  public Node getSettlementsNode() { return settlementsNode; }
  public Node getArmiesNode() { return armiesNode; }

  private static int clampIdx(int v, int max) { return Math.max(0, Math.min(max - 1, v)); }
}
