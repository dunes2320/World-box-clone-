import { TERRAIN, RESOURCE, RESOURCE_INFO } from "../config.js";
import { foundNewNation } from "../sim/nation.js";
import { createHuman } from "../sim/population.js";
import { igniteCell, explode, earthquake, blessing, zombieOutbreak, spawnTornado, spawnMonster } from "../sim/events.js";

export const TOOL_GROUPS = [
  {
    label: "Select",
    tools: [{ id: "select", name: "Select", icon: "\u{1F446}" }],
  },
  {
    label: "Terrain",
    tools: [
      { id: "water", name: "Water", icon: "\u{1F4A7}" },
      { id: "sand", name: "Sand", icon: "\u{1F3D6}️" },
      { id: "grass", name: "Grass", icon: "\u{1F331}" },
      { id: "dirt", name: "Dirt", icon: "\u{1F7E4}" },
      { id: "stone", name: "Mountain", icon: "⛰️" },
    ],
  },
  {
    label: "Life",
    tools: [
      { id: "forest", name: "Plant Forest", icon: "\u{1F333}" },
      { id: "human", name: "Wanderer", icon: "\u{1F9CD}" },
      { id: "foundNation", name: "Found Nation", icon: "\u{1F3F0}" },
    ],
  },
  {
    label: "Disasters",
    tools: [
      { id: "fire", name: "Fire", icon: "\u{1F525}" },
      { id: "meteor", name: "Meteor", icon: "☄️" },
      { id: "nuke", name: "Nuke", icon: "☢️" },
      { id: "earthquake", name: "Earthquake", icon: "\u{1F30B}" },
      { id: "tornado", name: "Tornado", icon: "\u{1F32A}️" },
      { id: "monster", name: "Kaiju", icon: "\u{1F999}" },
      { id: "zombie", name: "Outbreak", icon: "\u{1F9DF}" },
      { id: "blessing", name: "Blessing", icon: "✨" },
    ],
  },
];

function forEachInBrush(grid, cx, cz, size, fn) {
  const r = size - 1;
  grid.forEachInRadius(cx + 0.5, cz + 0.5, r, (x, y) => fn(x, y));
}

function paintTerrain(state, cx, cz, size, terrainType) {
  forEachInBrush(state.grid, cx, cz, size, (x, y) => state.grid.setTerrain(x, y, terrainType));
}

// Returns true if the tool actually mutated something (used to decide
// whether to keep painting on drag vs. a one-shot click tool).
export function applyTool(state, toolId, cx, cz, brushSize) {
  const grid = state.grid;
  if (!grid.inBounds(cx, cz)) return false;

  switch (toolId) {
    case "water": paintTerrain(state, cx, cz, brushSize, TERRAIN.WATER); return true;
    case "sand": paintTerrain(state, cx, cz, brushSize, TERRAIN.SAND); return true;
    case "grass": paintTerrain(state, cx, cz, brushSize, TERRAIN.GRASS); return true;
    case "dirt": paintTerrain(state, cx, cz, brushSize, TERRAIN.DIRT); return true;
    case "stone": paintTerrain(state, cx, cz, brushSize, TERRAIN.STONE); return true;

    case "forest":
      forEachInBrush(grid, cx, cz, brushSize, (x, y) => {
        const i = grid.idx(x, y);
        if (grid.terrain[i] === TERRAIN.GRASS && grid.resource[i] === RESOURCE.NONE && Math.random() < 0.7) {
          grid.resource[i] = RESOURCE.FOREST;
          grid.resourceAmount[i] = RESOURCE_INFO[RESOURCE.FOREST].yield * 6;
          grid.markDirtyIdx(i);
        }
      });
      return true;

    case "human": {
      const i = grid.idx(cx, cz);
      if (grid.isLand(i) && state.humans.length < 420) {
        state.humans.push(createHuman(cx + 0.5, cz + 0.5, -1, -1));
        return true;
      }
      return false;
    }

    case "foundNation": {
      const i = grid.idx(cx, cz);
      if (grid.isBuildable(i) && grid.slopeAt(cx, cz) < 1.4 && grid.settlementAt[i] < 0) {
        foundNewNation(state, cx, cz);
        return true;
      }
      return false;
    }

    case "fire":
      forEachInBrush(grid, cx, cz, brushSize, (x, y) => igniteCell(grid, x, y));
      return true;

    case "meteor":
      explode(state, cx + 0.5, cz + 0.5, Math.max(2.5, brushSize + 1.5));
      return true;

    case "nuke":
      explode(state, cx + 0.5, cz + 0.5, Math.max(6, brushSize + 5), { crater: false });
      return true;

    case "earthquake":
      earthquake(state, cx + 0.5, cz + 0.5, Math.max(4, brushSize + 3));
      return true;

    case "tornado":
      spawnTornado(state, cx + 0.5, cz + 0.5);
      return true;

    case "monster":
      spawnMonster(state, cx + 0.5, cz + 0.5);
      return true;

    case "zombie":
      zombieOutbreak(state, cx + 0.5, cz + 0.5, Math.max(2, brushSize), 1 + brushSize);
      return true;

    case "blessing":
      blessing(state, cx + 0.5, cz + 0.5, Math.max(4, brushSize + 3));
      return true;

    default:
      return false;
  }
}

// tools that should keep applying continuously while the mouse is held/dragged
export const CONTINUOUS_TOOLS = new Set(["water", "sand", "grass", "dirt", "stone", "forest", "fire"]);
