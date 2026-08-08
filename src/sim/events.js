import { TERRAIN, RESOURCE, RESOURCE_INFO, UNDEAD_NATION_ID, EVENTS } from "../config.js";
import { armyStrength, damageArmy } from "./military.js";

// ---- fire + vegetation: ambient world dynamics, always running ----
export function igniteCell(grid, x, y, life) {
  if (!grid.inBounds(x, y)) return;
  const i = grid.idx(x, y);
  if (grid.terrain[i] === TERRAIN.WATER || grid.terrain[i] === TERRAIN.STONE) return;
  if (!grid.burning[i] && (grid.terrain[i] === TERRAIN.GRASS || grid.resource[i] === RESOURCE.FOREST)) {
    grid.burning[i] = 1;
    grid.burnTimer[i] = life || 20 + Math.random() * 25;
    grid.markDirtyIdx(i);
  }
}

function updateFire(state) {
  const grid = state.grid;
  const burningCells = [];
  for (let i = 0; i < grid.cols * grid.rows; i++) if (grid.burning[i]) burningCells.push(i);

  for (const i of burningCells) {
    grid.burnTimer[i]--;
    if (grid.burnTimer[i] <= 0) {
      grid.burning[i] = 0;
      grid.resource[i] = RESOURCE.NONE;
      if (grid.terrain[i] === TERRAIN.GRASS) grid.terrain[i] = TERRAIN.DIRT;
      grid.markDirtyIdx(i);
      continue;
    }
    const x = i % grid.cols, y = (i / grid.cols) | 0;
    for (const [dx, dy] of [[-1, 0], [1, 0], [0, -1], [0, 1]]) {
      const nx = x + dx, ny = y + dy;
      if (!grid.inBounds(nx, ny)) continue;
      const ni = grid.idx(nx, ny);
      if (grid.burning[ni]) continue;
      const flammable = grid.terrain[ni] === TERRAIN.GRASS || grid.resource[ni] === RESOURCE.FOREST;
      if (flammable && Math.random() < 0.1) igniteCell(grid, nx, ny, 20 + Math.random() * 25);
    }
  }
}

function updateVegetation(state) {
  const grid = state.grid;
  const samples = 250;
  for (let s = 0; s < samples; s++) {
    const x = (Math.random() * grid.cols) | 0;
    const y = (Math.random() * grid.rows) | 0;
    const i = grid.idx(x, y);
    if (grid.terrain[i] !== TERRAIN.GRASS) continue;
    if (grid.resource[i] === RESOURCE.FOREST) {
      if (grid.resourceAmount[i] < RESOURCE_INFO[RESOURCE.FOREST].yield * 8 && Math.random() < 0.05) {
        grid.resourceAmount[i] += 1;
      }
    } else if (grid.resource[i] === RESOURCE.NONE && Math.random() < 0.004) {
      grid.resource[i] = RESOURCE.FOREST;
      grid.resourceAmount[i] = RESOURCE_INFO[RESOURCE.FOREST].yield * 2;
      grid.markDirtyIdx(i);
    }
  }
}

// ---- disaster / god-tool triggers (one-shot) ----
export function explode(state, cx, cy, radius, { crater = true } = {}) {
  const grid = state.grid;
  grid.forEachInRadius(cx, cy, radius, (x, y, d) => {
    const i = grid.idx(x, y);
    if (d < radius * 0.55) {
      if (crater) grid.terrain[i] = TERRAIN.STONE;
      grid.resource[i] = RESOURCE.NONE;
      grid.burning[i] = 0;
    } else {
      igniteCell(grid, x, y, 25 + Math.random() * 25);
    }
    grid.markDirtyIdx(i);
  });
  state.humans = state.humans.filter((h) => Math.hypot(h.x - cx, h.z - cy) > radius * 0.7);
  for (const settlement of state.settlements.values()) {
    const d = Math.hypot(settlement.x - cx, settlement.z - cy);
    if (d < radius) {
      const loss = Math.round((1 - d / radius) * settlement.populationCount * 0.4);
      settlement.stock.food = Math.max(0, settlement.stock.food - loss * 5);
    }
  }
}

export function earthquake(state, cx, cy, radius) {
  const grid = state.grid;
  grid.forEachInRadius(cx, cy, radius, (x, y, d) => {
    const i = grid.idx(x, y);
    if (grid.terrain[i] === TERRAIN.WATER) return;
    grid.height[i] += (Math.random() - 0.5) * 3 * (1 - d / radius);
    if (Math.random() < 0.08) grid.terrain[i] = TERRAIN.STONE;
    grid.markDirtyIdx(i);
  });
  for (const s of state.settlements.values()) {
    const d = Math.hypot(s.x - cx, s.z - cy);
    if (d < radius) s.siegeProgress = 0;
  }
  state.humans = state.humans.filter((h) => {
    const d = Math.hypot(h.x - cx, h.z - cy);
    return !(d < radius * 0.5 && Math.random() < 0.3);
  });
}

export function blessing(state, cx, cy, radius) {
  const grid = state.grid;
  grid.forEachInRadius(cx, cy, radius, (x, y) => {
    const i = grid.idx(x, y);
    if (grid.burning[i]) { grid.burning[i] = 0; grid.markDirtyIdx(i); }
  });
  for (const s of state.settlements.values()) {
    if (Math.hypot(s.x - cx, s.z - cy) <= radius) {
      s.stock.food += 60;
      const n = state.nations.get(s.nationId);
      if (n) n.treasury += 70;
    }
  }
}

export function zombieOutbreak(state, cx, cy, radius, count = 3) {
  let converted = 0;
  for (const h of state.humans) {
    if (converted >= count) break;
    if (h.nationId === UNDEAD_NATION_ID) continue;
    if (Math.hypot(h.x - cx, h.z - cy) <= radius) {
      h.nationId = UNDEAD_NATION_ID;
      h.settlementId = -1;
      h.job = null;
      h.carrying = null;
      h.state = "wander";
      converted++;
    }
  }
}

export function spawnTornado(state, x, z) {
  state.tornadoes.push({
    x, z,
    angle: Math.random() * Math.PI * 2,
    life: EVENTS.TORNADO_LIFETIME,
  });
}

function updateTornadoes(state) {
  const grid = state.grid;
  state.tornadoes = state.tornadoes.filter((t) => {
    t.angle += (Math.random() - 0.5) * 0.6;
    t.x += Math.cos(t.angle) * EVENTS.TORNADO_SPEED * 0.1;
    t.z += Math.sin(t.angle) * EVENTS.TORNADO_SPEED * 0.1;
    t.life--;
    state.humans = state.humans.filter((h) => {
      const d = Math.hypot(h.x - t.x, h.z - t.z);
      return !(d < 1.6 && Math.random() < 0.12);
    });
    grid.forEachInRadius(t.x, t.z, 1.3, (x, y) => {
      const i = grid.idx(x, y);
      if (grid.resource[i] === RESOURCE.FOREST && Math.random() < 0.2) {
        grid.resource[i] = RESOURCE.NONE;
        grid.markDirtyIdx(i);
      }
    });
    return t.life > 0 && grid.inBounds(Math.floor(t.x), Math.floor(t.z));
  });
}

export function spawnMonster(state, x, z) {
  if (state.monster) return false;
  state.monster = { x, z, hp: EVENTS.MONSTER_HP, maxHp: EVENTS.MONSTER_HP, life: EVENTS.MONSTER_LIFETIME };
  return true;
}

function updateMonster(state) {
  const m = state.monster;
  if (!m) return;
  m.life--;

  let target = null, bestD = Infinity;
  for (const s of state.settlements.values()) {
    const d = Math.hypot(s.x - m.x, s.z - m.z);
    if (d < bestD) { bestD = d; target = s; }
  }
  if (target) {
    const dx = target.x - m.x, dz = target.z - m.z;
    const dist = Math.hypot(dx, dz);
    if (dist > 1.4) {
      m.x += (dx / dist) * EVENTS.MONSTER_SPEED;
      m.z += (dz / dist) * EVENTS.MONSTER_SPEED;
    } else {
      const victims = state.humans.filter((h) => h.settlementId === target.id);
      for (let i = 0; i < 2 && victims.length; i++) {
        const v = victims[Math.floor(Math.random() * victims.length)];
        v.dead = true;
      }
      target.stock.food = Math.max(0, target.stock.food - 15);
      target.stock.wood = Math.max(0, target.stock.wood - 8);
      m.hp -= target.populationCount * 0.06;
    }
  }

  for (const army of state.armies.values()) {
    if (army.dead) continue;
    const d = Math.hypot(army.x - m.x, army.z - m.z);
    if (d < 1.7) {
      m.hp -= armyStrength(army) * 0.22;
      damageArmy(state, army, EVENTS.MONSTER_POWER * 0.4);
      const n = state.nations.get(army.nationId);
      if (n && m.hp <= 0) n.treasury += 200;
    }
  }

  state.humans = state.humans.filter((h) => !h.dead);
  if (m.hp <= 0 || m.life <= 0) state.monster = null;
}

export function updateEvents(state) {
  updateFire(state);
  updateVegetation(state);
  updateTornadoes(state);
  updateMonster(state);
}
