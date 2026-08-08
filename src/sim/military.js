import { MILITARY, DIPLOMACY } from "../config.js";
import { transferSettlement } from "./nation.js";
import { onConquest } from "./diplomacy.js";

const UNIT_TYPES = MILITARY.UNIT_TYPES;
let nextArmyId = 1;

export function armyStrength(army) {
  let s = 0;
  for (const key in army.units) s += army.units[key] * UNIT_TYPES[key].power;
  return s;
}

export function armySpeed(army) {
  let total = 0, weight = 0;
  for (const key in army.units) {
    const n = army.units[key];
    total += UNIT_TYPES[key].speed * n;
    weight += n;
  }
  return weight > 0 ? total / weight : 0.1;
}

export function armyUnitCount(army) {
  let n = 0;
  for (const key in army.units) n += army.units[key];
  return n;
}

export function damageArmy(state, army, dmg) { applyDamage(state, army, dmg); }

function applyDamage(state, army, dmg) {
  const total = armyStrength(army);
  if (total <= 0 || dmg <= 0) return;
  const ratio = Math.min(1, dmg / total);
  for (const key in army.units) {
    army.units[key] = Math.max(0, Math.floor(army.units[key] * (1 - ratio)));
  }
  army.strength = armyStrength(army);
  if (armyUnitCount(army) <= 0) killArmy(state, army);
}

function killArmy(state, army) {
  army.dead = true;
  const nation = state.nations.get(army.nationId);
  if (nation) nation.armyIds.delete(army.id);
}

// Pulls villagers out of a settlement's workforce and turns them into a unit
// batch, funded by the settlement's raw stock + the nation's gold treasury.
export function raiseArmy(state, settlementId, unitType, atSettlement = true) {
  const settlement = state.settlements.get(settlementId);
  if (!settlement) return { ok: false, reason: "no settlement" };
  const nation = state.nations.get(settlement.nationId);
  if (!nation) return { ok: false, reason: "no nation" };
  const spec = UNIT_TYPES[unitType];
  if (!spec) return { ok: false, reason: "bad unit" };

  const civilians = state.humans.filter((h) => h.settlementId === settlementId);
  const count = Math.min(MILITARY.RAISE_BATCH, civilians.length - 3); // keep a few workers behind
  if (count <= 0) return { ok: false, reason: "not enough population" };

  const goldCost = (spec.cost.gold || 0) * count;
  if (nation.treasury < goldCost) return { ok: false, reason: "not enough gold" };
  for (const key in spec.cost) {
    if (key === "gold") continue;
    const need = spec.cost[key] * count;
    if ((settlement.stock[key] || 0) < need) return { ok: false, reason: `not enough ${key}` };
  }

  nation.treasury -= goldCost;
  for (const key in spec.cost) {
    if (key === "gold") continue;
    settlement.stock[key] -= spec.cost[key] * count;
  }

  for (let i = 0; i < count; i++) {
    const idx = state.humans.indexOf(civilians[i]);
    if (idx >= 0) state.humans.splice(idx, 1);
  }

  let army = [...state.armies.values()].find(
    (a) => a.nationId === settlement.nationId && a.homeSettlementId === settlementId && !a.targetSettlementId && !a.targetArmyId
  );
  if (!army) {
    army = {
      id: nextArmyId++,
      nationId: settlement.nationId,
      homeSettlementId: settlementId,
      x: settlement.x + 0.5, z: settlement.z + 0.5,
      prevX: settlement.x + 0.5, prevZ: settlement.z + 0.5,
      targetX: settlement.x + 0.5, targetZ: settlement.z + 0.5,
      units: { militia: 0, swordsman: 0, archer: 0, knight: 0 },
      targetSettlementId: null,
      targetArmyId: null,
      state: "idle",
    };
    state.armies.set(army.id, army);
    nation.armyIds.add(army.id);
  }
  army.units[unitType] += count;
  army.strength = armyStrength(army);
  return { ok: true, count };
}

function nearestEnemySettlement(state, nation) {
  let best = null, bestD = Infinity;
  for (const s of state.settlements.values()) {
    if (s.nationId === nation.id) continue;
    const rel = state.diplomacy.getStatus(nation.id, s.nationId);
    if (rel !== DIPLOMACY.STATUS.WAR) continue;
    for (const sid of nation.settlementIds) {
      const home = state.settlements.get(sid);
      if (!home) continue;
      const d = Math.hypot(s.x - home.x, s.z - home.z);
      if (d < bestD) { bestD = d; best = s; }
    }
  }
  return best;
}

function moveArmyToward(army, tx, tz, speed) {
  const dx = tx - army.x, dz = tz - army.z;
  const dist = Math.hypot(dx, dz);
  if (dist < 0.05) return 0;
  const step = Math.min(dist, speed);
  army.x += (dx / dist) * step;
  army.z += (dz / dist) * step;
  return dist;
}

const ENGAGE_RANGE = 1.7;

export function updateMilitary(state) {
  // upkeep + AI orders
  for (const nation of state.nations.values()) {
    let upkeep = 0;
    for (const aid of nation.armyIds) {
      const a = state.armies.get(aid);
      if (a) for (const key in a.units) upkeep += a.units[key] * UNIT_TYPES[key].upkeep;
    }
    nation.treasury -= upkeep;
    if (nation.treasury < -80) {
      // can't pay the troops: desertion
      for (const aid of nation.armyIds) {
        const a = state.armies.get(aid);
        if (a && Math.random() < 0.15) applyDamage(state, a, armyStrength(a) * 0.2);
      }
      nation.treasury = -80;
    }

    if ((state.tick + nation.id) % 12 === 0) {
      for (const aid of [...nation.armyIds]) {
        const army = state.armies.get(aid);
        if (!army || army.dead) continue;
        if (army.targetSettlementId || army.targetArmyId) continue;
        const strength = armyStrength(army);
        if (strength < 4) continue;
        const target = nearestEnemySettlement(state, nation);
        if (target) { army.targetSettlementId = target.id; army.state = "marching"; }
      }

      // opportunistic auto-raise: keep a modest standing force if funds allow
      if (nation.treasury > 260 && Math.random() < 0.35) {
        const sid = [...nation.settlementIds][Math.floor(Math.random() * nation.settlementIds.size)];
        if (sid !== undefined) {
          const cheapest = nation.treasury > 600 ? "knight" : nation.treasury > 350 ? "swordsman" : "militia";
          raiseArmy(state, sid, cheapest);
        }
      }
    }
  }

  // movement
  for (const army of state.armies.values()) {
    if (army.dead) continue;
    army.prevX = army.x; army.prevZ = army.z;
    army.strength = armyStrength(army);
    let tx = army.targetX, tz = army.targetZ;
    if (army.targetSettlementId) {
      const s = state.settlements.get(army.targetSettlementId);
      if (!s) { army.targetSettlementId = null; }
      else { tx = s.x + 0.5; tz = s.z + 0.5; }
    }
    army.targetX = tx; army.targetZ = tz;
    moveArmyToward(army, tx, tz, armySpeed(army));
  }

  resolveSieges(state);
  resolveFieldBattles(state);

  for (const [id, army] of [...state.armies.entries()]) {
    if (army.dead) state.armies.delete(id);
  }
}

function resolveSieges(state) {
  for (const settlement of state.settlements.values()) {
    settlement.siegeProgress = settlement.siegeProgress || 0;
    const attackers = [];
    for (const army of state.armies.values()) {
      if (army.dead || army.nationId === settlement.nationId) continue;
      if (state.diplomacy.getStatus(army.nationId, settlement.nationId) !== DIPLOMACY.STATUS.WAR) continue;
      const d = Math.hypot(army.x - (settlement.x + 0.5), army.z - (settlement.z + 0.5));
      if (d <= ENGAGE_RANGE) attackers.push(army);
    }
    if (attackers.length === 0) {
      settlement.siegeProgress = Math.max(0, settlement.siegeProgress - 0.5);
      continue;
    }
    const defense = settlement.populationCount * 0.4 + 4;
    const attackTotal = attackers.reduce((sum, a) => sum + armyStrength(a), 0);

    if (attackTotal > defense) {
      settlement.siegeProgress += (attackTotal - defense) * 0.05;
      for (const a of attackers) applyDamage(state, a, defense * 0.09 * (armyStrength(a) / attackTotal));
    } else {
      settlement.siegeProgress = Math.max(0, settlement.siegeProgress - 1);
      for (const a of attackers) applyDamage(state, a, defense * 0.1 / attackers.length);
    }

    if (settlement.siegeProgress >= 24) {
      let winner = attackers[0];
      for (const a of attackers) if (armyStrength(a) > armyStrength(winner)) winner = a;
      const oldNationId = settlement.nationId;
      transferSettlement(state, settlement, winner.nationId);
      settlement.siegeProgress = 0;
      winner.targetSettlementId = null;
      onConquest(state, winner.nationId, oldNationId);
    }
  }
}

function resolveFieldBattles(state) {
  const armies = [...state.armies.values()].filter((a) => !a.dead);
  for (let i = 0; i < armies.length; i++) {
    const a = armies[i];
    if (a.dead) continue;
    for (let j = i + 1; j < armies.length; j++) {
      const b = armies[j];
      if (b.dead || a.nationId === b.nationId) continue;
      if (state.diplomacy.getStatus(a.nationId, b.nationId) !== DIPLOMACY.STATUS.WAR) continue;
      const d = Math.hypot(a.x - b.x, a.z - b.z);
      if (d > ENGAGE_RANGE) continue;
      const sa = armyStrength(a), sb = armyStrength(b);
      applyDamage(state, a, sb * 0.16 * (0.75 + Math.random() * 0.5));
      applyDamage(state, b, sa * 0.16 * (0.75 + Math.random() * 0.5));
    }
  }
}
