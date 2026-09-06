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

---

# PLAN v2 — scale-up + depth

Phases 1-6 are done: the game runs, the six god tools work, wars start
and end on their own. The follow-up brief keeps everything as-is and
adds two things: a much bigger world, and real depth on top of it.
Nothing is being rewritten. This section is for review before any code
is touched.

## Decisions from the second round of questions

| Question | Answer | Consequence |
|---|---|---|
| Kingdoms vs. species relations | **Both layers; species seed kingdom relations** | Kingdoms own the live relations. `Species.affinity(a,b)` is a starting bias for a new kingdom pair and a small drift bias each pass, so orc-elf kingdoms *tend* toward hostility but can ally. The 4x4 species matrix stays for the readout and for seeding, no longer decides war on its own. |
| Ambient combat vs. armies | **Armies replace ambient** | `CombatSystem` becomes army-only: fighting happens where armies are, not wherever enemies meet. Cheaper per tick and war reads as a discrete event. Border friction still exists in the diplomacy pass - it just drives the *decision* to raise an army, not damage on the ground. |
| RNG and save round-trip | **Keep `Random`; save seed + replay from genesis** | Existing seeds stay valid. `SaveManager` writes seed + tickCount + the god-tool command log. Load reseeds and re-runs. That means every god-tool call has to be recorded, which is what the event log needs to be anyway. |
| Perf verification | **I measure CPU budgets, you confirm real fps** | F3 overlay + a `--bench` mode that prints tick time, geometry rebuild ms, chunk rebuilds/frame, draw calls, retained memory. I hold those inside stated per-frame budgets. Real fps at 60 you confirm on hardware I can't run on. |

## Perf and pathing approach — this is where the risk lives

The 128->384 (or 512) jump multiplies tile count by 9-16x and unit
count by 4x. A lot of what worked at 128 will not survive. Here is
what changes and why.

### Chunk size and LOD

Chunk size goes from **16 to 32**. At 384x384 that is 144 chunks
(12x12); at 512 it is 256 chunks (16x16). Smaller chunks would give
more of them than the CPU can iterate cheaply; larger chunks would
make a single terraform edit rebuild too much geometry at once.

Two changes that pay for themselves at this scale:

- **Frustum culling per chunk.** Chunk AABBs are cheap to test against
  the camera. Off-screen chunks stop drawing entirely. At 512, an
  overhead view sees maybe a third of them.
- **Distant LOD.** Chunks past a distance threshold render as a
  4-to-1 merged mesh (one quad per 2x2 tile group). Same colour
  choice as the fine mesh, so no visual seam - the LOD boundary is
  literally where the resolution steps. Rebuilt lazily and cached.

### Sub-tile features

A village growing from 3 tiles to 20-40 means a house can no longer
*be* a tile. Instead, each tile carries a small list of **features**
with a local `(dx, dz)` offset inside the tile. Farms, houses,
plazas, roads, and props all live here. A tile with no features is
one byte in the feature-count array; a tile with features
indexes into a shared feature pool sized from the world, no per-tile
`ArrayList`.

Rendered by a new `StructureRenderer`, one batched mesh per feature
kind, so it stays flat-arrays-and-one-draw-call the way units are.

### Hierarchical pathfinding

Per-unit A* over 512x512 is a non-starter. Instead:

- **Portal graph** built from the chunk grid: each chunk exposes
  portals on its four sides where a walkable tile meets a walkable
  tile in the neighbouring chunk. Chunks connect chunks; portals
  connect chunks to chunks. That's a graph of a few hundred nodes,
  A* over it is negligible.
- **Flow field inside a chunk** for the last leg to the destination
  tile, computed once and shared by any unit heading to that
  destination in that chunk.
- **Path cache keyed by `(originVillage, destination)`** not by unit,
  so a caravan and a farmer walking the same road share one path
  and one flow field. Invalidated on terraform, war, or a road
  edit.

Villages that never send units very far never trigger any of this;
the cost scales with movement.

### Staggered updates

Units update on `id % N` per tick. N depends on what the tick is
doing:

- Ageing, hunger, breeding: staggered N=6, so each unit thinks
  ~1.6 times/sec at 10 tps. Nobody notices hunger a tenth of a
  second late.
- Movement: still every tick, so animation stays smooth.
- Combat (once armies are in): every tick, but only for units in
  an army - `Units.army` is checked first.

That drops the sim cost of 8000 units to roughly what 2000 costs
today for the once-per-6-ticks systems.

### Region aggregate

Anything that doesn't need per-unit granularity moves to
`RegionGrid` (one entry per 32-tile chunk): total population,
per-species population, food surplus, dominant kingdom. Diplomacy
and economy read the aggregate, not the pool. Rebuilt lazily on the
tick they're consumed on.

### Memory budget

Flat arrays sized from `worldSize` at construction, one allocation
each. At 512x512:

| Array | Size |
|---|---|
| `byte tileType`, `byte burn`, `byte biomeAffinity` | 256 KB each |
| `float height`, `float fertility` | 1 MB each |
| `short ownerVillage`, `short featureFirst`, `short kingdom` | 512 KB each |

Units at 8000: SoA is ~20 fields x 8000 x average 4 bytes = ~640
KB. Comfortably under 20 MB for all sim state at 512x512.

## Files added, files unchanged

Everything in the phase 1-6 tree stays. New files:

```
sim/
  RegionGrid.java          per-chunk aggregate for cheap sim queries
  Pathfinder.java          portal graph + chunk flow fields, cached
  Features.java            SoA feature pool: kind, tile, dx, dz, owner
  Buildings.java           blueprint table + placement rules
  Roads.java               road bit per tile, road-aware move cost
  Economy.java             stockpile per village, jobs, production
  Trade.java               caravan units on the road network
  Kingdoms.java            kingdom pool + relations, seeded by species
  Armies.java              army orders: march, siege, capture
  UnitLore.java            names, traits, skills, family lineage
  History.java             append-only event log with tick + subject
  Culture.java             per-kingdom knowledge + era
  Religion.java            faith spread along roads
  Wildlife.java            neutral animal SoA pool
  Powers.java              new god powers dispatched via disasters
  SaveManager.java         seed + tick + command log + event log

core/
  render/
    ChunkLod.java          merged 2x2 mesh per far chunk
    StructureRenderer.java one mesh per feature kind
    RoadRenderer.java      road ribbon mesh
    ArmyRenderer.java      army banner + count indicator
    WildlifeRenderer.java  animals as unit-like boxes
    OverlayRenderer.java   F3 debug overlay
  ui/
    HistoryPanel.java      scrollable event log
    KingdomPanel.java      inspector for a clicked kingdom
    LineagePanel.java      family tree for a clicked unit
    WorldSetup.java        Small / Medium / Large picker on new world
```

## Determinism, save/load, and the god-tool log

Every god-tool call (`spawnUnits`, `strike`, `Terraform.raise`,
etc.) now goes through a `GodCommand` record that gets appended to
`Simulation.commandLog`. `tick()` reads any commands whose scheduled
tick equals the current one and dispatches them before advancing.
That gives us three things at once:

- Save = `{ seed, currentTick, commands }`. Load re-runs.
- The event log gets each command entered as a "you did X" line for
  free.
- Determinism is provable by test: replay from tick 0, compare state
  to the pre-save state, must be bit-identical.

Because we're keeping `java.util.Random`, replay-on-load can be slow
on very old worlds. Two mitigations: on save, we snapshot every
20,000 ticks to disk alongside the log, and load resumes from the
nearest snapshot. A snapshot is not the deterministic source of
truth; the log is. Snapshots exist purely to skip work.

## Phase order

Each phase ends with `./gradlew build`, tests green, a commit, and a
runnable game where the new system is visible. **Phase 7 ships alone**
so you can run and confirm the scale-up before I add anything on top.

### Phase 7 - Scale-up + perf infrastructure
- World size becomes a parameter; default 384, `--size 256|384|512`
- Chunk size 32; array sizes derive from world size
- Frustum culling per chunk + distant LOD merged meshes
- `RegionGrid` aggregate, populated from the existing SoA pools
- Staggered unit updates via `id % N` on ageing/hunger/breeding
- Sub-tile `Features` pool wired up but empty of content
- `Pathfinder` implemented and unit-tested; no callers yet
- `OverlayRenderer` (F3 toggle): fps, tick ms (last 60 frames avg
  and max), unit count, chunk rebuilds/frame, draw calls, MB
  retained
- `--bench` CLI flag: fixed camera path, 30 seconds, prints those
  same numbers as CSV so regressions are catchable
- **Done when:** at 512x512 with 8000 units, my numbers fit the
  stated budgets and you confirm fps

### Phase 8 - Buildings and Roads
- `Buildings`: house / farm / barracks / dock / temple / market /
  wall / mine / lumber camp, with placement rules
- Village placement pass every N ticks decides what to build
- `Roads` bit-per-tile, generated by hierarchical pathfinder
  between village centre and outlying buildings, and between allied
  villages
- `Pathfinder` gets a road-aware cost function; units on a road
  move faster
- `StructureRenderer` + `RoadRenderer`
- Tests: placement rules (no farms on stone, docks touch water),
  road generation determinism, movement speed on/off road
- **Done when:** villages visibly build things and roads connect
  them

### Phase 9 - Economy and Growth
- Per-village stockpiles: food, wood, stone, gold
- Jobs assigned to units (`Units.job`): farmer, woodcutter, miner,
  builder, soldier, trader, none
- Buildings cost resources, take build time
- Village growth rate scales with food surplus; starvation shrinks
- `Trade`: caravan units spawned on roads carrying goods between
  friendly villages
- New inspector rows for a village: stockpiles, jobs breakdown
- Tests: production balances (starvation reachable, boom reachable),
  trade caravan round trip
- **Done when:** villages visibly prosper or wither on their own

### Phase 10 - Kingdoms, armies, and diplomacy
- `Kingdoms` pool: name, flag colour, capital, member villages
- Each village belongs to a kingdom; new villages join their species'
  nearest kingdom or found a new one if isolated
- Kingdom relations seeded from `Species.affinity(a,b)` plus drift
  from border friction, trade volume, and shared enemies
- States: peace, alliance, truce, war (hysteresis as with species)
- **Armies replace ambient combat**: `Armies` are ordered groups
  drawn from a village's population. `CombatSystem` rewritten to
  fight only where armies are
- Armies march (hierarchical path), siege villages, capture on
  siege success -> ownership transfers, buildings damaged
- Kingdoms split when a distant village rebels (distance from
  capital > threshold and relation with capital < threshold)
- `KingdomPanel` on click
- Tests: kingdom seeding, army order state machine, siege capture,
  rebellion split, hysteresis
- **Done when:** kingdoms rise, fight, absorb each other, and
  fracture, visibly

### Phase 11 - Unit depth (named, traits, families)
- `UnitLore`: name, traits (brave/greedy/sickly/strong/fertile),
  skills that grow with work, deed count
- Family lines: `parents[2]`, `children` compact list, inheritance
  of traits and (for kings) titles
- King and general as roles with distinct stats effects
- `LineagePanel` on click, `InspectorPanel` shows name and traits
- Traits actually do things: brave units join armies willingly,
  greedy traders skim, sickly units catch plague faster
- Tests: family tree consistency after 5,000 ticks, trait
  distribution stays bounded, no orphan lineages
- **Done when:** clicking any unit tells you a real story

### Phase 12 - Culture and Tech
- Per-kingdom knowledge counter fed by population and temples/markets
- Eras: stone, bronze, iron, medieval; each gates buildings/units
  and swaps their palette/geometry to distinguish
- Religions: founded at a temple, spread along trade routes, convert
  villages, occasionally split
- Tests: era progression monotonicity, religion spread bounded by
  trade graph reachability
- **Done when:** a screenshot shows visibly different eras across
  the map

### Phase 13 - Wildlife and Powers
- `Wildlife`: deer / wolves / bears / fish, own SoA pool, simple
  predator-prey; light per-tick cost via staggering
- Rare monsters (dragon, kraken) that attack villages
- God powers: blessing, curse, mind control, drought, tornado,
  tsunami, volcano, ice age, spawn kingdom. Each one dispatches
  through existing systems: `drought` lowers fertility, `mind
  control` reassigns kingdom, `spawn kingdom` uses the kingdom
  founding path
- Tests: predator-prey stays bounded (no extinction cascade from
  spawn defaults), each power actually affects the system it names
- **Done when:** a natural map shows animals living their lives,
  and every listed power has a visible effect

### Phase 14 - History
- `History`: append-only event log, tick + subject + template
- Events auto-generated by other systems: village founded, king
  died, war declared, dragon slew someone
- God tools automatically log too (from the command log)
- `HistoryPanel`: scrollable, filterable by kingdom or unit
- Tests: log is append-only, deterministic (same seed + same
  commands => same log), no duplicate lines from one event
- **Done when:** you can read the world's past

### Phase 15 - Save / load
- `SaveManager`: writes `{ seed, currentTick, commandLog,
  eventLog, snapshot? }`
- Load re-runs deterministically from nearest snapshot forward
- Autosave snapshot every 20,000 ticks
- Tests: save then load then run 1000 ticks == never save, run 1000
  ticks, bit-identical
- **Done when:** the round-trip test passes on any world

## Explicitly still out of scope

Sound, main menu, textures (vertex colours only), multiplayer, mod
support, installers. Save/load is now in scope, per the brief.
