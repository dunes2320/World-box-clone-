import * as THREE from "three";
import { TERRAIN } from "../config.js";

const BIOME_COLOR = {
  [TERRAIN.WATER]: new THREE.Color(0x2a4f7a),
  [TERRAIN.SAND]: new THREE.Color(0xd9c58a),
  [TERRAIN.GRASS]: new THREE.Color(0x4f9a44),
  [TERRAIN.DIRT]: new THREE.Color(0x7a5b3a),
  [TERRAIN.STONE]: new THREE.Color(0x8b8f96),
};

const FIRE_TINT = new THREE.Color(0xff7a1a);
const WATER_LEVEL = -1.0;

// Builds/updates one continuous mesh for the whole terrain: a (cols+1)x(rows+1)
// vertex grid, height-displaced per cell, vertex-colored by biome + territory
// tint. A separate transparent plane represents the sea.
export class TerrainMesh {
  constructor(grid, nationColorLookup) {
    this.grid = grid;
    this.nationColorLookup = nationColorLookup; // (nationId) => THREE.Color | null
    const cols = grid.cols, rows = grid.rows;
    const vCols = cols + 1, vRows = rows + 1;

    const positions = new Float32Array(vCols * vRows * 3);
    const colors = new Float32Array(vCols * vRows * 3);
    const indices = [];

    for (let gy = 0; gy < vRows; gy++) {
      for (let gx = 0; gx < vCols; gx++) {
        const v = gy * vCols + gx;
        positions[v * 3 + 0] = gx;
        positions[v * 3 + 1] = 0;
        positions[v * 3 + 2] = gy;
      }
    }
    for (let y = 0; y < rows; y++) {
      for (let x = 0; x < cols; x++) {
        const a = y * vCols + x;
        const b = a + 1;
        const c = a + vCols;
        const d = c + 1;
        indices.push(a, c, b, b, c, d);
      }
    }

    const geo = new THREE.BufferGeometry();
    geo.setAttribute("position", new THREE.BufferAttribute(positions, 3));
    geo.setAttribute("color", new THREE.BufferAttribute(colors, 3));
    geo.setIndex(indices);
    this.geometry = geo;
    this.vCols = vCols;
    this.vRows = vRows;

    this.material = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 1, metalness: 0 });
    this.mesh = new THREE.Mesh(geo, this.material);
    this.mesh.receiveShadow = false;

    const waterGeo = new THREE.PlaneGeometry(cols + 6, rows + 6, 1, 1);
    waterGeo.rotateX(-Math.PI / 2);
    waterGeo.translate(cols / 2, WATER_LEVEL, rows / 2);
    this.waterMesh = new THREE.Mesh(
      waterGeo,
      new THREE.MeshStandardMaterial({ color: 0x2f6fb0, transparent: true, opacity: 0.78, roughness: 0.25 })
    );

    this._tmpColor = new THREE.Color();
    this._fullRebuild();
  }

  _cellColorInto(out, ix, iy) {
    const grid = this.grid;
    const i = grid.idx(ix, iy);
    out.copy(BIOME_COLOR[grid.terrain[i]]);
    if (grid.burning[i]) {
      out.lerp(FIRE_TINT, 0.55);
    }
    const owner = grid.ownerNation[i];
    if (owner >= 0 && this.nationColorLookup) {
      const nc = this.nationColorLookup(owner);
      if (nc) out.lerp(nc, 0.22);
    }
  }

  _heightAt(ix, iy) {
    const grid = this.grid;
    const i = grid.idx(ix, iy);
    let h = grid.height[i];
    if (grid.terrain[i] === TERRAIN.WATER) h = Math.min(h, WATER_LEVEL - 0.15);
    return h;
  }

  _updateVertex(gx, gy) {
    const cols = this.grid.cols, rows = this.grid.rows;
    const cx = Math.min(gx, cols - 1);
    const cy = Math.min(gy, rows - 1);
    const v = gy * this.vCols + gx;
    const pos = this.geometry.attributes.position;
    const col = this.geometry.attributes.color;
    pos.array[v * 3 + 1] = this._heightAt(cx, cy);
    this._cellColorInto(this._tmpColor, cx, cy);
    col.array[v * 3 + 0] = this._tmpColor.r;
    col.array[v * 3 + 1] = this._tmpColor.g;
    col.array[v * 3 + 2] = this._tmpColor.b;
  }

  _fullRebuild() {
    for (let gy = 0; gy < this.vRows; gy++) {
      for (let gx = 0; gx < this.vCols; gx++) this._updateVertex(gx, gy);
    }
    this.geometry.attributes.position.needsUpdate = true;
    this.geometry.attributes.color.needsUpdate = true;
    this.geometry.computeVertexNormals();
  }

  setGrid(grid) {
    this.grid = grid;
    this._fullRebuild();
  }

  // Call once per frame (cheap no-op when nothing changed) after sim ticks
  // have populated grid.dirty with touched cell indices.
  flushDirty() {
    const dirty = this.grid.dirty;
    if (dirty.size === 0) return;
    const cols = this.grid.cols;
    const touched = new Set();
    dirty.forEach((i) => {
      const x = i % cols, y = (i / cols) | 0;
      // update the up-to-4 vertex corners that reference this cell
      touched.add(y * this.vCols + x);
      touched.add(y * this.vCols + x + 1);
      touched.add((y + 1) * this.vCols + x);
      touched.add((y + 1) * this.vCols + x + 1);
    });
    touched.forEach((v) => {
      const gx = v % this.vCols, gy = (v / this.vCols) | 0;
      this._updateVertex(gx, gy);
    });
    this.geometry.attributes.position.needsUpdate = true;
    this.geometry.attributes.color.needsUpdate = true;
    this.geometry.computeVertexNormals();
    dirty.clear();
  }
}
