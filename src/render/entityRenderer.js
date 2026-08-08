import * as THREE from "three";
import { SIM, RESOURCE, UNDEAD_NATION_ID } from "../config.js";

const ZOMBIE_COLOR = new THREE.Color(0x5a5a6e);
const DEPOSIT_COLORS = {
  [RESOURCE.STONE_DEPOSIT]: new THREE.Color(0x9aa0a8),
  [RESOURCE.IRON_DEPOSIT]: new THREE.Color(0xb06a4a),
  [RESOURCE.GOLD_DEPOSIT]: new THREE.Color(0xe6c53f),
};
const TREE_COLOR = new THREE.Color(0x235c28);
const FIRE_COLORS = [new THREE.Color(0xff5a1a), new THREE.Color(0xffab2e), new THREE.Color(0xff2e2e)];

const TREE_CAP = 2600;
const DEPOSIT_CAP = 900;
const SETTLEMENT_CAP = 48;
const ARMY_CAP = 96;
const FIRE_CAP = 600;

const dummy = new THREE.Object3D();
const color = new THREE.Color();

function makeInstanced(geo, mat, cap, scene) {
  const mesh = new THREE.InstancedMesh(geo, mat, cap);
  mesh.count = 0;
  mesh.frustumCulled = false;
  mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
  scene.add(mesh);
  return mesh;
}

export class EntityRenderer {
  constructor(scene, grid, nationColorLookup) {
    this.scene = scene;
    this.grid = grid;
    this.nationColorLookup = nationColorLookup;

    this.humanMesh = makeInstanced(
      new THREE.CapsuleGeometry(0.13, 0.22, 2, 5),
      new THREE.MeshStandardMaterial({ roughness: 0.85 }),
      SIM.MAX_HUMANS, scene
    );
    this.treeMesh = makeInstanced(
      new THREE.ConeGeometry(0.38, 0.95, 6),
      new THREE.MeshStandardMaterial({ color: TREE_COLOR, roughness: 1 }),
      TREE_CAP, scene
    );
    this.depositMesh = makeInstanced(
      new THREE.IcosahedronGeometry(0.32, 0),
      new THREE.MeshStandardMaterial({ roughness: 0.5, metalness: 0.2 }),
      DEPOSIT_CAP, scene
    );
    this.settlementMesh = makeInstanced(
      new THREE.CylinderGeometry(0, 0.55, 1.0, 4),
      new THREE.MeshStandardMaterial({ roughness: 0.7 }),
      SETTLEMENT_CAP, scene
    );
    this.armyMesh = makeInstanced(
      new THREE.OctahedronGeometry(0.32, 0),
      new THREE.MeshStandardMaterial({ roughness: 0.4, metalness: 0.3, emissive: 0x220000 }),
      ARMY_CAP, scene
    );
    this.fireMesh = makeInstanced(
      new THREE.ConeGeometry(0.3, 0.55, 4),
      new THREE.MeshStandardMaterial({ emissive: 0xff3300, emissiveIntensity: 1.2, color: 0xff5500 }),
      FIRE_CAP, scene
    );

    this.settlementOrder = [];
    this.armyOrder = [];

    this.monsterMesh = new THREE.Mesh(
      new THREE.DodecahedronGeometry(1.6, 0),
      new THREE.MeshStandardMaterial({ color: 0x4a1030, roughness: 0.6, emissive: 0x220015 })
    );
    this.monsterMesh.visible = false;
    scene.add(this.monsterMesh);

    this.tornadoMeshes = new Map();

    const ringGeo = new THREE.RingGeometry(0.7, 0.85, 24);
    ringGeo.rotateX(-Math.PI / 2);
    this.selectionRing = new THREE.Mesh(ringGeo, new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.85, side: THREE.DoubleSide }));
    this.selectionRing.visible = false;
    scene.add(this.selectionRing);
  }

  setGrid(grid) {
    this.grid = grid;
    for (const mesh of this.tornadoMeshes.values()) this.scene.remove(mesh);
    this.tornadoMeshes.clear();
    this.monsterMesh.visible = false;
    this.rebuildStatics();
  }

  setSelection(x, z, height, visible) {
    this.selectionRing.visible = visible;
    if (visible) this.selectionRing.position.set(x, height + 0.05, z);
  }

  // Trees / resource deposits barely move; rebuild their instance lists on a
  // slow cadence instead of scanning the whole grid every frame.
  rebuildStatics() {
    const grid = this.grid;
    let treeI = 0, depI = 0;
    for (let y = 0; y < grid.rows && (treeI < TREE_CAP || depI < DEPOSIT_CAP); y++) {
      for (let x = 0; x < grid.cols; x++) {
        const i = grid.idx(x, y);
        const res = grid.resource[i];
        if (res === RESOURCE.FOREST && treeI < TREE_CAP) {
          const scale = 0.6 + Math.min(1, grid.resourceAmount[i] / 48) * 0.6;
          dummy.position.set(x + 0.5, grid.height[i] + 0.42 * scale, y + 0.5);
          dummy.rotation.set(0, (x * 7 + y * 13) % 6.28, 0);
          dummy.scale.set(scale, scale, scale);
          dummy.updateMatrix();
          this.treeMesh.setMatrixAt(treeI++, dummy.matrix);
        } else if (res !== RESOURCE.NONE && res !== RESOURCE.FOREST && depI < DEPOSIT_CAP) {
          dummy.position.set(x + 0.5, grid.height[i] + 0.22, y + 0.5);
          dummy.rotation.set(0.3, (x * 3 + y * 5) % 6.28, 0.2);
          dummy.scale.set(1, 1, 1);
          dummy.updateMatrix();
          this.depositMesh.setMatrixAt(depI++, dummy.matrix);
          this.depositMesh.setColorAt(depI - 1, DEPOSIT_COLORS[res] || color.setHex(0xffffff));
        }
      }
    }
    this.treeMesh.count = treeI;
    this.treeMesh.instanceMatrix.needsUpdate = true;
    this.depositMesh.count = depI;
    this.depositMesh.instanceMatrix.needsUpdate = true;
    if (this.depositMesh.instanceColor) this.depositMesh.instanceColor.needsUpdate = true;
  }

  _nationColor(nationId) {
    if (nationId === UNDEAD_NATION_ID) return ZOMBIE_COLOR;
    const c = this.nationColorLookup(nationId);
    return c || color.setHex(0xaaaaaa);
  }

  update(state, alpha) {
    this._updateHumans(state, alpha);
    this._updateSettlements(state);
    this._updateArmies(state, alpha);
    this._updateFire(state);
    this._updateMonster(state, alpha);
    this._updateTornadoes(state);
  }

  _updateHumans(state, alpha) {
    const humans = state.humans;
    const n = Math.min(humans.length, SIM.MAX_HUMANS);
    for (let i = 0; i < n; i++) {
      const h = humans[i];
      const x = h.prevX + (h.x - h.prevX) * alpha;
      const z = h.prevZ + (h.z - h.prevZ) * alpha;
      const gi = state.grid.idx(Math.max(0, Math.min(state.grid.cols - 1, Math.floor(x))), Math.max(0, Math.min(state.grid.rows - 1, Math.floor(z))));
      const y = state.grid.height[gi] + 0.28;
      dummy.position.set(x, y, z);
      dummy.rotation.set(0, Math.atan2(h.x - h.prevX, h.z - h.prevZ) || 0, 0);
      dummy.scale.set(1, 1, 1);
      dummy.updateMatrix();
      this.humanMesh.setMatrixAt(i, dummy.matrix);
      this.humanMesh.setColorAt(i, this._nationColor(h.nationId));
    }
    this.humanMesh.count = n;
    this.humanMesh.instanceMatrix.needsUpdate = true;
    if (this.humanMesh.instanceColor) this.humanMesh.instanceColor.needsUpdate = true;
  }

  _updateSettlements(state) {
    const order = [];
    let i = 0;
    for (const s of state.settlements.values()) {
      if (i >= SETTLEMENT_CAP) break;
      const gi = state.grid.idx(s.x, s.z);
      const h = state.grid.height[gi];
      const scale = 0.55 + Math.sqrt(Math.max(1, s.populationCount)) * 0.13;
      dummy.position.set(s.x + 0.5, h + scale * 0.5, s.z + 0.5);
      dummy.rotation.set(0, 0, 0);
      dummy.scale.set(scale, scale, scale);
      dummy.updateMatrix();
      this.settlementMesh.setMatrixAt(i, dummy.matrix);
      const nation = state.nations.get(s.nationId);
      this.settlementMesh.setColorAt(i, nation ? color.setHex(nation.color) : color.setHex(0x999999));
      order.push(s.id);
      i++;
    }
    this.settlementMesh.count = i;
    this.settlementMesh.instanceMatrix.needsUpdate = true;
    if (this.settlementMesh.instanceColor) this.settlementMesh.instanceColor.needsUpdate = true;
    this.settlementOrder = order;
  }

  _updateArmies(state, alpha) {
    const order = [];
    let i = 0;
    for (const a of state.armies.values()) {
      if (i >= ARMY_CAP || a.dead) continue;
      const x = a.prevX + (a.x - a.prevX) * alpha;
      const z = a.prevZ + (a.z - a.prevZ) * alpha;
      const gi = state.grid.idx(Math.max(0, Math.min(state.grid.cols - 1, Math.floor(x))), Math.max(0, Math.min(state.grid.rows - 1, Math.floor(z))));
      const h = state.grid.height[gi];
      const scale = 0.5 + Math.min(1.4, (a.strength || 1) / 40);
      dummy.position.set(x, h + 0.9, z);
      dummy.rotation.set(0.4, state.tick * 0.05, 0);
      dummy.scale.set(scale, scale, scale);
      dummy.updateMatrix();
      this.armyMesh.setMatrixAt(i, dummy.matrix);
      const nation = state.nations.get(a.nationId);
      this.armyMesh.setColorAt(i, nation ? color.setHex(nation.color) : color.setHex(0xffffff));
      order.push(a.id);
      i++;
    }
    this.armyMesh.count = i;
    this.armyMesh.instanceMatrix.needsUpdate = true;
    if (this.armyMesh.instanceColor) this.armyMesh.instanceColor.needsUpdate = true;
    this.armyOrder = order;
  }

  _updateFire(state) {
    const grid = state.grid;
    let i = 0;
    for (let c = 0; c < grid.cols * grid.rows && i < FIRE_CAP; c++) {
      if (!grid.burning[c]) continue;
      const x = c % grid.cols, y = (c / grid.cols) | 0;
      const flick = 0.75 + Math.random() * 0.5;
      dummy.position.set(x + 0.5, grid.height[c] + 0.3 * flick, y + 0.5);
      dummy.rotation.set(0, Math.random() * 6.28, 0);
      dummy.scale.set(flick, flick * (0.8 + Math.random() * 0.5), flick);
      dummy.updateMatrix();
      this.fireMesh.setMatrixAt(i, dummy.matrix);
      this.fireMesh.setColorAt(i, FIRE_COLORS[(Math.random() * FIRE_COLORS.length) | 0]);
      i++;
    }
    this.fireMesh.count = i;
    this.fireMesh.instanceMatrix.needsUpdate = true;
    if (this.fireMesh.instanceColor) this.fireMesh.instanceColor.needsUpdate = true;
  }

  _updateMonster(state, alpha) {
    const m = state.monster;
    if (!m) { this.monsterMesh.visible = false; return; }
    this.monsterMesh.visible = true;
    const gi = state.grid.idx(Math.max(0, Math.min(state.grid.cols - 1, Math.floor(m.x))), Math.max(0, Math.min(state.grid.rows - 1, Math.floor(m.z))));
    const h = state.grid.height[gi];
    const scale = 0.7 + (m.hp / m.maxHp) * 0.6;
    this.monsterMesh.position.set(m.x, h + 1.6 * scale, m.z);
    this.monsterMesh.scale.set(scale, scale, scale);
    this.monsterMesh.rotation.y += 0.02;
  }

  _updateTornadoes(state) {
    const active = new Set(state.tornadoes.map((t) => t));
    for (const t of state.tornadoes) {
      let mesh = this.tornadoMeshes.get(t);
      if (!mesh) {
        mesh = new THREE.Mesh(
          new THREE.ConeGeometry(1.1, 3.4, 10, 1, true),
          new THREE.MeshStandardMaterial({ color: 0xbfc4cc, transparent: true, opacity: 0.55, side: THREE.DoubleSide })
        );
        this.scene.add(mesh);
        this.tornadoMeshes.set(t, mesh);
      }
      const gi = state.grid.idx(Math.max(0, Math.min(state.grid.cols - 1, Math.floor(t.x))), Math.max(0, Math.min(state.grid.rows - 1, Math.floor(t.z))));
      const h = state.grid.height[gi];
      mesh.position.set(t.x, h + 1.7, t.z);
      mesh.rotation.y += 0.35;
    }
    for (const [t, mesh] of [...this.tornadoMeshes.entries()]) {
      if (!active.has(t)) { this.scene.remove(mesh); this.tornadoMeshes.delete(t); }
    }
  }
}
