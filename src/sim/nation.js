import { NATION_COLORS, ECONOMY } from "../config.js";
import { createSettlement, randomSettlementName } from "./settlement.js";
import { findLandSpot as findSpot } from "../world/worldgen.js";

let nextNationId = 1;
let colorCursor = 0;

const NAME_PREFIX = ["Val", "Kor", "Thal", "Bran", "Els", "Dun", "Mor", "Ash", "Vor", "Cal", "Ost", "Fen"];
const NAME_SUFFIX = ["ia", "mark", "land", "gard", "heim", "ova", "stan", "wen", "dor", "ath"];

export function randomNationName(rng) {
  return rng.pick(NAME_PREFIX) + rng.pick(NAME_SUFFIX);
}

export function createNation(state, capitalSettlement, name) {
  const id = nextNationId++;
  const colorIndex = colorCursor % NATION_COLORS.length;
  colorCursor++;
  const nation = {
    id,
    name: name || `Nation ${id}`,
    colorIndex,
    color: NATION_COLORS[colorIndex],
    capitalSettlementId: capitalSettlement.id,
    settlementIds: new Set([capitalSettlement.id]),
    treasury: 180,
    taxRate: ECONOMY.TAX_RATE_DEFAULT,
    armyIds: new Set(),
    founded: state.tick,
    alive: true,
  };
  state.nations.set(id, nation);
  capitalSettlement.nationId = id;
  for (const h of state.humans) {
    if (h.settlementId === capitalSettlement.id) h.nationId = id;
  }
  return nation;
}

export function foundNewNation(state, x, z, name) {
  const settlement = createSettlement(state, x, z, -1, undefined);
  const nation = createNation(state, settlement, name);
  return nation;
}

export function totalMilitaryPower(state, nationId) {
  let power = 0;
  for (const army of state.armies.values()) {
    if (army.nationId !== nationId) continue;
    power += army.strength;
  }
  return power;
}

function trySettlementUpkeepSpending(state, nation) {
  // military upkeep is deducted in military.js; here we just guard against
  // treasuries going pathologically negative by trimming the tax rate.
  if (nation.treasury < -50) nation.taxRate = Math.min(0.45, nation.taxRate + 0.01);
  else if (nation.treasury > 400) nation.taxRate = Math.max(0.1, nation.taxRate - 0.002);
}

function tryExpand(state, nation, rng) {
  if (nation.treasury < 220) return;
  if (nation.settlementIds.size >= 6) return;
  if (Math.random() > 0.02) return;
  const capital = state.settlements.get(nation.capitalSettlementId);
  if (!capital) return;
  const anchor = [...nation.settlementIds].map((id) => state.settlements.get(id)).filter(Boolean);
  const from = anchor[Math.floor(Math.random() * anchor.length)] || capital;
  const spot = findSpot(state.grid, from.x, from.z, 16, state.rng);
  if (!spot) return;
  // avoid founding right on top of somebody else's territory
  const i = state.grid.idx(spot.x, spot.y);
  if (state.grid.ownerNation[i] >= 0 && state.grid.ownerNation[i] !== nation.id) return;
  nation.treasury -= 200;
  const settlement = createSettlement(state, spot.x, spot.y, nation.id, randomSettlementName(state.rng));
  nation.settlementIds.add(settlement.id);
}

export function updateNations(state) {
  for (const nation of [...state.nations.values()]) {
    if (!nation.alive) continue;

    let treasuryGain = 0;
    for (const sid of nation.settlementIds) {
      const settlement = state.settlements.get(sid);
      if (!settlement) continue;
      for (const key of ["wood", "stone", "iron", "gold_ore"]) {
        const surplus = Math.max(0, settlement.stock[key] - ECONOMY.SETTLEMENT_BUFFER);
        if (surplus <= 0) continue;
        const taxed = surplus * nation.taxRate * 0.25;
        settlement.stock[key] -= taxed;
        treasuryGain += taxed * state.market.prices[key];
      }
    }
    nation.treasury += treasuryGain;
    trySettlementUpkeepSpending(state, nation);
    tryExpand(state, nation, state.rng);

    // dissolve nations with no settlements left
    if (nation.settlementIds.size === 0) {
      nation.alive = false;
      state.nations.delete(nation.id);
    }
  }
}

export function transferSettlement(state, settlement, newNationId) {
  const oldNation = state.nations.get(settlement.nationId);
  if (oldNation) oldNation.settlementIds.delete(settlement.id);
  settlement.nationId = newNationId;
  const newNation = state.nations.get(newNationId);
  if (newNation) newNation.settlementIds.add(settlement.id);
  for (const h of state.humans) {
    if (h.settlementId === settlement.id) h.nationId = newNationId;
  }
  if (oldNation && oldNation.settlementIds.size === 0) {
    oldNation.alive = false;
    state.nations.delete(oldNation.id);
  }
}
