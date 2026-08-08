import * as THREE from "three";
import { OrbitControls } from "three/examples/jsm/controls/OrbitControls.js";
import { GRID } from "../config.js";

// Owns the renderer/camera/controls/lights. Nothing game-specific lives here.
export class ThreeApp {
  constructor(canvas) {
    this.canvas = canvas;
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: true, powerPreference: "high-performance" });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;

    this.scene = new THREE.Scene();
    this.scene.background = new THREE.Color(0x8fc7e8);
    this.scene.fog = new THREE.Fog(0x8fc7e8, 60, 145);

    const center = new THREE.Vector3(GRID.COLS / 2, 0, GRID.ROWS / 2);

    this.camera = new THREE.PerspectiveCamera(50, 1, 0.1, 500);
    this.camera.position.set(center.x - 30, 42, center.z + 46);
    this.camera.lookAt(center);

    this.controls = new OrbitControls(this.camera, canvas);
    this.controls.target.copy(center);
    this.controls.maxPolarAngle = Math.PI * 0.49;
    this.controls.minDistance = 8;
    this.controls.maxDistance = 130;
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.08;
    this.controls.update();

    this._buildLights();
    window.addEventListener("resize", () => this.resize());
    this.resize();
  }

  _buildLights() {
    const hemi = new THREE.HemisphereLight(0xbfe0ff, 0x35402a, 0.9);
    this.scene.add(hemi);

    const sun = new THREE.DirectionalLight(0xfff3d6, 1.4);
    sun.position.set(GRID.COLS * 0.3, 60, GRID.ROWS * 0.6);
    sun.target.position.set(GRID.COLS / 2, 0, GRID.ROWS / 2);
    this.scene.add(sun);
    this.scene.add(sun.target);
    this.sun = sun;

    const fill = new THREE.AmbientLight(0x445066, 0.35);
    this.scene.add(fill);
  }

  resize() {
    const parent = this.canvas.parentElement;
    const w = parent.clientWidth, h = parent.clientHeight;
    this.renderer.setSize(w, h, false);
    this.camera.aspect = w / Math.max(1, h);
    this.camera.updateProjectionMatrix();
  }

  render() {
    this.controls.update();
    this.renderer.render(this.scene, this.camera);
  }
}
