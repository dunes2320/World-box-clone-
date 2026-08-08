import { TERRAIN, RESOURCE, RESOURCE_INFO, WORLD_SEED } from "../config.js";
import { makeFbm } from "../utils/noise.js";
import { makeRng } from "../utils/rng.js";

// Builds a continent-ish island with beaches, plains, forests, hills and a
// mountain spine, then scatters resource deposits. Deterministic per seed.
export function generateWorld(grid, seed = WORLD_SEED) {
  const fbm = makeFbm(seed);
  const rng = makeRng(seed * 7 + 3);
  const { cols, rows } = grid;

  for (let y = 0; y < rows; y++) {
    for (let x = 0; x < cols; x++) {
      const i = grid.idx(x, y);
      const nx = x / cols - 0.5;
      const ny = y / rows - 0.5;
      const radial = Math.sqrt(nx * nx + ny * ny) * 2; // 0 center -> ~1.4 corner

      const base = fbm(x * 0.06, y * 0.06, 5, 2, 0.5);
      const warp = fbm(x * 0.02 + 50, y * 0.02 + 50, 3, 2, 0.5);
      const elevation = base * 0.75 + warp * 0.25 - radial * 0.55;

      let h = elevation * 14; // world-unit height scale
      grid.height[i] = h;

      if (h < -1.0) {
        grid.terrain[i] = TERRAIN.WATER;
      } else if (h < -0.15) {
        grid.terrain[i] = TERRAIN.SAND;
      } else if (h > 5.2) {
        grid.terrain[i] = TERRAIN.STONE;
      } else if (h > 3.6 && fbm(x * 0.1 + 200, y * 0.1 + 200, 2) > 0.55) {
        grid.terrain[i] = TERRAIN.STONE;
      } else {
        grid.terrain[i] = TERRAIN.GRASS;
      }
    }
  }

  // scatter resources on land
  for (let y = 0; y < rows; y++) {
    for (let x = 0; x < cols; x++) {
      const i = grid.idx(x, y);
      const t = grid.terrain[i];
      if (t === TERRAIN.WATER) continue;

      if (t === TERRAIN.GRASS) {
        const forestN = fbm(x * 0.12 + 900, y * 0.12 + 900, 3);
        if (forestN > 0.62 && rng.chance(0.55)) {
          grid.resource[i] = RESOURCE.FOREST;
          grid.resourceAmount[i] = RESOURCE_INFO[RESOURCE.FOREST].yield * 8;
        }
      } else if (t === TERRAIN.STONE) {
        const roll = rng.next();
        if (roll < 0.05) {
          grid.resource[i] = RESOURCE.GOLD_DEPOSIT;
          grid.resourceAmount[i] = RESOURCE_INFO[RESOURCE.GOLD_DEPOSIT].yield * 30;
        } else if (roll < 0.16) {
          grid.resource[i] = RESOURCE.IRON_DEPOSIT;
          grid.resourceAmount[i] = RESOURCE_INFO[RESOURCE.IRON_DEPOSIT].yield * 30;
        } else if (roll < 0.4) {
          grid.resource[i] = RESOURCE.STONE_DEPOSIT;
          grid.resourceAmount[i] = RESOURCE_INFO[RESOURCE.STONE_DEPOSIT].yield * 40;
        }
      }
    }
  }

  grid.dirty.clear();
  for (let i = 0; i < cols * rows; i++) grid.dirty.add(i);
}

// Finds a reasonable land spot (buildable, near water is nice-to-have, not
// required) within `radius` cells of (cx, cy). Used for settlement founding
// and random-spawn tools.
export function findLandSpot(grid, cx, cy, radius, rngInst) {
  for (let tries = 0; tries < 40; tries++) {
    const x = Math.round(cx + (rngInst.next() * 2 - 1) * radius);
    const y = Math.round(cy + (rngInst.next() * 2 - 1) * radius);
    if (!grid.inBounds(x, y)) continue;
    const i = grid.idx(x, y);
    if (grid.isBuildable(i) && grid.slopeAt(x, y) < 1.2) return { x, y };
  }
  return null;
}
