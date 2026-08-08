"use strict";

/* ---------------------------------------------------------
   WorldBox-style sandbox: grid terrain + simple ecosystem
   (trees, humans) + god-tool disasters (fire, lightning,
   meteor, nuke).
--------------------------------------------------------- */

// ---- Terrain types ----
const WATER = 0, SAND = 1, GRASS = 2, DIRT = 3, STONE = 4;

const TERRAIN_COLOR = {
  [WATER]: "#2f6fb0",
  [SAND]: "#d9c58a",
  [GRASS]: "#4f9a44",
  [DIRT]: "#7a5b3a",
  [STONE]: "#888f96",
};

const COLS = 100;
const ROWS = 60;
const CELL = 10; // internal pixel size per cell
const N = COLS * ROWS;

function idx(x, y) { return y * COLS + x; }
function inBounds(x, y) { return x >= 0 && y >= 0 && x < COLS && y < ROWS; }

// ---- World state (typed arrays for speed) ----
let terrain = new Uint8Array(N);
let treeStage = new Uint8Array(N);   // 0 none, 1 sapling, 2 grown
let burning = new Uint8Array(N);     // 0/1
let burnTimer = new Int16Array(N);
let humans = []; // {x,y,tx,ty,hunger,age,id}
let nextHumanId = 1;
let tick = 0;

const MAX_HUMANS = 400;
const MAX_HUNGER = 300;
const MATURE_AGE = 60;
const MAX_AGE = 2400;

// ---- Canvas setup ----
const canvas = document.getElementById("world");
const ctx = canvas.getContext("2d");
canvas.width = COLS * CELL;
canvas.height = ROWS * CELL;

const terrainCanvas = document.createElement("canvas");
terrainCanvas.width = canvas.width;
terrainCanvas.height = canvas.height;
const tctx = terrainCanvas.getContext("2d");

let dirty = new Set();

function markDirty(x, y) { dirty.add(idx(x, y)); }

function drawCell(x, y) {
  const i = idx(x, y);
  tctx.fillStyle = TERRAIN_COLOR[terrain[i]];
  tctx.fillRect(x * CELL, y * CELL, CELL, CELL);

  if (treeStage[i] > 0) {
    const cx = x * CELL + CELL / 2;
    const cy = y * CELL + CELL / 2;
    if (treeStage[i] === 1) {
      // sapling: small green dot
      tctx.fillStyle = "#2f7d32";
      tctx.beginPath();
      tctx.arc(cx, cy, CELL * 0.18, 0, Math.PI * 2);
      tctx.fill();
    } else {
      // grown tree: trunk + canopy
      tctx.fillStyle = "#5a3b20";
      tctx.fillRect(cx - CELL * 0.08, cy - CELL * 0.05, CELL * 0.16, CELL * 0.35);
      tctx.fillStyle = "#215c26";
      tctx.beginPath();
      tctx.arc(cx, cy - CELL * 0.18, CELL * 0.32, 0, Math.PI * 2);
      tctx.fill();
    }
  }
}

function fullRedraw() {
  for (let y = 0; y < ROWS; y++) {
    for (let x = 0; x < COLS; x++) drawCell(x, y);
  }
}

function initWorld() {
  terrain.fill(GRASS);
  treeStage.fill(0);
  burning.fill(0);
  burnTimer.fill(0);
  humans = [];
  tick = 0;

  // simple generated landmass: lake + a mountain range + beaches
  for (let y = 0; y < ROWS; y++) {
    for (let x = 0; x < COLS; x++) {
      const i = idx(x, y);
      const nx = x / COLS - 0.5, ny = y / ROWS - 0.5;
      const d = Math.sqrt(nx * nx + ny * ny);
      const n = Math.sin(x * 0.15) * Math.cos(y * 0.15) + Math.sin(x * 0.05 + y * 0.08);
      if (d > 0.62 + n * 0.03) {
        terrain[i] = WATER;
      } else if (d > 0.55 + n * 0.03) {
        terrain[i] = SAND;
      } else if (n > 1.15) {
        terrain[i] = STONE;
      } else {
        terrain[i] = GRASS;
        if (Math.random() < 0.03) treeStage[i] = 2;
      }
    }
  }
  fullRedraw();
  updateStats();
}

// ---- Tools ----
let currentTool = "water";
let brushSize = 3;
let mouseDown = false;
let lastPaintCell = null;

const toolButtons = document.querySelectorAll(".tool");
toolButtons.forEach((btn) => {
  btn.addEventListener("click", () => {
    toolButtons.forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    currentTool = btn.dataset.tool;
  });
});
toolButtons[0].classList.add("active");

const brushSlider = document.getElementById("brushSize");
const brushLabel = document.getElementById("brushSizeLabel");
brushSlider.addEventListener("input", () => {
  brushSize = parseInt(brushSlider.value, 10);
  brushLabel.textContent = brushSize;
});

document.getElementById("resetBtn").addEventListener("click", () => {
  if (confirm("Reset the whole world?")) initWorld();
});

// speed controls
let speed = 1; // ticks per ~200ms base interval multiplier; 0 = paused
document.querySelectorAll(".speedBtn").forEach((btn) => {
  btn.addEventListener("click", () => {
    document.querySelectorAll(".speedBtn").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    speed = parseInt(btn.dataset.speed, 10);
  });
});

// ---- Mouse handling ----
function canvasPos(evt) {
  const rect = canvas.getBoundingClientRect();
  const scaleX = canvas.width / rect.width;
  const scaleY = canvas.height / rect.height;
  const px = (evt.clientX - rect.left) * scaleX;
  const py = (evt.clientY - rect.top) * scaleY;
  return { x: Math.floor(px / CELL), y: Math.floor(py / CELL) };
}

canvas.addEventListener("mousedown", (e) => {
  mouseDown = true;
  applyTool(canvasPos(e));
});
window.addEventListener("mouseup", () => { mouseDown = false; lastPaintCell = null; });
canvas.addEventListener("mousemove", (e) => {
  if (!mouseDown) return;
  const pos = canvasPos(e);
  if (lastPaintCell && lastPaintCell.x === pos.x && lastPaintCell.y === pos.y) return;
  lastPaintCell = pos;
  applyTool(pos);
});
canvas.addEventListener("wheel", (e) => {
  e.preventDefault();
  brushSize = Math.min(10, Math.max(1, brushSize + (e.deltaY < 0 ? 1 : -1)));
  brushSlider.value = brushSize;
  brushLabel.textContent = brushSize;
}, { passive: false });

function forEachInBrush(cx, cy, size, fn) {
  const r = size - 1;
  for (let y = cy - r; y <= cy + r; y++) {
    for (let x = cx - r; x <= cx + r; x++) {
      if (!inBounds(x, y)) continue;
      if ((x - cx) ** 2 + (y - cy) ** 2 <= r * r + 0.5) fn(x, y);
    }
  }
}

function setTerrain(x, y, t) {
  const i = idx(x, y);
  terrain[i] = t;
  treeStage[i] = 0;
  burning[i] = 0;
  burnTimer[i] = 0;
  markDirty(x, y);
}

function igniteCell(x, y, life) {
  const i = idx(x, y);
  if (terrain[i] === WATER || terrain[i] === STONE) return;
  if ((terrain[i] === GRASS || treeStage[i] > 0) && !burning[i]) {
    burning[i] = 1;
    burnTimer[i] = life || (20 + Math.random() * 25) | 0;
  }
}

function explode(cx, cy, radius, opts) {
  opts = opts || {};
  for (let y = cy - radius; y <= cy + radius; y++) {
    for (let x = cx - radius; x <= cx + radius; x++) {
      if (!inBounds(x, y)) continue;
      const d = Math.sqrt((x - cx) ** 2 + (y - cy) ** 2);
      if (d > radius) continue;
      const i = idx(x, y);
      if (d < radius * 0.6) {
        terrain[i] = opts.crater === false ? terrain[i] : STONE;
        treeStage[i] = 0;
        burning[i] = 0;
      } else {
        igniteCell(x, y, 30 + Math.random() * 20);
      }
      markDirty(x, y);
    }
  }
  // kill humans in range
  humans = humans.filter((h) => {
    const d = Math.hypot(h.x - cx, h.y - cy);
    return d > radius;
  });
}

function applyTool(pos) {
  const { x, y } = pos;
  if (!inBounds(x, y)) return;

  switch (currentTool) {
    case "water":
      forEachInBrush(x, y, brushSize, (cx, cy) => setTerrain(cx, cy, WATER));
      break;
    case "sand":
      forEachInBrush(x, y, brushSize, (cx, cy) => setTerrain(cx, cy, SAND));
      break;
    case "grass":
      forEachInBrush(x, y, brushSize, (cx, cy) => setTerrain(cx, cy, GRASS));
      break;
    case "dirt":
      forEachInBrush(x, y, brushSize, (cx, cy) => setTerrain(cx, cy, DIRT));
      break;
    case "stone":
      forEachInBrush(x, y, brushSize, (cx, cy) => setTerrain(cx, cy, STONE));
      break;
    case "tree":
      forEachInBrush(x, y, brushSize, (cx, cy) => {
        const i = idx(cx, cy);
        if (terrain[i] === GRASS && treeStage[i] === 0 && Math.random() < 0.6) {
          treeStage[i] = 2;
          markDirty(cx, cy);
        }
      });
      break;
    case "human":
      forEachInBrush(x, y, brushSize, (cx, cy) => {
        const i = idx(cx, cy);
        if (terrain[i] !== WATER && terrain[i] !== STONE && humans.length < MAX_HUMANS && Math.random() < 0.5) {
          spawnHuman(cx + 0.5, cy + 0.5);
        }
      });
      break;
    case "fire":
      forEachInBrush(x, y, brushSize, (cx, cy) => igniteCell(cx, cy));
      break;
    case "lightning":
      igniteCell(x, y, 40);
      forEachInBrush(x, y, Math.max(1, brushSize - 1), (cx, cy) => {
        if (Math.random() < 0.5) igniteCell(cx, cy);
      });
      break;
    case "meteor":
      explode(x, y, Math.max(2, brushSize + 1));
      break;
    case "nuke":
      explode(x, y, Math.max(5, brushSize + 4), { crater: false });
      forEachInBrush(x, y, Math.max(5, brushSize + 4), (cx, cy) => {
        const i = idx(cx, cy);
        if (terrain[i] !== WATER) terrain[i] = DIRT;
        markDirty(cx, cy);
      });
      break;
  }
}

// ---- Entities: Humans ----
function spawnHuman(x, y) {
  humans.push({
    id: nextHumanId++,
    x, y,
    tx: x, ty: y,
    hunger: 60 + Math.random() * 40,
    age: 0,
    hue: 190 + Math.random() * 60,
  });
}

function findNearbyTree(h, radius) {
  const cx = Math.floor(h.x), cy = Math.floor(h.y);
  let best = null, bestD = Infinity;
  for (let y = cy - radius; y <= cy + radius; y++) {
    for (let x = cx - radius; x <= cx + radius; x++) {
      if (!inBounds(x, y)) continue;
      if (treeStage[idx(x, y)] === 2) {
        const d = (x - h.x) ** 2 + (y - h.y) ** 2;
        if (d < bestD) { bestD = d; best = { x: x + 0.5, y: y + 0.5 }; }
      }
    }
  }
  return best;
}

function passable(x, y) {
  if (!inBounds(Math.floor(x), Math.floor(y))) return false;
  const t = terrain[idx(Math.floor(x), Math.floor(y))];
  return t !== WATER && t !== STONE;
}

function updateHumans() {
  const speedMove = 0.08;
  const next = [];
  for (const h of humans) {
    h.age++;
    h.hunger += 1;

    // death by hunger
    if (h.hunger >= MAX_HUNGER) continue;
    // death by old age (increasing chance)
    if (h.age > MAX_AGE && Math.random() < 0.02) continue;
    // death by fire
    const ci = idx(Math.floor(h.x), Math.floor(h.y));
    if (burning[ci] && Math.random() < 0.5) continue;

    // pick a target
    const hungry = h.hunger > 120;
    if (hungry) {
      const tree = findNearbyTree(h, 8);
      if (tree) { h.tx = tree.x; h.ty = tree.y; }
    }
    if (Math.hypot(h.tx - h.x, h.ty - h.y) < 0.15 || Math.random() < 0.01) {
      // pick new random wander target if arrived or occasionally
      let tries = 0;
      let nx, ny;
      do {
        nx = h.x + (Math.random() * 2 - 1) * 6;
        ny = h.y + (Math.random() * 2 - 1) * 6;
        tries++;
      } while (!passable(nx, ny) && tries < 5);
      if (passable(nx, ny)) { h.tx = nx; h.ty = ny; }
    }

    // move toward target
    const dx = h.tx - h.x, dy = h.ty - h.y;
    const dist = Math.hypot(dx, dy);
    if (dist > 0.01) {
      const step = Math.min(dist, speedMove);
      const nx = h.x + (dx / dist) * step;
      const ny = h.y + (dy / dist) * step;
      if (passable(nx, ny)) { h.x = nx; h.y = ny; }
      else { h.tx = h.x; h.ty = h.y; }
    }

    // eat tree if standing on one
    const hi = idx(Math.floor(h.x), Math.floor(h.y));
    if (treeStage[hi] === 2 && h.hunger > 40) {
      treeStage[hi] = 0;
      markDirty(Math.floor(h.x), Math.floor(h.y));
      h.hunger = Math.max(0, h.hunger - 150);
    }

    // reproduce
    if (h.hunger < 60 && h.age > MATURE_AGE && humans.length + next.length < MAX_HUMANS && Math.random() < 0.004) {
      const cx = h.x + (Math.random() * 2 - 1);
      const cy = h.y + (Math.random() * 2 - 1);
      if (passable(cx, cy)) spawnHuman(cx, cy);
    }

    next.push(h);
  }
  humans = next;
}

// ---- Fire & vegetation simulation ----
let burningCells = new Set();

function updateFire() {
  const toExtinguish = [];
  burningCells.forEach((i) => {
    burnTimer[i]--;
    if (burnTimer[i] <= 0) {
      toExtinguish.push(i);
      return;
    }
    const x = i % COLS, y = (i / COLS) | 0;
    const neighbors = [[x - 1, y], [x + 1, y], [x, y - 1], [x, y + 1]];
    for (const [nx, ny] of neighbors) {
      if (!inBounds(nx, ny)) continue;
      const ni = idx(nx, ny);
      if (!burning[ni] && (treeStage[ni] === 2 || (terrain[ni] === GRASS && Math.random() < 0.25))) {
        if (Math.random() < 0.12) {
          burning[ni] = 1;
          burnTimer[ni] = 20 + Math.random() * 25;
          burningCells.add(ni);
        }
      }
    }
  });
  for (const i of toExtinguish) {
    burning[i] = 0;
    treeStage[i] = 0;
    if (terrain[i] === GRASS) terrain[i] = DIRT;
    burningCells.delete(i);
    const x = i % COLS, y = (i / COLS) | 0;
    markDirty(x, y);
  }
  // sync any newly ignited cells from igniteCell() calls into the set
  for (let i = 0; i < N; i++) {
    if (burning[i] && !burningCells.has(i)) burningCells.add(i);
  }
}

function updateVegetation() {
  // sparse random sampling for growth/spread to keep cost low
  const samples = 400;
  for (let s = 0; s < samples; s++) {
    const x = (Math.random() * COLS) | 0;
    const y = (Math.random() * ROWS) | 0;
    const i = idx(x, y);
    if (terrain[i] === GRASS) {
      if (treeStage[i] === 1 && Math.random() < 0.02) {
        treeStage[i] = 2;
        markDirty(x, y);
      } else if (treeStage[i] === 2 && Math.random() < 0.01) {
        const nx = x + ((Math.random() * 3) | 0) - 1;
        const ny = y + ((Math.random() * 3) | 0) - 1;
        if (inBounds(nx, ny)) {
          const ni = idx(nx, ny);
          if (terrain[ni] === GRASS && treeStage[ni] === 0) {
            treeStage[ni] = 1;
            markDirty(nx, ny);
          }
        }
      } else if (treeStage[i] === 0 && Math.random() < 0.002) {
        treeStage[i] = 1;
        markDirty(x, y);
      }
    } else if (terrain[i] === DIRT && Math.random() < 0.01) {
      // check for adjacent grass to spread from
      const dirs = [[-1, 0], [1, 0], [0, -1], [0, 1]];
      for (const [dx, dy] of dirs) {
        const nx = x + dx, ny = y + dy;
        if (inBounds(nx, ny) && terrain[idx(nx, ny)] === GRASS) {
          terrain[i] = GRASS;
          markDirty(x, y);
          break;
        }
      }
    }
  }
}

// ---- Main tick ----
function simulate() {
  tick++;
  updateFire();
  updateVegetation();
  updateHumans();
  updateStats();
}

function updateStats() {
  document.getElementById("statHumans").textContent = humans.length;
  let trees = 0;
  for (let i = 0; i < N; i++) if (treeStage[i] === 2) trees++;
  document.getElementById("statTrees").textContent = trees;
  document.getElementById("statTick").textContent = tick;
}

// ---- Render loop ----
function flushDirty() {
  if (dirty.size === 0) return;
  dirty.forEach((i) => {
    const x = i % COLS, y = (i / COLS) | 0;
    drawCell(x, y);
  });
  dirty.clear();
}

function render(time) {
  flushDirty();
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(terrainCanvas, 0, 0);

  // fire flicker overlay
  const flick = 0.6 + 0.3 * Math.sin(time / 90);
  burningCells.forEach((i) => {
    const x = i % COLS, y = (i / COLS) | 0;
    ctx.fillStyle = `rgba(255,${100 + Math.floor(Math.random() * 80)},0,${flick})`;
    ctx.fillRect(x * CELL, y * CELL, CELL, CELL);
  });

  // humans
  for (const h of humans) {
    const px = h.x * CELL, py = h.y * CELL;
    ctx.beginPath();
    ctx.fillStyle = h.hunger > 150 ? "#e04b4b" : `hsl(${h.hue}, 70%, 60%)`;
    ctx.arc(px, py, CELL * 0.28, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = "rgba(0,0,0,0.4)";
    ctx.lineWidth = 1;
    ctx.stroke();
  }

  requestAnimationFrame(render);
}

// ---- Simulation loop (fixed-ish interval, speed-adjustable) ----
let lastTickTime = 0;
function loop(time) {
  const interval = 160; // ms per tick at speed=1
  if (speed > 0 && time - lastTickTime > interval / speed) {
    lastTickTime = time;
    simulate();
  }
  requestAnimationFrame(loop);
}

// ---- Boot ----
initWorld();
requestAnimationFrame(render);
requestAnimationFrame(loop);
