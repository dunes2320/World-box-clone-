import { WorldGrid } from "../world/grid.js";
import { generateWorld, findLandSpot } from "../world/worldgen.js";
import { updatePopulation } from "./population.js";
import { updateSettlements, recomputeTerritory } from "./settlement.js";
import { updateNations, foundNewNation, randomNationName } from "./nation.js";
import { GlobalMarket, updateEconomy } from "./economy.js";
import { updateMilitary } from "./military.js";
import { DiplomacyManager, updateDiplomacy } from "./diplomacy.js";
import { updateEvents } from "./events.js";
import { makeRng } from "../utils/rng.js";
import { WORLD_SEED } from "../config.js";

export function createInitialState(seed = WORLD_SEED + Math.floor(Math.random() * 1e6)) {
  const grid = new WorldGrid();
  generateWorld(grid, seed);

  const state = {
    grid,
    humans: [],
    settlements: new Map(),
    nations: new Map(),
    armies: new Map(),
    market: new GlobalMarket(),
    diplomacy: new DiplomacyManager(),
    tornadoes: [],
    monster: null,
    tick: 0,
    rng: makeRng(seed + 99),
    selection: null,
    paused: false,
    speed: 1,
  };

  seedStartingNations(state, 6);
  recomputeTerritory(state);
  return state;
}

function seedStartingNations(state, count) {
  const grid = state.grid;
  const spots = [];
  let attempts = 0;
  while (spots.length < count && attempts < 400) {
    attempts++;
    const cx = state.rng.range(grid.cols * 0.15, grid.cols * 0.85);
    const cy = state.rng.range(grid.rows * 0.15, grid.rows * 0.85);
    const spot = findLandSpot(grid, cx, cy, 3, state.rng);
    if (!spot) continue;
    const tooClose = spots.some((s) => Math.hypot(s.x - spot.x, s.y - spot.y) < grid.cols * 0.16);
    if (tooClose) continue;
    spots.push(spot);
  }
  for (const spot of spots) {
    foundNewNation(state, spot.x, spot.y, randomNationName(state.rng));
  }
}

export function simulationTick(state) {
  state.tick++;
  updatePopulation(state);
  updateSettlements(state);
  updateNations(state);
  updateEconomy(state);
  updateMilitary(state);
  updateDiplomacy(state);
  updateEvents(state);
}
