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
  // skin stays a neutral flesh tone regardless of nation - two endpoints
  // individuals get blended between (see skinTone) for some real variety
  // instead of every person sharing one identical tone
  private static final ColorRGBA SKIN_TONE_A = new ColorRGBA(0.89f, 0.70f, 0.55f, 1f);
  private static final ColorRGBA SKIN_TONE_B = new ColorRGBA(0.45f, 0.30f, 0.20f, 1f);
  // a civilian's shoulder joint, in the clothes/skin geometry's own
  // hip-anchored local frame (mesh-local y=0 = hip - see the leg mesh
  // comments above); an arm Geometry's own local origin sits here so
  // rotating it swings from the shoulder, not some arbitrary point.
  private static final float SHOULDER_X = 0.19f;
  private static final float SHOULDER_Y = 0.40f;
  private static final float ARM_SWING_AMPLITUDE = 0.55f; // radians
  // a civilian with no living nation (a wanderer, or one whose nation
  // just fell) reads as this neutral gray - was allocated fresh as a
  // `new ColorRGBA(...)` argument on every single active human, every
  // single frame, regardless of whether it was ever actually used.
  private static final ColorRGBA HUMAN_FALLBACK_COLOR = new ColorRGBA(0.6f, 0.6f, 0.65f, 1f);
  // Scratch Quaternions reused across iterations of the per-frame human
  // update loop instead of `new Quaternion()`-ing 2-3 of them per active
  // person (setLocalRotation always copies the values it's given into the
  // Geometry's own internal Quaternion - see Transform.setRotation - so
  // it's always safe to immediately reuse the scratch instance for the
  // next person right after). At a population in the thousands this was
  // several thousand small heap allocations every single frame, real GC
  // pressure that reads as periodic stutter on real hardware even where
  // it doesn't show up as a raw frame-time average.
  private final Quaternion scratchYaw = new Quaternion();
  private final Quaternion scratchSwing = new Quaternion();
  private final Quaternion scratchArmRot = new Quaternion();
  private static final ColorRGBA COMBAT_FLASH_COLOR = new ColorRGBA(1f, 0.15f, 0.05f, 1f);
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
  // a market's own accent color - used directly rather than through
  // BUSINESS_COLORS since a market is looked up by b.type, not the
  // resourceKey the rest of that map is keyed on.
  private static final ColorRGBA MARKET_COLOR = new ColorRGBA(0.82f, 0.66f, 0.35f, 1f);

  private static final int TREE_CAP_SAMPLE = 2600;
  private static final int DEPOSIT_CAP_SAMPLE = 900;
  // a global cap shared across every settlement on the map - at only 700
  // it ran out after the first couple dozen settlements in iteration
  // order, so most settlements (especially the small ones, which were
  // also excluded outright below) rendered as a single bare marker with
  // no houses around it at all, reading as "a circle with a square in
  // it" instead of a village
  private static final int HOUSE_CAP_SAMPLE = 6000;
  private static final int FOLIAGE_CAP_SAMPLE = 5500;
  // Full per-person detail (separate skin/arm geometries, swing animation,
  // individual click targets) only gets rendered for whoever's within this
  // radius of the camera's focus point, capped at NEAR_HUMAN_CAP - see
  // updateHumans. Everyone else is folded into one shared batched mesh, the
  // same trick already used for trees/houses, so total draw-call cost stops
  // scaling with total population and instead tracks how many people are
  // actually near the camera at once.
  private static final float NEAR_HUMAN_RADIUS = 46f;
  private static final float NEAR_HUMAN_RADIUS2 = NEAR_HUMAN_RADIUS * NEAR_HUMAN_RADIUS;
  private static final int NEAR_HUMAN_CAP = 600;
  // the crowd batch is rebaked only every this-many frames, not every
  // single one - a small, distant figure a few pixels tall doesn't need
  // buttery-smooth per-frame repositioning, and skipping the rebake most
  // frames is what keeps the far-population cost off the per-frame budget
  private static final int CROWD_REBUILD_INTERVAL = 6;
  private static final ColorRGBA HOUSE_FALLBACK = new ColorRGBA(0.8f, 0.75f, 0.62f, 1f);
  private static final ColorRGBA RUIN_COLOR = new ColorRGBA(0.32f, 0.3f, 0.28f, 1f);
  // these used to be tuned for a small 128x128 map with a handful of
  // nations - a long game on the bigger map can easily grow past several
  // hundred settlements/businesses, and anything beyond the cap used to
  // just silently never get a rendering slot at all (a settlement or
  // business that's fully real in the simulation but invisible in the
  // world - reads exactly like "cities vanishing")
  private static final int SETTLEMENT_CAP = 400;
  private static final int SIEGE_LABEL_CAP = 20;
  private static final int LANDMARK_CAP = 120;
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
  private static final ColorRGBA MONUMENT_COLOR = new ColorRGBA(0.68f, 0.6f, 0.42f, 1f);
  private static final ColorRGBA MILITARY_BASE_COLOR = new ColorRGBA(0.35f, 0.38f, 0.26f, 1f);
  private static final ColorRGBA SIEGE_LABEL_COLOR = new ColorRGBA(1f, 0.28f, 0.18f, 1f);

  private final AssetManager assets;
  private final Node root;
  private final NationColorLookup nationColor;
  private WorldGrid grid;

  private final Mesh treeCanopyTemplate, treeTrunkTemplate, depositTemplate, stoneDepositTemplate, humanTemplate;
  /** A civilian's arms are their own separate meshes/geometries, pivoted
   * at the shoulder (mesh-local y=0) so they can actually swing with the
   * walk cycle instead of being rigidly baked into the torso - see
   * updateHumans. The right arm carries whatever job tool (axe/pickaxe)
   * this person is using, merged onto the arm itself so the tool swings
   * with the hand instead of floating fixed on the body. A serving
   * soldier is rendered through this exact same pipeline (see
   * updateHumans' soldier branch) - not a separate creature model - with
   * a weapon mesh on the same arm instead of a job tool. */
  private final Mesh humanArmLTemplate, humanArmRTemplate, humanArmRAxeTemplate, humanArmRPickaxeTemplate;
  /** one merged right-arm+weapon mesh per distinct weapon (see
   * Config.UnitSpec.weapon), keyed by that weapon string - which one a
   * given soldier gets is decided per real person (see pickUnitForHuman),
   * same "swap the mesh, not the geometry" trick as the axe/pickaxe. */
  private final Map<String, Mesh> humanArmRWeaponTemplates;
  /** Skin (head/face) is a separate mesh+geometry from clothing/gear, each
   * with its own material - skin stays a neutral, individually-varied
   * flesh tone while clothing/hair/tools take the nation's accent color,
   * instead of the whole body reading as one flat nation-colored blob. */
  private final Mesh humanSkinTemplate;
  private final Mesh hutTemplate, townTemplate, cityTemplate, businessTemplate, bankTemplate, houseTemplate;
  private final Mesh farmTemplate, marketTemplate, statueTemplate, monumentTemplate, militaryBaseTemplate;
  private final Mesh flagTemplate, fireTemplate, sparkleTemplate;
  private final Mesh cloudTemplate, rainTemplate, foliageTemplate, flowerTemplate;

  private final Geometry treesGeom, treeTrunksGeom, depositsGeom, stoneDepositsGeom, housesGeom;
  private final Geometry foliageGeom, flowersGeom;
  // the far-population LOD batch (see NEAR_HUMAN_RADIUS) - one merged mesh
  // of humanTemplate placements, rebuilt on its own throttled cadence
  // inside updateHumans rather than rebuildStatics' tick-based one, since
  // it needs to react to camera movement, not just simulation state
  private Geometry crowdGeom;
  private final List<PropBatcher.Placement> crowdCandidates = new ArrayList<>();
  private int crowdFrameCounter = 0;

  private final Node settlementsNode = new Node("settlements");
  private final Node humansNode = new Node("humans");
  private final Node businessesNode = new Node("businesses");
  private final Node banksNode = new Node("banks");
  private final Node statuesNode = new Node("statues");
  private final Node monumentsNode = new Node("monuments");
  private final Node militaryBasesNode = new Node("militaryBases");
  private final Node flagsNode = new Node("flags");
  private final Node firesNode = new Node("fires");
  private final Node smokeNode = new Node("smoke");
  private final Node sparklesNode = new Node("sparkles");
  private final Node rainNode = new Node("rain");
  private final Node cloudsNode = new Node("clouds");
  private final Node siegeLabelsNode = new Node("siegeLabels");
  private final Geometry[] settlementPool = new Geometry[SETTLEMENT_CAP];
  private final Geometry[] humanPool = new Geometry[Config.MAX_HUMANS];
  private final Geometry[] humanSkinPool = new Geometry[Config.MAX_HUMANS];
  private final Geometry[] humanArmLPool = new Geometry[Config.MAX_HUMANS];
  private final Geometry[] humanArmRPool = new Geometry[Config.MAX_HUMANS];
  // last mesh actually bound to each right-arm slot, so a person whose
  // job hasn't changed since last frame doesn't cost a Mesh rebind - see
  // updateHumans.
  private final Mesh[] lastArmRMesh = new Mesh[Config.MAX_HUMANS];
  private final Geometry[] businessPool = new Geometry[BUSINESS_CAP];
  private final Geometry[] bankPool = new Geometry[BANK_CAP];
  private final Geometry[] statuePool = new Geometry[BANK_CAP];
  private final Geometry[] monumentPool = new Geometry[LANDMARK_CAP];
  private final Geometry[] militaryBasePool = new Geometry[LANDMARK_CAP];
  private final Geometry[] flagPool = new Geometry[SETTLEMENT_CAP];
  private final Geometry[] firePool = new Geometry[FIRE_CAP];
  private final Geometry[] smokePool = new Geometry[FIRE_CAP];
  private final Geometry[] sparklePool = new Geometry[SPARKLE_CAP];
  private final Geometry[] rainPool = new Geometry[RAIN_CAP];
  private final List<Geometry> cloudGeoms = new ArrayList<>();
  private final Node[] siegeLabelNodes = new Node[SIEGE_LABEL_CAP];
  private final com.jme3.font.BitmapText[] siegeLabels = new com.jme3.font.BitmapText[SIEGE_LABEL_CAP];

  // Every update*() below runs once per rendered frame (not once per sim
  // tick - see GameApp.simpleUpdate), and each used to re-cull its ENTIRE
  // unused pool tail every single frame regardless of how much of that
  // pool was actually ever in use - e.g. re-issuing setCullHint(Always)
  // on up to ~2,900 already-culled human slots x4 pools, 60+ times a
  // second, just to keep a population of a few dozen hidden. Tracking how
  // far each pool reached last frame means only the newly-vacated slots
  // (last frame's count down to this frame's) ever need re-culling - zero
  // work once a pool's occupancy stops shrinking.
  private int lastHumanCount = 0, lastSettlementCount = 0, lastBusinessCount = 0;

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
  // one floating name label per possible nation, always shown above its
  // capital (not just while selected) - the selected nation's own label
  // is bigger/brighter as the "highlight" the player asked for
  private static final int NATION_LABEL_CAP = 64;
  private final Node[] nationLabelNodes = new Node[NATION_LABEL_CAP];
  private final com.jme3.font.BitmapText[] nationLabelTexts = new com.jme3.font.BitmapText[NATION_LABEL_CAP];
  private static final ColorRGBA NATION_LABEL_COLOR = new ColorRGBA(0.93f, 0.95f, 0.99f, 0.92f);
  private static final ColorRGBA NATION_LABEL_SELECTED_COLOR = new ColorRGBA(1f, 0.86f, 0.3f, 1f);

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
    // iron/gold ore used to be a single smooth bipyramid "gem" - reads as
    // a polished blob rather than raw mineral. An angular jutting crystal
    // cluster instead; stone gets its own jumbled boulder cluster below
    // since a rock and an ore vein shouldn't look like the same thing.
    depositTemplate = MeshUtil.buildCrystalCluster(0.42f);
    stoneDepositTemplate = MeshUtil.buildRockCluster(0.5f);
    // a villager used to be one solid torso box plus a head - a believable
    // silhouette from a distance, but a single blob up close with no sense
    // of a person actually standing there. Separate legs, a distinct
    // torso/head, plus a hair tuft, a collar and belt (clothing), and two
    // small eye bumps (a face) read as an actual little figure instead,
    // while keeping the same low-poly block style as everything else and
    // the same overall footprint (local y -0.5..0.72) so placement math
    // elsewhere doesn't need to change.
    Mesh legL = MeshUtil.translatedCopy(new Box(0.05f, 0.25f, 0.06f), -0.06f, -0.25f, 0);
    Mesh legR = MeshUtil.translatedCopy(new Box(0.05f, 0.25f, 0.06f), 0.06f, -0.25f, 0);
    Mesh torso = MeshUtil.translatedCopy(new Box(0.15f, 0.22f, 0.1f), 0, 0.22f, 0);
    Mesh head = MeshUtil.translatedCopy(new Box(0.12f, 0.14f, 0.12f), 0, 0.58f, 0);
    // clothing: a collar at the neckline and a belt at the waist - thin
    // bands slightly wider than the body they wrap, so they read as
    // fabric rather than more torso
    Mesh collar = MeshUtil.translatedCopy(new Box(0.165f, 0.02f, 0.115f), 0, 0.44f, 0);
    Mesh belt = MeshUtil.translatedCopy(new Box(0.165f, 0.02f, 0.115f), 0, 0f, 0);
    // hair: a simple tuft sitting on top of the head
    Mesh hair = MeshUtil.translatedCopy(new Box(0.1f, 0.035f, 0.1f), 0, 0.735f, 0);
    // face: two small eye bumps on the front of the head, proud enough of
    // the surface to catch a highlight/shadow (see the SSAO pass) instead
    // of just disappearing into the block
    Mesh eyeL = MeshUtil.translatedCopy(new Box(0.02f, 0.02f, 0.01f), -0.05f, 0.6f, 0.125f);
    Mesh eyeR = MeshUtil.translatedCopy(new Box(0.02f, 0.02f, 0.01f), 0.05f, 0.6f, 0.125f);
    // skin (head + face) is its own mesh/material, kept out of the
    // nation-colored group entirely - see humanSkinTemplate's field
    // comment for why
    humanSkinTemplate = MeshUtil.mergeMeshes(head, MeshUtil.mergeMeshes(eyeL, eyeR));
    humanTemplate = MeshUtil.mergeMeshes(
        MeshUtil.mergeMeshes(legL, legR),
        MeshUtil.mergeMeshes(torso, MeshUtil.mergeMeshes(collar, MeshUtil.mergeMeshes(belt, hair))));

    // arms: separate meshes/geometries from the clothes/skin above, each
    // pivoted at its own mesh-local origin (y=0) sitting at the shoulder -
    // roughly SHOULDER_Y above the hip, which is the clothes geometry's
    // own translation anchor (see updateHumans) - so rotating the arm
    // Geometry actually swings it from the shoulder instead of the whole
    // limb orbiting some arbitrary point. Job-appropriate gear (the same
    // "silhouette carries the meaning, not color" trick already used for
    // soldiers' carried weapons) now merges onto the right arm itself
    // instead of the torso, so an axe or pickaxe actually moves with the
    // swinging hand instead of floating fixed on the body.
    Mesh armLBase = MeshUtil.translatedCopy(new Box(0.045f, 0.2f, 0.055f), -SHOULDER_X, -0.2f, 0);
    Mesh armRBase = MeshUtil.translatedCopy(new Box(0.045f, 0.2f, 0.055f), SHOULDER_X, -0.2f, 0);
    humanArmLTemplate = armLBase;
    humanArmRTemplate = armRBase;
    // axeMesh()/pickaxeMesh() were built hip-relative (y=0 at the hip,
    // matching the clothes mesh); shift down by SHOULDER_Y to land in the
    // arm mesh's own shoulder-relative frame at roughly the same hand
    // height as before.
    humanArmRAxeTemplate = MeshUtil.mergeMeshes(armRBase.deepClone(), MeshUtil.translatedCopy(axeMesh(), 0, -SHOULDER_Y, 0));
    humanArmRPickaxeTemplate = MeshUtil.mergeMeshes(armRBase.deepClone(), MeshUtil.translatedCopy(pickaxeMesh(), 0, -SHOULDER_Y, 0));

    // a soldier is a real person from this settlement's own population
    // (see Military.raiseArmy) - not a different creature model wearing a
    // uniform. They're drawn as exactly the same villager body as anyone
    // else; the only visual difference is what's in their hand, same
    // "silhouette carries the meaning" trick as a civilian's job tool -
    // just a weapon instead of an axe/pickaxe, merged onto the same right
    // arm template, shifted into its shoulder-relative frame the same way.
    Map<String, Mesh> weaponArmTemplates = new HashMap<>();
    for (Config.UnitSpec spec : Config.UNIT_TYPES.values()) {
      if (weaponArmTemplates.containsKey(spec.weapon)) continue;
      weaponArmTemplates.put(spec.weapon,
          MeshUtil.mergeMeshes(armRBase.deepClone(), MeshUtil.translatedCopy(weaponMesh(spec.weapon), 0, -SHOULDER_Y, 0)));
    }
    weaponArmTemplates.put("banner",
        MeshUtil.mergeMeshes(armRBase.deepClone(), MeshUtil.translatedCopy(weaponMesh("banner"), 0, -SHOULDER_Y, 0)));
    humanArmRWeaponTemplates = weaponArmTemplates;

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
    // a grander stepped-plinth monument for any settlement that's grown
    // into a real city - a distinct, wider silhouette from the capital's
    // thin obelisk statue above
    monumentTemplate = MeshUtil.mergeMeshes(
        MeshUtil.mergeMeshes(
            new Box(0.6f, 0.14f, 0.6f),
            MeshUtil.translatedCopy(new Box(0.42f, 0.14f, 0.42f), 0, 0.28f, 0)),
        MeshUtil.translatedCopy(new Box(0.22f, 0.5f, 0.22f), 0, 0.85f, 0));
    // a squat fortified compound with a watchtower corner - planted at
    // every nation's capital and any settlement currently garrisoning a
    // standing army
    militaryBaseTemplate = MeshUtil.mergeMeshes(
        new Box(0.72f, 0.3f, 0.5f),
        MeshUtil.translatedCopy(new Box(0.16f, 0.45f, 0.16f), 0.5f, 0.45f, 0.3f));

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

    // ground-level foliage: a fanned-out clump of 5 tapered blades, tall
    // enough to actually read as grass next to a 1-unit terrain block
    // instead of the old two-crossed-boxes "stick" (0.18 tall and boxy
    // enough to look like a peg rather than a plant). Bottom sits at
    // local y=0 like the rock cluster.
    foliageTemplate = MeshUtil.buildGrassTuft(0.42f);
    flowerTemplate = MeshUtil.buildGem(0.07f, 0.14f);

    // small satellite houses scattered around a settlement's main building
    // - the town-hall marker alone read as a single icon, not a village.
    // Base size bumped up (was 0.34/0.22, rendered at a further 0.55-0.82x
    // on top of that) - a house that ends up smaller than the person
    // standing next to it doesn't read as a building at all.
    houseTemplate = MeshUtil.mergeMeshes(
        new Box(0.4f, 0.3f, 0.4f),
        MeshUtil.translatedCopy(new Box(0.3f, 0.16f, 0.3f), 0, 0.46f, 0));

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

    for (int i = 0; i < Config.MAX_HUMANS; i++) {
      Geometry g = new Geometry("Human" + i, humanTemplate);
      g.setMaterial(soloColorMaterial(ColorRGBA.White));
      g.setCullHint(Spatial.CullHint.Always);
      humansNode.attachChild(g);
      humanPool[i] = g;

      Geometry skin = new Geometry("HumanSkin" + i, humanSkinTemplate);
      skin.setMaterial(soloColorMaterial(SKIN_TONE_A));
      skin.setCullHint(Spatial.CullHint.Always);
      humansNode.attachChild(skin);
      humanSkinPool[i] = skin;

      Geometry armL = new Geometry("HumanArmL" + i, humanArmLTemplate);
      armL.setMaterial(soloColorMaterial(ColorRGBA.White));
      armL.setCullHint(Spatial.CullHint.Always);
      humansNode.attachChild(armL);
      humanArmLPool[i] = armL;

      Geometry armR = new Geometry("HumanArmR" + i, humanArmRTemplate);
      armR.setMaterial(soloColorMaterial(ColorRGBA.White));
      armR.setCullHint(Spatial.CullHint.Always);
      humansNode.attachChild(armR);
      humanArmRPool[i] = armR;
    }
    crowdGeom = new Geometry("HumanCrowd", humanTemplate.deepClone());
    crowdGeom.setMaterial(vertexColorMaterial());
    crowdGeom.setCullHint(Spatial.CullHint.Always);
    humansNode.attachChild(crowdGeom);
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

    for (int i = 0; i < LANDMARK_CAP; i++) {
      Geometry g = new Geometry("Monument" + i, monumentTemplate);
      g.setMaterial(soloColorMaterial(MONUMENT_COLOR));
      g.setCullHint(Spatial.CullHint.Always);
      monumentsNode.attachChild(g);
      monumentPool[i] = g;
    }
    root.attachChild(monumentsNode);

    for (int i = 0; i < LANDMARK_CAP; i++) {
      Geometry g = new Geometry("MilitaryBase" + i, militaryBaseTemplate);
      g.setMaterial(soloColorMaterial(MILITARY_BASE_COLOR));
      g.setCullHint(Spatial.CullHint.Always);
      militaryBasesNode.attachChild(g);
      militaryBasePool[i] = g;
    }
    root.attachChild(militaryBasesNode);

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

    // billboarded nation-name label, one per possible nation, always
    // floating above its capital - the selected one is styled bigger and
    // brighter in updateNationLabels() below
    com.jme3.font.BitmapFont font = assets.loadFont("Interface/Fonts/Default.fnt");
    for (int i = 0; i < NATION_LABEL_CAP; i++) {
      com.jme3.font.BitmapText t = new com.jme3.font.BitmapText(font);
      t.setSize(0.3f);
      t.setColor(NATION_LABEL_COLOR);
      Node n = new Node("NationLabel" + i);
      n.attachChild(t);
      n.addControl(new com.jme3.scene.control.BillboardControl());
      n.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
      n.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
      n.setCullHint(Spatial.CullHint.Always);
      root.attachChild(n);
      nationLabelNodes[i] = n;
      nationLabelTexts[i] = t;
    }

    // a floating "CITY FALLING n%" billboard over any settlement whose
    // garrison has been wiped out/routed and is actively being captured -
    // the WorldBox-style capture-percentage readout the player asked for,
    // built from the same billboarded-text trick as the nation-name label
    // above rather than a 2D UI overlay
    for (int i = 0; i < SIEGE_LABEL_CAP; i++) {
      com.jme3.font.BitmapText t = new com.jme3.font.BitmapText(font);
      t.setSize(0.3f);
      t.setColor(SIEGE_LABEL_COLOR);
      Node n = new Node("SiegeLabel" + i);
      n.attachChild(t);
      n.addControl(new com.jme3.scene.control.BillboardControl());
      n.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
      n.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
      n.setCullHint(Spatial.CullHint.Always);
      siegeLabelsNode.attachChild(n);
      siegeLabelNodes[i] = n;
      siegeLabels[i] = t;
    }
    root.attachChild(siegeLabelsNode);
  }

  /** A lumberjack's axe, held at roughly hand height on a villager's
   * detailed body - same "silhouette, not color, carries the meaning"
   * trick as a soldier's weapon. */
  private Mesh axeMesh() {
    Mesh handle = new Box(0.018f, 0.15f, 0.018f);
    Mesh blade = MeshUtil.translatedCopy(new Box(0.045f, 0.045f, 0.014f), 0.045f, 0.15f, 0);
    Mesh w = MeshUtil.mergeMeshes(handle, blade);
    MeshUtil.rotateInPlace(w, new Quaternion().fromAngleAxis(0.4f, Vector3f.UNIT_Z));
    return MeshUtil.translatedCopy(w, 0.16f, 0.1f, 0.05f);
  }

  /** A miner's pickaxe - shared by stone/iron/gold jobs, since they're all
   * "digging" work; only the axe/pickaxe/bare-hands split needs to read
   * at a glance, not every individual resource. */
  private Mesh pickaxeMesh() {
    Mesh handle = new Box(0.018f, 0.17f, 0.018f);
    Mesh head = MeshUtil.translatedCopy(new Box(0.08f, 0.018f, 0.018f), 0, 0.17f, 0);
    Mesh w = MeshUtil.mergeMeshes(handle, head);
    MeshUtil.rotateInPlace(w, new Quaternion().fromAngleAxis(0.35f, Vector3f.UNIT_Z));
    return MeshUtil.translatedCopy(w, 0.16f, 0.1f, 0.05f);
  }

  /** Builds a carried-weapon mesh, positioned/angled near a soldier's hand,
   * to be merged onto the humanoid body template - the silhouette (not the
   * color, since a soldier stays solid nation-colored) is what tells spear
   * apart from sword apart from rifle. */
  private Mesh weaponMesh(String weapon) {
    Mesh w;
    switch (weapon) {
      case "spear":
        w = MeshUtil.mergeMeshes(new Box(0.03f, 0.42f, 0.03f),
            MeshUtil.translatedCopy(new Box(0.06f, 0.08f, 0.06f), 0, 0.42f, 0));
        MeshUtil.rotateInPlace(w, new Quaternion().fromAngleAxis(0.5f, Vector3f.UNIT_Z));
        return MeshUtil.translatedCopy(w, 0.22f, 0.55f, 0.05f);
      case "sword":
        w = MeshUtil.mergeMeshes(new Box(0.025f, 0.26f, 0.06f),
            MeshUtil.translatedCopy(new Box(0.05f, 0.06f, 0.05f), 0, -0.26f, 0));
        MeshUtil.rotateInPlace(w, new Quaternion().fromAngleAxis(-0.6f, Vector3f.UNIT_Z));
        return MeshUtil.translatedCopy(w, 0.2f, 0.5f, 0.08f);
      case "bow":
        w = MeshUtil.mergeMeshes(new Box(0.02f, 0.24f, 0.02f),
            MeshUtil.translatedCopy(new Box(0.09f, 0.02f, 0.02f), 0, 0.2f, 0));
        return MeshUtil.translatedCopy(w, 0.24f, 0.55f, 0.04f);
      case "lance":
        w = new Box(0.035f, 0.6f, 0.035f);
        MeshUtil.rotateInPlace(w, new Quaternion().fromAngleAxis(0.65f, Vector3f.UNIT_Z));
        return MeshUtil.translatedCopy(w, 0.26f, 0.6f, 0.05f);
      case "musket":
      case "rifle":
        w = MeshUtil.mergeMeshes(new Box(0.03f, 0.32f, 0.03f),
            MeshUtil.translatedCopy(new Box(0.04f, 0.08f, 0.05f), 0, -0.32f, 0));
        MeshUtil.rotateInPlace(w, new Quaternion().fromAngleAxis(0.35f, Vector3f.UNIT_Z));
        return MeshUtil.translatedCopy(w, 0.22f, 0.5f, 0.1f);
      case "banner":
        // WorldBox: "armies... follow the person wielding the banner" - a
        // tall pole with a flag flying above head height so this one
        // person visibly stands out from the rest of their army at a
        // glance, same nation-colored silhouette-only trick as any weapon.
        w = MeshUtil.mergeMeshes(new Box(0.02f, 0.55f, 0.02f),
            MeshUtil.translatedCopy(new Box(0.14f, 0.11f, 0.015f), 0.13f, 0.42f, 0));
        return MeshUtil.translatedCopy(w, 0.2f, 0.68f, 0.05f);
      default:
        return MeshUtil.translatedCopy(new Box(0.03f, 0.2f, 0.03f), 0.2f, 0.5f, 0.05f);
    }
  }

  /** Every living nation's name, always floating above its capital -
   * previously only shown for whichever nation happened to be selected.
   * The selected nation (selectedNationId, or -1 for none) gets a
   * bigger, brighter, gold-colored label and sits a little higher so it
   * reads as genuinely highlighted rather than just another label in the
   * crowd. */
  public void updateNationLabels(GameState state, int selectedNationId, float camDistance) {
    int i = 0;
    // Billboarded text is sized in world units, so left alone it shrinks
    // with distance exactly like any other 3D object - fine for most
    // things, but a nation's name is how a player reads the map at a
    // zoomed-out, whole-world view, so it should read MORE clearly out
    // there, not less. Scaling the world-space size up with camera
    // distance overpowers the normal perspective shrink instead of just
    // canceling it out, so the label actually grows on screen as the
    // camera pulls back.
    float zoomFactor = clamp(camDistance / 45f, 0.65f, 2.6f);
    for (Nation n : state.nations.values()) {
      if (i >= NATION_LABEL_CAP) break;
      Settlement capital = state.settlements.get(n.capitalSettlementId);
      if (capital == null) continue;
      Node node = nationLabelNodes[i];
      com.jme3.font.BitmapText t = nationLabelTexts[i];
      boolean selected = n.id == selectedNationId;
      String text = n.displayName();
      if (!t.getText().equals(text)) t.setText(text);
      float size = (selected ? 0.62f : 0.42f) * zoomFactor;
      if (Math.abs(t.getSize() - size) > 0.001f) t.setSize(size);
      t.setColor(selected ? NATION_LABEL_SELECTED_COLOR : NATION_LABEL_COLOR);
      float width = t.getLineWidth();
      t.setLocalTranslation(-width / 2f, 0, 0);
      float h = grid.height[grid.idx(capital.x, capital.z)];
      node.setLocalTranslation(capital.x + 0.5f, h + (selected ? 4.8f : 4.2f), capital.z + 0.5f);
      node.setCullHint(Spatial.CullHint.Inherit);
      i++;
    }
    for (; i < NATION_LABEL_CAP; i++) nationLabelNodes[i].setCullHint(Spatial.CullHint.Always);
  }

  private Material vertexColorMaterial() {
    Material mat = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
    mat.setBoolean("UseVertexColor", true);
    mat.setColor("Specular", new ColorRGBA(0.14f, 0.14f, 0.13f, 1f));
    mat.setFloat("Shininess", 8f);
    return mat;
  }

  private Material soloColorMaterial(ColorRGBA c) {
    Material mat = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
    mat.setBoolean("UseMaterialColors", true);
    mat.setColor("Diffuse", c);
    mat.setColor("Ambient", c);
    mat.setColor("Specular", new ColorRGBA(0.14f, 0.14f, 0.13f, 1f));
    mat.setFloat("Shininess", 8f);
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
  private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

  private static float hash01(int x, int y, int salt) {
    int h = x * 374761393 + y * 668265263 + salt * 2147483647;
    h = (h ^ (h >>> 13)) * 1274126177;
    h = h ^ (h >>> 16);
    return (h & 0xFFFF) / 65535f;
  }

  /** A villager's skin tone: stable per-individual (keyed off their own id,
   * not their pooled render slot, so it doesn't shuffle as slots get
   * reused between different people frame to frame), blended between the
   * two neutral endpoints above - independent of their nation's color. */
  private static ColorRGBA skinTone(int personId) {
    return SKIN_TONE_A.clone().interpolateLocal(SKIN_TONE_B, hash01(personId, 911, 37));
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

    // Each of these categories has a hard per-category cap well below how
    // many eligible cells a map this size regularly holds (e.g. a few
    // thousand forest tiles against a 2600-tree cap). Taking the first N
    // found in top-left-to-bottom-right scan order used to mean every
    // rebuild only ever placed props in whatever portion of the map the
    // scan reached before the cap filled - trees only ever grew on one
    // side of the map, and burning one down elsewhere freed a slot that
    // the next never-yet-scanned cell in order grabbed, reading as trees
    // visibly crawling across the map as fires happened. Counting eligible
    // cells first and keeping each one via a per-cell hash roll (same
    // family as jitterAxis - a fixed function of that cell's own x/y, not
    // of scan order or how many neighbors already got picked) samples
    // evenly across the whole map and is stable regardless of what burns
    // down or regrows anywhere else.
    int forestCount = 0, stoneResCount = 0, mineralCount = 0, foliageEligibleCount = 0;
    for (int y = 0; y < grid.rows; y++) {
      for (int x = 0; x < grid.cols; x++) {
        int i = grid.idx(x, y);
        byte res = grid.resource[i];
        if (res == Config.RES_FOREST) forestCount++;
        else if (res == Config.RES_STONE) stoneResCount++;
        else if (res != Config.RES_NONE) mineralCount++;
        else if (grid.terrain[i] == Config.GRASS && hash01(x, y, 6) < 0.19f) foliageEligibleCount++;
      }
    }
    float forestKeep = forestCount > TREE_CAP_SAMPLE ? TREE_CAP_SAMPLE / (float) forestCount : 1f;
    float stoneKeep = stoneResCount > DEPOSIT_CAP_SAMPLE ? DEPOSIT_CAP_SAMPLE / (float) stoneResCount : 1f;
    float mineralKeep = mineralCount > DEPOSIT_CAP_SAMPLE ? DEPOSIT_CAP_SAMPLE / (float) mineralCount : 1f;
    float foliageKeep = foliageEligibleCount > FOLIAGE_CAP_SAMPLE ? FOLIAGE_CAP_SAMPLE / (float) foliageEligibleCount : 1f;

    for (int y = 0; y < grid.rows; y++) {
      for (int x = 0; x < grid.cols; x++) {
        int i = grid.idx(x, y);
        byte res = grid.resource[i];
        if (res == Config.RES_FOREST && hash01(x, y, 21) < forestKeep) {
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
        } else if (res == Config.RES_STONE && hash01(x, y, 22) < stoneKeep) {
          float rotY = (float) ((x * 3 + y * 5) % 6.28);
          ColorRGBA c = DEPOSIT_COLORS.getOrDefault(res, ColorRGBA.White);
          float jx = x + 0.5f + jitterAxis(x, y, 3);
          float jz = y + 0.5f + jitterAxis(x, y, 4);
          // buildRockCluster's boxes (and buildCrystalCluster's spikes
          // below) are each lifted by their own half height already, so
          // both clusters sit flush at local y=0 - no extra ground offset
          // needed, unlike the old origin-centered gem template
          stoneDeposits.add(new PropBatcher.Placement(jx, grid.height[i], jz, rotY, 1f, c));
        } else if (res != Config.RES_NONE && res != Config.RES_FOREST && hash01(x, y, 23) < mineralKeep) {
          float rotY = (float) ((x * 3 + y * 5) % 6.28);
          ColorRGBA c = DEPOSIT_COLORS.getOrDefault(res, ColorRGBA.White);
          float jx = x + 0.5f + jitterAxis(x, y, 3);
          float jz = y + 0.5f + jitterAxis(x, y, 4);
          deposits.add(new PropBatcher.Placement(jx, grid.height[i], jz, rotY, 1f, c));
        } else if (grid.terrain[i] == Config.GRASS && res == Config.RES_NONE
            && hash01(x, y, 6) < 0.19f && hash01(x, y, 24) < foliageKeep) {
          // patchy coverage rather than every single grass block - real
          // ground cover grows in clumps, not a uniform carpet, and a
          // literal every-cell carpet would blow well past a sane vertex
          // budget on a 256x256 map anyway. Each placement is already a
          // 7-blade tuft (MeshUtil.buildGrassTuft), not a single blade.
          float rotY = hash01(x, y, 7) * 6.28f;
          float jx = x + 0.5f + jitterAxis(x, y, 8);
          float jz = y + 0.5f + jitterAxis(x, y, 9);
          float scale = 0.7f + hash01(x, y, 10) * 0.7f;
          ColorRGBA tuftColor = FOLIAGE_COLOR.clone().interpolateLocal(TREE_COLOR, hash01(x, y, 11) * 0.4f);
          foliage.add(new PropBatcher.Placement(jx, grid.height[i], jz, rotY, scale, tuftColor));
          if (hash01(x, y, 12) < 0.16f && flowers.size() < FOLIAGE_CAP_SAMPLE) {
            ColorRGBA fc = FLOWER_COLORS[(int) (hash01(x, y, 13) * FLOWER_COLORS.length) % FLOWER_COLORS.length];
            flowers.add(new PropBatcher.Placement(jx, grid.height[i] + 0.32f * scale, jz, rotY, scale, fc));
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
        // spiral placement (radius grows with i, see Settlement.housePosition)
        // reads as an organic cluster of streets/blocks instead of a single
        // uniform ring - and is the same formula Nation.updateRoads routes
        // its street spurs out to, so the roads actually reach the houses.
        double[] spot = Settlement.housePosition(s, i);
        float hx = (float) spot[0], hz = (float) spot[1];
        int gx = clampIdx((int) Math.floor(hx), grid.cols), gz = clampIdx((int) Math.floor(hz), grid.rows);
        float hh = grid.height[grid.idx(gx, gz)];
        ColorRGBA c = i < occupiedHouses ? color : vacantColor;
        // was 0.55-0.82x on top of an already-small base - a house ended
        // up smaller than the person standing next to it. Now bigger than
        // a villager (roughly 1.2 units tall) without dwarfing the
        // town-hall marker it clusters around.
        float houseScale = 0.95f + (i % 4) * 0.14f;
        float angle = i * 2.4f + s.id * 0.7f;
        // houseTemplate's base box is centered at its own origin (half-
        // height 0.3) so it needs lifting by half its scaled height
        houses.add(new PropBatcher.Placement(hx, hh + 0.3f * houseScale, hz, angle, houseScale, c));
      }
    }
    housesGeom.setMesh(PropBatcher.bake(houseTemplate, houses));
    housesGeom.setCullHint(houses.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
  }

  public void update(GameState state, float alpha, float animTime, Vector3f camFocus) {
    updateSettlements(state);
    buildSoldierPositions(state);
    updateHumans(state, alpha, animTime, camFocus);
    updateBusinesses(state);
    updateBanks(state);
    updateStatues(state);
    updateLandmarks(state);
    updateSiegeIndicators(state);
    updateMonster(state);
    updateTornadoes(state);
    updateFires(state, animTime);
    updateSmoke(state);
    updateSparkles(state);
    updateWeather(state, alpha, animTime);
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
    int activeSettlements = i;
    for (; i < Math.max(activeSettlements, lastSettlementCount); i++) {
      settlementPool[i].setCullHint(Spatial.CullHint.Always);
      flagPool[i].setCullHint(Spatial.CullHint.Always);
    }
    lastSettlementCount = activeSettlements;
    lastSettlementCount = i;
  }

  private void updateFires(GameState state, float animTime) {
    int count = Math.min(FIRE_CAP, burningCache.size());
    for (int i = 0; i < count; i++) {
      int cell = burningCache.get(i);
      int gx = cell % grid.cols, gz = cell / grid.cols;
      Geometry g = firePool[i];
      float h = grid.height[cell];
      // animTime is continuous real time (like the water shader's clock),
      // not the simulation tick counter - keying flicker off state.tick
      // meant it only advanced once per ~220ms tick and looked like a
      // stutter/lag rather than a flame, since a full render frame comes
      // far more often than that.
      float flicker = (float) Math.sin(animTime * 6.0 + i * 1.7) * 0.5f + 0.5f;
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
    // a business's placement used to depend only on its own global id, at
    // a fixed radius (1.4) that overlapped the first couple of houses'
    // spiral (which starts at radius 1.0) almost every time. Businesses
    // now get their own outward spiral on a per-settlement local index,
    // starting past where the house spiral's radius tops out (see
    // updateHouses' houseCount<=32 spiral, max radius ~5.0).
    Map<Integer, Integer> localIndex = new HashMap<>();
    for (Business b : state.businesses.values()) {
      if (i >= BUSINESS_CAP) break;
      Settlement s = state.settlements.get(b.settlementId);
      if (s == null) continue;
      Geometry g = businessPool[i];
      float h = grid.height[grid.idx(s.x, s.z)];
      int local = localIndex.merge(b.settlementId, 1, Integer::sum) - 1;
      float angle = local * 1.9f + s.id * 0.7f;
      float radius = 6.4f + (local % 3) * 1.1f;
      float ox = (float) Math.cos(angle) * radius, oz = (float) Math.sin(angle) * radius;
      float scale = (float) (0.7 + Math.min(1.5, b.capital / 60.0));
      // farmTemplate is already ground-anchored at construction; market's
      // and the default extraction cube's base boxes are centered on
      // their own origin and need lifting by half their own height
      float groundOffset = b.type.equals("farm") ? 0f : b.type.equals("market") ? 0.22f : 0.34f;
      g.setLocalTranslation(s.x + 0.5f + ox, h + groundOffset * scale, s.z + 0.5f + oz);
      g.setLocalScale(scale);
      g.setMesh(b.type.equals("farm") ? farmTemplate : b.type.equals("market") ? marketTemplate : businessTemplate);
      ColorRGBA color = b.type.equals("market") ? MARKET_COLOR
          : BUSINESS_COLORS.getOrDefault(b.resourceKey, ColorRGBA.White);
      setSoloColor(g.getMaterial(), color);
      g.setCullHint(Spatial.CullHint.Inherit);
      i++;
    }
    int activeBusinesses = i;
    for (; i < Math.max(activeBusinesses, lastBusinessCount); i++) businessPool[i].setCullHint(Spatial.CullHint.Always);
    lastBusinessCount = activeBusinesses;
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

  /** Monuments (any settlement that's grown into a real city) and military
   * bases (every capital, plus any settlement currently garrisoning a
   * standing army) - the new building types the player asked for beyond
   * the existing per-capital statue. */
  private void updateLandmarks(GameState state) {
    Map<Integer, Boolean> garrisoned = new HashMap<>();
    for (Army a : state.armies.values()) if (!a.dead) garrisoned.put(a.homeSettlementId, true);

    int mi = 0, bi = 0;
    for (Settlement s : state.settlements.values()) {
      if (s.abandoned) continue;
      float h = grid.height[grid.idx(s.x, s.z)];
      Nation nation = state.nations.get(s.nationId);
      ColorRGBA color = nation != null ? nationOrFallback(nation.id, MONUMENT_COLOR) : MONUMENT_COLOR;

      if (s.populationCount >= 30 && mi < LANDMARK_CAP) {
        Geometry g = monumentPool[mi++];
        g.setLocalTranslation(s.x + 0.5f - 2.0f, h + 0.14f * 0.9f, s.z + 0.5f + 2.0f);
        g.setLocalScale(0.9f);
        setSoloColor(g.getMaterial(), MONUMENT_COLOR.clone().interpolateLocal(color, 0.25f));
        g.setCullHint(Spatial.CullHint.Inherit);
      }

      boolean isCapital = nation != null && nation.capitalSettlementId == s.id;
      if (nation != null && (isCapital || garrisoned.getOrDefault(s.id, false)) && bi < LANDMARK_CAP) {
        Geometry g = militaryBasePool[bi++];
        g.setLocalTranslation(s.x + 0.5f - 2.4f, h + 0.15f, s.z + 0.5f - 2.4f);
        g.setLocalScale(0.85f);
        setSoloColor(g.getMaterial(), MILITARY_BASE_COLOR.clone().interpolateLocal(color, 0.2f));
        g.setCullHint(Spatial.CullHint.Inherit);
      }
    }
    for (; mi < LANDMARK_CAP; mi++) monumentPool[mi].setCullHint(Spatial.CullHint.Always);
    for (; bi < LANDMARK_CAP; bi++) militaryBasePool[bi].setCullHint(Spatial.CullHint.Always);
  }

  /** Floating "CITY FALLING n%" readout over any settlement whose garrison
   * has already been wiped out or routed and is actively being captured -
   * only shows once there's genuinely no one left defending, matching
   * "the city starts to fall" rather than every skirmish at the gates. */
  private void updateSiegeIndicators(GameState state) {
    int i = 0;
    for (Settlement s : state.settlements.values()) {
      if (i >= SIEGE_LABEL_CAP) break;
      if (s.abandoned || s.siegeProgress <= 0.5) continue;
      Node n = siegeLabelNodes[i];
      com.jme3.font.BitmapText t = siegeLabels[i];
      float h = grid.height[grid.idx(s.x, s.z)];
      String text = "CITY FALLING " + (int) Math.min(100, s.siegeProgress) + "%";
      if (!t.getText().equals(text)) t.setText(text);
      float width = t.getLineWidth();
      t.setLocalTranslation(-width / 2f, 0, 0);
      n.setLocalTranslation(s.x + 0.5f, h + 3.4f, s.z + 0.5f);
      n.setCullHint(Spatial.CullHint.Inherit);
      i++;
    }
    for (; i < SIEGE_LABEL_CAP; i++) siegeLabelNodes[i].setCullHint(Spatial.CullHint.Always);
  }

  /** Deterministic per-slot offset within an army's "scrum" cluster - a
   * loose huddle around the army's actual (x,z), not a rigid grid
   * formation, matching how WorldBox's own soldiers cluster loosely
   * around a rally point rather than marching in ranks. Stable per
   * (armyId, slot) so an individual soldier doesn't jitter to a new spot
   * every frame. */
  /** A soldier's position (their army's, since Population.update stops
   * moving a person the moment they enlist - see Human.role) plus
   * whatever else updateHumans' soldier branch needs to draw them,
   * gathered once per frame in buildSoldierPositions rather than re-
   * searching every army for every soldier. */
  private static final class SoldierPos {
    final Army army; final int totalUnits;
    final double x, z, prevX, prevZ;
    SoldierPos(Army army, int totalUnits, double x, double z, double prevX, double prevZ) {
      this.army = army; this.totalUnits = totalUnits;
      this.x = x; this.z = z; this.prevX = prevX; this.prevZ = prevZ;
    }
  }
  private final Map<Integer, SoldierPos> soldierPosByHumanId = new HashMap<>();

  /** Built once per frame, before updateHumans - a soldier is drawn
   * through the exact same per-person pipeline as any civilian (see
   * updateHumans), just positioned at their army's current location
   * instead of their own (frozen since they enlisted) x/z. One shared
   * SoldierPos per army, not one per person, since a whole army's roster
   * shares the same position/composition. */
  private void buildSoldierPositions(GameState state) {
    soldierPosByHumanId.clear();
    for (Army a : state.armies.values()) {
      if (a.dead || a.memberHumanIds.isEmpty()) continue;
      int totalUnits = 0;
      for (int c : a.units.values()) totalUnits += c;
      if (totalUnits <= 0) continue;
      SoldierPos pos = new SoldierPos(a, totalUnits, a.x, a.z, a.prevX, a.prevZ);
      for (int hid : a.memberHumanIds) soldierPosByHumanId.put(hid, pos);
    }
  }

  /** Deterministic pseudo-random 0..1 for picking which unit type a given
   * real person in the army currently represents - stable across frames
   * (keyed off their own actual id, not a reused render slot) so a
   * soldier doesn't flicker between unit types tick to tick. */
  private static float humanUnitSeed(int armyId, int humanId) {
    return hash01(armyId * 17 + humanId, humanId * 7 + 3, 71);
  }

  /** Walks the army's unit composition in insertion order (weakest-to-
   * strongest, matching Config.UNIT_TYPES) and picks whichever type the
   * seed falls into, weighted by that type's share of the army's total
   * headcount - so a mostly-militia army's people show mostly spears and
   * only occasionally a knight's lance. */
  private static String pickUnitForHuman(Army army, int totalUnits, float seed) {
    float target = seed * totalUnits;
    float acc = 0;
    String last = "militia";
    for (Map.Entry<String, Integer> e : army.units.entrySet()) {
      if (e.getValue() <= 0) continue;
      last = e.getKey();
      acc += e.getValue();
      if (target <= acc) return e.getKey();
    }
    return last;
  }

  private void updateHumans(GameState state, float alpha, float animTime, Vector3f camFocus) {
    List<Human> humans = state.humans;
    int n = Math.min(humans.size(), Config.MAX_HUMANS);
    // Full per-person detail (separate skin/arm geometries, swing
    // animation, individual click targets) only gets rendered for whoever
    // is within NEAR_HUMAN_RADIUS of the camera's focus point, capped at
    // NEAR_HUMAN_CAP - at a population in the thousands, doing that for
    // every single person regardless of where they are on the map is what
    // tanked frame rate as population grew. Everyone else instead gets
    // folded into one shared "crowd" mesh (crowdGeom), rebuilt only every
    // CROWD_REBUILD_INTERVAL frames since a distant figure a few pixels
    // tall doesn't need buttery-smooth per-frame repositioning.
    boolean rebuildCrowd = (crowdFrameCounter++ % CROWD_REBUILD_INTERVAL) == 0;
    if (rebuildCrowd) crowdCandidates.clear();
    int nearCount = 0;
    for (int i = 0; i < n; i++) {
      Human h = humans.get(i);
      Geometry g = humanPool[i];
      // a serving soldier is a real person from this population, not a
      // separate creature model - they're drawn through this exact same
      // pipeline as any civilian (see WorldBox's own approach: population
      // figures fight in place, there's no distinct "unit" sprite), just
      // positioned at their army's current location (buildSoldierPositions)
      // instead of their own x/z, which Population.update stops updating
      // the moment they enlist.
      boolean isSoldier = "soldier".equals(h.role);
      SoldierPos sp = isSoldier ? soldierPosByHumanId.get(h.id) : null;
      if (isSoldier && sp == null) {
        // no live army roster found for them (shouldn't normally happen) -
        // nothing sane to draw them at, so stay hidden rather than show
        // them frozen at a stale pre-enlistment position
        g.setCullHint(Spatial.CullHint.Always);
        humanSkinPool[i].setCullHint(Spatial.CullHint.Always);
        humanArmLPool[i].setCullHint(Spatial.CullHint.Always);
        humanArmRPool[i].setCullHint(Spatial.CullHint.Always);
        continue;
      }

      double x, z, dx, dz;
      if (isSoldier) {
        x = sp.prevX + (sp.x - sp.prevX) * alpha;
        z = sp.prevZ + (sp.z - sp.prevZ) * alpha;
        // a whole roster shares one army position - without some spread
        // every soldier in the same army would stack on the exact same
        // point. Scattered per real person (their own id), not a reused
        // render slot, so it stays stable frame to frame.
        x += jitterAxis(h.id, sp.army.id, 101) * 2.4;
        z += jitterAxis(h.id, sp.army.id, 103) * 2.4;
        dx = sp.x - sp.prevX; dz = sp.z - sp.prevZ;
      } else {
        x = h.prevX + (h.x - h.prevX) * alpha;
        z = h.prevZ + (h.z - h.prevZ) * alpha;
        dx = h.x - h.prevX; dz = h.z - h.prevZ;
      }

      double camdx = x - camFocus.x, camdz = z - camFocus.z;
      boolean near = nearCount < NEAR_HUMAN_CAP && camdx * camdx + camdz * camdz < NEAR_HUMAN_RADIUS2;
      if (!near) {
        // far from the camera (or already past this frame's near budget) -
        // no per-frame Geometry updates or draw calls for this person at
        // all; on a crowd-rebuild frame they instead get folded into the
        // single batched crowd mesh below.
        g.setCullHint(Spatial.CullHint.Always);
        humanSkinPool[i].setCullHint(Spatial.CullHint.Always);
        humanArmLPool[i].setCullHint(Spatial.CullHint.Always);
        humanArmRPool[i].setCullHint(Spatial.CullHint.Always);
        if (rebuildCrowd) {
          int cgx = clampIdx((int) Math.floor(x), grid.cols), cgz = clampIdx((int) Math.floor(z), grid.rows);
          float chgt = grid.height[grid.idx(cgx, cgz)];
          boolean crowdZombie = h.nationId == Config.UNDEAD_NATION_ID;
          ColorRGBA cc = crowdZombie ? ZOMBIE_COLOR : nationOrFallback(h.nationId, HUMAN_FALLBACK_COLOR);
          float cyaw = (Math.abs(dx) > 1e-5 || Math.abs(dz) > 1e-5)
              ? (float) Math.atan2(dx, dz) : hash01(h.id, 444, 5) * 6.28f;
          crowdCandidates.add(new PropBatcher.Placement((float) x, chgt + 0.5f, (float) z, cyaw, 1f, cc));
        }
        continue;
      }
      nearCount++;

      int gx = clampIdx((int) Math.floor(x), grid.cols), gz = clampIdx((int) Math.floor(z), grid.rows);
      float hgt = grid.height[grid.idx(gx, gz)];
      boolean moving = Math.abs(dx) > 1e-5 || Math.abs(dz) > 1e-5;
      // a small continuous up/down bounce while actually walking - real
      // real-time animation (animTime, same continuous clock as fire/
      // rain/clouds), not a per-tick snap, with each person's own phase
      // offset (their id) so a crowd doesn't bob in lockstep
      float bob = moving ? (float) Math.abs(Math.sin(animTime * 9.0 + h.id * 0.9)) * 0.05f : 0f;
      g.setLocalTranslation((float) x, hgt + 0.5f + bob, (float) z);
      if (moving) {
        float yaw = (float) Math.atan2(dx, dz);
        g.setLocalRotation(scratchYaw.fromAngleAxis(yaw, Vector3f.UNIT_Y));
      }
      // g's mesh is bound to humanTemplate once at pool creation and never
      // needs to change again - job/weapon gear now lives on the arm (see
      // below), so this used to re-set the exact same mesh reference on
      // every single active human, every single frame, for nothing.
      boolean zombie = h.nationId == Config.UNDEAD_NATION_ID;
      ColorRGBA c = zombie ? ZOMBIE_COLOR : nationOrFallback(h.nationId, HUMAN_FALLBACK_COLOR);
      if (isSoldier && sp.army.combatFlashTimer > 0) {
        // a soldier actually fighting right now flashes red - the same
        // hard, unmissable "this is happening right here" cue the old
        // abstract army visual used, now on the real person themselves
        c = c.clone().interpolateLocal(COMBAT_FLASH_COLOR, sp.army.combatFlashTimer / 18f);
      }
      setSoloColor(g.getMaterial(), c);
      g.setUserData("humanId", h.id);
      g.setCullHint(Spatial.CullHint.Inherit);

      // arms swing from the shoulder, opposite each other, only while
      // actually walking - driven off animTime (the same continuous
      // real-time clock the bob above uses), not h.walkPhase, which only
      // advances once per SIM TICK (~220ms - see Population.moveToward)
      // and held the swing angle frozen for many render frames at a time
      // before jumping to the next value, reading as slow and robotic
      // instead of a smooth swing. Same per-person phase offset (h.id) as
      // the bob so a crowd doesn't swing in lockstep, and locked to the
      // same frequency so arms and the step-bounce move together.
      // Standing still, arms just hang at rest, following whatever
      // direction the body is already facing (bodyRot is only updated
      // above when moving, same as the body itself).
      Quaternion bodyRot = g.getLocalRotation();
      float swingPhase = (float) (animTime * 9.0 + h.id * 0.9);
      float swing = moving ? (float) Math.sin(swingPhase) * ARM_SWING_AMPLITUDE : 0f;
      Geometry armL = humanArmLPool[i];
      Geometry armR = humanArmRPool[i];
      float armY = hgt + 0.5f + bob + SHOULDER_Y;
      armL.setLocalTranslation((float) x, armY, (float) z);
      armR.setLocalTranslation((float) x, armY, (float) z);
      armL.setLocalRotation(bodyRot.mult(scratchSwing.fromAngleAxis(swing, Vector3f.UNIT_X), scratchArmRot));
      armR.setLocalRotation(bodyRot.mult(scratchSwing.fromAngleAxis(-swing, Vector3f.UNIT_X), scratchArmRot));

      Mesh armRMesh;
      if (isSoldier) {
        // whatever unit type this person currently represents in their
        // army's makeup (see pickUnitForHuman) decides which weapon
        // silhouette they carry - same "one prop on the arm" idea as a
        // civilian's job tool, just a sword/spear/rifle instead of an axe.
        boolean isGeneral = sp.army.generalHumanId != null && sp.army.generalHumanId == h.id;
        Mesh gearMesh;
        if (isGeneral) {
          gearMesh = humanArmRWeaponTemplates.get("banner");
        } else {
          String unitType = pickUnitForHuman(sp.army, sp.totalUnits, humanUnitSeed(sp.army.id, h.id));
          Config.UnitSpec spec = Config.UNIT_TYPES.get(unitType);
          gearMesh = spec != null ? humanArmRWeaponTemplates.get(spec.weapon) : null;
        }
        armRMesh = gearMesh != null ? gearMesh : humanArmRTemplate;
      } else {
        // job-appropriate gear: a lumberjack's axe or a miner's pickaxe
        // (stone/iron/gold all read as "digging") moves with the right
        // arm itself instead of being fixed to the torso; everyone else
        // is bare-handed.
        armRMesh = "wood".equals(h.job) ? humanArmRAxeTemplate
            : ("stone".equals(h.job) || "iron".equals(h.job) || "gold".equals(h.job)) ? humanArmRPickaxeTemplate
            : humanArmRTemplate;
      }
      // most people keep the same job/unit tick to tick, so most frames
      // this slot's mesh hasn't actually changed - skip the rebind unless
      // it has, same idea as the tail-cull tracking above.
      if (lastArmRMesh[i] != armRMesh) { armR.setMesh(armRMesh); lastArmRMesh[i] = armRMesh; }
      setSoloColor(armL.getMaterial(), c);
      setSoloColor(armR.getMaterial(), c);
      armL.setCullHint(Spatial.CullHint.Inherit);
      armR.setCullHint(Spatial.CullHint.Inherit);

      // skin geometry rides along with the clothing one - same position,
      // rotation and bob, just its own neutral (or, for the undead, still
      // sickly-tinted) tone instead of the nation's accent color
      Geometry skin = humanSkinPool[i];
      skin.setLocalTranslation(g.getLocalTranslation());
      skin.setLocalRotation(g.getLocalRotation());
      setSoloColor(skin.getMaterial(), zombie ? ZOMBIE_COLOR : skinTone(h.id));
      skin.setCullHint(Spatial.CullHint.Inherit);
    }
    if (rebuildCrowd) {
      crowdGeom.setMesh(PropBatcher.bake(humanTemplate, crowdCandidates));
      crowdGeom.setCullHint(crowdCandidates.isEmpty() ? Spatial.CullHint.Always : Spatial.CullHint.Inherit);
    }
    // only the slots that were active last frame and aren't anymore need
    // re-culling - re-issuing setCullHint(Always) on the same already-
    // culled slot every frame, all the way out to MAX_HUMANS, was pure
    // per-frame overhead completely decoupled from the real population.
    for (int i = n; i < Math.max(n, lastHumanCount); i++) {
      humanPool[i].setCullHint(Spatial.CullHint.Always);
      humanSkinPool[i].setCullHint(Spatial.CullHint.Always);
      humanArmLPool[i].setCullHint(Spatial.CullHint.Always);
      humanArmRPool[i].setCullHint(Spatial.CullHint.Always);
    }
    lastHumanCount = n;
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

  private void updateWeather(GameState state, float alpha, float animTime) {
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
      // clouds only move once per simulation tick, not every render
      // frame - without interpolating between prevX/prevZ (same fix as
      // armies/humans) they visibly hopped forward once per tick instead
      // of drifting, which read as jitter
      float cx = (float) (c.prevX + (c.x - c.prevX) * alpha);
      float cz = (float) (c.prevZ + (c.z - c.prevZ) * alpha);
      // a little per-cloud height variation (stable per id) so a mixed
      // sky of big/small clouds also reads as layered, not all pinned to
      // one flat plane
      float height = CLOUD_HEIGHT + jitterAxis(c.id, 0, 91) * 2.5f;
      g.setLocalTranslation(cx, height, cz);
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
          // Continuous real time again, not state.tick - each drop falls
          // in about 0.7s (not 0.7s * gameSpeed) with its own phase offset
          // so the rain reads as a fast, continuous shower instead of
          // hitching forward once per simulation tick.
          float phase = (animTime * 1.4f + j * 0.081f) % 1f;
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
  public Node getHumansNode() { return humansNode; }

  private static int clampIdx(int v, int max) { return Math.max(0, Math.min(max - 1, v)); }
}
