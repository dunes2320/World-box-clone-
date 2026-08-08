import { TERRAIN, RESOURCE, RESOURCE_INFO, UNDEAD_NATION_ID } from "../config.js";

// Individual villagers. Food is handled abstractly at the settlement level
// (territory + population -> auto food production/consumption), so an
// individual human's only job is to gather wood/stone/iron and haul it home,
// or to flee danger. When drafted, a human is removed from this array and
// folded into an Army's unit counts (see military.js).
let nextHumanId = 1;

export function createHuman(x, z, nationId, settlementId) {
  return {
    id: nextHumanId++,
    x, z, prevX: x, prevZ: z,
    nationId, settlementId,
    job: null,
    state: "wander",
    targetX: x, targetZ: z,
    gatherX: -1, gatherY: -1, gatherTimer: 0,
    carrying: null, // { type, amount }
    fleeTimer: 0,
    age: 0,
    hue: 0,
  };
}

const JOB_RESOURCE = {
  wood: RESOURCE.FOREST,
  stone: RESOURCE.STONE_DEPOSIT,
  iron: RESOURCE.IRON_DEPOSIT,
};

function passable(grid, x, z) {
  const gx = Math.floor(x), gz = Math.floor(z);
  if (!grid.inBounds(gx, gz)) return false;
  return grid.terrain[grid.idx(gx, gz)] !== TERRAIN.WATER;
}

function pickWanderTarget(grid, h) {
  for (let i = 0; i < 5; i++) {
    const nx = h.x + (Math.random() * 2 - 1) * 5;
    const nz = h.z + (Math.random() * 2 - 1) * 5;
    if (passable(grid, nx, nz)) { h.targetX = nx; h.targetZ = nz; return; }
  }
}

function findResourceCell(grid, cx, cz, resourceType, radius) {
  let best = null, bestD = Infinity;
  const r = Math.ceil(radius);
  for (let dy = -r; dy <= r; dy++) {
    for (let dx = -r; dx <= r; dx++) {
      const x = Math.floor(cx) + dx, y = Math.floor(cz) + dy;
      if (!grid.inBounds(x, y)) continue;
      const i = grid.idx(x, y);
      if (grid.resource[i] === resourceType && grid.resourceAmount[i] > 0) {
        const d = dx * dx + dy * dy;
        if (d < bestD) { bestD = d; best = { x, y }; }
      }
    }
  }
  return best;
}

function assignJob(state, h) {
  const settlement = state.settlements.get(h.settlementId);
  if (!settlement) { h.job = null; return; }
  // pick whichever tracked resource is scarcest relative to a healthy buffer
  const needs = ["wood", "stone", "iron"].map((k) => ({ k, stock: settlement.stock[k] }));
  needs.sort((a, b) => a.stock - b.stock);
  for (const n of needs) {
    const cell = findResourceCell(state.grid, settlement.x, settlement.z, JOB_RESOURCE[n.k], 14);
    if (cell) {
      h.job = n.k;
      h.gatherX = cell.x;
      h.gatherY = cell.y;
      h.targetX = cell.x + 0.5;
      h.targetZ = cell.y + 0.5;
      h.state = "gather";
      return;
    }
  }
  h.job = null;
  h.state = "wander";
}

function moveToward(grid, h, speed) {
  const dx = h.targetX - h.x, dz = h.targetZ - h.z;
  const dist = Math.hypot(dx, dz);
  if (dist < 0.05) return dist;
  const step = Math.min(dist, speed);
  const nx = h.x + (dx / dist) * step;
  const nz = h.z + (dz / dist) * step;
  if (passable(grid, nx, nz)) { h.x = nx; h.z = nz; }
  else pickWanderTarget(grid, h);
  return dist;
}

const GATHER_TICKS = 5;
const SPEED = 0.34;

export function updatePopulation(state) {
  const grid = state.grid;
  const next = [];
  for (const h of state.humans) {
    if (h.dead) continue;
    h.prevX = h.x; h.prevZ = h.z;
    h.age++;

    if (h.nationId === UNDEAD_NATION_ID) {
      updateZombie(state, h);
      next.push(h);
      continue;
    }

    const ci = grid.idx(Math.floor(h.x), Math.floor(h.z));
    if (grid.burning[ci] && Math.random() < 0.35) continue; // burned to death

    if (grid.burning[ci] || nearbyFire(grid, h.x, h.z)) {
      h.state = "flee";
      h.fleeTimer = 25;
      const away = Math.atan2(h.z - grid.rows / 2, h.x - grid.cols / 2) + (Math.random() - 0.5);
      h.targetX = clamp(h.x + Math.cos(away) * 6, 0, grid.cols - 1);
      h.targetZ = clamp(h.z + Math.sin(away) * 6, 0, grid.rows - 1);
    }

    if (h.state === "flee") {
      moveToward(grid, h, SPEED * 1.4);
      h.fleeTimer--;
      if (h.fleeTimer <= 0) h.state = "wander";
      next.push(h);
      continue;
    }

    if (h.state === "gather") {
      const dist = moveToward(grid, h, SPEED);
      if (dist < 0.15) {
        h.gatherTimer++;
        if (h.gatherTimer >= GATHER_TICKS) {
          const gi = grid.idx(h.gatherX, h.gatherY);
          const info = RESOURCE_INFO[grid.resource[gi]];
          if (info && grid.resourceAmount[gi] > 0) {
            const amt = Math.min(info.yield, grid.resourceAmount[gi]);
            grid.resourceAmount[gi] -= amt;
            if (grid.resourceAmount[gi] <= 0 && !info.respawn) {
              grid.resource[gi] = RESOURCE.NONE;
              grid.markDirtyIdx(gi);
            }
            h.carrying = { type: info.key, amount: amt };
            const settlement = state.settlements.get(h.settlementId);
            if (settlement) { h.targetX = settlement.x + 0.5; h.targetZ = settlement.z + 0.5; }
            h.state = "haul";
          } else {
            assignJob(state, h);
          }
          h.gatherTimer = 0;
        }
      }
    } else if (h.state === "haul") {
      const settlement = state.settlements.get(h.settlementId);
      if (!settlement) { h.state = "wander"; }
      else {
        const dist = moveToward(grid, h, SPEED);
        if (dist < 0.2) {
          settlement.stock[h.carrying.type] = (settlement.stock[h.carrying.type] || 0) + h.carrying.amount;
          h.carrying = null;
          assignJob(state, h);
        }
      }
    } else {
      // wander / unemployed
      if (Math.random() < 0.02 || moveToward(grid, h, SPEED) < 0.1) pickWanderTarget(grid, h);
      if (Math.random() < 0.05) assignJob(state, h);
    }

    next.push(h);
  }
  state.humans = next.filter((h) => !h.dead);
}

function updateZombie(state, h) {
  const grid = state.grid;
  let target = null, bestD = 64;
  for (const other of state.humans) {
    if (other === h || other.dead || other.nationId === UNDEAD_NATION_ID) continue;
    const d = (other.x - h.x) ** 2 + (other.z - h.z) ** 2;
    if (d < bestD) { bestD = d; target = other; }
  }
  if (target) {
    h.targetX = target.x; h.targetZ = target.z;
    const dist = moveToward(grid, h, SPEED * 0.8);
    if (dist < 0.5) {
      if (Math.random() < 0.5) {
        target.nationId = UNDEAD_NATION_ID;
        target.settlementId = -1;
        target.job = null;
        target.carrying = null;
        target.state = "wander";
      } else {
        target.dead = true;
      }
    }
  } else if (Math.random() < 0.03 || moveToward(grid, h, SPEED * 0.5) < 0.1) {
    pickWanderTarget(grid, h);
  }
}

function nearbyFire(grid, x, z) {
  const gx = Math.floor(x), gz = Math.floor(z);
  for (let dy = -1; dy <= 1; dy++) {
    for (let dx = -1; dx <= 1; dx++) {
      const nx = gx + dx, ny = gz + dy;
      if (grid.inBounds(nx, ny) && grid.burning[grid.idx(nx, ny)]) return true;
    }
  }
  return false;
}

function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

export { assignJob };
