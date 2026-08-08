import * as THREE from "three";

const raycaster = new THREE.Raycaster();
const ndc = new THREE.Vector2();

function setNdcFromEvent(event, canvas) {
  const rect = canvas.getBoundingClientRect();
  ndc.x = ((event.clientX - rect.left) / rect.width) * 2 - 1;
  ndc.y = -((event.clientY - rect.top) / rect.height) * 2 + 1;
}

// Raycasts against the real (height-displaced) terrain mesh so painting
// tools follow the actual ground rather than a flat plane.
export function pickTerrainCell(app, terrainMesh, event) {
  setNdcFromEvent(event, app.canvas);
  raycaster.setFromCamera(ndc, app.camera);
  const hits = raycaster.intersectObject(terrainMesh.mesh, false);
  if (hits.length === 0) return null;
  const p = hits[0].point;
  const x = Math.floor(p.x), z = Math.floor(p.z);
  if (!terrainMesh.grid.inBounds(x, z)) return null;
  return { x, z, point: p };
}

// Raycasts an instanced mesh and returns the picked instance index (or -1).
export function pickInstance(app, mesh, event) {
  if (!mesh || mesh.count === 0) return -1;
  setNdcFromEvent(event, app.canvas);
  raycaster.setFromCamera(ndc, app.camera);
  const hits = raycaster.intersectObject(mesh, false);
  if (hits.length === 0) return -1;
  return hits[0].instanceId ?? -1;
}
