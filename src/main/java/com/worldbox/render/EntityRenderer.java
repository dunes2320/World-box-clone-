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
import com.worldbox.sim.Cloud;
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
  private static final ColorRGBA FOLIAGE_COLOR = new ColorRGBA(0.29f, 0.52f, 0.22f, 1f);
  private static final ColorRGBA[] FLOWER_COLORS = {
      new ColorRGBA(0.95f, 0.85f, 0.25f, 1f), // yellow
      new ColorRGBA(0.92f, 0.35f, 0.4f, 1f),  // red-pink
      new ColorRGBA(0.95f, 0.95f, 0.95f, 1f), // white
      new ColorRGBA(0.62f, 0.42f, 0.85f, 1f), // purple
  };
  private static final ColorRGBA BANK_COLOR = new ColorRGBA(0.85f, 0.72f, 0.25f, 1f);
  private static final Map<String, ColorRGBA> BUSINESS_COLORS = new HashMap<>();
  static {
    BUSINESS_COLORS.put("wood", new ColorRGBA(0.45f, 0.32f, 0.18f, 1f));
    BUSINESS_COLORS.put("stone", new ColorRGBA(0.55f, 0.57f, 0.60f, 1f));
    BUSINESS_COLORS.put("iron", new ColorRGBA(0.75f, 0.4f, 0.2f, 1f));
    BUSINESS_COLORS.put("food", new ColorRGBA(0.85f, 0.72f, 0.22f, 1f));
    BUSINESS_COLORS.put("market", new ColorRGBA(0.62f, 0.32f, 0.72f, 1f));
  }

  private static final int TREE_CAP_SAMPLE = 2600;
  private static final int DEPOSIT_CAP_SAMPLE = 900;
  // a global cap shared across every settlement on the map - at only 700
  // it ran out after the first couple dozen settlements in iteration
  // order, so most settlements (especially the small ones, which were
  // also excluded outright below) rendered as a single bare marker with
  // no houses around it at all, reading as "a circle with a square in
  // it" instead of a village
  private static final int HOUSE_CAP_SAMPLE = 6000;
  private static final int FOLIAGE_CAP_SAMPLE = 4500;
  private static final ColorRGBA HOUSE_FALLBACK = new ColorRGBA(0.8f, 0.75f, 0.62f, 1f);
  private static final ColorRGBA RUIN_COLOR = new ColorRGBA(0.32f, 0.3f, 0.28f, 1f);
  // these used to be tuned for a small 128x128 map with a handful of
  // nations - a long game on the bigger map can easily grow past several
  // hundred settlements/businesses, and anything beyond the cap used to
  // just silently never get a rendering slot at all (a settlement or
  // business that's fully real in the simulation but invisible in the
  // world - reads exactly like "cities vanishing")
  private static final int SETTLEMENT_CAP = 400;
  private static final int ARMY_CAP = 96;
  private static final int BUSINESS_CAP = 600;
  private static final int BANK_CAP = 80;
  // matches Events.MAX_SIMULTANEOUS_FIRE - a fire that big is now the hard
  // ceiling on how much can ever be burning at once, so this guarantees
  // every burning cell actually gets a flame/smoke prop instead of the
  // fire silently continuing to spread past what's visible
  private static final int FIRE_CAP = 220;
  private static final int SPARKLE_CAP = 28;
  private static final int RAIN_CAP = 140;
  private static final ColorRGBA FLAME_A = new ColorRGBA(1f, 0.48f, 0.1f, 1f);
  private static final ColorRGBA FLAME_B = new ColorRGBA(1f, 0.82f, 0.25f, 1f);
  private static final ColorRGBA SPARKLE_COLOR = new ColorRGBA(1f, 0.95f, 0.6f, 1f);

  private final AssetManager assets;
  private final Node root;
  private final NationColorLookup nationColor;
  private WorldGrid grid;

  private final Mesh treeCanopyTemplate, treeTrunkTemplate, depositTemplate, stoneDepositTemplate, humanTemplate, armyTemplate;
  private final Mesh hutTemplate, townTemplate, cityTemplate, businessTemplate, bankTemplate, houseTemplate;
  private final Mesh farmTemplate, marketTemplate, statueTemplate;
  private final Mesh flagTemplate, fireTemplate, sparkleTemplate;
  private final Mesh cloudTemplate, rainTemplate, foliageTemplate, flowerTemplate;

  private final Geometry treesGeom, treeTrunksGeom, depositsGeom, stoneDepositsGeom, housesGeom;
  private final Geometry foliageGeom, flowersGeom;

  private final Node settlementsNode = new Node("settlements");
  private final Node armiesNode = new Node("armies");
  private final Node humansNode = new Node("humans");
  private final Node businessesNode = new Node("businesses");
  private final Node banksNode = new Node("banks");
  private final Node statuesNode = new Node("statues");
  private final Node flagsNode = new Node("flags");
  private final Node firesNode = new Node("fires");
  private final Node smokeNode = new Node("smoke");
  private final Node sparklesNode = new Node("sparkles");
  private final Node rainNode = new Node("rain");
  private final Node cloudsNode = new Node("clouds");
  private final Geometry[] settlementPool = new Geometry[SETTLEMENT_CAP];
  private final Geometry[] armyPool = new Geometry[ARMY_CAP];
  private final Geometry[] humanPool = new Geometry[Config.MAX_HUMANS];
  private final Geometry[] businessPool = new Geometry[BUSINESS_CAP];
  private final Geometry[] bankPool = new Geometry[BANK_CAP];
  private final Geometry[] statuePool = new Geometry[BANK_CAP];
  private final Geometry[] flagPool = new Geometry[SETTLEMENT_CAP];
  private final Geometry[] firePool = new Geometry[FIRE_CAP];
  private final Geometry[] smokePool = new Geometry[FIRE_CAP];
  private final Geometry[] sparklePool = new Geometry[SPARKLE_CAP];
  private final Geometry[] rainPool = new Geometry[RAIN_CAP];
  private final List<Geometry> cloudGeoms = new ArrayList<>();

  /** Slow-cadence caches of burning/gold cell indices, refreshed alongside
   * trees/deposits in rebuildStatics() and consumed every frame by the
   * (cheap) per-instance fire flicker / sparkle animation. */
  private final List<Integer> burningCache = new ArrayList<>();
  private final List<Integer> goldCache = new ArrayList<>();

  private final Node monsterGeom;
  private double monsterLastX = Double.NaN, monsterLastZ, monsterYaw = 0;
  private final List<Geometry> tornadoGeoms = new ArrayList<>();
  private final Geometry selectionRing;
  private final Geometry brushRing;
  private final Node nationLabelNode = new Node("nationLabelBillboard");
  private final com.jme3.font.BitmapText nationLabelText;

  public EntityRenderer(Node root, AssetManager assets, WorldGrid grid, NationColorLookup nationColor) {
    this.root = root;
    this.assets = assets;
    this.grid = grid;
    this.nationColor = nationColor;

    // Every prop below is built from stacked/merged boxes instead of
    // rounded cones/cylinders/toruses - the whole world reads as voxel
    // construction, not just the ground. Sized against a 1x1x1 terrain
    // block: a person used to stand less than half a block tall (like an
    // ant on a paving stone), trees barely topped a person's height, and
    // a "city" marker was already over 2 blocks tall while a "hut" was
    // under 1 - none of it read as one consistent world. A person is now
    // a believable ~0.8 of a block tall, and every other prop is scaled
    // relative to that, not to its own disconnected old number.
    treeCanopyTemplate = MeshUtil.mergeMeshes(
        new Box(0.55f, 0.42f, 0.55f),
        MeshUtil.translatedCopy(new Box(0.38f, 0.32f, 0.38f), 0, 0.55f, 0));
    treeTrunkTemplate = new Box(0.15f, 0.42f, 0.15f);
    depositTemplate = MeshUtil.buildGem(0.4f, 0.55f);
    // stone used to share the same smooth "gem" mesh as iron/gold ore,
    // which reads fine as a polished crystal but looks like a bland blob
    // for plain rock - a jumbled boulder cluster instead
    stoneDepositTemplate = MeshUtil.buildRockCluster(0.5f);
    humanTemplate = MeshUtil.mergeMeshes(
        new Box(0.15f, 0.5f, 0.12f),
        MeshUtil.translatedCopy(new Box(0.13f, 0.13f, 0.13f), 0, 0.63f, 0));
    armyTemplate = MeshUtil.mergeMeshes(
        new Box(0.24f, 0.55f, 0.2f),
        MeshUtil.translatedCopy(new Box(0.16f, 0.16f, 0.16f), 0, 0.7f, 0));

    // settlement tiers: a small hut, a boxy town hall, a tall stacked city
    hutTemplate = MeshUtil.mergeMeshes(
        new Box(0.65f, 0.45f, 0.65f),
        MeshUtil.translatedCopy(new Box(0.5f, 0.28f, 0.5f), 0, 0.73f, 0));
    townTemplate = new Box(0.85f, 0.8f, 0.85f);
    cityTemplate = MeshUtil.mergeMeshes(
        MeshUtil.translatedCopy(new Box(0.95f, 1.1f, 0.95f), 0, 1.1f, 0),
        MeshUtil.translatedCopy(new Box(0.62f, 0.7f, 0.62f), 0, 2.9f, 0));

    businessTemplate = new Box(0.34f, 0.34f, 0.34f);
    bankTemplate = MeshUtil.mergeMeshes(
        new Box(0.4f, 0.78f, 0.4f),
        MeshUtil.translatedCopy(new Box(0.52f, 0.18f, 0.52f), 0, 0.96f, 0));

    // a farm reads as low tilled rows, not a generic cube
    farmTemplate = MeshUtil.mergeMeshes(
        MeshUtil.mergeMeshes(
            MeshUtil.translatedCopy(new Box(0.5f, 0.08f, 0.14f), 0, 0.08f, -0.32f),
            MeshUtil.translatedCopy(new Box(0.5f, 0.08f, 0.14f), 0, 0.08f, 0)),
        MeshUtil.translatedCopy(new Box(0.5f, 0.08f, 0.14f), 0, 0.08f, 0.32f));
    // a market stall: a peaked awning over a low counter
    marketTemplate = MeshUtil.mergeMeshes(
        new Box(0.37f, 0.22f, 0.37f),
        MeshUtil.translatedCopy(new Box(0.47f, 0.06f, 0.47f), 0, 0.5f, 0));
    // a simple plinth-and-obelisk landmark planted at every nation's capital
    statueTemplate = MeshUtil.mergeMeshes(
        new Box(0.47f, 0.13f, 0.47f),
        MeshUtil.translatedCopy(new Box(0.16f, 0.78f, 0.16f), 0, 0.78f, 0));

    // nation banner: a slim pole with a small flag near the top, planted
    // beside every settlement so a nation's color reads at a glance.
    Mesh pole = MeshUtil.translatedCopy(new Box(0.05f, 0.85f, 0.05f), 0, 0.86f, 0);
    Mesh cloth = MeshUtil.translatedCopy(new Box(0.22f, 0.13f, 0.03f), 0.22f, 1.45f, 0);
    flagTemplate = MeshUtil.mergeMeshes(pole, cloth);

    fireTemplate = MeshUtil.mergeMeshes(
        new Box(0.2f, 0.24f, 0.2f),
        MeshUtil.translatedCopy(new Box(0.11f, 0.18f, 0.11f), 0, 0.4f, 0));

    sparkleTemplate = MeshUtil.buildGem(0.13f, 0.25f);

    // a puffy cloud: three overlapping flat boxes so it doesn't read as
    // one obvious slab from below
    cloudTemplate = MeshUtil.mergeMeshes(MeshUtil.mergeMeshes(
        new Box(1.6f, 0.32f, 1.1f),
        MeshUtil.translatedCopy(new Box(1f, 0.26f, 0.9f), 1.1f, 0.08f, 0.2f)),
        MeshUtil.translatedCopy(new Box(0.9f, 0.24f, 0.85f), -1f, 0.05f, -0.15f));
    rainTemplate = new Box(0.02f, 0.32f, 0.02f);

    // ground-level foliage: two thin crossed blades reading as a little
    // tuft of grass/weeds from any angle, not just a flat card that
    // vanishes edge-on. Bottom sits at local y=0 like the rock cluster.
    Mesh bladeA = MeshUtil.translatedCopy(new Box(0.11f, 0.09f, 0.015f), 0, 0.09f, 0);
    Mesh bladeB = new Box(0.015f, 0.09f, 0.11f);
    MeshUtil.rotateYInPlace(bladeB, 0.3f);
    bladeB = MeshUtil.translatedCopy(bladeB, 0, 0.09f, 0);
    foliageTemplate = MeshUtil.mergeMeshes(bladeA, bladeB);
    flowerTemplate = MeshUtil.buildGem(0.045f, 0.09f);

    // small satellite houses scattered around a settlement's main building
    // - the town-hall marker alone read as a single icon, not a village
    houseTemplate = MeshUtil.mergeMeshes(
        new Box(0.34f, 0.22f, 0.34f),
        MeshUtil.translatedCopy(new Box(0.25f, 0.14f, 0.25f), 0, 0.36f, 0));

    treesGeom = new Geometry("Trees", treeCanopyTemplate.deepClone());
    treesGeom.setMaterial(vertexColorMaterial());
    root.attachChild(treesGeom);

    treeTrunksGeom = new Geometry("TreeTrunks", treeTrunkTemplate.deepClone());
    treeTrunksGeom.setMaterial(vertexColorMaterial());
    root.attachChild(treeTrunksGeom);

    depositsGeom = new Geometry("Deposits", depositTemplate.deepClone());
    depositsGeom.setMaterial(vertexColorMaterial());
    root.attachChild(depositsGeom);

    stoneDepositsGeom = new Geometry("StoneDeposits", stoneDepositTemplate.deepClone());
    stoneDepositsGeom.setMaterial(vertexColorMaterial());
    root.attachChild(stoneDepositsGeom);

    foliageGeom = new Geometry("Foliage", foliageTemplate.deepClone());
    foliageGeom.setMaterial(vertexColorMaterial());
    foliageGeom.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
    root.attachChild(foliageGeom);

    flowersGeom = new Geometry("Flowers", flowerTemplate.deepClone());
    flowersGeom.setMaterial(vertexColorMaterial());
    flowersGeom.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
    root.attachChild(flowersGeom);

    housesGeom = new Geometry("Houses", houseTemplate.deepClone());
    housesGeom.setMaterial(vertexColorMaterial());
    root.attachChild(housesGeom);

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

    for (int i = 0; i < BANK_CAP; i++) {
      Geometry g = new Geometry("Statue" + i, statueTemplate);
      g.setMaterial(soloColorMaterial(new ColorRGBA(0.75f, 0.75f, 0.78f, 1f)));
      g.setCullHint(Spatial.CullHint.Always);
      statuesNode.attachChild(g);
      statuePool[i] = g;
    }
    root.attachChild(statuesNode);

    for (int i = 0; i < SETTLEMENT_CAP; i++) {
      Geometry g = new Geometry("Flag" + i, flagTemplate);
      g.setMaterial(soloColorMaterial(ColorRGBA.White));
      g.setCullHint(Spatial.CullHint.Always);
      flagsNode.attachChild(g);
      flagPool[i] = g;
    }
    root.attachChild(flagsNode);

    for (int i = 0; i < FIRE_CAP; i++) {
      Geometry g = new Geometry("Fire" + i, fireTemplate);
      g.setMaterial(soloColorMaterial(FLAME_A));
      g.setCullHint(Spatial.CullHint.Always);
      firesNode.attachChild(g);
      firePool[i] = g;
    }
    root.attachChild(firesNode);

    // a soft, rising, fading puff above every burning cell - cheap enough
    // (one low-poly sphere each) to run alongside the flames themselves
    com.jme3.scene.shape.Sphere smokeMesh = new com.jme3.scene.shape.Sphere(6, 6, 0.35f);
    for (int i = 0; i < FIRE_CAP; i++) {
      Geometry g = new Geometry("Smoke" + i, smokeMesh);
      Material smokeMat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
      smokeMat.setColor("Color", new ColorRGBA(0.35f, 0.35f, 0.37f, 0.5f));
      smokeMat.setTransparent(true);
      smokeMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
      g.setMaterial(smokeMat);
      g.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
      g.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
      g.setCullHint(Spatial.CullHint.Always);
      smokeNode.attachChild(g);
      smokePool[i] = g;
    }
    root.attachChild(smokeNode);

    for (int i = 0; i < SPARKLE_CAP; i++) {
      Geometry g = new Geometry("Sparkle" + i, sparkleTemplate);
      g.setMaterial(soloColorMaterial(SPARKLE_COLOR));
      g.setCullHint(Spatial.CullHint.Always);
      sparklesNode.attachChild(g);
      sparklePool[i] = g;
    }
    root.attachChild(sparklesNode);

    ColorRGBA rainColor = new ColorRGBA(0.62f, 0.72f, 0.85f, 0.6f);
    for (int i = 0; i < RAIN_CAP; i++) {
      Geometry g = new Geometry("Rain" + i, rainTemplate);
      Material rainMat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
      rainMat.setColor("Color", rainColor);
      rainMat.setTransparent(true);
      rainMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
      g.setMaterial(rainMat);
      g.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
      g.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
      g.setCullHint(Spatial.CullHint.Always);
      rainNode.attachChild(g);
      rainPool[i] = g;
    }
    root.attachChild(rainNode);
    root.attachChild(cloudsNode);

    // a plain floating cube read as an inert "big moving block" rather
    // than a creature - a body/head/spine silhouette at least reads as
    // something alive, and it now turns to face its direction of travel
    // instead of spinning in place for no reason
    monsterGeom = new Node("Monster");
    Material monsterMat = soloColorMaterial(new ColorRGBA(0.29f, 0.06f, 0.19f, 1f));
    Material spikeMat = soloColorMaterial(new ColorRGBA(0.55f, 0.12f, 0.08f, 1f));
    Geometry monsterBody = new Geometry("MonsterBody", new Box(1.1f, 0.75f, 1.7f));
    monsterBody.setMaterial(monsterMat);
    monsterGeom.attachChild(monsterBody);
    Geometry monsterHead = new Geometry("MonsterHead", new Box(0.65f, 0.55f, 0.65f));
    monsterHead.setMaterial(monsterMat);
    monsterHead.setLocalTranslation(0, 0.25f, 1.9f);
    monsterGeom.attachChild(monsterHead);
    for (float sz : new float[]{-1.1f, -0.3f, 0.5f, 1.2f}) {
      Geometry spike = new Geometry("MonsterSpike", new Box(0.15f, 0.5f, 0.15f));
      spike.setMaterial(spikeMat);
      spike.setLocalTranslation(0, 0.9f, sz);
      monsterGeom.attachChild(spike);
    }
    monsterGeom.setCullHint(Spatial.CullHint.Always);
    root.attachChild(monsterGeom);

    Torus ringMesh = new Torus(24, 6, 0.06f, 0.8f);
    selectionRing = new Geometry("Selection", ringMesh);
    Material ringMat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
    ringMat.setColor("Color", ColorRGBA.White);
    selectionRing.setMaterial(ringMat);
    selectionRing.setLocalRotation(new Quaternion().fromAngleAxis((float) Math.PI / 2, Vector3f.UNIT_X));
    selectionRing.setCullHint(Spatial.CullHint.Always);
    selectionRing.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
    root.attachChild(selectionRing);

    // unit-radius ring, scaled per-frame to the active tool's actual brush
    // radius so the player can see exactly what a click will affect
    Torus brushMesh = new Torus(28, 5, 0.045f, 1f);
    brushRing = new Geometry("BrushIndicator", brushMesh);
    Material brushMat = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
    brushMat.setColor("Color", new ColorRGBA(1f, 0.92f, 0.3f, 0.85f));
    brushMat.setTransparent(true);
    brushMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
    brushRing.setMaterial(brushMat);
    brushRing.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
    brushRing.setLocalRotation(new Quaternion().fromAngleAxis((float) Math.PI / 2, Vector3f.UNIT_X));
    brushRing.setCullHint(Spatial.CullHint.Always);
    brushRing.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
    root.attachChild(brushRing);

    // billboarded nation-name label, shown floating above the capital
    // whenever that nation is the current selection
    com.jme3.font.BitmapFont font = assets.loadFont("Interface/Fonts/Default.fnt");
    nationLabelText = new com.jme3.font.BitmapText(font);
    nationLabelText.setSize(0.32f);
    nationLabelText.setColor(ColorRGBA.White);
    nationLabelNode.attachChild(nationLabelText);
    nationLabelNode.addControl(new com.jme3.scene.control.BillboardControl());
    nationLabelNode.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
    nationLabelNode.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
    nationLabelNode.setCullHint(Spatial.CullHint.Always);
    root.attachChild(nationLabelNode);
  }

  /** Shows `text` floating above (x,h,z), billboarded to always face the
   * camera - used to hang a nation's name over its capital while that
   * nation is selected. */
  public void setNationLabel(String text, float x, float h, float z, boolean visible) {
    nationLabelNode.setCullHint(visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
    if (!visible) return;
    if (!nationLabelText.getText().equals(text)) nationLabelText.setText(text);
    float width = nationLabelText.getLineWidth();
    nationLabelText.setLocalTranslation(-width / 2f, 0, 0);
    nationLabelNode.setLocalTranslation(x, h + 4.2f, z);
  }

  private Material vertexColorMaterial() {
    Material mat = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
    mat.setBoolean("UseVertexColor", true);
    mat.setColor("Specular", ColorRGBA.Black);
    mat.setFloat("Shininess", 1f);
    return mat;
  }

  private Material soloColorMaterial(ColorRGBA c) {
    Material mat = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
    mat.setBoolean("UseMaterialColors", true);
    mat.setColor("Diffuse", c);
    mat.setColor("Ambient", c);
    mat.setColor("Specular", ColorRGBA.Black);
    mat.setFloat("Shininess", 1f);
    return mat;
  }

  /** soloColorMaterial() encodes a flat color as both Diffuse and Ambient
   * under Lighting.j3md; every runtime re-color needs to touch both to
   * keep matching the old "just set Color" behavior. */
  private static void setSoloColor(Material mat, ColorRGBA c) {
    mat.setColor("Diffuse", c);
    mat.setColor("Ambient", c);
  }

  public void setGrid(GameState state) {
    this.grid = state.grid;
    for (Geometry g : tornadoGeoms) g.removeFromParent();
    tornadoGeoms.clear();
    for (Geometry g : cloudGeoms) g.removeFromParent();
    cloudGeoms.clear();
    monsterGeom.setCullHint(Spatial.CullHint.Always);
    rebuildStatics(state);
  }

  private ColorRGBA nationOrFallback(int nationId, ColorRGBA fallback) {
    if (nationId == Config.UNDEAD_NATION_ID) return ZOMBIE_COLOR;
    if (nationColor != null) {
      ColorRGBA c = nationColor.colorFor(nationId);
      if (c != null) return c;
    }
    return fallback;
  }

  /** Deterministic per-cell offset so trees/deposits don't all sit dead
   * center in their grid cell, which read as an obvious planted-in-rows
   * grid from any distance. Same hash-and-fold trick as
   * VoxelChunkRenderer's block mottling - stable across rebuilds (no
   * popping/resettling every time statics refresh) without needing to
   * store anything per-cell. */
  private static float jitterAxis(int x, int y, int salt) {
    int h = x * 374761393 + y * 668265263 + salt * 2147483647;
    h = (h ^ (h >>> 13)) * 1274126177;
    h = h ^ (h >>> 16);
    return ((h & 0xFFFF) / 65535f - 0.5f) * 0.62f;
  }

  /** Same hash family as jitterAxis but folded to a plain 0..1 - used for
   * per-cell yes/no decisions (does this grass cell get a foliage tuft,
   * is it a flower) that need to be stable across rebuilds, not another
   * position offset. */
  private static float hash01(int x, int y, int salt) {
    int h = x * 374761393 + y * 668265263 + salt * 2147483647;
    h = (h ^ (h >>> 13)) * 1274126177;
    h = h ^ (h >>> 16);
    return (h & 0xFFFF) / 65535f;
  }

  /** Trees/deposits/houses barely move; rebuild their instance lists on a
   * slow cadence instead of every frame. */
  public void rebuildStatics(GameState state) {
    List<PropBatcher.Placement> canopies = new ArrayList<>();
    List<PropBatcher.Placement> trunks = new ArrayList<>();
    List<PropBatcher.Placement> deposits = new ArrayList<>();
    List<PropBatcher.Placement> stoneDeposits = new ArrayList<>();
    List<PropBatcher.Placement> foliage = new ArrayList<>();
    List<PropBatcher.Placement> flowers = new ArrayList<>();
    burningCache.clear();
    goldCache.clear();
    for (int y = 0; y < grid.rows; y++) {
      for (int x = 0; x < grid.cols; x++) {
        int i = grid.idx(x, y);
        byte res = grid.resource[i];
        if (res == Config.RES_FOREST && canopies.size() < TREE_CAP_SAMPLE) {
          float scale = 0.6f + Math.min(1f, grid.resourceAmount[i] / 48f) * 0.6f;
          float rotY = (float) ((x * 7 + y * 13) % 6.28);
          float jx = x + 0.5f + jitterAxis(x, y, 1);
          float jz = y + 0.5f + jitterAxis(x, y, 2);
          // trunkTemplate is centered at its own origin (half-height
          // 0.42), so it needs to be lifted by half its (scaled) height
          // to actually sit on the ground instead of being buried in it
          float trunkHalf = 0.42f * scale;
          float trunkTop = grid.height[i] + trunkHalf * 2;
          trunks.add(new PropBatcher.Placement(jx, grid.height[i] + trunkHalf, jz, rotY, scale, TRUNK_COLOR));
          canopies.add(new PropBatcher.Placement(jx, trunkTop, jz, rotY, scale, TREE_COLOR));
        } else if (res == Config.RES_STONE && stoneDeposits.size() < DEPOSIT_CAP_SAMPLE) {
          float rotY = (float) ((x * 3 + y * 5) % 6.28);
          ColorRGBA c = DEPOSIT_COLORS.getOrDefault(res, ColorRGBA.White);
          float jx = x + 0.5f + jitterAxis(x, y, 3);
          float jz = y + 0.5f + jitterAxis(x, y, 4);
          // buildRockCluster's boxes are each lifted by their own half
          // height already, so the cluster's bottom sits flush at local
          // y=0 - no extra ground offset needed, unlike the origin-
          // centered gem template below
          stoneDeposits.add(new PropBatcher.Placement(jx, grid.height[i], jz, rotY, 1f, c));
        } else if (res != Config.RES_NONE && res != Config.RES_FOREST && deposits.size() < DEPOSIT_CAP_SAMPLE) {
          float rotY = (float) ((x * 3 + y * 5) % 6.28);
          ColorRGBA c = DEPOSIT_COLORS.getOrDefault(res, ColorRGBA.White);
          float jx = x + 0.5f + jitterAxis(x, y, 3);
          float jz = y + 0.5f + jitterAxis(x, y, 4);
          deposits.add(new PropBatcher.Placement(jx, grid.height[i] + 0.28f, jz, rotY, 1f, c));
        } else if (grid.terrain[i] == Config.GRASS && res == Config.RES_NONE
            && foliage.size() < FOLIAGE_CAP_SAMPLE && hash01(x, y, 6) < 0.14f) {
          // sparse, patchy coverage rather than every single grass block -
          // real ground cover grows in clumps, not a uniform carpet, and a
          // literal every-cell carpet would blow well past a sane vertex
          // budget on a 256x256 map anyway
          float rotY = hash01(x, y, 7) * 6.28f;
          float jx = x + 0.5f + jitterAxis(x, y, 8);
          float jz = y + 0.5f + jitterAxis(x, y, 9);
          float scale = 0.7f + hash01(x, y, 10) * 0.7f;
          ColorRGBA tuftColor = FOLIAGE_COLOR.clone().interpolateLocal(TREE_COLOR, hash01(x, y, 11) * 0.4f);
          foliage.add(new PropBatcher.Placement(jx, grid.height[i], jz, rotY, scale, tuftColor));
          if (hash01(x, y, 12) < 0.1f && flowers.size() < FOLIAGE_CAP_SAMPLE) {
            ColorRGBA fc = FLOWER_COLORS[(int) (hash01(x, y, 13) * FLOWER_COLORS.length) % FLOWER_COLORS.length];
            flowers.add(new PropBatcher.Placement(jx, grid.height[i] + 0.14f * scale, jz, rotY, scale, fc));
          }
        }
        if (res == Config.RES_GOLD && goldCache.size() < SPARKLE_CAP) goldCache.add(i);
        if (grid.burning[i] && burningCache.size() < FIRE_CAP) burningCache.add(i);
      }
    }
    treesGeom.setMesh(PropBatcher.bake(treeCanopyTemplate, canopies));
    treesGeom.setCullHint(canopies.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
    treeTrunksGeom.setMesh(PropBatcher.bake(treeTrunkTemplate, trunks));
    treeTrunksGeom.setCullHint(trunks.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
    depositsGeom.setMesh(PropBatcher.bake(depositTemplate, deposits));
    depositsGeom.setCullHint(deposits.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
    stoneDepositsGeom.setMesh(PropBatcher.bake(stoneDepositTemplate, stoneDeposits));
    stoneDepositsGeom.setCullHint(stoneDeposits.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
    foliageGeom.setMesh(PropBatcher.bake(foliageTemplate, foliage));
    foliageGeom.setCullHint(foliage.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
    flowersGeom.setMesh(PropBatcher.bake(flowerTemplate, flowers));
    flowersGeom.setCullHint(flowers.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);

    // one pass over every human to count actual housed residents per
    // settlement - cheap here (throttled to every 20 ticks) and avoids an
    // O(settlements x humans) scan below
    Map<Integer, Integer> housedBySettlement = new HashMap<>();
    for (Human h : state.humans) {
      if (h.hasHouse) housedBySettlement.merge(h.settlementId, 1, Integer::sum);
    }

    List<PropBatcher.Placement> houses = new ArrayList<>();
    for (Settlement s : state.settlements.values()) {
      if (houses.size() >= HOUSE_CAP_SAMPLE) break;
      if (s.populationCount < 1) continue;
      Nation nation = state.nations.get(s.nationId);
      ColorRGBA color = nation != null ? nationOrFallback(nation.id, HOUSE_FALLBACK) : HOUSE_FALLBACK;
      // a vacant house reads as an obviously washed-out, duller version
      // of the nation's color instead of looking identical to an
      // occupied one
      ColorRGBA vacantColor = color.clone().interpolateLocal(ColorRGBA.White, 0.65f);
      vacantColor.a = 0.75f;
      int housed = housedBySettlement.getOrDefault(s.id, 0);
      int occupiedHouses = (int) Math.ceil(housed / Settlement.PEOPLE_PER_HOUSE);
      // even a lone founder gets a couple of houses instead of nothing;
      // a full-size settlement gets a real cluster instead of topping
      // out at 10 no matter how big it's grown
      int houseCount = Math.min(32, Math.max(2 + s.populationCount / 3, occupiedHouses));
      for (int i = 0; i < houseCount && houses.size() < HOUSE_CAP_SAMPLE; i++) {
        // a spiral placement (radius grows with i) reads as an organic
        // cluster of streets/blocks instead of a single uniform ring
        float angle = i * 2.4f + s.id * 0.7f;
        float radius = 1.0f + (i % 6) * 0.5f + (i / 6) * 0.7f;
        float hx = s.x + 0.5f + (float) Math.cos(angle) * radius;
        float hz = s.z + 0.5f + (float) Math.sin(angle) * radius;
        int gx = clampIdx((int) Math.floor(hx), grid.cols), gz = clampIdx((int) Math.floor(hz), grid.rows);
        float hh = grid.height[grid.idx(gx, gz)];
        ColorRGBA c = i < occupiedHouses ? color : vacantColor;
        float houseScale = 0.55f + (i % 4) * 0.09f;
        // houseTemplate's base box is centered at its own origin (half-
        // height 0.22) so it needs lifting by half its scaled height
        houses.add(new PropBatcher.Placement(hx, hh + 0.22f * houseScale, hz, angle, houseScale, c));
      }
    }
    housesGeom.setMesh(PropBatcher.bake(houseTemplate, houses));
    housesGeom.setCullHint(houses.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
  }

  public void update(GameState state, float alpha) {
    updateSettlements(state);
    updateArmies(state, alpha);
    updateHumans(state, alpha);
    updateBusinesses(state);
    updateBanks(state);
    updateStatues(state);
    updateMonster(state);
    updateTornadoes(state);
    updateFires(state);
    updateSmoke(state);
    updateSparkles(state);
    updateWeather(state);
  }

  private Mesh tierTemplate(int population) {
    if (population >= 35) return cityTemplate;
    if (population >= 15) return townTemplate;
    return hutTemplate;
  }

  // each tier's base box is centered at its own local origin, so it needs
  // to be lifted by half its own height to sit on the ground rather than
  // being buried - hut/town/city all have different base heights
  private float tierGroundOffset(int population) {
    if (population >= 35) return 1.1f;
    if (population >= 15) return 0.8f;
    return 0.45f;
  }

  private void updateSettlements(GameState state) {
    int i = 0;
    for (Settlement s : state.settlements.values()) {
      if (i >= SETTLEMENT_CAP) break;
      Geometry g = settlementPool[i];
      float h = grid.height[grid.idx(s.x, s.z)];
      float scale = (float) (0.55 + Math.sqrt(Math.max(1, s.populationCount)) * 0.13);
      g.setMesh(tierTemplate(s.populationCount));
      g.setLocalTranslation(s.x + 0.5f, h + tierGroundOffset(s.populationCount) * scale, s.z + 0.5f);
      g.setLocalScale(scale);
      Geometry flag = flagPool[i];
      if (s.abandoned) {
        // ruin: structure stays standing (per design) but drained of its
        // nation's color and flying no flag - a dead settlement, not a
        // living one that just happens to have a small population
        setSoloColor(g.getMaterial(), RUIN_COLOR);
        flag.setCullHint(Spatial.CullHint.Always);
      } else {
        Nation nation = state.nations.get(s.nationId);
        ColorRGBA c = nation != null ? nationOrFallback(nation.id, ColorRGBA.Gray) : ColorRGBA.Gray;
        setSoloColor(g.getMaterial(), c);
        float flagOffset = scale * 0.55f + 0.65f;
        flag.setLocalTranslation(s.x + 0.5f + flagOffset, h, s.z + 0.5f + flagOffset);
        flag.setLocalScale(0.85f);
        setSoloColor(flag.getMaterial(), c);
        flag.setCullHint(Spatial.CullHint.Inherit);
      }
      g.setUserData("settlementId", s.id);
      g.setCullHint(Spatial.CullHint.Inherit);
      i++;
    }
    for (; i < SETTLEMENT_CAP; i++) {
      settlementPool[i].setCullHint(Spatial.CullHint.Always);
      flagPool[i].setCullHint(Spatial.CullHint.Always);
    }
  }

  private void updateFires(GameState state) {
    int count = Math.min(FIRE_CAP, burningCache.size());
    for (int i = 0; i < count; i++) {
      int cell = burningCache.get(i);
      int gx = cell % grid.cols, gz = cell / grid.cols;
      Geometry g = firePool[i];
      float h = grid.height[cell];
      float flicker = (float) Math.sin(state.tick * 0.4 + i * 1.7) * 0.5f + 0.5f;
      float fireScale = 0.8f + flicker * 0.5f;
      g.setLocalTranslation(gx + 0.5f, h + 0.24f * fireScale, gz + 0.5f);
      g.setLocalScale(fireScale);
      setSoloColor(g.getMaterial(), lerpColor(FLAME_A, FLAME_B, flicker));
      g.setCullHint(Spatial.CullHint.Inherit);
    }
    for (int i = count; i < FIRE_CAP; i++) firePool[i].setCullHint(Spatial.CullHint.Always);
  }

  private void updateSmoke(GameState state) {
    int count = Math.min(FIRE_CAP, burningCache.size());
    for (int i = 0; i < count; i++) {
      int cell = burningCache.get(i);
      int gx = cell % grid.cols, gz = cell / grid.cols;
      Geometry g = smokePool[i];
      float h = grid.height[cell];
      float phase = ((state.tick * 0.012f) + i * 0.37f) % 1f;
      float rise = phase * 3.2f;
      float drift = (float) Math.sin(i * 1.3f + phase * 2f) * 0.3f;
      float alpha = (float) Math.sin(phase * Math.PI) * 0.4f;
      float scale = 0.4f + phase * 0.8f;
      g.setLocalTranslation(gx + 0.5f + drift, h + 0.7f + rise, gz + 0.5f + drift * 0.6f);
      g.setLocalScale(scale);
      Material mat = g.getMaterial();
      mat.setColor("Color", new ColorRGBA(0.33f, 0.33f, 0.35f, Math.max(0f, alpha)));
      g.setCullHint(Spatial.CullHint.Inherit);
    }
    for (int i = count; i < FIRE_CAP; i++) smokePool[i].setCullHint(Spatial.CullHint.Always);
  }

  private void updateSparkles(GameState state) {
    int count = Math.min(SPARKLE_CAP, goldCache.size());
    for (int i = 0; i < count; i++) {
      int cell = goldCache.get(i);
      int gx = cell % grid.cols, gz = cell / grid.cols;
      Geometry g = sparklePool[i];
      float h = grid.height[cell];
      float twinkle = (float) Math.sin(state.tick * 0.15 + i * 2.3) * 0.5f + 0.5f;
      g.setLocalTranslation(gx + 0.5f, h + 0.55f + twinkle * 0.08f, gz + 0.5f);
      g.setLocalScale(0.5f + twinkle * 0.6f);
      setSoloColor(g.getMaterial(), SPARKLE_COLOR.mult(0.7f + twinkle * 0.5f));
      g.setCullHint(Spatial.CullHint.Inherit);
    }
    for (int i = count; i < SPARKLE_CAP; i++) sparklePool[i].setCullHint(Spatial.CullHint.Always);
  }

  private static ColorRGBA lerpColor(ColorRGBA a, ColorRGBA b, float t) {
    return new ColorRGBA(
        a.r + (b.r - a.r) * t,
        a.g + (b.g - a.g) * t,
        a.b + (b.b - a.b) * t,
        1f);
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
      float scale = (float) (0.7 + Math.min(1.5, b.capital / 60.0));
      // farmTemplate is already ground-anchored at construction; market's
      // and the default extraction cube's base boxes are centered on
      // their own origin and need lifting by half their own height
      float groundOffset = b.type.equals("farm") ? 0f : b.type.equals("market") ? 0.22f : 0.34f;
      g.setLocalTranslation(s.x + 0.5f + ox, h + groundOffset * scale, s.z + 0.5f + oz);
      g.setLocalScale(scale);
      g.setMesh(b.type.equals("farm") ? farmTemplate : b.type.equals("market") ? marketTemplate : businessTemplate);
      ColorRGBA color = b.type.equals("market") ? new ColorRGBA(0.82f, 0.66f, 0.35f, 1f)
          : BUSINESS_COLORS.getOrDefault(b.resourceKey, ColorRGBA.White);
      setSoloColor(g.getMaterial(), color);
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
      g.setLocalTranslation(capital.x + 0.5f - 1.6f, h + 0.4f * 0.7f, capital.z + 0.5f - 1.6f);
      g.setLocalScale(0.7f);
      setSoloColor(g.getMaterial(), n.bank.justCrashed ? ColorRGBA.Red : BANK_COLOR);
      g.setCullHint(Spatial.CullHint.Inherit);
      i++;
    }
    for (; i < BANK_CAP; i++) bankPool[i].setCullHint(Spatial.CullHint.Always);
  }

  /** A simple plinth-and-obelisk landmark at every nation's capital - a
   * small bit of civic identity beyond a bare marker+flag, and an easy
   * way to spot a capital from a distance. */
  private void updateStatues(GameState state) {
    int i = 0;
    for (Nation n : state.nations.values()) {
      if (i >= BANK_CAP) break;
      Settlement capital = state.settlements.get(n.capitalSettlementId);
      if (capital == null) continue;
      Geometry g = statuePool[i];
      float h = grid.height[grid.idx(capital.x, capital.z)];
      g.setLocalTranslation(capital.x + 0.5f + 1.6f, h + 0.13f * 0.8f, capital.z + 0.5f - 1.6f);
      g.setLocalScale(0.8f);
      g.setCullHint(Spatial.CullHint.Inherit);
      i++;
    }
    for (; i < BANK_CAP; i++) statuePool[i].setCullHint(Spatial.CullHint.Always);
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
      // a battle used to be two numbers quietly shrinking with nothing to
      // see - an army actually trading blows this tick now visibly flares
      // and jitters instead of just standing there
      boolean fighting = a.combatFlashTimer > 0;
      float flash = fighting ? a.combatFlashTimer / 18f : 0f;
      if (fighting) scale *= 1f + flash * 0.35f;
      g.setLocalTranslation((float) x + (fighting ? (float) (Math.random() - 0.5) * 0.15f : 0f),
          h + 0.55f * scale, (float) z + (fighting ? (float) (Math.random() - 0.5) * 0.15f : 0f));
      g.setLocalScale(scale);
      g.setLocalRotation(new Quaternion().fromAngleAxis(state.tick * 0.05f, Vector3f.UNIT_Y));
      Nation nation = state.nations.get(a.nationId);
      ColorRGBA c = nation != null ? nationOrFallback(nation.id, ColorRGBA.White) : ColorRGBA.White;
      if (fighting) c = c.clone().interpolateLocal(new ColorRGBA(1f, 0.15f, 0.05f, 1f), flash);
      setSoloColor(g.getMaterial(), c);
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
      g.setLocalTranslation((float) x, hgt + 0.5f, (float) z);
      double dx = h.x - h.prevX, dz = h.z - h.prevZ;
      if (Math.abs(dx) > 1e-5 || Math.abs(dz) > 1e-5) {
        float yaw = (float) Math.atan2(dx, dz);
        g.setLocalRotation(new Quaternion().fromAngleAxis(yaw, Vector3f.UNIT_Y));
      }
      ColorRGBA c = h.nationId == Config.UNDEAD_NATION_ID
          ? ZOMBIE_COLOR
          : nationOrFallback(h.nationId, new ColorRGBA(0.6f, 0.6f, 0.65f, 1f));
      setSoloColor(g.getMaterial(), c);
      g.setUserData("humanId", h.id);
      g.setCullHint(Spatial.CullHint.Inherit);
    }
    for (int i = n; i < Config.MAX_HUMANS; i++) humanPool[i].setCullHint(Spatial.CullHint.Always);
  }

  private void updateMonster(GameState state) {
    Monster m = state.monster;
    if (m == null) { monsterGeom.setCullHint(Spatial.CullHint.Always); monsterLastX = Double.NaN; return; }
    monsterGeom.setCullHint(Spatial.CullHint.Inherit);
    int gx = clampIdx((int) Math.floor(m.x), grid.cols), gz = clampIdx((int) Math.floor(m.z), grid.rows);
    float h = grid.height[grid.idx(gx, gz)];
    float scale = (float) (0.7 + (m.hp / m.maxHp) * 0.6);
    monsterGeom.setLocalTranslation((float) m.x, h + 1.1f * scale, (float) m.z);
    monsterGeom.setLocalScale(scale);
    if (!Double.isNaN(monsterLastX)) {
      double dx = m.x - monsterLastX, dz = m.z - monsterLastZ;
      if (dx * dx + dz * dz > 1e-6) monsterYaw = Math.atan2(dx, dz);
    }
    monsterGeom.setLocalRotation(new Quaternion().fromAngleAxis((float) monsterYaw, Vector3f.UNIT_Y));
    monsterLastX = m.x;
    monsterLastZ = m.z;
  }

  private static final ColorRGBA CLOUD_COLOR = new ColorRGBA(0.96f, 0.97f, 0.99f, 0.85f);
  private static final ColorRGBA STORM_COLOR = new ColorRGBA(0.42f, 0.45f, 0.5f, 0.92f);
  private static final float CLOUD_HEIGHT = 15f;

  private void updateWeather(GameState state) {
    while (cloudGeoms.size() < state.clouds.size()) {
      Geometry g = new Geometry("Cloud", cloudTemplate);
      g.setMaterial(soloColorMaterial(CLOUD_COLOR));
      g.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
      cloudsNode.attachChild(g);
      cloudGeoms.add(g);
    }
    while (cloudGeoms.size() > state.clouds.size()) {
      cloudGeoms.remove(cloudGeoms.size() - 1).removeFromParent();
    }
    List<Cloud> stormClouds = new ArrayList<>();
    for (int i = 0; i < state.clouds.size(); i++) {
      Cloud c = state.clouds.get(i);
      Geometry g = cloudGeoms.get(i);
      float scale = (float) (c.radius / 5.0);
      g.setLocalTranslation((float) c.x, CLOUD_HEIGHT, (float) c.z);
      g.setLocalScale(scale);
      setSoloColor(g.getMaterial(), c.stormy ? STORM_COLOR : CLOUD_COLOR);
      if (c.stormy) stormClouds.add(c);
    }

    // rain: split the shared drop budget across however many storms are
    // actually active right now, each drop falling on a loop from cloud
    // height down to the ground, its horizontal spot within the storm's
    // radius picked once (per slot) via the same hash-scatter trick used
    // for tree/deposit jitter so it doesn't shimmer between frames
    int slot = 0;
    if (!stormClouds.isEmpty()) {
      int perStorm = RAIN_CAP / stormClouds.size();
      for (Cloud c : stormClouds) {
        int gx = clampIdx((int) Math.floor(c.x), grid.cols), gz = clampIdx((int) Math.floor(c.z), grid.rows);
        float groundH = grid.height[grid.idx(gx, gz)];
        for (int j = 0; j < perStorm && slot < RAIN_CAP; j++, slot++) {
          float ox = (jitterAxis(c.id * 97 + j, 11, 5)) * (float) c.radius * 1.6f;
          float oz = (jitterAxis(c.id * 97 + j, 13, 7)) * (float) c.radius * 1.6f;
          float fallSpan = CLOUD_HEIGHT - groundH;
          float phase = ((state.tick * 3 + j * 37) % 100) / 100f;
          float y = CLOUD_HEIGHT - phase * fallSpan;
          Geometry g = rainPool[slot];
          g.setLocalTranslation((float) c.x + ox, y, (float) c.z + oz);
          g.setCullHint(Spatial.CullHint.Inherit);
        }
      }
    }
    for (; slot < RAIN_CAP; slot++) rainPool[slot].setCullHint(Spatial.CullHint.Always);
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
      g.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
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

  /** Shows a ring on the ground at (x,z) sized to `radius` - lets the
   * player see exactly what a terrain tool will affect before clicking. */
  public void setBrushIndicator(float x, float z, float h, float radius, boolean visible) {
    brushRing.setCullHint(visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
    if (visible) {
      brushRing.setLocalTranslation(x, h + 0.22f, z);
      brushRing.setLocalScale(Math.max(0.3f, radius));
    }
  }

  public Node getSettlementsNode() { return settlementsNode; }
  public Node getArmiesNode() { return armiesNode; }
  public Node getHumansNode() { return humansNode; }

  private static int clampIdx(int v, int max) { return Math.max(0, Math.min(max - 1, v)); }
}
