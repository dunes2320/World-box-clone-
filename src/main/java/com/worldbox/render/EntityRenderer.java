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
import com.worldbox.sim.Bank;
import com.worldbox.sim.Business;
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
  private static final ColorRGBA TRUNK_COLOR = new ColorRGBA(0.365f, 0.259f, 0.157f, 1f);
  private static final ColorRGBA BANK_COLOR = new ColorRGBA(0.85f, 0.72f, 0.25f, 1f);
  private static final Map<String, ColorRGBA> BUSINESS_COLORS = new HashMap<>();
  static {
    BUSINESS_COLORS.put("wood", new ColorRGBA(0.45f, 0.32f, 0.18f, 1f));
    BUSINESS_COLORS.put("stone", new ColorRGBA(0.55f, 0.57f, 0.60f, 1f));
    BUSINESS_COLORS.put("iron", new ColorRGBA(0.75f, 0.4f, 0.2f, 1f));
  }

  private static final int TREE_CAP_SAMPLE = 2600;
  private static final int DEPOSIT_CAP_SAMPLE = 900;
  private static final int SETTLEMENT_CAP = 48;
  private static final int ARMY_CAP = 96;
  private static final int BUSINESS_CAP = 96;
  private static final int BANK_CAP = 32;

  private final AssetManager assets;
  private final Node root;
  private final NationColorLookup nationColor;
  private WorldGrid grid;

  private final Mesh treeCanopyTemplate, treeTrunkTemplate, depositTemplate, humanTemplate, armyTemplate;
  private final Mesh hutTemplate, townTemplate, cityTemplate, businessTemplate, bankTemplate;

  private final Geometry treesGeom, treeTrunksGeom, depositsGeom;

  private final Node settlementsNode = new Node("settlements");
  private final Node armiesNode = new Node("armies");
  private final Node humansNode = new Node("humans");
  private final Node businessesNode = new Node("businesses");
  private final Node banksNode = new Node("banks");
  private final Geometry[] settlementPool = new Geometry[SETTLEMENT_CAP];
  private final Geometry[] armyPool = new Geometry[ARMY_CAP];
  private final Geometry[] humanPool = new Geometry[Config.MAX_HUMANS];
  private final Geometry[] businessPool = new Geometry[BUSINESS_CAP];
  private final Geometry[] bankPool = new Geometry[BANK_CAP];

  private final Geometry monsterGeom;
  private final List<Geometry> tornadoGeoms = new ArrayList<>();
  private final Geometry selectionRing;

  public EntityRenderer(Node root, AssetManager assets, WorldGrid grid, NationColorLookup nationColor) {
    this.root = root;
    this.assets = assets;
    this.grid = grid;
    this.nationColor = nationColor;

    treeCanopyTemplate = new Cylinder(2, 6, 0.34f, 0.001f, 0.75f, true, false);
    MeshUtil.reorientZToY(treeCanopyTemplate);
    treeTrunkTemplate = new Cylinder(2, 5, 0.09f, 0.11f, 0.4f, true, false);
    MeshUtil.reorientZToY(treeTrunkTemplate);
    depositTemplate = MeshUtil.buildGem(0.32f, 0.5f);
    humanTemplate = new Cylinder(2, 6, 0.13f, 0.13f, 0.5f, true, false);
    MeshUtil.reorientZToY(humanTemplate);
    armyTemplate = new Cylinder(2, 4, 0.32f, 0.001f, 0.6f, true, false);
    MeshUtil.reorientZToY(armyTemplate);

    // settlement tiers: a small hut, a boxy town hall, a tall stacked city
    hutTemplate = new Cylinder(2, 4, 0.5f, 0.001f, 0.9f, true, false);
    MeshUtil.reorientZToY(hutTemplate);
    townTemplate = new Box(0.55f, 0.5f, 0.55f);
    cityTemplate = MeshUtil.mergeMeshes(
        MeshUtil.translatedCopy(new Box(0.6f, 0.7f, 0.6f), 0, 0.7f, 0),
        MeshUtil.translatedCopy(new Box(0.4f, 0.45f, 0.4f), 0, 1.85f, 0));

    businessTemplate = new Box(0.22f, 0.22f, 0.22f);
    bankTemplate = MeshUtil.buildPillar(0.28f, 0.5f, 1.3f);

    treesGeom = new Geometry("Trees", treeCanopyTemplate.deepClone());
    treesGeom.setMaterial(vertexColorMaterial());
    root.attachChild(treesGeom);

    treeTrunksGeom = new Geometry("TreeTrunks", treeTrunkTemplate.deepClone());
    treeTrunksGeom.setMaterial(vertexColorMaterial());
    root.attachChild(treeTrunksGeom);

    depositsGeom = new Geometry("Deposits", depositTemplate.deepClone());
    depositsGeom.setMaterial(vertexColorMaterial());
    root.attachChild(depositsGeom);

    for (int i = 0; i < SETTLEMENT_CAP; i++) {
      Geometry g = new Geometry("Settlement" + i, hutTemplate);
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

    for (int i = 0; i < BUSINESS_CAP; i++) {
      Geometry g = new Geometry("Business" + i, businessTemplate);
      g.setMaterial(soloColorMaterial(ColorRGBA.White));
      g.setCullHint(Spatial.CullHint.Always);
      businessesNode.attachChild(g);
      businessPool[i] = g;
    }
    root.attachChild(businessesNode);

    for (int i = 0; i < BANK_CAP; i++) {
      Geometry g = new Geometry("Bank" + i, bankTemplate);
      g.setMaterial(soloColorMaterial(BANK_COLOR));
      g.setCullHint(Spatial.CullHint.Always);
      banksNode.attachChild(g);
      bankPool[i] = g;
    }
    root.attachChild(banksNode);

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
    List<PropBatcher.Placement> canopies = new ArrayList<>();
    List<PropBatcher.Placement> trunks = new ArrayList<>();
    List<PropBatcher.Placement> deposits = new ArrayList<>();
    for (int y = 0; y < grid.rows && (canopies.size() < TREE_CAP_SAMPLE || deposits.size() < DEPOSIT_CAP_SAMPLE); y++) {
      for (int x = 0; x < grid.cols; x++) {
        int i = grid.idx(x, y);
        byte res = grid.resource[i];
        if (res == Config.RES_FOREST && canopies.size() < TREE_CAP_SAMPLE) {
          float scale = 0.6f + Math.min(1f, grid.resourceAmount[i] / 48f) * 0.6f;
          float rotY = (float) ((x * 7 + y * 13) % 6.28);
          float trunkTop = grid.height[i] + 0.38f * scale;
          trunks.add(new PropBatcher.Placement(x + 0.5f, grid.height[i], y + 0.5f, rotY, scale, TRUNK_COLOR));
          canopies.add(new PropBatcher.Placement(x + 0.5f, trunkTop, y + 0.5f, rotY, scale, TREE_COLOR));
        } else if (res != Config.RES_NONE && res != Config.RES_FOREST && deposits.size() < DEPOSIT_CAP_SAMPLE) {
          float rotY = (float) ((x * 3 + y * 5) % 6.28);
          ColorRGBA c = DEPOSIT_COLORS.getOrDefault(res, ColorRGBA.White);
          deposits.add(new PropBatcher.Placement(x + 0.5f, grid.height[i] + 0.28f, y + 0.5f, rotY, 1f, c));
        }
      }
    }
    treesGeom.setMesh(PropBatcher.bake(treeCanopyTemplate, canopies));
    treesGeom.setCullHint(canopies.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
    treeTrunksGeom.setMesh(PropBatcher.bake(treeTrunkTemplate, trunks));
    treeTrunksGeom.setCullHint(trunks.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
    depositsGeom.setMesh(PropBatcher.bake(depositTemplate, deposits));
    depositsGeom.setCullHint(deposits.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
  }

  public void update(GameState state, float alpha) {
    updateSettlements(state);
    updateArmies(state, alpha);
    updateHumans(state, alpha);
    updateBusinesses(state);
    updateBanks(state);
    updateMonster(state);
    updateTornadoes(state);
  }

  private Mesh tierTemplate(int population) {
    if (population >= 35) return cityTemplate;
    if (population >= 15) return townTemplate;
    return hutTemplate;
  }

  private void updateSettlements(GameState state) {
    int i = 0;
    for (Settlement s : state.settlements.values()) {
      if (i >= SETTLEMENT_CAP) break;
      Geometry g = settlementPool[i];
      float h = grid.height[grid.idx(s.x, s.z)];
      float scale = (float) (0.55 + Math.sqrt(Math.max(1, s.populationCount)) * 0.13);
      g.setMesh(tierTemplate(s.populationCount));
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

  private void updateBusinesses(GameState state) {
    int i = 0;
    for (Business b : state.businesses.values()) {
      if (i >= BUSINESS_CAP) break;
      Settlement s = state.settlements.get(b.settlementId);
      if (s == null) continue;
      Geometry g = businessPool[i];
      float h = grid.height[grid.idx(s.x, s.z)];
      // scatter around the settlement a bit so multiple businesses don't overlap
      float angle = (b.id * 2.399963f);
      float ox = (float) Math.cos(angle) * 1.4f, oz = (float) Math.sin(angle) * 1.4f;
      g.setLocalTranslation(s.x + 0.5f + ox, h + 0.22f, s.z + 0.5f + oz);
      g.setLocalScale((float) (0.7 + Math.min(1.5, b.capital / 60.0)));
      g.getMaterial().setColor("Color", BUSINESS_COLORS.getOrDefault(b.resourceKey, ColorRGBA.White));
      g.setCullHint(Spatial.CullHint.Inherit);
      i++;
    }
    for (; i < BUSINESS_CAP; i++) businessPool[i].setCullHint(Spatial.CullHint.Always);
  }

  private void updateBanks(GameState state) {
    int i = 0;
    for (Nation n : state.nations.values()) {
      if (i >= BANK_CAP) break;
      Settlement capital = state.settlements.get(n.capitalSettlementId);
      if (capital == null) continue;
      Geometry g = bankPool[i];
      float h = grid.height[grid.idx(capital.x, capital.z)];
      g.setLocalTranslation(capital.x + 0.5f - 1.6f, h, capital.z + 0.5f - 1.6f);
      g.setLocalScale(0.7f);
      g.getMaterial().setColor("Color", n.bank.justCrashed ? ColorRGBA.Red : BANK_COLOR);
      g.setCullHint(Spatial.CullHint.Inherit);
      i++;
    }
    for (; i < BANK_CAP; i++) bankPool[i].setCullHint(Spatial.CullHint.Always);
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
