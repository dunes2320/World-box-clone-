# PLAN.md — 3D god-simulator sandbox

A desktop 3D god-sim in the spirit of WorldBox: look down on a living
world, watch civilizations grow on their own, intervene with terraform
tools and disasters.

Nothing has been deleted or written yet. This document is for review
first; the teardown in Phase 1 is the first thing that happens after
you approve it.

---

## Decisions from the opening questions

| Question | Answer | Consequence |
|---|---|---|
| World start | **Empty until you spawn** | Terrain-only on load. The spawn tool is the entry point to the whole sim, so it lands in Phase 3 and the world is deliberately lifeless before then. |
| Species hostility | **Per-pair relations that drift** | A 4×4 relation matrix (6 live pairs) that drifts each tick and crosses war/peace thresholds. Needs a relations model in sim and a readout in the stats panel. |
| Old Maven CI | **Delete** | `.github/workflows/build-installers.yml` goes with the rest. No CI until/unless you want one. |
| Unit look | **Small 2–3 box figure** | Body + head, species-colored. ~36 tris each — 2000 units is ~72k tris in one mesh, comfortably inside budget. |

---

## What gets deleted (Phase 1, step 1)

Everything below is removed. All of it stays recoverable from git
history at commit `a0682c8` on this branch — nothing is lost, it just
stops being checked out.

```
src/                                  57 tracked files, the whole jME/Maven game
pom.xml
dependency-reduced-pom.xml
.github/workflows/build-installers.yml
target/                               (untracked build output)
```

Kept: `.git`, `.gitignore` (rewritten for Gradle — it currently has
Maven-only entries), `.claude/`.

Note: **there is no `README.md`** in this repo — nothing tracked and
nothing on disk. One gets written as part of Phase 1 rather than kept.

---

## Stack

- **Java 21**, **Gradle with Kotlin DSL**, wrapper committed
- **libGDX 1.13.x, LWJGL3 backend**, 3D API only: `ModelBatch`,
  hand-built `Mesh`, `PerspectiveCamera`, `scene2d.ui` for the overlay
- **JUnit 5** for sim tests
- No other engine dependencies. Noise is hand-written, not a library.
- Runs with `./gradlew lwjgl3:run`

### Why three Gradle subprojects

The requirement "the sim package must compile and unit-test without
libGDX on the classpath" is worth *enforcing* structurally rather than
just documenting. `sim` is its own subproject that does not declare a
libGDX dependency, so a stray `import com.badlogic.gdx...` in sim is a
compile error, not a code-review catch.

```
:sim      pure Java + JUnit. No libGDX. Deterministic.
:core     depends on :sim + libGDX. Render, UI, game loop.
:lwjgl3   depends on :core. Desktop launcher, `run` task.
```

Dependencies point one way: `lwjgl3 → core → sim`. Render reads sim
state; sim never reads render. God tools are *commands into* the sim,
which is not the sim reading render.

---

## File structure

```
.
├─ settings.gradle.kts                 includes :sim :core :lwjgl3
├─ build.gradle.kts                    shared config, Java 21 toolchain
├─ gradle/wrapper/                     committed wrapper
├─ gradlew / gradlew.bat
├─ .gitignore                          rewritten: build/, .gradle/, IDE
├─ README.md                           new — what it is, how to run
├─ PLAN.md                             this file
│
├─ sim/
│  ├─ build.gradle.kts                 java-library; JUnit 5 only
│  └─ src/
│     ├─ main/java/com/game/sim/
│     │  ├─ SimConfig.java             all tunables in one place
│     │  ├─ Noise.java                 hand-written simplex, seeded
│     │  ├─ TileType.java              byte constants, 8 types
│     │  ├─ Species.java               byte constants: human/orc/elf/dwarf
│     │  ├─ World.java                 flat arrays + chunk dirty flags
│     │  ├─ WorldGen.java              height + biome from noise
│     │  ├─ Units.java                 SoA pool, int free list
│     │  ├─ Villages.java              SoA pool
│     │  ├─ Relations.java             per-pair drift matrix, war/peace
│     │  ├─ SimClock.java              fixed-step accumulator (testable)
│     │  ├─ Simulation.java            owns state, tick() orchestrator
│     │  ├─ UnitSystem.java            wander, hunger, age, reproduce, die
│     │  ├─ VillageSystem.java         settling, territory growth
│     │  ├─ CombatSystem.java          border fights
│     │  ├─ Terraform.java             brush ops on the grid
│     │  └─ Disasters.java             meteor/lightning/fire/quake/flood/plague
│     └─ test/java/com/game/sim/       determinism, pool, clock, systems
│
├─ core/
│  ├─ build.gradle.kts                 project(":sim") + gdx
│  └─ src/main/java/com/game/
│     ├─ GodGame.java                  ApplicationAdapter; loop, wiring
│     ├─ render/
│     │  ├─ RtsCamera.java             rotate/pan/zoom, clamped pitch
│     │  ├─ TilePicker.java            screen ray → tile index
│     │  ├─ TerrainPalette.java        tile → vertex color, border tint
│     │  ├─ ChunkMesh.java             one 16×16 chunk's Mesh
│     │  ├─ TerrainRenderer.java       64 chunks, rebuilds only dirty ones
│     │  ├─ UnitRenderer.java          ALL units in one rebuilt Mesh
│     │  └─ EffectRenderer.java        fire, meteor, lightning visuals
│     └─ ui/
│        ├─ ToolState.java             selected tool, radius, species
│        ├─ Hud.java                   Stage, layout, skin
│        ├─ ToolPalette.java           bottom bar
│        ├─ SpeedControls.java         pause / 1x / 2x / 5x
│        ├─ InspectorPanel.java        clicked tile: type, height, owner, units
│        └─ StatsPanel.java            population per species, relations
│
└─ lwjgl3/
   ├─ build.gradle.kts                 application plugin, `run`
   └─ src/main/java/com/game/lwjgl3/Lwjgl3Launcher.java
```

---

## Data layout

**World** — 128×128 = 16,384 tiles, flat primitive arrays, no per-tile
objects:

```java
byte[]  tileType      // deep water, shallow water, sand, grass,
                      // forest, hill, mountain, snow
float[] height
short[] ownerVillage  // -1 = unclaimed
byte[]  burn          // 0 = unlit, else remaining fire ticks
float[] fertility     // drives forest regrowth + food
boolean[] chunkDirty  // 8×8 = 64 chunks, one flag each
```

**Units** — struct-of-arrays pool with an `int[] freeList`, no
allocation in the hot loop, no per-unit object:

```java
float[] x, z          // world position
byte[]  species
byte[]  state         // wander / seek food / fight / settle
short[] health, age
byte[]  hunger
short[] homeVillage   // -1 = nomadic
boolean[] alive
int[] freeList; int freeCount; int capacity;
```

Spawning pops from the free list; death pushes back. Iteration walks
`0..highWaterMark` and skips dead — no compaction, no shuffling, so
indices stay stable for the renderer and inspector.

**Villages** — same SoA treatment: `x`, `z`, `species`, `population`,
`radius`, `alive`.

**Determinism contract:** same seed + same sequence of god-tool
commands ⇒ byte-identical world. That means, inside sim: one seeded
`java.util.Random`, no `Math.random()`, no wall-clock reads, and no
iteration over `HashMap`/`HashSet` (arrays and index loops only).
Tested, not assumed.

---

## Loop and timing

The sim ticks at a **fixed 10 ticks/sec** through an accumulator, fully
decoupled from render framerate. `SimClock` lives in sim so the
accumulation is unit-testable on its own; `GodGame` feeds it frame
deltas and runs however many ticks it reports.

Speed controls scale ticks per second: pause = 0, 1× = 10, 2× = 20,
5× = 50. The accumulator caps catch-up iterations so a slow frame
can't spiral.

---

## Terrain rendering

Each tile is drawn **blocky/stepped**, not smoothly interpolated: a
flat top quad at the tile's own height, plus vertical side quads
wherever a neighbor sits lower. That gives the low-poly stepped look,
makes flat shading trivial (every vertex of a face shares one color),
and means territory borders are just a tint applied to the top quad's
vertex color.

Terrain splits into **64 chunks of 16×16**. Each owns one `Mesh`. A
terraform edit flags only the chunks it touched (plus neighbors on a
seam) and only those rebuild.

Units are **one single mesh** rebuilt per frame from the SoA pool — not
one `ModelInstance` each. Target: 60fps at 2000 units.

---

## Build order — 6 phases

Each phase ends with `./gradlew build`, tests green, and a git commit.

### Phase 1 — Teardown + runnable terrain, camera, one tool
The first thing you can actually look at.
- Delete the old project; write `.gitignore`, `README.md`, Gradle
  wrapper and the three-subproject build
- `Noise`, `TileType`, `World`, `WorldGen` — seeded height + biomes
- `ChunkMesh` / `TerrainRenderer` — 64 chunks, vertex-colored, flat-shaded
- `RtsCamera` — left-drag rotate, right-drag/WASD pan, scroll zoom,
  clamped pitch
- `TilePicker` — screen ray → tile
- `Terraform` raise/lower with adjustable radius + dirty-chunk rebuild
- Tests: noise determinism, worldgen determinism, chunk-dirty marking
- **Done when:** `./gradlew lwjgl3:run` opens a world you can fly
  around and deform.

### Phase 2 — UI shell + the rest of terraform
- `Hud`, `ToolPalette`, `SpeedControls`, `InspectorPanel`, `ToolState`
- Remaining brushes: add water, add forest; radius control
- Click a tile to inspect: type, height, owner, unit count
- `SimClock` + accumulator wired to the speed buttons
- Tests: clock accumulation at each speed, terraform brush shapes
- **Done when:** every terraform tool is driven from the palette and
  the world can be paused and stepped.

### Phase 3 — Units: pool, spawning, rendering, life cycle
The world stops being empty.
- `Units` SoA pool + free list; `Species`
- `UnitSystem`: wander, hunger, eat, age, starve, die
- Population cap + culling
- Spawn tool (pick species + radius)
- `UnitRenderer`: all units in one batched mesh, 2–3 box figures
- `StatsPanel`: population per species
- Tests: pool alloc/free/reuse, cap enforcement, starvation, determinism
  of a 1000-tick run
- **Done when:** 2000 units hold 60fps and the population is stable
  rather than exploding or dying out.

### Phase 4 — Villages, territory, borders
- `Villages` pool; units settle and found villages
- `VillageSystem`: territory claim that grows over time into
  `ownerVillage`
- Border rendering as a colored tint on claimed tiles
- Reproduction tied to village food/space
- Inspector shows owning village
- Tests: settling rules, territory growth bounds, no double-claim
- **Done when:** civilizations visibly appear and spread on their own.

### Phase 5 — Species relations and war
- `Relations`: 4×4 pair matrix, drift per tick, war/peace thresholds
- `CombatSystem`: adjacent hostile territory triggers fights; casualties
- Relations readout in the stats panel
- Tests: drift is bounded and symmetric, war triggers at the threshold,
  combat conserves the population it should
- **Done when:** wars start, run, and end on their own, and you can
  read why.

### Phase 6 — Disasters + polish
- `Disasters`: meteor, lightning, fire (spreads through forest, burns
  out), earthquake, flood, plague
- Disaster section in the palette; `EffectRenderer` visuals
- Final perf pass at 2000 units, README finished
- Tests: fire spreads and terminates, plague decays, quake reshapes
  terrain deterministically
- **Done when:** every tool in the brief works and the game holds
  frame rate under a full-map fire.

---

## Explicitly out of scope

Not in the brief, so not built unless you ask: save/load, sound, main
menu or world-setup screen, textures (vertex colors only),
multiplayer, mod support, installers/packaging.
