package com.worldbox.sim;

import com.worldbox.config.Config;
import com.worldbox.render.GovStructures;
import com.worldbox.render.HouseVariants;
import com.worldbox.util.Rng;
import com.worldbox.world.VoxelWorld;
import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Settlement implements java.io.Serializable {
  private static int nextId = 1;

  /** Loading a save must never let a freshly created instance reuse an id
   * already present in the loaded data - bump the counter past whatever
   * the save actually contained. */
  public static void restoreNextId(int maxSeenId) { if (maxSeenId >= nextId) nextId = maxSeenId + 1; }

  private static final String[] NAME_A = {
      "Oak", "Stone", "River", "North", "South", "East", "West", "Iron", "Gold", "Sun", "Wind", "Hill",
      "White", "Black", "Green", "Silver", "Amber", "Copper", "Elm", "Birch", "Willow", "Thorn", "Ash",
      "Frost", "Shadow", "Bright", "Clear", "Deep", "Fair", "High", "Low", "Old", "New", "Red", "Grey"
  };
  private static final String[] NAME_B = {
      "ford", "haven", "burg", "shire", "port", "hold", "watch", "mere", "vale", "reach", "crest", "fell",
      "wood", "field", "brook", "gate", "hollow", "wick", "moor", "dale", "worth", "stead", "cross", "bridge"
  };
  private static final String[] NAME_MID = {"", "", "", "en", "in", "on", "el"};

  /** Two or occasionally three phoneme pieces, matching the trick used for
   * nation names - keeps hundreds of settlements from repeating. */
  public static String randomSettlementName(Rng rng) {
    String mid = rng.next() < 0.25 ? rng.pick(NAME_MID) : "";
    return rng.pick(NAME_A) + mid + rng.pick(NAME_B);
  }

  public final int id;
  public int nationId;
  public final int x, z;
  public String name;
  public final Map<String, Double> stock = new HashMap<>();
  public int populationCount = 0;
  public int farmCells = 6;
  // must stay at least as big as the widest a settlement's own houses/
  // businesses ever spiral out to (EntityRenderer.updateHouses/
  // updateBusinesses; real block-built houses need much more spread than
  // the old decorative props did - see housePosition/business placement)
  // - a settlement whose own buildings sit outside its own claimed land
  // is exactly what let two nations' territories interleave with each
  // other's buildings and look broken.
  public double radius = 16;
  public double growthAccum = 0;
  public int starveTicks = 0;
  public double siegeProgress = 0;
  /** current defender manpower - depletes as attackers fight through it.
   * -1 means "not yet initialized", lazily set on first contact (see
   * Military.resolveSieges) so a settlement's garrison always reflects
   * its population/era at the moment it's actually attacked. Once this
   * hits 0 every defender is dead or has fled and the city starts to
   * fall (siegeProgress then measures the physical capture, not combat). */
  public double garrisonHp = -1;
  public final int founded;
  /** True once population has hit 0 - the settlement leaves its nation
   * and territory but its structures stay standing as a visible ruin. */
  public boolean abandoned = false;
  public double housingStock = 5;
  /** Ever-incrementing counter feeding the spiral house-placement formula
   * (housePosition) - kept persistent (not just "however many houses
   * exist right now") so a house that's since been destroyed doesn't
   * free up its old ring slot for a new house to silently reuse; every
   * house this settlement ever builds gets its own fresh spot. */
  public int houseSpiralIndex = 0;

  private Settlement(int x, int z, int nationId, String name, int foundedTick) {
    this.id = nextId++;
    this.x = x; this.z = z;
    this.nationId = nationId;
    this.name = name;
    this.founded = foundedTick;
    stock.put("food", 60.0);
    stock.put("wood", 30.0);
    stock.put("stone", 10.0);
    stock.put("iron", 0.0);
    stock.put("gold_ore", 0.0);
    stock.put("tools", 0.0);
    stock.put("luxury", 0.0);
  }

  public static Settlement create(GameState state, int x, int z, int nationId, String name) {
    Settlement settlement = new Settlement(x, z, nationId, name != null ? name : "Settlement", state.tick);
    if (name == null) settlement.name = "Settlement " + settlement.id;
    state.settlements.put(settlement.id, settlement);
    state.grid.settlementAt[state.grid.idx(x, z)] = settlement.id;

    int startPop = 5;
    for (int i = 0; i < startPop; i++) {
      if (state.humans.size() >= Config.MAX_HUMANS) break;
      double ang = (i / (double) startPop) * Math.PI * 2;
      double hx = x + 0.5 + Math.cos(ang) * 2.4;
      double hz = z + 0.5 + Math.sin(ang) * 2.4;
      Human founder = Population.createAdult(hx, hz, nationId, settlement.id);
      // a fresh settlement's starting housingStock (5 houses, 20 capacity)
      // comfortably covers its 5 founders - they move into real houses on
      // day one instead of just existing without one
      founder.hasHouse = i < settlement.housingStock * PEOPLE_PER_HOUSE;
      state.humans.add(founder);
    }

    // the settlement's own government marker (hut/town/city - see
    // EntityRenderer.updateSettlements) sits at the town center and never
    // moves, so its lot gets graded once here, sized for the LARGEST tier
    // it could ever grow into (see GovStructures.maxFootprintHalfExtent) -
    // otherwise a later population-driven tier upgrade to a wider building
    // would land on ground that was only ever leveled for the small
    // starting hut, reading as the exact same floating/clipping bug a
    // house's own lot grading fixes.
    terraformFootprint(state, x + 0.5, z + 0.5, GovStructures.maxFootprintColumns());

    // a fresh settlement's starting houses each rise from bare foundation
    // the same visible, block-by-block way organic growth does (see
    // update()'s construction pass just below) - a settlement popping
    // into existence with its houses already fully built read as
    // buildings materializing out of nowhere with no one actually having
    // built them. A staggered head start (earlier houses further along)
    // means the town doesn't read as a totally empty field either - it's
    // mid-construction, like the founders have already been at it a
    // while, not starting from literally nothing.
    for (int i = 0; i < (int) settlement.housingStock; i++) {
      double headStart = Math.max(0, 0.7 - i * 0.18);
      spawnHouseBuilding(state, settlement, headStart);
    }

    claimTerritory(state, settlement);
    return settlement;
  }

  /** Where house #i of this settlement's spiral cluster actually sits in
   * world space - the single source of truth for that layout, shared by
   * EntityRenderer (drawing the houses) and Nation (routing road spurs
   * out to them) so the streets actually reach the houses instead of the
   * two computing independent, uncoordinated patterns. */
  /** Real lots on a real street grid - a suburb, not a scattered cluster.
   * Every house/mansion is a physical object with real interior volume
   * (see Blueprint) that must never overlap another one, and a fixed
   * grid of non-overlapping lots is what guarantees that by construction:
   * index i always maps to exactly one (row, col) lot, so no two houses
   * in the same settlement can ever land on the same spot, no per-pair
   * distance checks needed. LOT_SPACING is sized wider than even a
   * mansion's own footprint (see MANSION_FOOTPRINT) plus a real street's
   * width between rows. The whole grid starts DOWNTOWN_CLEARANCE units
   * out from the town center and only grows in the +Z direction (rotated
   * a quarter-turn per settlement id, so neighboring towns don't all
   * line up identically) - the center itself stays clear for downtown
   * (see EntityRenderer.updateBusinesses/updateBanks/hutTemplate's own
   * exact-center placement), the same way a real town's commercial core
   * sits apart from its residential blocks. Spacing sized off the widest
   * real footprint any house variant actually has (a mansion's chimney
   * included - see HouseVariants/Blueprint.footprintHalfExtent), doubled
   * for a real street's width plus yard on both sides between rows/
   * columns, rather than a guessed constant that turned out to leave
   * neighboring lots almost touching. */
  private static final double LOT_SPACING = houseSpacing();
  private static final double DOWNTOWN_CLEARANCE = LOT_SPACING + 1.0;
  private static final int LOTS_PER_ROW = 7;

  private static double houseSpacing() {
    float maxHalfExtent = 0;
    for (String name : HouseVariants.NAMES) {
      maxHalfExtent = Math.max(maxHalfExtent, HouseVariants.blueprintFor(name).footprintHalfExtent());
    }
    return maxHalfExtent * 2 + 3.0;
  }

  public static double[] housePosition(Settlement s, int i) {
    int row = i / LOTS_PER_ROW, col = i % LOTS_PER_ROW;
    double ox = (col - (LOTS_PER_ROW - 1) / 2.0) * LOT_SPACING;
    double oz = DOWNTOWN_CLEARANCE + row * LOT_SPACING;
    double rot = Math.floorMod(s.id, 4) * (Math.PI / 2);
    double rx = ox * Math.cos(rot) - oz * Math.sin(rot);
    double rz = ox * Math.sin(rot) + oz * Math.cos(rot);
    return new double[]{s.x + 0.5 + rx, s.z + 0.5 + rz};
  }

  /** The next free spiral ring position that actually lands on dry land -
   * a house (unlike a settlement's abstract claimed territory, which
   * simply skips water cells entirely) used to get placed with no terrain
   * check at all, so a coastal town's spiral could - and did - plant
   * houses out on open water. Mountains (terrain==STONE) are excluded the
   * same way water is - not just because a house sitting on bare rock
   * looks wrong, but because terraformFootprint forcing a flat pad at a
   * mountainside anchor's height bulldozes an artificial stone plateau/
   * column straight out of the slope, which is exactly what was reading
   * as a "random pillar" jutting out of a hillside. Gives up after a
   * generous number of rings (a settlement boxed in by water/mountains on
   * every side at every ring tried is a real, if rare, possibility)
   * rather than looping forever. */
  private static double[] findBuildingSpot(GameState state, Settlement s) {
    WorldGrid grid = state.grid;
    for (int attempts = 0; attempts < 60; attempts++) {
      double[] spot = housePosition(s, s.houseSpiralIndex++);
      int gx = (int) Math.floor(spot[0]), gz = (int) Math.floor(spot[1]);
      if (!grid.inBounds(gx, gz) || grid.isBuildable(grid.idx(gx, gz))) return spot;
    }
    return null;
  }

  /** Creates one real house - one of several genuinely different shapes
   * (or, for a wealthy nation, occasionally a mansion instead), picked at
   * random per building rather than every house in a settlement being
   * the same cookie-cutter shape - at the next dry spiral spot and adds
   * it to state.buildings - the single place a house Building ever comes
   * into existence, whether at founding (fully built) or from later
   * organic growth (starts at progress 0 and visibly rises - see
   * update()'s construction pass). */
  private static void spawnHouseBuilding(GameState state, Settlement s, double startProgress) {
    double[] spot = findBuildingSpot(state, s);
    if (spot == null) return; // genuinely boxed in by water this attempt - try again next time housing grows
    Nation nation = state.nations.get(s.nationId);
    boolean mansion = nation != null && nation.landValueIndex > 1.3 && Math.random() < 0.2;
    String[] pool = HouseVariants.ORDINARY_NAMES;
    String type = mansion ? "mansion" : pool[(int) (Math.random() * pool.length)];
    Building b = new Building(s.id, s.nationId, type, spot[0], spot[1]);
    b.progress = startProgress;
    state.buildings.put(b.id, b);
    // every new building's site gets leveled first, the same way a real
    // crew grades a lot before framing goes up - including a
    // settlement's own pre-built starting homes, not just later organic
    // growth - otherwise the building's single flat blueprint mesh would
    // sit clipped into (or floating over) whatever slope happened to
    // already be there right from the very first time a settlement is
    // founded. Sized off this exact variant's real footprint (see
    // HouseVariants/Blueprint.footprintHalfExtent) - including its
    // free-standing chimney, which sits well outside the wall footprint
    // proper and used to be left standing on unleveled ground, reading as
    // a small detached pillar next to an otherwise flush house.
    b.hasFoundation = terraformFootprint(state, spot[0], spot[1], HouseVariants.blueprintFor(type).footprintColumns());
  }

  /** How many extra coarse cells past the flat core taper back down/up to
   * the land's own natural height - a real building pad doesn't end in a
   * sheer cliff at the property line, it grades out into a gentle slope
   * over a few yards. Without this, terraformFootprint's flat pad met
   * undisturbed ground in a hard vertical step - exactly "like a chunk was
   * taken out of the land". */
  private static final int TERRAFORM_TAPER = 3;

  /** Flattens the ground under a new building's real footprint - the
   * actual set of (x, z) columns its blueprint occupies (see Blueprint.
   * footprintColumns: the foundation slab and the chimney's own separate
   * column), not a circle sized off whichever single point sits furthest
   * from center. A circle that size (almost always driven by the
   * chimney, several world units out) wastefully flattened a lot of
   * empty ground on the side of the building away from the chimney too -
   * a much bigger, unnaturally flat area than the building actually
   * needs, which is what read as an oversized "chunk taken out of the
   * land" around every house. Hugging the real shape instead means the
   * flattened area is basically just the building's own footprint plus a
   * small margin.
   *
   * Every real FINE sub-column under the true footprint gets set to the
   * exact same target height (see VoxelWorld.levelColumn), not just
   * shifted by a uniform delta from wherever it already happened to be
   * (which - since generation's per-fine-column jitter means neighboring
   * columns don't necessarily start at the same height - left the
   * "leveled" site with the same bumps it started with, just at a
   * different average elevation, which is what was still reading as
   * buildings floating/clipping into the ground). Beyond that core, a
   * TAPER ring (see TERRAFORM_TAPER) blends the target height back down/
   * up to each cell's own natural height the further out it sits, so the
   * pad reads as a graded lot with a gentle slope at its edge instead of
   * a flat-topped block dropped onto the landscape. Not a fake cosmetic
   * flourish - a real change to the voxel terrain the building then sits
   * flush on. Never touches a cell that was already water or a mountain
   * (findBuildingSpot only ever picks a dry, buildable anchor, but a
   * low-lying/mountainous neighbor could still be real terrain that
   * shouldn't get paved over - see isBuildable below).
   *
   * @return true if any column actually needed to move to reach the
   * target height - ground that was already flat here needs no
   * cobblestone plinth poured around the building (see Building.
   * hasFoundation/EntityRenderer.updateHouses), same as a real house built
   * on already-level ground getting no raised curb. */
  private static boolean terraformFootprint(GameState state, double cx, double cz, List<float[]> footprintColumns) {
    WorldGrid grid = state.grid;
    VoxelWorld voxels = state.voxels;
    int anchorX = (int) Math.floor(cx), anchorZ = (int) Math.floor(cz);
    if (!grid.inBounds(anchorX, anchorZ)) return true;
    int targetTop = Math.round(grid.height[grid.idx(anchorX, anchorZ)] * VoxelWorld.FINE) + VoxelWorld.Y_OFFSET - 1;

    float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
    for (float[] col : footprintColumns) {
      minX = Math.min(minX, col[0]); maxX = Math.max(maxX, col[0]);
      minZ = Math.min(minZ, col[1]); maxZ = Math.max(maxZ, col[1]);
    }
    int margin = 1 + TERRAFORM_TAPER;
    int loDx = (int) Math.floor(minX) - margin, hiDx = (int) Math.ceil(maxX) + margin;
    int loDz = (int) Math.floor(minZ) - margin, hiDz = (int) Math.ceil(maxZ) + margin;

    boolean graded = false;
    for (int dz = loDz; dz <= hiDz; dz++) {
      for (int dx = loDx; dx <= hiDx; dx++) {
        int x = anchorX + dx, z = anchorZ + dz;
        if (!grid.inBounds(x, z)) continue;
        int i = grid.idx(x, z);
        // mountains are never terraformed, same as water - forcing a flat
        // pad at the anchor's height across a real slope would bulldoze an
        // artificial stone plateau/column straight out of the mountainside
        // (see findBuildingSpot's own comment - this is what was reading
        // as a "random pillar" jutting out of a hillside)
        if (!grid.isBuildable(i)) continue;

        // distance from this cell's own center to the NEAREST real
        // footprint column - not from the building's overall center -
        // so the flattened area hugs the building's actual silhouette
        double cellX = dx + 0.5, cellZ = dz + 0.5;
        double nearest = Double.MAX_VALUE;
        for (float[] col : footprintColumns) {
          double d = Math.hypot(cellX - col[0], cellZ - col[1]);
          if (d < nearest) nearest = d;
        }
        if (nearest > 1.0 + TERRAFORM_TAPER) continue; // fully natural out here - leave it alone

        byte fillType = VoxelWorld.blockForTerrain(grid.terrain[i]);
        int cellTarget;
        if (nearest <= 1.0) {
          // right under (or one cell from) some real part of the
          // building - dead flush with targetTop, same as before
          cellTarget = targetTop;
          if (voxels.columnTopY(x * VoxelWorld.FINE, z * VoxelWorld.FINE) != targetTop) graded = true;
        } else {
          // beyond that: blend toward this cell's OWN natural height
          // (read before this cell is touched) - 100% target right at
          // the core's edge, fading to 0% (fully natural) by the outer
          // taper ring
          double t = Math.max(0, 1.0 - (nearest - 1.0) / TERRAFORM_TAPER);
          int naturalTop = Math.round(grid.height[i] * VoxelWorld.FINE) + VoxelWorld.Y_OFFSET - 1;
          cellTarget = naturalTop + (int) Math.round((targetTop - naturalTop) * t);
        }
        for (int fdz = 0; fdz < VoxelWorld.FINE; fdz++) {
          for (int fdx = 0; fdx < VoxelWorld.FINE; fdx++) {
            int fx = x * VoxelWorld.FINE + fdx, fz = z * VoxelWorld.FINE + fdz;
            voxels.levelColumn(fx, fz, cellTarget, fillType);
          }
        }
        voxels.resyncHeight(grid, x, z);
        grid.markDirtyIdx(i);
      }
    }
    return graded;
  }

  /** A settlement's claimed land slowly reads as actually shaped by
   * someone, not just houses dropped onto raw wilderness - every owned
   * cell's own FINE sub-columns get leveled to that cell's single already-
   * existing rounded height, clearing generation's sparse per-fine-column
   * jitter (see VoxelWorld.generate's fineHash) wherever a nation actually
   * holds the ground. This is NOT a big multi-cell flatten: each cell
   * still follows its own grid.height, so the broader hill/slope profile
   * across the territory is untouched - only the fine bumpiness WITHIN a
   * single cell disappears, the way a real town's tended yards and streets
   * read distinctly smoother than the wild ground just past its border,
   * without leveling an entire hillside into one flat plateau. Bounded to
   * a modest radius around the settlement center (not the full territory
   * radius, which can reach 60) so this stays cheap regardless of how
   * large a nation's claimed land eventually grows - cheaper, in fact,
   * than the claimTerritory sweep it already runs alongside every 25
   * ticks. */
  private static final double TERRITORY_DECLUTTER_RADIUS = 22;

  private static void declutterTerritory(GameState state, Settlement settlement) {
    WorldGrid grid = state.grid;
    VoxelWorld voxels = state.voxels;
    double radius = Math.min(TERRITORY_DECLUTTER_RADIUS, settlement.radius);
    int r = (int) Math.ceil(radius);
    for (int dz = -r; dz <= r; dz++) {
      int z = settlement.z + dz;
      if (z < 0 || z >= grid.rows) continue;
      for (int dx = -r; dx <= r; dx++) {
        int x = settlement.x + dx;
        if (x < 0 || x >= grid.cols || Math.hypot(dx, dz) > radius) continue;
        int i = grid.idx(x, z);
        // mountains stay wild even inside a nation's own borders - a
        // nation can own a mountain (territory claiming is unrelated to
        // buildability), it just never gets graded/decluttered like the
        // rest of its land does
        if (grid.ownerNation[i] != settlement.nationId || !grid.isBuildable(i)) continue;
        int targetTop = Math.round(grid.height[i] * VoxelWorld.FINE) + VoxelWorld.Y_OFFSET - 1;
        byte fillType = VoxelWorld.blockForTerrain(grid.terrain[i]);
        boolean changed = false;
        for (int fdz = 0; fdz < VoxelWorld.FINE; fdz++) {
          for (int fdx = 0; fdx < VoxelWorld.FINE; fdx++) {
            int fx = x * VoxelWorld.FINE + fdx, fz = z * VoxelWorld.FINE + fdz;
            if (voxels.columnTopY(fx, fz) != targetTop) {
              changed = true;
              voxels.levelColumn(fx, fz, targetTop, fillType);
            }
          }
        }
        if (changed) { voxels.resyncHeight(grid, x, z); grid.markDirtyIdx(i); }
      }
    }
  }

  /** Whether a candidate founding spot is actually free of any OTHER
   * living nation's territory - checked against every rival settlement's
   * own authoritative .radius directly, not the painted ownerNation grid.
   * The grid only gets repainted every 25 ticks (see Settlement.update)
   * and its per-cell "effective strength" formula goes negative well
   * inside a settlement's own nominal radius at long range, so a spot
   * geometrically deep inside a wealthy rival's territory could still
   * read as unclaimed on the grid at the exact moment someone tries to
   * found there. Comparing straight-line distance against .radius has no
   * such lag or fringe - it's the same number that eventually decides
   * whether the grid paints that cell for that settlement at all, so a
   * town center can never end up founded inside a border it'll later
   * turn out to already be inside. A modest buffer keeps the new
   * settlement's own minimum radius from immediately touching its
   * neighbor's on day one. */
  private static final double FOUNDING_BORDER_BUFFER = 6;

  public static boolean spotClearOfRivals(GameState state, int x, int y, int excludeNationId) {
    double cx = x + 0.5, cy = y + 0.5;
    for (Settlement other : state.settlements.values()) {
      if (other.abandoned || other.nationId == excludeNationId || other.nationId < 0) continue;
      if (!state.nations.containsKey(other.nationId)) continue;
      double d = Math.hypot(cx - other.x, cy - other.z);
      if (d < other.radius + FOUNDING_BORDER_BUFFER) return false;
    }
    return true;
  }

  /** How many houses a settlement this size actually gets placed - kept
   * in step with EntityRenderer's own cap so road spurs don't reach past
   * where the last house actually is. */
  public static int estimatedHouseCount(Settlement s) {
    return Math.min(32, Math.max(2 + s.populationCount / 3, 2));
  }

  /** Whether this settlement currently has an unoccupied house to give a
   * new or joining resident - counts actual housed residents rather than
   * just populationCount, since a settlement can carry homeless citizens
   * who are still waiting on one. */
  public static boolean hasHouseRoom(GameState state, Settlement settlement) {
    double capacity = settlement.housingStock * PEOPLE_PER_HOUSE;
    int housed = 0;
    for (Human h : state.humans) if (h.settlementId == settlement.id && h.hasHouse) housed++;
    return housed < capacity;
  }

  /** Claims cells in range that are either already ours or genuinely
   * unclaimed wilderness - contested by "claim strength" (population +
   * treasury + military power, falling off with distance) among rival
   * claims on open land, same as before. Once a cell is held by another
   * LIVING nation, ordinary peaceful growth can no longer take it no
   * matter how much richer/stronger this settlement is - that's what
   * war is for now (see Nation.transferSettlement's forced reclaim on
   * conquest, and Settlement.abandon releasing a dead nation's cells
   * back to unclaimed). */
  public static void claimTerritory(GameState state, Settlement settlement) {
    claimTerritory(state, settlement, false);
  }

  /** Unconditionally hands this settlement's surrounding cells to its
   * current nation regardless of who held them before - used right after
   * a conquest (see Nation.transferSettlement) so the captured city's own
   * land changes hands immediately instead of staying painted the
   * defeated nation's color until the next peaceful claim pass silently
   * refuses to touch it. */
  public static void forceClaimTerritory(GameState state, Settlement settlement) {
    claimTerritory(state, settlement, true);
  }

  /** Deterministic wobble on the claim falloff so a settlement's border
   * reads as an organic, hand-drawn coastline instead of a mathematically
   * perfect circle - stable across rebuilds (same trick used for foliage/
   * tree jitter in EntityRenderer) since it's keyed off fixed cell
   * coordinates and the settlement's own id, not anything that changes
   * tick to tick. Hashed on a coarsened (~3-cell) grid rather than the
   * raw cell coordinates so the wobble is spatially smooth - neighboring
   * cells drift together instead of each rolling independent noise, which
   * used to let two rival settlements' unrelated noise fields interleave
   * cell-by-cell into a visibly speckled, "static-y" frontier instead of
   * a single wavy line. */
  private static float borderNoise(int x, int y, int salt) {
    int cx = Math.floorDiv(x, 5), cy = Math.floorDiv(y, 5);
    int h = cx * 374761393 + cy * 668265263 + salt * 2147483647;
    h = (h ^ (h >>> 13)) * 1274126177;
    h = h ^ (h >>> 16);
    return ((h & 0xFFFF) / 65535f - 0.5f) * 3.5f;
  }

  private static void claimTerritory(GameState state, Settlement settlement, boolean force) {
    WorldGrid grid = state.grid;
    Nation nation = state.nations.get(settlement.nationId);
    double treasury = nation != null ? Math.max(0, nation.treasury) : 0;
    // Military power deliberately does NOT feed peaceful claim strength -
    // building an army and declaring war used to visibly balloon a
    // settlement's territory on its own, which read as "we took land just
    // by going to war" even though not one enemy cell actually changed
    // hands. An army's only real effect on the map is what it physically
    // does: win a siege and take the city (see Nation.transferSettlement's
    // forceClaimTerritory below), which is the one and only way a rival's
    // already-claimed soil is meant to change owners.
    float strength = (float) (settlement.populationCount + treasury * 0.01);
    grid.forEachInRadius(settlement.x, settlement.z, settlement.radius, (x, y, d) -> {
      int i = grid.idx(x, y);
      if (grid.terrain[i] == Config.WATER) return;
      boolean alreadyOurs = grid.ownerNation[i] == settlement.nationId;
      if (!force && !alreadyOurs && grid.ownerNation[i] >= 0) return;
      float wobble = borderNoise(x, y, settlement.id);
      float effective = strength - ((float) d + wobble) * 4f;
      if (force || alreadyOurs || effective >= grid.claimStrength[i]) {
        grid.ownerNation[i] = settlement.nationId;
        grid.claimStrength[i] = effective;
        grid.markDirtyIdx(i);
      }
    });
  }

  private static int countFarmCells(GameState state, Settlement settlement) {
    WorldGrid grid = state.grid;
    // the visible farm plot is a smaller ring right around the village
    // center, distinct from the wider (untilled) territory radius
    double plotRadius = Math.min(4.5, 2.2 + Math.sqrt(settlement.populationCount) * 0.25);
    int[] n = {0};
    grid.forEachInRadius(settlement.x, settlement.z, settlement.radius, (x, y, d) -> {
      int i = grid.idx(x, y);
      boolean owned = grid.terrain[i] == Config.GRASS && grid.ownerNation[i] == settlement.nationId;
      if (owned) n[0]++;
      boolean plot = owned && d <= plotRadius;
      if (grid.isFarmland[i] != plot) { grid.isFarmland[i] = plot; grid.markDirtyIdx(i); }
    });
    return n[0];
  }

  private static final double FOOD_PER_WORKER = 0.55;
  private static final double FOOD_PER_POP = 0.42;
  private static final int POP_CAP_PER_SETTLEMENT = 70;
  public static final double PEOPLE_PER_HOUSE = 4.0;
  private static final double HOUSE_WOOD_COST = 12.0;

  /** A house rises from bare foundation to fully built over roughly 3
   * months of game time - slow enough to actually watch happen in real
   * play (30 ticks/1 month completed in well under 10 real seconds even
   * at the default game speed, which read as an instant pop-in rather
   * than construction), not so slow it reads as never finishing. */
  private static final double CONSTRUCTION_RATE = 1.0 / 90.0;

  public static void update(GameState state) {
    // real, individually-placed buildings actually rising - see
    // Building's own class comment. A flat per-tick pass over every
    // building regardless of settlement, not nested inside the
    // per-settlement loop below, so this stays O(buildings) instead of
    // O(settlements x buildings).
    for (Building b : state.buildings.values()) {
      if (b.progress < 1.0) b.progress = Math.min(1.0, b.progress + CONSTRUCTION_RATE);
    }

    for (Settlement s : state.settlements.values()) s.populationCount = 0;
    // one pass over every human to figure out which settlements actually
    // have a mature male and a mature female resident - growth below only
    // fires for settlements with a real pair, not just enough stored food
    Map<Integer, Boolean> hasMatureMale = new HashMap<>();
    Map<Integer, Boolean> hasMatureFemale = new HashMap<>();
    for (Human h : state.humans) {
      Settlement s = state.settlements.get(h.settlementId);
      if (s != null) s.populationCount++;
      if (h.age >= Config.MATURE_AGE) {
        if (h.female) hasMatureFemale.put(h.settlementId, true);
        else hasMatureMale.put(h.settlementId, true);
      }
    }

    List<Settlement> toAbandon = null;
    for (Settlement settlement : state.settlements.values()) {
      if (!settlement.abandoned && settlement.populationCount == 0) {
        if (toAbandon == null) toAbandon = new ArrayList<>();
        toAbandon.add(settlement);
      }
    }
    if (toAbandon != null) for (Settlement settlement : toAbandon) abandon(state, settlement);

    for (Settlement settlement : state.settlements.values()) {
      if (settlement.abandoned) continue;

      if (state.tick % 25 == 0) {
        // territory used to be population-only and capped at a modest 11 -
        // even a flourishing empire topped out claiming a small patch
        // around each town, leaving most of the map permanently unclaimed
        // wilderness. Now a rich settlement genuinely presses outward
        // further than a poor one, and claimTerritory (below) lets that
        // win contested (unclaimed) cells from a weaker neighbor. Cap
        // raised from 24 to 44 (and the growth rate itself bumped) - even
        // a thriving, long-running world still had most of the map
        // sitting empty since circles that size never grew large enough
        // to meet their neighbors and fill the gaps between capitals.
        // Deliberately NOT a function of military power - see the comment
        // on claimTerritory's strength calc for why: growing an army
        // shouldn't by itself balloon a nation's claimed land at all: the
        // only thing an army does to the map is win a city, not grow a
        // border.
        Nation homeNation = state.nations.get(settlement.nationId);
        double wealthBonus = homeNation != null ? Math.sqrt(Math.max(0, homeNation.treasury)) * 0.16 : 0;
        // floor and cap both raised (10->16, 44->60) to match housePosition's
        // wider spiral spacing for real multi-block houses (see its own
        // comment) - never lets the claim radius shrink back below where
        // this settlement's own buildings actually sit
        settlement.radius = Math.min(60, 16 + Math.sqrt(settlement.populationCount) * 1.6 + wealthBonus);
        settlement.farmCells = countFarmCells(state, settlement);
        claimTerritory(state, settlement);
        declutterTerritory(state, settlement);

        // public housing backstop: housingStock is built to track total
        // population, not how many residents can actually afford to buy
        // back in after a repossession (see Population.maybeBuyHouse), so
        // a rising share could end up permanently homeless right next to
        // a rising number of genuinely empty houses - "most homes go
        // vacant" alongside a homelessness crisis, at the same time. If
        // this settlement has any vacancy, hand one over to a resident
        // still without a roof - gradual (one per cycle), not instant,
        // but no longer stuck behind an unreachable savings bar either.
        double houseRoom = settlement.housingStock * PEOPLE_PER_HOUSE;
        int housedCount = 0;
        for (Human h : state.humans) if (h.settlementId == settlement.id && h.hasHouse) housedCount++;
        if (housedCount < houseRoom) {
          for (Human h : state.humans) {
            if (h.settlementId == settlement.id && !h.hasHouse) { h.hasHouse = true; break; }
          }
        }
      }

      int farmWorkers = Math.min(settlement.populationCount, settlement.farmCells);
      // a small unconditional foraging trickle, independent of farmland -
      // a settlement founded on a grass-poor spot (sand, or boxed in by
      // mountains) would otherwise produce exactly zero food forever and
      // starve out immediately, which used to be harmless (a dead
      // settlement just sat inert) but now genuinely deletes the whole
      // nation if it was that settlement's only one - this buys a real
      // settlement enough time to trade, expand, or be reinforced instead
      // real farming (the per-worker term) depends on how good this
      // settlement's own land actually is - see WorldGrid.fertility, its
      // own broad low-frequency field independent of terrain/moisture, so
      // a nation founded on genuinely fertile ground grows real food
      // faster than one boxed into poor land, same idea already applied
      // to mineral deposits (WorldGen's regional favorability). The
      // unconditional trickle right after it is foraging, not real
      // farming, so it isn't gated by land quality.
      float fertility = state.grid.fertility[state.grid.idx(settlement.x, settlement.z)];
      double production = farmWorkers * FOOD_PER_WORKER * (0.5 + fertility) + Math.min(settlement.farmCells, 3) * 0.08 + 1.2;
      double consumption = settlement.populationCount * FOOD_PER_POP;
      settlement.stock.merge("food", production - consumption, Double::sum);

      // a farm needs a hired hand to run, and that hand is paid a
      // government-set daily wage - the same wagePolicy lever that governs
      // every other job, so an underpaying government shows up here too.
      // Unlike a haul-wage this isn't backed by a matching market sale, so
      // it's kept modest - a subsidy the treasury carries, not a 1:1 cost.
      if (farmWorkers > 0) {
        Nation farmNation = state.nations.get(settlement.nationId);
        if (farmNation != null) {
          double foodPrice = state.market.prices.getOrDefault("food", 1.0);
          farmNation.treasury -= farmWorkers * FOOD_PER_WORKER * foodPrice * farmNation.wagePolicy * 0.35;
        }
      }

      if (settlement.stock.get("food") < 0) {
        settlement.starveTicks++;
        settlement.stock.put("food", Math.max(settlement.stock.get("food"), -30));
        if (settlement.starveTicks > 10 && settlement.populationCount > 0) {
          List<Human> victims = new ArrayList<>();
          for (Human h : state.humans) if (h.settlementId == settlement.id) victims.add(h);
          int killCount = Math.min(victims.size(), 1 + (int) (Math.random() * 2));
          for (int i = 0; i < killCount && !victims.isEmpty(); i++) {
            victims.get((int) (Math.random() * victims.size())).dead = true;
            DeathStats.starve++;
          }
          state.humans.removeIf(h -> h.dead);
          settlement.starveTicks = 0;
          settlement.stock.put("food", 0.0);
        }
      } else {
        settlement.starveTicks = 0;
      }

      // every citizen needs a roof: build houses out of spare wood whenever
      // the settlement is running low on space for its current population
      double houseCapacity = settlement.housingStock * PEOPLE_PER_HOUSE;
      if (settlement.populationCount >= houseCapacity - 2
          && settlement.stock.get("wood") > HOUSE_WOOD_COST + Config.SETTLEMENT_BUFFER * 0.5) {
        settlement.stock.merge("wood", -HOUSE_WOOD_COST, Double::sum);
        settlement.housingStock += 1;
        spawnHouseBuilding(state, settlement, 0.0);
        houseCapacity = settlement.housingStock * PEOPLE_PER_HOUSE;
        // the house that was just built goes to whoever in this
        // settlement still doesn't have one
        for (Human h : state.humans) {
          if (h.settlementId == settlement.id && !h.hasHouse) { h.hasHouse = true; break; }
        }
      }

      // a hard requirement (zero growth without a same-settlement pair)
      // turned any settlement that randomly skewed to one gender - common
      // once war/starvation shrinks a population down to a few people -
      // into a death spiral with no way back, since it could then never
      // grow again on its own. A real pair still grows the settlement far
      // faster, but a settlement without one isn't stuck at zero forever.
      boolean canReproduce = hasMatureMale.getOrDefault(settlement.id, false)
          && hasMatureFemale.getOrDefault(settlement.id, false);
      // growth used to only check the food buffer at this exact instant,
      // not whether the settlement could actually go on FEEDING the new
      // mouth - a settlement with little or no farmland would grow past
      // what its passive trickle production could sustain, go negative,
      // and get its population violently cut back down by the starvation
      // kill below, then grow right back into the same wall. Capping
      // growth at what farmCells can support (plus the trickle's own
      // small headroom) keeps population growth a steady climb instead of
      // that boom-and-starve sawtooth.
      if (settlement.stock.get("food") > Config.SETTLEMENT_BUFFER
          && settlement.populationCount < POP_CAP_PER_SETTLEMENT
          && settlement.populationCount < houseCapacity
          && settlement.populationCount < settlement.farmCells + 4
          && state.humans.size() < Config.MAX_HUMANS) {
        settlement.growthAccum += canReproduce ? 0.02 : 0.003;
        if (settlement.growthAccum >= 1) {
          settlement.growthAccum -= 1;
          settlement.stock.merge("food", -18.0, Double::sum);
          double ang = Math.random() * Math.PI * 2;
          // a birth only happens when populationCount < houseCapacity
          // above, so there's guaranteed to be room for this one
          Human baby = Population.createHuman(
              settlement.x + 0.5 + Math.cos(ang) * 1.5,
              settlement.z + 0.5 + Math.sin(ang) * 1.5,
              settlement.nationId, settlement.id);
          baby.hasHouse = true;
          state.humans.add(baby);
        }
      }
    }
  }

  /** A settlement that just lost its last citizen leaves its nation and
   * releases its territory/farmland back to no-man's-land; its structures
   * stay physically standing (settlementAt is left alone, permanently
   * blocking a new settlement from founding on the exact same spot) but
   * the record itself is removed from state.settlements below - nothing
   * else ever looks a settlement up by ID expecting a ruin to still be
   * there, and leaving thousands of dead entries around over a long game
   * both inflated every "settlements" count in the UI/logs and leaked
   * memory that never gets reclaimed. */
  private static void abandon(GameState state, Settlement settlement) {
    settlement.abandoned = true;
    // zero out (rather than clear) so the many call sites that assume the
    // standard keys are always present don't NPE on a missing entry
    for (String key : settlement.stock.keySet()) settlement.stock.put(key, 0.0);

    int oldNationId = settlement.nationId;
    Nation nation = state.nations.get(oldNationId);
    if (nation != null) {
      nation.settlementIds.remove(Integer.valueOf(settlement.id));
      if (nation.capitalSettlementId == settlement.id) {
        nation.capitalSettlementId = nation.settlementIds.isEmpty() ? -1 : nation.settlementIds.iterator().next();
      }
    }
    settlement.nationId = -1;

    WorldGrid grid = state.grid;
    grid.forEachInRadius(settlement.x, settlement.z, settlement.radius, (x, y, d) -> {
      int i = grid.idx(x, y);
      if (grid.ownerNation[i] == oldNationId) {
        grid.ownerNation[i] = -1;
        // otherwise this settlement's last claim strength lingers forever
        // and can block a neighbor from ever claiming the cell, even
        // though nothing is actually holding it anymore
        grid.claimStrength[i] = 0;
        grid.markDirtyIdx(i);
      }
      if (grid.isFarmland[i]) { grid.isFarmland[i] = false; grid.markDirtyIdx(i); }
    });

    List<Integer> deadBusinesses = new ArrayList<>();
    for (Business b : state.businesses.values()) if (b.settlementId == settlement.id) deadBusinesses.add(b.id);
    for (int id : deadBusinesses) state.businesses.remove(id);

    state.settlements.remove(settlement.id);
  }

  /** Full re-claim pass across every settlement, nearest-wins. Cheap enough
   * to run every few dozen ticks rather than continuously. */
  public static void recomputeTerritory(GameState state) {
    WorldGrid grid = state.grid;
    List<Settlement> settlements = new ArrayList<>(state.settlements.values());
    java.util.Arrays.fill(grid.ownerNation, -1);
    if (settlements.isEmpty()) return;
    for (int y = 0; y < grid.rows; y++) {
      for (int x = 0; x < grid.cols; x++) {
        int i = grid.idx(x, y);
        if (grid.terrain[i] == Config.WATER) continue;
        Settlement best = null;
        double bestD = Double.MAX_VALUE;
        for (Settlement s : settlements) {
          double d = Math.hypot(x - s.x, y - s.z);
          if (d <= s.radius && d < bestD) { bestD = d; best = s; }
        }
        if (best != null) grid.ownerNation[i] = best.nationId;
      }
    }
    for (int i = 0; i < grid.cols * grid.rows; i++) grid.markDirtyIdx(i);
  }
}
