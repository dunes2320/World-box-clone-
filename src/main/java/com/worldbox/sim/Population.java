package com.worldbox.sim;

import com.worldbox.config.Config;
import com.worldbox.world.WorldGrid;

import java.util.ArrayList;
import java.util.List;

/** Individual villagers. Food is handled abstractly at the settlement level
 * (territory + population -> auto food production/consumption), so a
 * human's only job is to gather wood/stone/iron and haul it home, or flee
 * danger. When drafted, a human is removed from the list and folded into an
 * Army's unit counts (see Military). */
public class Population {
  private static final double SPEED = 0.34;
  private static final int GATHER_TICKS = 5;

  public static Human createHuman(double x, double z, int nationId, int settlementId) {
    return new Human(x, z, nationId, settlementId);
  }

  private static boolean passable(WorldGrid grid, double x, double z) {
    int gx = (int) Math.floor(x), gz = (int) Math.floor(z);
    if (!grid.inBounds(gx, gz)) return false;
    return grid.terrain[grid.idx(gx, gz)] != Config.WATER;
  }

  private static void pickWanderTarget(WorldGrid grid, Human h) {
    for (int i = 0; i < 5; i++) {
      double nx = h.x + (Math.random() * 2 - 1) * 5;
      double nz = h.z + (Math.random() * 2 - 1) * 5;
      if (passable(grid, nx, nz)) { h.targetX = nx; h.targetZ = nz; return; }
    }
  }

  /** Only unclaimed (no-man's-land) cells or the searching nation's own
   * territory are eligible - a nation's population can't quietly poach
   * resources sitting inside another nation's borders. Getting at those
   * means declaring war and taking the territory, not simply walking in. */
  private static int[] findResourceCell(WorldGrid grid, double cx, double cz, byte resourceType, double radius, int nationId) {
    int best = -1, bestX = -1, bestY = -1;
    double bestD = Double.MAX_VALUE;
    int r = (int) Math.ceil(radius);
    int cxi = (int) Math.floor(cx), czi = (int) Math.floor(cz);
    for (int dy = -r; dy <= r; dy++) {
      for (int dx = -r; dx <= r; dx++) {
        int x = cxi + dx, y = czi + dy;
        if (!grid.inBounds(x, y)) continue;
        int i = grid.idx(x, y);
        if (grid.resource[i] != resourceType || grid.resourceAmount[i] <= 0) continue;
        int owner = grid.ownerNation[i];
        if (owner >= 0 && owner != nationId) continue;
        double d = (double) dx * dx + (double) dy * dy;
        if (d < bestD) { bestD = d; bestX = x; bestY = y; best = i; }
      }
    }
    if (best < 0) return null;
    return new int[]{bestX, bestY};
  }

  private static byte jobResource(String job) {
    switch (job) {
      case "wood": return Config.RES_FOREST;
      case "stone": return Config.RES_STONE;
      case "iron": return Config.RES_IRON;
      case "gold": return Config.RES_GOLD;
      default: return Config.RES_NONE;
    }
  }

  /** New/idle workers are offered the government gold mine first - a real
   * job with real pay, but not everyone's job. Only a limited number of
   * citizens can hold it at once, scaled to the nation's size, so a young
   * nation has a small mine and a large one can run a bigger one. Once
   * that's full (or there's no reachable gold), workers fall back to
   * whichever tracked resource the settlement needs most, as before. */
  private static void assignJob(GameState state, Human h) {
    Settlement settlement = state.settlements.get(h.settlementId);
    if (settlement == null) { h.job = null; return; }

    int nationPop = 0, goldMiners = 0;
    for (Human other : state.humans) {
      if (other.dead || other.nationId != h.nationId) continue;
      nationPop++;
      if ("gold".equals(other.job)) goldMiners++;
    }
    int goldCap = 1 + nationPop / 8;
    if (goldMiners < goldCap) {
      int[] goldCell = findResourceCell(state.grid, settlement.x, settlement.z, Config.RES_GOLD, 14, h.nationId);
      if (goldCell != null) {
        h.job = "gold";
        h.gatherX = goldCell[0];
        h.gatherY = goldCell[1];
        h.targetX = goldCell[0] + 0.5;
        h.targetZ = goldCell[1] + 0.5;
        h.state = "gather";
        return;
      }
    }

    String[] keys = {"wood", "stone", "iron"};
    // pick whichever tracked resource is scarcest relative to a healthy buffer
    String[] sorted = keys.clone();
    java.util.Arrays.sort(sorted, (a, b) -> Double.compare(settlement.stock.get(a), settlement.stock.get(b)));
    for (String k : sorted) {
      int[] cell = findResourceCell(state.grid, settlement.x, settlement.z, jobResource(k), 14, h.nationId);
      if (cell != null) {
        h.job = k;
        h.gatherX = cell[0];
        h.gatherY = cell[1];
        h.targetX = cell[0] + 0.5;
        h.targetZ = cell[1] + 0.5;
        h.state = "gather";
        return;
      }
    }
    h.job = null;
    h.state = "wander";
  }

  private static final double HOUSE_PRICE = 40;

  private static void applyLivingCost(GameState state, Human h) {
    h.wealth -= 0.05;
    resolveFinances(state, h);
    if (!h.hasHouse) maybeBuyHouse(state.nations.get(h.nationId), h);
  }

  /** Losing a house to repossession shouldn't be a life sentence - once a
   * defaulted citizen is debt-free again and has saved up a real cushion
   * on top of the price, they buy back in. Without this, every default
   * ever taken just accumulates forever and homelessness only ever
   * ratchets upward across a long game. */
  private static void maybeBuyHouse(Nation nation, Human h) {
    if (h.hasHouse || h.debt > 0 || h.wealth < HOUSE_PRICE * 1.5) return;
    h.wealth -= HOUSE_PRICE;
    h.hasHouse = true;
    if (nation != null) nation.bank.reserves += HOUSE_PRICE;
  }

  /** A citizen's wealth can't just sit arbitrarily negative forever - if
   * they dip below -5 with no existing debt, they take out a modest loan
   * to cover it, same as before. But if they're ALREADY in debt and still
   * can't make ends meet, that's a real default: the bank can't get its
   * money back from someone with nothing, so it repossesses their house
   * and sells it, recovering only about half the original loan's value -
   * the rest is a straight loss. Either way nobody's wealth just sits at
   * -400 forever with no consequence. */
  private static void resolveFinances(GameState state, Human h) {
    if (h.wealth >= -5) return;
    Nation nation = state.nations.get(h.nationId);
    if (h.debt <= 0) {
      // the loan is real borrowed money, not conjured out of nowhere - it
      // comes out of the nation's own bank, same pool business loans draw
      // from, so total money in the system stays accountable
      double loan = 20;
      h.debt += loan;
      h.wealth += loan;
      if (nation != null) {
        nation.bank.reserves = Math.max(0, nation.bank.reserves - loan);
        nation.bank.loans += loan;
      }
    } else {
      defaultOnLoan(nation, h);
    }
  }

  private static void defaultOnLoan(Nation nation, Human h) {
    if (nation != null) {
      nation.bank.loans = Math.max(0, nation.bank.loans - h.debt);
      if (h.hasHouse) {
        nation.bank.reserves += h.debt * 0.5;
        h.hasHouse = false;
      }
    }
    h.debt = 0;
    h.wealth = 0;
  }

  /** A jobless citizen still gets a modest government benefit - enough to
   * get by day to day, not enough to make unemployment comfortable versus
   * actually working. Funded straight out of the treasury like any other
   * government spending, so a nation with a lot of unemployment really
   * does feel it in its books. */
  private static void payUnemploymentBenefit(GameState state, Human h) {
    Nation nation = state.nations.get(h.nationId);
    if (nation == null) return;
    double benefit = 0.12;
    nation.treasury -= benefit;
    h.wealth += benefit;
  }

  // This is where currency actually enters the world: a hauled load is sold
  // at the going market price, and whoever employs that worker - a private
  // business if one exists for that resource, otherwise the public sector
  // (the nation treasury) - pays them a cut as a wage. Wages first clear any
  // outstanding home loan; see resolveFinances for what happens if their
  // savings go negative anyway.
  private static void payWage(GameState state, Settlement settlement, String resourceKey, double amount, Human h) {
    Nation nation = state.nations.get(settlement.nationId);
    double value = amount * state.market.prices.getOrDefault(resourceKey, 1.0);
    double wage = value * (nation != null ? nation.wagePolicy : 0.35);

    Business employer = null;
    for (Business b : state.businesses.values()) {
      if (b.settlementId == settlement.id && b.resourceKey.equals(resourceKey)) { employer = b; break; }
    }
    if (employer != null && employer.capital >= wage) {
      employer.capital -= wage;
    } else if (nation != null) {
      nation.treasury -= wage;
    }

    if (h.debt > 0) {
      double repay = Math.min(h.debt, wage);
      h.debt -= repay;
      wage -= repay;
      if (nation != null) nation.bank.loans = Math.max(0, nation.bank.loans - repay);
    }
    h.wealth += wage;
    resolveFinances(state, h);
  }

  private static double moveToward(WorldGrid grid, Human h, double speed) {
    double dx = h.targetX - h.x, dz = h.targetZ - h.z;
    double dist = Math.hypot(dx, dz);
    if (dist < 0.05) return dist;
    double step = Math.min(dist, speed);
    double nx = h.x + (dx / dist) * step;
    double nz = h.z + (dz / dist) * step;
    if (passable(grid, nx, nz)) { h.x = nx; h.z = nz; }
    else pickWanderTarget(grid, h);
    return dist;
  }

  private static boolean nearbyFire(WorldGrid grid, double x, double z) {
    int gx = (int) Math.floor(x), gz = (int) Math.floor(z);
    for (int dy = -1; dy <= 1; dy++) {
      for (int dx = -1; dx <= 1; dx++) {
        int nx = gx + dx, ny = gz + dy;
        if (grid.inBounds(nx, ny) && grid.burning[grid.idx(nx, ny)]) return true;
      }
    }
    return false;
  }

  private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
  private static int clampCoord(int v, int max) { return Math.max(0, Math.min(max - 1, v)); }

  // every villager is a mortal individual, not just a population counter:
  // h.age is in days (one tick = one day, see util.Calendar), so this is a
  // real human lifespan - once they cross 60 their death odds climb day by
  // day, clustering natural deaths mostly between 60 and 90 rather than an
  // exact cutoff - some live a bit longer, some a bit shorter
  private static final int OLD_AGE_START = 60 * com.worldbox.util.Calendar.DAYS_PER_YEAR;
  private static final double OLD_AGE_SPAN = 30 * com.worldbox.util.Calendar.DAYS_PER_YEAR;

  private static boolean diesOfOldAge(Human h) {
    if (h.age <= OLD_AGE_START) return false;
    double t = Math.min(1.0, (h.age - OLD_AGE_START) / OLD_AGE_SPAN);
    return Math.random() < t * t * 0.02;
  }

  // every person cycles through work, home, and a bit of leisure - "go to
  // work, go home, take a vacation" - staggered per-person (via h.id) so a
  // whole settlement doesn't clock in and out in lockstep, and weighted by
  // industriousness so lazier people work less. This is a behavioral
  // rhythm, not a literal calendar day (a real day is one tick, see
  // util.Calendar) - more like a working season within the year.
  private static final int ROUTINE_CYCLE = 300;

  private static void updateRoutine(GameState state, Human h) {
    double workFrac = 0.55 + h.personality.industriousness * 0.3; // 0.55..0.85
    double homeFrac = (1 - workFrac) * 0.6;
    double t = ((state.tick + h.id * 37) % ROUTINE_CYCLE) / (double) ROUTINE_CYCLE;
    if (t < workFrac) h.routine = "work";
    else if (t < workFrac + homeFrac) h.routine = "home";
    else h.routine = "leisure";
  }

  private static void pickLeisureTarget(WorldGrid grid, Human h) {
    for (int i = 0; i < 5; i++) {
      double nx = h.x + (Math.random() * 2 - 1) * 13;
      double nz = h.z + (Math.random() * 2 - 1) * 13;
      if (passable(grid, nx, nz)) { h.targetX = nx; h.targetZ = nz; return; }
    }
  }

  public static void update(GameState state) {
    WorldGrid grid = state.grid;
    List<Human> next = new ArrayList<>(state.humans.size());
    // founding a nation adds fresh settlers to state.humans, which we can't
    // do mid-iteration over that same list - so queue it and do it after.
    List<int[]> pendingFoundings = new ArrayList<>();
    for (Human h : state.humans) {
      if (h.dead) continue;
      h.prevX = h.x; h.prevZ = h.z;
      h.age++;

      if (h.nationId == Config.UNDEAD_NATION_ID) {
        updateZombie(state, h);
        next.add(h);
        continue;
      }

      if (diesOfOldAge(h)) continue;

      // no nation means no money and no debt - a wanderer has nothing to
      // spend and nothing to owe until they actually join or found one
      if (h.nationId >= 0) applyLivingCost(state, h);

      int ci = grid.idx(clampCoord((int) Math.floor(h.x), grid.cols), clampCoord((int) Math.floor(h.z), grid.rows));
      if (grid.burning[ci] && Math.random() < 0.35) continue; // burned to death

      if (grid.burning[ci] || nearbyFire(grid, h.x, h.z)) {
        h.state = "flee";
        h.fleeTimer = 25;
        double away = Math.atan2(h.z - grid.rows / 2.0, h.x - grid.cols / 2.0) + (Math.random() - 0.5);
        h.targetX = clamp(h.x + Math.cos(away) * 6, 0, grid.cols - 1);
        h.targetZ = clamp(h.z + Math.sin(away) * 6, 0, grid.rows - 1);
      }

      if (h.state.equals("flee")) {
        moveToward(grid, h, SPEED * 1.4);
        h.fleeTimer--;
        if (h.fleeTimer <= 0) h.state = "wander";
        next.add(h);
        continue;
      }

      if (h.nationId == -1) {
        updateWanderer(state, h, pendingFoundings);
        next.add(h);
        continue;
      }

      updateRoutine(state, h);

      if (h.state.equals("gather")) {
        double dist = moveToward(grid, h, SPEED);
        if (dist < 0.15) {
          h.gatherTimer++;
          if (h.gatherTimer >= GATHER_TICKS) {
            int gi = grid.idx(h.gatherX, h.gatherY);
            Config.ResourceInfo info = Config.RESOURCE_INFO.get(grid.resource[gi]);
            if (info != null && grid.resourceAmount[gi] > 0) {
              int amt = Math.min(info.yieldAmt, grid.resourceAmount[gi]);
              grid.resourceAmount[gi] -= amt;
              if (grid.resourceAmount[gi] <= 0 && !info.respawns) {
                grid.resource[gi] = Config.RES_NONE;
                grid.markDirtyIdx(gi);
              }
              h.carryingType = info.key;
              h.carryingAmount = amt;
              Settlement settlement = state.settlements.get(h.settlementId);
              if (settlement != null) { h.targetX = settlement.x + 0.5; h.targetZ = settlement.z + 0.5; }
              h.state = "haul";
            } else if (h.routine.equals("work")) {
              assignJob(state, h);
            } else {
              h.job = null;
              h.state = "wander";
            }
            h.gatherTimer = 0;
          }
        }
      } else if (h.state.equals("haul")) {
        Settlement settlement = state.settlements.get(h.settlementId);
        if (settlement == null) {
          h.state = "wander";
        } else {
          double dist = moveToward(grid, h, SPEED);
          if (dist < 0.2) {
            double cur = settlement.stock.getOrDefault(h.carryingType, 0.0);
            settlement.stock.put(h.carryingType, cur + h.carryingAmount);
            payWage(state, settlement, h.carryingType, h.carryingAmount, h);
            h.carryingType = null;
            if (h.routine.equals("work")) {
              assignJob(state, h);
            } else {
              h.job = null;
              h.state = "wander";
            }
          }
        }
      } else if (h.routine.equals("work")) {
        if (Math.random() < 0.02 || moveToward(grid, h, SPEED) < 0.1) pickWanderTarget(grid, h);
        if (Math.random() < 0.05) assignJob(state, h);
        if (h.job == null) payUnemploymentBenefit(state, h);
      } else if (h.routine.equals("home")) {
        Settlement home = state.settlements.get(h.settlementId);
        if (home != null) {
          double d = Math.hypot(h.x - (home.x + 0.5), h.z - (home.z + 0.5));
          if (d > 2.5 || Math.random() < 0.02) {
            double ang = Math.random() * Math.PI * 2;
            h.targetX = home.x + 0.5 + Math.cos(ang) * 1.5;
            h.targetZ = home.z + 0.5 + Math.sin(ang) * 1.5;
          }
        }
        moveToward(grid, h, SPEED * 0.7);
      } else { // leisure - off on a trip further from home
        if (Math.random() < 0.015 || moveToward(grid, h, SPEED * 0.85) < 0.1) pickLeisureTarget(grid, h);
      }

      next.add(h);
    }
    next.removeIf(h -> h.dead);
    state.humans = next;
    for (int[] spot : pendingFoundings) Nation.foundNewNation(state, spot[0], spot[1], null);
  }

  private static final double JOIN_RADIUS = 9;
  private static final int ISOLATION_THRESHOLD = 140;

  /** Nation-less humans (spawned as wanderers, or the sole survivors of a
   * fallen nation) either migrate to the nearest settlement they can find,
   * or - if truly isolated for long enough - strike out and found a new
   * nation themselves. This is how every nation in the game comes to
   * exist; nothing is pre-seeded. */
  private static void updateWanderer(GameState state, Human h, List<int[]> pendingFoundings) {
    WorldGrid grid = state.grid;
    Settlement nearest = null;
    double bestD = Double.MAX_VALUE;
    for (Settlement s : state.settlements.values()) {
      if (s.abandoned) continue;
      double d = Math.hypot(s.x - h.x, s.z - h.z);
      if (d < bestD) { bestD = d; nearest = s; }
    }

    if (nearest != null && bestD <= JOIN_RADIUS) {
      h.isolationTicks = 0;
      h.targetX = nearest.x + 0.5;
      h.targetZ = nearest.z + 0.5;
      double dist = moveToward(grid, h, SPEED);
      if (dist < 0.6) {
        h.nationId = nearest.nationId;
        h.settlementId = nearest.id;
        h.state = "wander";
      }
      return;
    }

    h.isolationTicks++;
    if (Math.random() < 0.02 || moveToward(grid, h, SPEED) < 0.1) pickWanderTarget(grid, h);

    boolean trulyIsolated = nearest == null || bestD > JOIN_RADIUS * 2.2;
    if (h.isolationTicks > ISOLATION_THRESHOLD && trulyIsolated && Math.random() < 0.03) {
      int gx = (int) Math.floor(h.x), gz = (int) Math.floor(h.z);
      boolean spotOk = grid.inBounds(gx, gz) && grid.isBuildable(grid.idx(gx, gz))
          && grid.slopeAt(gx, gz) < 1.4 && grid.settlementAt[grid.idx(gx, gz)] < 0;
      if (!spotOk) {
        com.worldbox.world.WorldGen.Spot spot = com.worldbox.world.WorldGen.findLandSpot(grid, h.x, h.z, 4, state.rng);
        if (spot == null) return;
        gx = spot.x; gz = spot.y;
      }
      h.dead = true; // folded into the new settlement's founding population
      pendingFoundings.add(new int[]{gx, gz});
    }
  }

  private static void updateZombie(GameState state, Human h) {
    WorldGrid grid = state.grid;
    Human target = null;
    double bestD = 64;
    for (Human other : state.humans) {
      if (other == h || other.dead || other.nationId == Config.UNDEAD_NATION_ID) continue;
      double d = (other.x - h.x) * (other.x - h.x) + (other.z - h.z) * (other.z - h.z);
      if (d < bestD) { bestD = d; target = other; }
    }
    if (target != null) {
      h.targetX = target.x; h.targetZ = target.z;
      double dist = moveToward(grid, h, SPEED * 0.8);
      if (dist < 0.5) {
        if (Math.random() < 0.5) {
          target.nationId = Config.UNDEAD_NATION_ID;
          target.settlementId = -1;
          target.job = null;
          target.carryingType = null;
          target.state = "wander";
        } else {
          target.dead = true;
        }
      }
    } else if (Math.random() < 0.03 || moveToward(grid, h, SPEED * 0.5) < 0.1) {
      pickWanderTarget(grid, h);
    }
  }
}
