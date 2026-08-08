import * as THREE from "three";
import "./style.css";
import { ThreeApp } from "./render/scene.js";
import { TerrainMesh } from "./render/terrainMesh.js";
import { EntityRenderer } from "./render/entityRenderer.js";
import { pickTerrainCell, pickInstance } from "./render/picking.js";
import { createInitialState, simulationTick } from "./sim/simulation.js";
import { applyTool, CONTINUOUS_TOOLS } from "./tools/godTools.js";
import { Hud } from "./ui/hud.js";
import { SIM } from "./config.js";

let state = createInitialState();

const canvas = document.getElementById("world");
const app = new ThreeApp(canvas);

// disable left-drag orbiting so left-click is free for painting/selection
app.controls.mouseButtons = { LEFT: null, MIDDLE: THREE.MOUSE.PAN, RIGHT: THREE.MOUSE.ROTATE };

function nationColorLookup(nationId) {
  const n = state.nations.get(nationId);
  return n ? new THREE.Color(n.color) : null;
}

const terrainMesh = new TerrainMesh(state.grid, nationColorLookup);
app.scene.add(terrainMesh.mesh);
app.scene.add(terrainMesh.waterMesh);

const entityRenderer = new EntityRenderer(app.scene, state.grid, nationColorLookup);
entityRenderer.rebuildStatics();

let tool = "select";
let brushSize = 3;
let selection = null;
let speed = 1;

const hud = new Hud({
  getState: () => state,
  getTool: () => tool,
  setTool: (id) => { tool = id; },
  getBrushSize: () => brushSize,
  setBrushSize: (n) => { brushSize = n; },
  getSelection: () => selection,
  setSelection: (sel) => { selection = sel; hud.notifySelectionChanged(); },
  setSpeed: (n) => { speed = n; },
  resetWorld: () => resetWorld(),
});

function resetWorld() {
  state = createInitialState();
  terrainMesh.setGrid(state.grid);
  entityRenderer.setGrid(state.grid);
  selection = null;
  hud.notifySelectionChanged();
  lastTickTime = performance.now();
}

// ---- input: left-click paints/selects, right-drag rotates, middle pans ----
let painting = false;
let lastCell = null;

function handlePick(event) {
  if (tool === "select") {
    let instanceId = pickInstance(app, entityRenderer.settlementMesh, event);
    if (instanceId >= 0 && instanceId < entityRenderer.settlementOrder.length) {
      selection = { type: "settlement", id: entityRenderer.settlementOrder[instanceId] };
      hud.notifySelectionChanged();
      return;
    }
    instanceId = pickInstance(app, entityRenderer.armyMesh, event);
    if (instanceId >= 0 && instanceId < entityRenderer.armyOrder.length) {
      const army = state.armies.get(entityRenderer.armyOrder[instanceId]);
      if (army) { selection = { type: "nation", id: army.nationId }; hud.notifySelectionChanged(); return; }
    }
    selection = null;
    hud.notifySelectionChanged();
    return;
  }

  const cell = pickTerrainCell(app, terrainMesh, event);
  if (!cell) return;
  if (lastCell && lastCell.x === cell.x && lastCell.z === cell.z) return;
  lastCell = cell;
  applyTool(state, tool, cell.x, cell.z, brushSize);
}

canvas.addEventListener("mousedown", (e) => {
  if (e.button !== 0) return;
  painting = true;
  lastCell = null;
  handlePick(e);
});
window.addEventListener("mouseup", () => { painting = false; lastCell = null; });
canvas.addEventListener("mousemove", (e) => {
  if (!painting) return;
  if (tool !== "select" && !CONTINUOUS_TOOLS.has(tool)) return;
  handlePick(e);
});
canvas.addEventListener("contextmenu", (e) => e.preventDefault());

// ---- simulation + render loop ----
let lastTickTime = performance.now();

function maybeTick(now) {
  if (speed <= 0) { lastTickTime = now; return; }
  const interval = SIM.TICK_MS / speed;
  let iterations = 0;
  while (now - lastTickTime > interval && iterations < 8) {
    simulationTick(state);
    if (state.tick % 20 === 0) entityRenderer.rebuildStatics();
    lastTickTime += interval;
    iterations++;
  }
  if (iterations >= 8) lastTickTime = now;
}

function updateSelectionRing() {
  if (!selection || selection.type !== "settlement") { entityRenderer.setSelection(0, 0, 0, false); return; }
  const s = state.settlements.get(selection.id);
  if (!s) { entityRenderer.setSelection(0, 0, 0, false); return; }
  const h = state.grid.height[state.grid.idx(s.x, s.z)];
  entityRenderer.setSelection(s.x + 0.5, s.z + 0.5, h, true);
}

function frame(now) {
  maybeTick(now);
  const interval = speed > 0 ? SIM.TICK_MS / speed : SIM.TICK_MS;
  const alpha = speed > 0 ? Math.min(1, (now - lastTickTime) / interval) : 1;

  terrainMesh.flushDirty();
  entityRenderer.update(state, alpha);
  updateSelectionRing();
  app.render();
  hud.tick(now);

  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

// lightweight debug/testing hook
window.__game = { getState: () => state, app, entityRenderer, terrainMesh, THREE };
