import { TERRAIN, SIM, ECONOMY } from "../config.js";
import { createHuman } from "./population.js";

let nextSettlementId = 1;
const NAME_A = ["Oak", "Stone", "River", "North", "South", "Iron", "Gold", "Sun", "Wind", "Hill", "White", "Black", "Green", "Silver", "Amber"];
const NAME_B = ["ford", "haven", "burg", "shire", "port", "hold", "watch", "mere", "vale", "reach", "crest", "fell"];

export function randomSettlementName(rng) {
  const a = rng.pick(NAME_A);
  const b = rng.pick(NAME_B);
  return `${a}${b}`;
}

export function createSettlement(state, x, z, nationId, name) {
  const id = nextSettlementId++;
  const settlement = {
    id,
    nationId,
    x, z,
    name: name || `Settlement ${id}`,
    stock: { food: 60, wood: 30, stone: 10, iron: 0, gold_ore: 0 },
    populationCount: 0,
    farmCells: 6,
    radius: 4,
    growthAccum: 0,
    starveTicks: 0,
    founded: state.tick,
  };
  state.settlements.set(id, settlement);
  state.grid.settlementAt[state.grid.idx(x, z)] = id;

  const startPop = 5;
  for (let i = 0; i < startPop; i++) {
    if (state.humans.length >= SIM.MAX_HUMANS) break;
    const ang = (i / startPop) * Math.PI * 2;
    const hx = x + 0.5 + Math.cos(ang) * 1.5;
    const hz = z + 0.5 + Math.sin(ang) * 1.5;
    state.humans.push(createHuman(hx, hz, nationId, id));
  }

  claimTerritory(state, settlement);
  return settlement;
}

export function claimTerritory(state, settlement) {
  const grid = state.grid;
  grid.forEachInRadius(settlement.x, settlement.z, settlement.radius, (x, y) => {
    const i = grid.idx(x, y);
    if (grid.terrain[i] === TERRAIN.WATER) return;
    grid.ownerNation[i] = settlement.nationId;
    grid.markDirtyIdx(i);
  });
}

function countFarmCells(state, settlement) {
  const grid = state.grid;
  let n = 0;
  grid.forEachInRadius(settlement.x, settlement.z, settlement.radius, (x, y) => {
    const i = grid.idx(x, y);
    if (grid.terrain[i] === TERRAIN.GRASS && grid.ownerNation[i] === settlement.nationId) n++;
  });
  return n;
}

const FOOD_PER_WORKER = 0.55;
const FOOD_PER_POP = 0.42;
const POP_CAP_PER_SETTLEMENT = 70;

export function updateSettlements(state) {
  // single pass population tally
  for (const s of state.settlements.values()) s.populationCount = 0;
  for (const h of state.humans) {
    const s = state.settlements.get(h.settlementId);
    if (s) s.populationCount++;
  }

  for (const settlement of state.settlements.values()) {
    if (state.tick % 25 === 0) {
      settlement.radius = Math.min(11, 3.5 + Math.sqrt(settlement.populationCount) * 0.85);
      settlement.farmCells = countFarmCells(state, settlement);
      claimTerritory(state, settlement);
    }

    const production = Math.min(settlement.populationCount, settlement.farmCells) * FOOD_PER_WORKER;
    const consumption = settlement.populationCount * FOOD_PER_POP;
    settlement.stock.food += production - consumption;

    if (settlement.stock.food < 0) {
      settlement.starveTicks++;
      settlement.stock.food = Math.max(settlement.stock.food, -30);
      if (settlement.starveTicks > 10 && settlement.populationCount > 0) {
        const victims = state.humans.filter((h) => h.settlementId === settlement.id);
        const killCount = Math.min(victims.length, 1 + Math.floor(Math.random() * 2));
        for (let i = 0; i < killCount; i++) {
          const v = victims[Math.floor(Math.random() * victims.length)];
          v.dead = true;
        }
        state.humans = state.humans.filter((h) => !h.dead);
        settlement.starveTicks = 0;
        settlement.stock.food = 0;
      }
    } else {
      settlement.starveTicks = 0;
    }

    if (
      settlement.stock.food > ECONOMY.SETTLEMENT_BUFFER &&
      settlement.populationCount < POP_CAP_PER_SETTLEMENT &&
      state.humans.length < SIM.MAX_HUMANS
    ) {
      settlement.growthAccum += 0.015;
      if (settlement.growthAccum >= 1) {
        settlement.growthAccum -= 1;
        settlement.stock.food -= 18;
        const ang = Math.random() * Math.PI * 2;
        state.humans.push(createHuman(
          settlement.x + 0.5 + Math.cos(ang) * 1.5,
          settlement.z + 0.5 + Math.sin(ang) * 1.5,
          settlement.nationId,
          settlement.id
        ));
      }
    }
  }
}

// Full re-claim pass across every settlement, nearest-wins. Cheap enough to
// run every few dozen ticks rather than continuously.
export function recomputeTerritory(state) {
  const grid = state.grid;
  const settlements = [...state.settlements.values()];
  grid.ownerNation.fill(-1);
  if (settlements.length === 0) return;
  for (let y = 0; y < grid.rows; y++) {
    for (let x = 0; x < grid.cols; x++) {
      const i = grid.idx(x, y);
      if (grid.terrain[i] === TERRAIN.WATER) continue;
      let best = null, bestD = Infinity;
      for (const s of settlements) {
        const d = Math.hypot(x - s.x, y - s.z);
        if (d <= s.radius && d < bestD) { bestD = d; best = s; }
      }
      if (best) grid.ownerNation[i] = best.nationId;
    }
  }
  for (let i = 0; i < grid.cols * grid.rows; i++) grid.markDirtyIdx(i);
}

export function removeSettlement(state, id) {
  state.settlements.delete(id);
  for (const h of state.humans) if (h.settlementId === id) h.settlementId = -1;
}
