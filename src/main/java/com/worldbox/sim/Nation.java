package com.worldbox.sim;

import com.worldbox.config.Config;
import com.worldbox.util.Rng;
import com.worldbox.world.WorldGen;
import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class Nation {
  private static int nextId = 1;
  private static int colorCursor = 0;

  private static final String[] NAME_PREFIX = {
      "Val", "Kor", "Thal", "Bran", "Els", "Dun", "Mor", "Ash", "Vor", "Cal", "Ost", "Fen",
      "Ber", "Gal", "Nor", "Ryn", "Sel", "Tor", "Wex", "Zan", "Had", "Lior", "Mir", "Ked",
      "Sev", "Aur", "Bel", "Cres", "Dra", "Erd", "Fal", "Gris", "Hol", "Ith", "Jor", "Kael"
  };
  private static final String[] NAME_MID = {"", "", "", "en", "ar", "in", "on", "ell", "and"};
  private static final String[] NAME_SUFFIX = {
      "ia", "mark", "land", "gard", "heim", "ova", "stan", "wen", "dor", "ath",
      "burg", "shire", "vale", "moor", "crest", "haven", "reach", "ford", "wick", "ton"
  };

  /** Builds a plausible-sounding core name (e.g. "Valendoria",
   * "Korshire") - two or three syllables from wide phoneme pools instead
   * of a single prefix+suffix pair, so nations stop rhyming with each
   * other after a dozen or so get founded. */
  public static String randomNationName(Rng rng) {
    String mid = rng.pick(NAME_MID);
    String core = rng.pick(NAME_PREFIX) + mid + rng.pick(NAME_SUFFIX);
    return Character.toUpperCase(core.charAt(0)) + core.substring(1);
  }

  /** The government-flavored title placed before/after a nation's core
   * name - "Kingdom of Valendoria", "Korshire Empire" - so the government
   * mechanics and the name players see actually agree with each other. */
  public String displayName() {
    switch (government) {
      case Government.MONARCHY: return "Kingdom of " + name;
      case Government.DEMOCRACY: return "Republic of " + name;
      case Government.AUTOCRACY: return name + " Empire";
      case Government.OLIGARCHY: return name + " Consortium";
      default: return name;
    }
  }

  public final int id;
  public String name;
  public final int colorIndex;
  public final int color;
  public int capitalSettlementId;
  public final Set<Integer> settlementIds = new LinkedHashSet<>();
  public double treasury = 180;
  public double taxRate = Config.TAX_RATE_DEFAULT;
  public final Set<Integer> armyIds = new LinkedHashSet<>();
  public final int founded;
  public boolean alive = true;
  public String ideology; // "capitalism" | "communism"
  public final Bank bank = new Bank();
  public String government; // Government.DEMOCRACY | AUTOCRACY | MONARCHY | OLIGARCHY
  public double stability = 65;
  /** last ~120 tax-cycle samples of treasury, for the economy graph. */
  public final java.util.ArrayDeque<Double> treasuryHistory = new java.util.ArrayDeque<>();
  /** last ~120 samples of this nation's businesses' combined market-cap
   * style valuation - the primary series for the stock-market-style
   * economy graph. */
  public final java.util.ArrayDeque<Double> marketCapHistory = new java.util.ArrayDeque<>();
  /** last ~120 snapshots of the jobless share of this nation's population. */
  public final java.util.ArrayDeque<Double> unemploymentHistory = new java.util.ArrayDeque<>();
  /** last ~120 periodic readings of business+trade revenue generated -
   * a flow (per ~20-tick window), not a running total like treasury. */
  public final java.util.ArrayDeque<Double> gdpHistory = new java.util.ArrayDeque<>();
  /** last ~120 samples of treasury-per-capita measured in gold - how much
   * gold a citizen's per-capita share of the treasury could buy. */
  public final java.util.ArrayDeque<Double> currencyHistory = new java.util.ArrayDeque<>();
  /** revenue generated this sampling window (business output + national
   * trade), reset to 0 every time it's rolled into gdpHistory. */
  public double gdpAccum = 0;
  /** the nation's currency name, e.g. "Valendorian Crown" - cosmetic, but
   * ties the currency-vs-gold graph to something with a name. */
  public String currencyName;

  private static final String[] CURRENCY_SUFFIX = {"Crown", "Mark", "Pound", "Dinar", "Franc", "Ducat", "Guilder", "Real", "Krona", "Talent"};

  private Nation(int founded, String name) {
    this.id = nextId++;
    this.colorIndex = colorCursor++ % Config.NATION_COLORS.length;
    this.color = Config.NATION_COLORS[this.colorIndex];
    this.name = name;
    this.founded = founded;
    this.ideology = Math.random() < 0.5 ? "capitalism" : "communism";
    this.government = Government.random();
    this.currencyName = (name != null ? name : "National") + " " + CURRENCY_SUFFIX[(int) (Math.random() * CURRENCY_SUFFIX.length)];
  }

  public static Nation create(GameState state, Settlement capitalSettlement, String name) {
    Nation nation = new Nation(state.tick, name != null ? name : null);
    if (name == null) nation.name = "Nation " + nation.id;
    nation.capitalSettlementId = capitalSettlement.id;
    nation.settlementIds.add(capitalSettlement.id);
    state.nations.put(nation.id, nation);
    capitalSettlement.nationId = nation.id;
    for (Human h : state.humans) if (h.settlementId == capitalSettlement.id) h.nationId = nation.id;
    return nation;
  }

  public static Nation foundNewNation(GameState state, int x, int z, String name) {
    Settlement settlement = Settlement.create(state, x, z, -1, null);
    return create(state, settlement, name);
  }

  public static double totalMilitaryPower(GameState state, int nationId) {
    double power = 0;
    for (Army army : state.armies.values()) {
      if (army.nationId != nationId) continue;
      power += army.strength;
    }
    return power;
  }

  private static void trySettlementUpkeepSpending(Nation nation) {
    if (nation.treasury < -50) nation.taxRate = Math.min(0.45, nation.taxRate + 0.01);
    else if (nation.treasury > 400) nation.taxRate = Math.max(0.1, nation.taxRate - 0.002);
  }

  private static void tryExpand(GameState state, Nation nation) {
    if (nation.treasury < 220) return;
    if (nation.settlementIds.size() >= 6) return;
    if (Math.random() > 0.02) return;
    Settlement capital = state.settlements.get(nation.capitalSettlementId);
    if (capital == null) return;
    List<Settlement> anchors = new ArrayList<>();
    for (int sid : nation.settlementIds) {
      Settlement s = state.settlements.get(sid);
      if (s != null) anchors.add(s);
    }
    Settlement from = anchors.isEmpty() ? capital : anchors.get((int) (Math.random() * anchors.size()));
    WorldGen.Spot spot = WorldGen.findLandSpot(state.grid, from.x, from.z, 16, state.rng);
    if (spot == null) return;
    int i = state.grid.idx(spot.x, spot.y);
    if (state.grid.ownerNation[i] >= 0 && state.grid.ownerNation[i] != nation.id) return;
    nation.treasury -= 200;
    Settlement settlement = Settlement.create(state, spot.x, spot.y, nation.id, Settlement.randomSettlementName(state.rng));
    nation.settlementIds.add(settlement.id);
  }

  public static void update(GameState state) {
    if (state.tick % 30 == 0) updateRoads(state);

    for (Nation nation : new ArrayList<>(state.nations.values())) {
      if (!nation.alive) continue;

      double treasuryGain = 0;
      for (int sid : nation.settlementIds) {
        Settlement settlement = state.settlements.get(sid);
        if (settlement == null) continue;
        for (String key : new String[]{"wood", "stone", "iron", "gold_ore"}) {
          double surplus = Math.max(0, settlement.stock.get(key) - Config.SETTLEMENT_BUFFER);
          if (surplus <= 0) continue;
          double taxed = surplus * nation.taxRate * 0.25;
          settlement.stock.merge(key, -taxed, Double::sum);
          treasuryGain += taxed * state.market.prices.get(key);
        }
      }
      nation.treasury += treasuryGain;
      trySettlementUpkeepSpending(nation);
      tryExpand(state, nation);

      if (nation.settlementIds.isEmpty()) {
        nation.alive = false;
        state.nations.remove(nation.id);
      }
    }
  }

  /** Redraws every nation's road network from scratch each cycle - a
   * straight line from each settlement to its nation's capital, skipping
   * water. Simple, but gives every nation visible, purposeful
   * infrastructure connecting it together instead of isolated dots. */
  private static void updateRoads(GameState state) {
    WorldGrid grid = state.grid;
    boolean[] before = grid.isRoad.clone();
    java.util.Arrays.fill(grid.isRoad, false);
    for (Nation nation : state.nations.values()) {
      if (!nation.alive) continue;
      Settlement capital = state.settlements.get(nation.capitalSettlementId);
      if (capital == null) continue;
      for (int sid : nation.settlementIds) {
        if (sid == nation.capitalSettlementId) continue;
        Settlement s = state.settlements.get(sid);
        if (s == null) continue;
        drawRoad(grid, capital.x, capital.z, s.x, s.z);
      }
    }
    // only re-mesh chunks whose road status actually changed, not the
    // whole map, since this redraws from scratch every cycle
    for (int i = 0; i < grid.isRoad.length; i++) {
      if (grid.isRoad[i] != before[i]) grid.markDirtyIdx(i);
    }
  }

  private static void drawRoad(WorldGrid grid, int x0, int z0, int x1, int z1) {
    int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
    int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
    int err = dx - dz;
    int x = x0, z = z0;
    while (true) {
      if (grid.inBounds(x, z)) {
        int i = grid.idx(x, z);
        if (grid.terrain[i] != Config.WATER) grid.isRoad[i] = true;
      }
      if (x == x1 && z == z1) break;
      int e2 = 2 * err;
      if (e2 > -dz) { err -= dz; x += sx; }
      if (e2 < dx) { err += dx; z += sz; }
    }
  }

  public static void transferSettlement(GameState state, Settlement settlement, int newNationId) {
    Nation oldNation = state.nations.get(settlement.nationId);
    if (oldNation != null) oldNation.settlementIds.remove(settlement.id);
    settlement.nationId = newNationId;
    Nation newNation = state.nations.get(newNationId);
    if (newNation != null) newNation.settlementIds.add(settlement.id);
    for (Human h : state.humans) if (h.settlementId == settlement.id) h.nationId = newNationId;
    if (oldNation != null && oldNation.settlementIds.isEmpty()) {
      oldNation.alive = false;
      state.nations.remove(oldNation.id);
    }
  }
}
