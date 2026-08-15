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

  /** Total nations ever founded this game, alive or extinct - lets debug
   * tooling report an extinction rate (founded vs. currently alive)
   * instead of only ever seeing whoever's still standing. */
  public static int totalFounded() { return nextId - 1; }

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
  /** Government-set share of a sold haul's value that goes to the worker
   * as a wage - a real policy lever, not a fixed constant, so different
   * governments (and different treasuries) pay their workers differently. */
  public double wagePolicy = 0.35;
  public final Set<Integer> armyIds = new LinkedHashSet<>();
  public final int founded;
  public boolean alive = true;
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
  /** last ~120 samples of this currency's exchange rate (1.0 = par at
   * founding); this is what actually crashes when a currency collapses. */
  public final java.util.ArrayDeque<Double> currencyHistory = new java.util.ArrayDeque<>();
  /** last ~120 windowed inflation readings - the rate new money entered
   * circulation outpacing real output growth. */
  public final java.util.ArrayDeque<Double> inflationHistory = new java.util.ArrayDeque<>();
  /** last ~120 samples of the average citizen's personal wealth - the
   * ordinary-person view of the economy, distinct from treasury (the
   * government's money) or GDP (total output). */
  public final java.util.ArrayDeque<Double> wealthHistory = new java.util.ArrayDeque<>();
  /** true once this nation only prints to cover deficits with no reserve
   * backing - once dominant enough, a currency can leave the gold standard
   * and never goes back, same as real reserve currencies. */
  public boolean goldStandard = true;
  /** total currency this nation has ever put into circulation - grows
   * only when the government prints to cover a deficit. */
  public double moneySupply = 180;
  /** physical gold this nation has actually banked (from mined gold_ore
   * sales) - while on the gold standard this caps how much can be
   * printed, so a nation can't fabricate backing it doesn't have. */
  public double goldReserves = 0;
  /** currency value relative to its own founding value; 0 once collapsed. */
  public double exchangeRate = 1.0;
  public double inflationRate = 0;
  /** once a currency hyperinflates to worthlessness it never recovers -
   * exactly like a real collapsed currency. */
  public boolean currencyCollapsed = false;
  /** money printed since the last inflation sample window. */
  public double printedThisWindow = 0;
  /** "loose" prints through any deficit, "tight" refuses to print and
   * eats the deficit instead, "neutral" lets government type + stability
   * decide case by case. Governments auto-pick this; a reckless (loose)
   * pick under a bad, unstable leader is exactly what causes a crash. */
  public String monetaryPolicy = "neutral";
  /** last-sampled jobless share of this nation's population - cached here
   * (rather than recomputed every tick) so stability can react to it
   * without an extra full population scan each tick. */
  public double unemploymentRate = 0;
  /** revenue generated this sampling window (business output + national
   * trade), reset to 0 every time it's rolled into gdpHistory. */
  public double gdpAccum = 0;
  /** A slow-drifting, mean-reverting business-cycle multiplier on real
   * trade/production revenue - without it, a mature economy with a
   * saturated population just settles into one static equilibrium GDP
   * forever, with nothing but the rare price-crash to disturb it. Real
   * economies have actual multi-year expansions and recessions even once
   * "grown up"; this is what gives this one the same. */
  public double econCycle = 1.0;
  /** the nation's currency name, e.g. "Valendorian Crown" - cosmetic, but
   * ties the currency-vs-gold graph to something with a name. */
  public String currencyName;
  /** the person actually running the country - their personality is a
   * real input into how the nation behaves, not flavor text. */
  public Leader leader;

  private static final String[] CURRENCY_SUFFIX = {"Crown", "Mark", "Pound", "Dinar", "Franc", "Ducat", "Guilder", "Real", "Krona", "Talent"};

  /** A real consequence of a revolution or economic collapse: the old
   * currency is scrapped and a new one is issued. Money supply resets to
   * whatever's left in the treasury (can't un-print what was already
   * spent into the economy) and the exchange rate starts fresh at par. */
  public void issueNewCurrency() {
    this.currencyName = this.name + " " + CURRENCY_SUFFIX[(int) (Math.random() * CURRENCY_SUFFIX.length)];
    this.moneySupply = Math.max(50, this.treasury);
    this.exchangeRate = 1.0;
    this.inflationRate = 0;
    this.printedThisWindow = 0;
    this.currencyCollapsed = false;
  }

  private Nation(int founded, String name, Rng rng) {
    this.id = nextId++;
    this.colorIndex = colorCursor++ % Config.NATION_COLORS.length;
    this.color = Config.NATION_COLORS[this.colorIndex];
    this.name = name != null ? name : randomNationName(rng != null ? rng : new Rng((long) (Math.random() * Long.MAX_VALUE)));
    this.founded = founded;
    this.government = Government.random();
    this.currencyName = this.name + " " + CURRENCY_SUFFIX[(int) (Math.random() * CURRENCY_SUFFIX.length)];
    this.leader = new Leader(this.government);
  }

  public static Nation create(GameState state, Settlement capitalSettlement, String name) {
    Nation nation = new Nation(state.tick, name, state.rng);
    nation.capitalSettlementId = capitalSettlement.id;
    nation.settlementIds.add(capitalSettlement.id);
    state.nations.put(nation.id, nation);
    capitalSettlement.nationId = nation.id;
    for (Human h : state.humans) if (h.settlementId == capitalSettlement.id) h.nationId = nation.id;
    EventLog.log(state, "nation", nation.name + " was founded");
    return nation;
  }

  public static Nation foundNewNation(GameState state, int x, int z, String name) {
    Settlement settlement = Settlement.create(state, x, z, -1, Settlement.randomSettlementName(state.rng));
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

  /** How far along the weapon-tech timeline this nation is, purely a
   * function of its age - see Config.ERA_* - so a war that drags on long
   * enough naturally escalates from spears to guns to tanks without a
   * separate research system to build. */
  public static int era(GameState state, Nation nation) {
    int age = state.tick - nation.founded;
    int era = Config.ERA_ANCIENT;
    for (int i = 0; i < Config.ERA_AGE_TICKS.length; i++) {
      if (age >= Config.ERA_AGE_TICKS[i]) era = i;
    }
    return era;
  }

  private static void trySettlementUpkeepSpending(Nation nation) {
    if (nation.treasury < -50) nation.taxRate = Math.min(0.45, nation.taxRate + 0.01);
    else if (nation.treasury > 400) nation.taxRate = Math.max(0.1, nation.taxRate - 0.002);
  }

  /** Wages are a real policy choice: democracies bid wages up to keep
   * voters happy, oligarchies/autocracies keep them low to protect
   * capital/the state, and any government facing a deep deficit cuts
   * wages further as an austerity measure - a bad leader running the
   * treasury into the ground shows up here as real people getting paid
   * less, not just as an abstract stat. */
  private static void updateWagePolicy(Nation nation) {
    double target;
    switch (nation.government == null ? "" : nation.government) {
      case Government.DEMOCRACY: target = 0.44; break;
      case Government.MONARCHY: target = 0.32; break;
      case Government.AUTOCRACY: target = 0.26; break;
      case Government.OLIGARCHY: target = 0.20; break;
      default: target = 0.35;
    }
    if (nation.treasury < -50) target *= 0.65;
    else if (nation.treasury < 0) target *= 0.85;
    // a greedy leader keeps a bigger cut for the state/themselves no
    // matter what the government structure nominally allows
    if (nation.leader != null) target *= (1 - nation.leader.personality.greed * 0.35);
    nation.wagePolicy += (target - nation.wagePolicy) * 0.01;
    nation.wagePolicy = Math.max(0.10, Math.min(0.55, nation.wagePolicy));
  }

  /** A government's monetary stance isn't fixed - a stable, accountable
   * government (high stability, democracy) chooses discipline; an
   * unstable or unaccountable one reaches for the printing press instead
   * of admitting a deficit. This is the "bad leader" lever the whole
   * currency system hangs off of. */
  private static void updateMonetaryPolicy(Nation nation) {
    if (nation.currencyCollapsed) { nation.monetaryPolicy = "loose"; return; }
    // a genuinely foolish leader is reckless regardless of what the
    // government structure or the books would otherwise justify - this is
    // the single biggest "bad leader causes a crash" lever
    if (nation.leader != null && nation.leader.personality.wisdom < 0.2) {
      nation.monetaryPolicy = "loose";
      return;
    }
    boolean accountable = nation.government.equals(Government.DEMOCRACY);
    boolean wise = nation.leader == null || nation.leader.personality.wisdom > 0.4;
    if (nation.stability > 55 && (accountable || nation.stability > 75) && wise) {
      nation.monetaryPolicy = "tight";
    } else if (nation.stability < 30 || (!accountable && nation.treasury < -20)) {
      nation.monetaryPolicy = "loose";
    } else {
      nation.monetaryPolicy = "neutral";
    }
  }

  /** The heart of the currency simulation: deficits get covered by
   * printing money rather than shown to the player directly, exactly like
   * a real central bank quietly monetizing debt - the player only finds
   * out once inflation and a weakening exchange rate make it visible. A
   * gold-standard nation can only print up to what its banked gold could
   * cover; a fiat nation (one that has left the gold standard) can print
   * without limit, for better or worse. */
  private static void updateCurrency(GameState state, Nation nation) {
    if (nation.currencyCollapsed) {
      nation.exchangeRate = 0;
      return;
    }

    if (nation.treasury < 0) {
      boolean willPrint = !nation.monetaryPolicy.equals("tight");
      if (willPrint) {
        double need = -nation.treasury;
        double goldPrice = state.market.prices.getOrDefault("gold_ore", 1.0);
        double printed = need;
        if (nation.goldStandard) {
          // fractional reserve: banked gold backs several times its value in
          // circulating currency, plus every nation keeps a small
          // unconditional cushion so a modest deficit doesn't instantly and
          // permanently jam the printing press for a nation with no gold yet
          double backingCapacity = nation.goldReserves * goldPrice * 4.0;
          double headroom = Math.max(nation.moneySupply * 0.05, backingCapacity - nation.moneySupply);
          printed = Math.min(need, Math.max(0, headroom));
        }
        if (printed > 0) {
          nation.treasury += printed;
          nation.moneySupply += printed;
          nation.printedThisWindow += printed;
        }
      }
    }

    // a currency dominant enough to trust the whole world's trade can
    // afford to cut loose from the gold standard and print at will - "pull
    // a US" - once gone, nobody goes back to a hard peg
    if (nation.goldStandard && nation.stability > 55 && Math.random() < 0.0004) {
      double worldSupply = 0;
      int count = 0;
      for (Nation o : state.nations.values()) {
        if (!o.alive) continue;
        worldSupply += o.moneySupply;
        count++;
      }
      double avg = count > 0 ? worldSupply / count : 0;
      if (avg > 0 && nation.moneySupply > avg * 1.8) nation.goldStandard = false;
    }
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
    if (state.tick % 150 == 0) fillTerritoryGaps(state);

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
      updateWagePolicy(nation);
      updateMonetaryPolicy(nation);
      updateCurrency(state, nation);
      tryExpand(state, nation);

      if (nation.settlementIds.isEmpty()) {
        killNation(state, nation);
      }
    }
  }

  /** Wipes a nation's world presence: its armies, and the nation record
   * itself (with all its stats/history). Settlements were already released
   * back to no-man's-land by Settlement.abandon() before this is called. */
  /** Mops up small unclaimed pockets fully enclosed by one nation's own
   * territory (a "donut hole" where two of that nation's overlapping
   * circular claims almost but didn't quite meet) - a real border still
   * only ever moves by growth or conquest (see Settlement.claimTerritory),
   * this just stops the map from being visibly perforated with tiny
   * unclaimed gaps inside a nation's own borders. Deliberately narrow
   * (every one of a cell's 4 neighbors must already agree) so it never
   * bites into genuinely contested or neutral wilderness between two
   * different nations. */
  private static void fillTerritoryGaps(GameState state) {
    WorldGrid grid = state.grid;
    for (int y = 0; y < grid.rows; y++) {
      for (int x = 0; x < grid.cols; x++) {
        int i = grid.idx(x, y);
        if (grid.terrain[i] == Config.WATER || grid.ownerNation[i] >= 0) continue;
        int owner = -2;
        boolean unanimous = true;
        int neighbors = 0;
        if (x > 0) { neighbors++; int o = grid.ownerNation[grid.idx(x - 1, y)]; if (o < 0) { unanimous = false; } else if (owner == -2) owner = o; else if (owner != o) unanimous = false; }
        if (x < grid.cols - 1) { neighbors++; int o = grid.ownerNation[grid.idx(x + 1, y)]; if (o < 0) { unanimous = false; } else if (owner == -2) owner = o; else if (owner != o) unanimous = false; }
        if (y > 0) { neighbors++; int o = grid.ownerNation[grid.idx(x, y - 1)]; if (o < 0) { unanimous = false; } else if (owner == -2) owner = o; else if (owner != o) unanimous = false; }
        if (y < grid.rows - 1) { neighbors++; int o = grid.ownerNation[grid.idx(x, y + 1)]; if (o < 0) { unanimous = false; } else if (owner == -2) owner = o; else if (owner != o) unanimous = false; }
        if (unanimous && neighbors == 4 && owner >= 0) {
          grid.ownerNation[i] = owner;
          grid.claimStrength[i] = 1f;
          grid.markDirtyIdx(i);
        }
      }
    }
  }

  public static void killNation(GameState state, Nation nation) {
    nation.alive = false;
    for (int aid : new ArrayList<>(nation.armyIds)) {
      Army a = state.armies.get(aid);
      if (a != null) a.dead = true;
    }
    state.nations.remove(nation.id);
    state.diplomacy.removeNation(nation.id);
    EventLog.log(state, "nation", nation.name + " has fallen");
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
        Settlement s = state.settlements.get(sid);
        if (s == null) continue;
        drawSettlementStreets(grid, s);
        if (sid == nation.capitalSettlementId) continue;
        drawRoad(grid, capital.x, capital.z, s.x, s.z);
      }
    }
    // only re-mesh chunks whose road status actually changed, not the
    // whole map, since this redraws from scratch every cycle
    for (int i = 0; i < grid.isRoad.length; i++) {
      if (grid.isRoad[i] != before[i]) grid.markDirtyIdx(i);
    }
  }

  /** A local street grid inside a single settlement - an 8-point star out
   * from the town center plus a ring road at half that reach, connecting
   * the center toward where its houses/farms actually cluster (see
   * EntityRenderer's house spiral) instead of leaving every city's
   * interior as untouched open ground with only the inter-city highway
   * passing nearby. */
  private static void drawSettlementStreets(WorldGrid grid, Settlement s) {
    int cx = s.x, cz = s.z;
    int reach = (int) Math.max(2, Math.min(9, s.radius * 0.55));
    for (int dir = 0; dir < 8; dir++) {
      double ang = dir * Math.PI / 4;
      int tx = cx + (int) Math.round(Math.cos(ang) * reach);
      int tz = cz + (int) Math.round(Math.sin(ang) * reach);
      drawRoad(grid, cx, cz, tx, tz);
    }
    int ringSteps = 16;
    int ringR = Math.max(1, reach / 2);
    int px = cx + ringR, pz = cz;
    for (int k = 1; k <= ringSteps; k++) {
      double ang = k * (2 * Math.PI / ringSteps);
      int nx = cx + (int) Math.round(Math.cos(ang) * ringR);
      int nz = cz + (int) Math.round(Math.sin(ang) * ringR);
      drawRoad(grid, px, pz, nx, nz);
      px = nx; pz = nz;
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
    // territory is now locked against peaceful takeover (see
    // Settlement.claimTerritory) - without this, a conquered city's own
    // surrounding land would stay painted the defeated nation's color
    // forever, since the new owner's normal claim pass now refuses to
    // touch cells held by another (still-living) nation
    Settlement.forceClaimTerritory(state, settlement);
    if (oldNation != null && oldNation.settlementIds.isEmpty()) {
      killNation(state, oldNation);
    }
  }
}
