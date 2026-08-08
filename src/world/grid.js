import { GRID, TERRAIN, RESOURCE } from "../config.js";

const { COLS, ROWS } = GRID;
const N = COLS * ROWS;

// The grid is the "physical layer" of the world: elevation, terrain type,
// resource deposits, fire state and territory ownership. Everything else
// (population, settlements, nations) reads/writes into this shared layer.
export class WorldGrid {
  constructor() {
    this.cols = COLS;
    this.rows = ROWS;
    this.height = new Float32Array(N);
    this.terrain = new Uint8Array(N).fill(TERRAIN.GRASS);
    this.resource = new Uint8Array(N).fill(RESOURCE.NONE);
    this.resourceAmount = new Uint16Array(N);
    this.burning = new Uint8Array(N);
    this.burnTimer = new Int16Array(N);
    this.ownerNation = new Int16Array(N).fill(-1);
    this.settlementAt = new Int16Array(N).fill(-1);
    this.dirty = new Set(); // cell indices needing a re-render this frame
  }

  idx(x, y) { return y * this.cols + x; }
  inBounds(x, y) { return x >= 0 && y >= 0 && x < this.cols && y < this.rows; }

  markDirty(x, y) { this.dirty.add(this.idx(x, y)); }
  markDirtyIdx(i) { this.dirty.add(i); }

  isLand(i) { return this.terrain[i] !== TERRAIN.WATER; }
  isBuildable(i) {
    return this.terrain[i] === TERRAIN.GRASS || this.terrain[i] === TERRAIN.SAND || this.terrain[i] === TERRAIN.DIRT;
  }

  slopeAt(x, y) {
    const h = this.height;
    const c = this.idx(x, y);
    let maxDiff = 0;
    for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const nx = x + dx, ny = y + dy;
      if (!this.inBounds(nx, ny)) continue;
      maxDiff = Math.max(maxDiff, Math.abs(h[c] - h[this.idx(nx, ny)]));
    }
    return maxDiff;
  }

  setTerrain(x, y, t) {
    const i = this.idx(x, y);
    this.terrain[i] = t;
    if (t === TERRAIN.WATER) {
      this.resource[i] = RESOURCE.NONE;
      this.resourceAmount[i] = 0;
    }
    this.burning[i] = 0;
    this.burnTimer[i] = 0;
    this.markDirtyIdx(i);
  }

  forEachInRadius(cx, cy, radius, fn) {
    const r = Math.max(0, radius);
    for (let y = Math.floor(cy - r); y <= Math.ceil(cy + r); y++) {
      for (let x = Math.floor(cx - r); x <= Math.ceil(cx + r); x++) {
        if (!this.inBounds(x, y)) continue;
        const d = Math.hypot(x - cx, y - cy);
        if (d <= r) fn(x, y, d);
      }
    }
  }
}

export const N_CELLS = N;
