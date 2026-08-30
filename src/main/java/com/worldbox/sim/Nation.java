package com.worldbox.sim;

import com.worldbox.config.Config;
import com.worldbox.util.Rng;
import com.worldbox.world.WorldGen;
import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Nation implements java.io.Serializable {
  private static int nextId = 1;
  private static int colorCursor = 0;
  /** Share of ordinary domestic taxation kept as a physical goods reserve
   * (Nation.stockpile) instead of being sold for cash on the spot - see
   * Nation.update's tax loop. */
  private static final double IN_KIND_TAX_SHARE = 0.35;

  /** Total nations ever founded this game, alive or extinct - lets debug
   * tooling report an extinction rate (founded vs. currently alive)
   * instead of only ever seeing whoever's still standing. */
  public static int totalFounded() { return nextId - 1; }

  /** Loading a save must never let a freshly founded nation reuse an id
   * already present in the loaded data, or hand out a color already worn
   * by a loaded nation for a lap of the palette - bump both counters past
   * whatever the save actually contained. */
  public static void restoreNextId(int maxSeenId) { if (maxSeenId >= nextId) nextId = maxSeenId + 1; }
  public static void restoreColorCursor(int atLeast) { if (atLeast > colorCursor) colorCursor = atLeast; }

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
  /** The territory border's own color - a complementary hue rotated off
   * the interior's (see pickBorderColor), not just a brighter version of
   * the same color, so a nation's outline reads as a distinct accent
   * rather than an intensified fill. Guaranteed distinct from every
   * other currently-alive nation's (color, borderColor) pair - a shared
   * exact combo would make two unrelated nations' territories genuinely
   * indistinguishable from each other. */
  public final int borderColor;
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
  /** last ~120 samples of this nation's overall stability (0-100, see
   * Government.updateStability) - the single "how healthy is this
   * country right now" line, folding in treasury, war, unemployment and
   * leadership all at once instead of reading five separate numbers. */
  public final java.util.ArrayDeque<Double> stabilityHistory = new java.util.ArrayDeque<>();
  /** last ~120 samples of this nation's total military power (see
   * Nation.totalMilitaryPower) - the "Military" graph line. */
  public final java.util.ArrayDeque<Double> militaryHistory = new java.util.ArrayDeque<>();
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

  /** A real government-held goods reserve, distinct from any one
   * settlement's own stock or a business's own capital - filled by taking
   * a slice of ordinary taxation in-kind (see Economy.collectTax) instead
   * of always immediately monetizing it, and drawn down by cross-border
   * export trade (see Economy.updateForeignTrade). What "the government
   * keeps a stockpile" actually means in practice: a visible, spendable
   * reserve of real goods the state can draw on, not just a treasury
   * number. */
  public final Map<String, Double> stockpile = new java.util.HashMap<>();
  /** Government levy on this nation's own exporters, taken out of a sale's
   * proceeds before the business ever sees the money - real income for
   * the state, same idea as taxRate but specific to cross-border trade. */
  public double exportTaxRate = 0.08;
  /** Government levy this nation charges on top of the price when one of
   * its own businesses imports (draws down) a foreign stockpile - the
   * buyer-side counterpart to exportTaxRate. */
  public double importTaxRate = 0.08;
  /** A slow-moving index of how expensive land/property is here - rises
   * with a strong currency and a prosperous treasury, falls with a weak
   * one (see Economy.updateLandValue). 1.0 = the same as it was at
   * founding. Scales real costs: a house, or founding a new business. */
  public double landValueIndex = 1.0;
  /** Revenue earned this sampling window, broken down by which sector of
   * the economy actually earned it (see Config.SECTORS / Business.sector)
   * - rolled into sectorHistory and reset every time Government.update
   * takes its monthly sample, same cadence/pattern as gdpAccum. */
  public final Map<String, Double> sectorRevenue = new java.util.HashMap<>();
  /** last ~120 monthly samples of sectorRevenue, per sector - the series
   * the HUD's per-nation "Sectors" graph tab plots. */
  public final Map<String, java.util.ArrayDeque<Double>> sectorHistory = new java.util.LinkedHashMap<>();
  /** the person actually running the country - their personality is a
   * real input into how the nation behaves, not flavor text. */
  public Leader leader;
  /** "volunteer" | "conscription" - how this nation fills its army from
   * its own citizens (see Military.raiseArmy). Leans out of government
   * type (autocracy/monarchy toward conscription, democracy/oligarchy
   * toward volunteers) with some randomness, set once at founding, same
   * as any other real policy stance rather than a hidden implementation
   * detail. */
  public String recruitmentPolicy;

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

  private Nation(int founded, String name, Rng rng, java.util.Collection<Nation> livingNations) {
    this.id = nextId++;
    this.colorIndex = colorCursor++ % Config.NATION_COLORS.length;
    this.color = Config.NATION_COLORS[this.colorIndex];
    this.borderColor = pickBorderColor(this.color, livingNations);
    this.name = name != null ? name : randomNationName(rng != null ? rng : new Rng((long) (Math.random() * Long.MAX_VALUE)));
    this.founded = founded;
    this.government = Government.random();
    this.currencyName = this.name + " " + CURRENCY_SUFFIX[(int) (Math.random() * CURRENCY_SUFFIX.length)];
    this.leader = new Leader(this.government);
    boolean conscriptionLeaning = Government.AUTOCRACY.equals(this.government) || Government.MONARCHY.equals(this.government);
    double conscriptionChance = conscriptionLeaning ? 0.7 : 0.25;
    this.recruitmentPolicy = Math.random() < conscriptionChance ? "conscription" : "volunteer";
    for (String sector : Config.SECTORS) {
      sectorRevenue.put(sector, 0.0);
      sectorHistory.put(sector, new java.util.ArrayDeque<>());
    }
    for (String key : GlobalMarket.keys()) stockpile.put(key, 0.0);
  }

  /** A complementary hue (opposite side of the color wheel from the
   * interior) rather than just a brighter tint of the same one, tried at
   * increasing offsets from that starting point until the resulting
   * (color, borderColor) pair doesn't match any other currently-living
   * nation's - so two nations never read as visually identical borders,
   * even if they happen to share the same interior (the palette is
   * finite and does eventually repeat). */
  private static int pickBorderColor(int interior, java.util.Collection<Nation> livingNations) {
    float[] hsb = java.awt.Color.RGBtoHSB((interior >> 16) & 0xFF, (interior >> 8) & 0xFF, interior & 0xFF, null);
    for (int attempt = 0; attempt < 40; attempt++) {
      float hue = hsb[0] + 0.5f + attempt * 0.083f; // 0.5 = complementary; each retry nudges further around the wheel
      hue -= (float) Math.floor(hue);
      int candidate = java.awt.Color.HSBtoRGB(hue, Math.max(0.6f, hsb[1]), Math.min(1f, hsb[2] * 1.1f + 0.15f)) & 0xFFFFFF;
      boolean collides = false;
      if (livingNations != null) {
        for (Nation other : livingNations) {
          if (other.alive && other.color == interior && other.borderColor == candidate) { collides = true; break; }
        }
      }
      if (!collides) return candidate;
    }
    // practically unreachable (40 distinct hue offsets against a handful
    // of concurrently-alive nations), but fall back to the plain
    // complementary rather than leave borderColor uninitialized
    float hue = hsb[0] + 0.5f;
    hue -= (float) Math.floor(hue);
    return java.awt.Color.HSBtoRGB(hue, Math.max(0.6f, hsb[1]), Math.min(1f, hsb[2] * 1.1f + 0.15f)) & 0xFFFFFF;
  }

  public static Nation create(GameState state, Settlement capitalSettlement, String name) {
    Nation nation = new Nation(state.tick, name, state.rng, state.nations.values());
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
    if (!Settlement.spotClearOfRivals(state, spot.x, spot.y, nation.id)) return;
    if (Settlement.tooCloseToAnySettlement(state, spot.x, spot.y)) return;
    nation.treasury -= 200;
    Settlement settlement = Settlement.create(state, spot.x, spot.y, nation.id, Settlement.randomSettlementName(state.rng));
    nation.settlementIds.add(settlement.id);
  }

  public static void update(GameState state) {
    if (state.tick % 30 == 0) updateRoads(state);
    if (state.tick % 5 == 0) advanceRoadConstruction(state);
    if (state.tick % 150 == 0) { fillTerritoryGaps(state); smoothBorders(state); }

    for (Nation nation : new ArrayList<>(state.nations.values())) {
      if (!nation.alive) continue;

      double treasuryGain = 0;
      for (int sid : nation.settlementIds) {
        Settlement settlement = state.settlements.get(sid);
        if (settlement == null) continue;
        for (String key : new String[]{"wood", "stone", "iron", "gold_ore", "tools", "luxury"}) {
          double surplus = Math.max(0, settlement.stock.getOrDefault(key, 0.0) - Config.SETTLEMENT_BUFFER);
          if (surplus <= 0) continue;
          double taxed = surplus * nation.taxRate * 0.25;
          settlement.stock.merge(key, -taxed, Double::sum);
          // a real government keeps a stockpile, not just a treasury
          // number - a slice of what it taxes stays as physical goods
          // (see Nation.stockpile) instead of always being immediately
          // monetized, and that reserve is what actually funds this
          // nation's own exports (see Economy.updateForeignTrade)
          double inKind = taxed * IN_KIND_TAX_SHARE;
          nation.stockpile.merge(key, inKind, Double::sum);
          treasuryGain += (taxed - inKind) * Economy.nationPrice(state, nation, key);
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

  /** A once-in-a-while cleanup pass that mops up "salt and pepper"
   * ownership noise where two rival settlements' claim wobbles interleave
   * near a shared frontier (see Settlement.borderNoise) - a cell
   * surrounded by a majority of one neighbor owner flips to match it.
   * Two rounds back to back (each against its own fresh snapshot, so
   * flips within a round never cascade into each other) rather than one -
   * a single pass can leave a cell that only became a minority after ITS
   * neighbors flipped still standing out on its own; running it twice
   * catches that without needing to wait for the next 150-tick cycle.
   * Still deliberately narrow (needs 5 of the 8 surrounding land cells to
   * agree) so it only ever mops up actual speckling, never eats into a
   * real, contiguous chunk of someone's territory. */
  private static void smoothBorders(GameState state) {
    smoothBordersPass(state);
    smoothBordersPass(state);
  }

  private static void smoothBordersPass(GameState state) {
    WorldGrid grid = state.grid;
    int[] before = grid.ownerNation.clone();
    byte[] terrain = grid.terrain;
    int[] seenOwner = new int[8];
    int[] seenCount = new int[8];
    for (int y = 0; y < grid.rows; y++) {
      for (int x = 0; x < grid.cols; x++) {
        int i = grid.idx(x, y);
        if (terrain[i] == Config.WATER) continue;
        int owner = before[i];
        int distinct = 0, total = 0;
        for (int dy = -1; dy <= 1; dy++) {
          for (int dx = -1; dx <= 1; dx++) {
            if (dx == 0 && dy == 0) continue;
            int nx = x + dx, ny = y + dy;
            if (!grid.inBounds(nx, ny)) continue;
            int ni = grid.idx(nx, ny);
            if (terrain[ni] == Config.WATER) continue;
            int no = before[ni];
            total++;
            int slot = -1;
            for (int k = 0; k < distinct; k++) if (seenOwner[k] == no) { slot = k; break; }
            if (slot < 0) { slot = distinct++; seenOwner[slot] = no; seenCount[slot] = 0; }
            seenCount[slot]++;
          }
        }
        if (total < 6) continue; // too close to the map edge to judge a real majority
        // a cell already held by a currently-living nation is off limits
        // here no matter how it votes - this pass is gap-filling/noise
        // cleanup, not a backdoor way for territory to change hands
        // between two living nations without a war. Only an unclaimed
        // cell, or one still painted in a nation that's since fallen, is
        // ever up for grabs.
        if (owner >= 0 && state.nations.containsKey(owner)) continue;
        int bestOwner = owner, bestCount = -1;
        for (int k = 0; k < distinct; k++) {
          if (seenCount[k] > bestCount) { bestCount = seenCount[k]; bestOwner = seenOwner[k]; }
        }
        if (bestOwner != owner && bestCount >= 5) {
          grid.ownerNation[i] = bestOwner;
          grid.claimStrength[i] = bestOwner >= 0 ? 1f : 0f;
          grid.markDirtyIdx(i);
        }
      }
    }
  }

  public static void killNation(GameState state, Nation nation) {
    nation.alive = false;
    for (int aid : new ArrayList<>(nation.armyIds)) {
      Army a = state.armies.get(aid);
      if (a != null) Military.killArmy(state, a);
    }
    state.nations.remove(nation.id);
    state.diplomacy.removeNation(nation.id);
    EventLog.log(state, "nation", nation.name + " has fallen");
  }

  /** Recomputes the TARGET road network from scratch each cycle - a
   * straight line from each settlement to its nation's capital, skipping
   * water - gives every nation visible, purposeful infrastructure
   * connecting it together instead of isolated dots. This only plans
   * which cells SHOULD eventually be a road; it doesn't build any of
   * them itself (that would make the whole network pop in instantly the
   * moment it's planned) - see advanceRoadConstruction for the part that
   * actually lays them down over time. */
  private static void updateRoads(GameState state) {
    WorldGrid grid = state.grid;
    java.util.Arrays.fill(grid.roadPlanned, false);
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
  }

  private static final float ROAD_BUILD_RATE = 1f / 12f;

  /** Builders actually lay a real gravel/stone path down, one cell's
   * worth of progress at a time, instead of the target network from
   * updateRoads just appearing - a road visibly grows out from the
   * settlement over real time. Once a cell's progress reaches 1 it
   * becomes a genuine PATH surface block (see VoxelWorld.PATH), not just
   * a color tint, the same "humans are actually building this" idea as
   * a house's own staged construction. Cells that fall out of the
   * planned network (a nation lost the settlement it led to, say) simply
   * stop advancing rather than un-building - roads essentially never
   * need to disappear in practice, and tearing one up for that rare case
   * isn't worth the extra bookkeeping. */
  private static void advanceRoadConstruction(GameState state) {
    WorldGrid grid = state.grid;
    for (int i = 0; i < grid.roadPlanned.length; i++) {
      if (!grid.roadPlanned[i] || grid.isRoad[i]) continue;
      grid.roadProgress[i] += ROAD_BUILD_RATE;
      if (grid.roadProgress[i] < 1f) continue;
      grid.isRoad[i] = true;
      int x = i % grid.cols, z = i / grid.cols;
      state.voxels.paintColumnSurface(x, z, com.worldbox.world.VoxelWorld.PATH);
      state.voxels.resyncHeight(grid, x, z);
      grid.markDirtyIdx(i);
    }
  }

  /** A local street grid inside a single settlement - a short spur run out
   * to every one of its actual houses (Settlement.housePosition - the
   * exact same spiral EntityRenderer places them at, so the streets
   * genuinely reach the buildings instead of a decorative ring that
   * doesn't lead anywhere) plus one out to the edge of its farmland, on
   * top of the 8-point star giving the settlement's interior some general
   * road coverage beyond just the inter-city highway passing nearby. */
  private static void drawSettlementStreets(WorldGrid grid, Settlement s) {
    int cx = s.x, cz = s.z;
    int reach = (int) Math.max(2, Math.min(9, s.radius * 0.55));
    for (int dir = 0; dir < 8; dir++) {
      double ang = dir * Math.PI / 4;
      int tx = cx + (int) Math.round(Math.cos(ang) * reach);
      int tz = cz + (int) Math.round(Math.sin(ang) * reach);
      drawRoad(grid, cx, cz, tx, tz);
    }
    int houseCount = Settlement.estimatedHouseCount(s);
    for (int i = 0; i < houseCount; i++) {
      double[] spot = Settlement.housePosition(s, i);
      drawRoad(grid, cx, cz, (int) Math.round(spot[0]), (int) Math.round(spot[1]));
    }
    // a spur out to the edge of the tilled farm ring (see
    // Settlement.countFarmCells' plotRadius) so fields aren't left
    // completely unconnected from the settlement's own street grid
    double plotRadius = Math.min(4.5, 2.2 + Math.sqrt(s.populationCount) * 0.25);
    int fx = cx + (int) Math.round(plotRadius);
    drawRoad(grid, cx, cz, fx, cz);
  }

  /** A deterministic perpendicular bend offset for one road endpoint pair -
   * the same "hash off fixed inputs" trick Settlement.borderNoise already
   * uses for organic-looking territory borders, so the same world always
   * grows the exact same road shape. */
  private static double roadBend(int x0, int z0, int x1, int z1) {
    int h = x0 * 374761393 + z0 * 668265263 + x1 * 2147483647 + z1 * 1274126177;
    h = (h ^ (h >>> 13)) * 668265263;
    h = h ^ (h >>> 16);
    return (h & 0xFFFF) / 65535.0 - 0.5;
  }

  /** A single perfectly straight Bresenham line reads as a rigid staircase
   * on anything but a pure 45-degree/axis-aligned run (Bresenham steps
   * whole cells at a time, which shows as a jagged zigzag on an in-between
   * angle) - real roads curve. Recursively bends a long road through a
   * deterministically offset midpoint (see roadBend) into up to 4 shorter,
   * gently angled segments instead of one long rigid diagonal; a short
   * spur (already close to its target, or a deep recursion leaf) stays a
   * plain straight segment, since there's no room for a curve to read on
   * a run that short anyway. */
  private static void drawRoad(WorldGrid grid, int x0, int z0, int x1, int z1) {
    drawRoad(grid, x0, z0, x1, z1, 2);
  }

  private static void drawRoad(WorldGrid grid, int x0, int z0, int x1, int z1, int bendsLeft) {
    double dist = Math.hypot(x1 - x0, z1 - z0);
    if (bendsLeft > 0 && dist > 7) {
      double mx = (x0 + x1) / 2.0, mz = (z0 + z1) / 2.0;
      double px = -(z1 - z0) / dist, pz = (x1 - x0) / dist; // unit perpendicular
      double bend = roadBend(x0, z0, x1, z1) * Math.min(dist * 0.3, 5.0);
      int bx = (int) Math.round(mx + px * bend), bz = (int) Math.round(mz + pz * bend);
      drawRoad(grid, x0, z0, bx, bz, bendsLeft - 1);
      drawRoad(grid, bx, bz, x1, z1, bendsLeft - 1);
      return;
    }
    drawRoadSegment(grid, x0, z0, x1, z1);
  }

  private static void drawRoadSegment(WorldGrid grid, int x0, int z0, int x1, int z1) {
    int dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
    int sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
    // widened to 2 cells (the dominant axis of travel gets a neighbor
    // marked alongside it) - a single-cell-wide line makes Bresenham's
    // stairstep turns read as sharp jagged pixel steps; a real 2-wide
    // swath reads as a laid path even where the line has to step
    boolean wideOnZ = dx >= dz;
    int err = dx - dz;
    int x = x0, z = z0;
    while (true) {
      markRoadCell(grid, x, z);
      markRoadCell(grid, wideOnZ ? x : x + 1, wideOnZ ? z + 1 : z);
      if (x == x1 && z == z1) break;
      int e2 = 2 * err;
      if (e2 > -dz) { err -= dz; x += sx; }
      if (e2 < dx) { err += dx; z += sz; }
    }
  }

  private static void markRoadCell(WorldGrid grid, int x, int z) {
    if (!grid.inBounds(x, z)) return;
    int i = grid.idx(x, z);
    if (grid.terrain[i] != Config.WATER) grid.roadPlanned[i] = true;
  }

  public static void transferSettlement(GameState state, Settlement settlement, int newNationId) {
    Nation oldNation = state.nations.get(settlement.nationId);
    int oldNationId = settlement.nationId;
    if (oldNation != null) oldNation.settlementIds.remove(settlement.id);
    settlement.nationId = newNationId;
    Nation newNation = state.nations.get(newNationId);
    if (newNation != null) newNation.settlementIds.add(settlement.id);

    // a captured city's people aren't just relabeled en masse - each one
    // makes their own call: stay under the new flag (and actually convert,
    // taking up the new nation's citizenship) or stay loyal to the nation
    // they grew up in and leave for one of its other cities. A wiser,
    // more settled person is likelier to stay put and adapt; a more
    // ambitious one is likelier to walk rather than submit. Someone whose
    // old nation has nowhere else left becomes a homeless wanderer
    // instead - a real refugee, with the same chance any isolated
    // wanderer has of eventually founding a new nation of their own (see
    // Population.updateWanderer).
    List<Settlement> oldNationRemainingSettlements = new ArrayList<>();
    if (oldNation != null) {
      for (int sid : oldNation.settlementIds) {
        Settlement s = state.settlements.get(sid);
        if (s != null) oldNationRemainingSettlements.add(s);
      }
    }
    int stayed = 0, left = 0, refugees = 0;
    for (Human h : state.humans) {
      if (h.settlementId != settlement.id || h.nationId != oldNationId) continue;
      double stayChance = 0.45 + h.personality.wisdom * 0.25 - h.personality.ambition * 0.2;
      if (Math.random() < stayChance) {
        h.nationId = newNationId;
        stayed++;
      } else if (!oldNationRemainingSettlements.isEmpty()) {
        Settlement dest = oldNationRemainingSettlements.get((int) (Math.random() * oldNationRemainingSettlements.size()));
        h.settlementId = dest.id;
        h.x = dest.x + 0.5; h.z = dest.z + 0.5;
        h.prevX = h.x; h.prevZ = h.z;
        h.hasHouse = false; // their old house was back in the city they just left
        left++;
      } else {
        h.nationId = -1;
        h.settlementId = -1;
        h.hasHouse = false;
        refugees++;
      }
    }

    // territory is now locked against peaceful takeover (see
    // Settlement.claimTerritory) - without this, a conquered city's own
    // surrounding land would stay painted the defeated nation's color
    // forever, since the new owner's normal claim pass now refuses to
    // touch cells held by another (still-living) nation
    Settlement.forceClaimTerritory(state, settlement);
    if (left > 0 || refugees > 0) {
      EventLog.log(state, "war", (stayed) + " of " + settlement.name + "'s people stayed and swore allegiance to "
          + (newNation != null ? newNation.name : "their new rulers") + "; " + (left + refugees)
          + " fled rather than submit" + (refugees > 0 ? " (" + refugees + " with nowhere left to go)" : ""));
    }
    if (oldNation != null && oldNation.settlementIds.isEmpty()) {
      killNation(state, oldNation);
    }
  }
}
