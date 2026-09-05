# God Sim

A desktop 3D god-simulator sandbox in the spirit of WorldBox: you look down on
a living world, watch civilizations grow on their own, and interfere with
terraform tools and disasters.

Java 21 · Gradle (Kotlin DSL) · libGDX with the LWJGL3 backend, 3D API only.
No textures anywhere — the whole look is flat-shaded vertex-coloured geometry.

## Running

```sh
./gradlew lwjgl3:run
```

Optional arguments:

```sh
./gradlew lwjgl3:run --args="--seed 2024"
```

| Flag | Meaning |
|---|---|
| `--seed <long>` | Generate a specific world. Omit for a random one. |
| `--frames <n>` | Render `n` frames then exit. Automated verification only. |
| `--screenshot <path>` | With `--frames`, write the final frame to a PNG. |
| `--ticks <n>` | With `--frames`, run the simulation forward before capturing. |
| `--closeup` | With `--frames`, drop the camera in among the units. |
| `--stress <n>` | With `--frames`, spawn `n` units to measure render cost. |
| `--war` | With `--frames`, declare a war and stage a battle to capture. |
| `--disasters` | With `--frames`, unleash all six disasters on populated ground. |
| `--firestorm` | With `--frames`, forest the whole island and set light to it. |

## Controls

| Input | Action |
|---|---|
| Left-click / drag | Apply the selected tool to the world |
| Middle-drag *or* Alt + left-drag | Rotate the camera |
| Right-drag | Pan |
| `W` `A` `S` `D` | Pan |
| Scroll wheel | Zoom |
| `1`–`6` | Select tool: Inspect, Raise, Lower, Water, Forest, Spawn |
| `M` `L` `F` `Q` `V` `P` | Meteor, Lightning, Fire, Quake, Flood, Plague |
| `[` `]` | Shrink / grow the brush |
| Space | Pause and unpause |
| Escape | Close the inspector, or quit if nothing is selected |

Tools start on **Inspect**, which is non-destructive — click any tile to read
its terrain, elevation, owner and unit count. Four are terraform brushes and
one spawns units; the radius slider (or the bracket keys) sizes them all.
Picking a species from the palette arms the spawn tool automatically.

The six disasters sit on their own row above the shaping tools, tinted so a
meteor is never one absent-minded click away. They fire once per click rather
than repeating while held — holding a terraform brush is a stroke, but holding
a meteor would be sixty meteors. They get letter keys because twelve tools do
not fit on the number row without reaching for keys nobody would guess.

The brief called for left-drag to rotate the camera *and* for god tools to be
applied by dragging on the world. Those are the same gesture, so the bare left
button goes to the tool — the thing you do constantly in a god game — and
camera rotation moved to the middle button or Alt+left.

## Project layout

Three Gradle subprojects, with dependencies pointing one way only
(`lwjgl3 → core → sim`):

| Module | Contents |
|---|---|
| `sim` | `com.game.sim` — the whole simulation. Plain Java, **no libGDX**, deterministic from a seed. |
| `core` | `com.game.render` and `com.game.ui` — meshes, camera, picking, the game loop. |
| `lwjgl3` | Desktop launcher. |

The split is deliberate rather than cosmetic. `:sim` declares no libGDX
dependency at all, so a stray `import com.badlogic.gdx...` in the simulation is
a compile error instead of something a reviewer has to notice. Render reads
simulation state to draw it and sends god-tool commands in; the simulation
never calls back.

### Data layout

The world is flat primitive arrays — `byte[] tileType`, `float[] height`,
`short[] ownerVillage` and so on — rather than an object per tile, so a
full-map sweep stays linear in memory and allocation-free. Units will use the
same struct-of-arrays treatment with an `int` free list.

### Timing

The simulation runs at a fixed **10 ticks per second** through an accumulator
(`SimClock`), completely decoupled from render framerate. Speed controls scale
that rate; pausing stops it dead without banking a backlog of ticks.

## Determinism

Same seed plus the same sequence of god-tool commands produces a bit-identical
world. Inside the simulation that means one seeded `java.util.Random`, no
`Math.random()`, no wall-clock reads, and no iteration over hash-ordered
collections. There are tests for it.

## Tests

```sh
./gradlew build      # compiles everything and runs every test
./gradlew :sim:test  # simulation only - runs with no libGDX on the classpath
```

## Build status

All six phases are complete. Terrain generation, chunked meshing, the RTS
camera and ray picking; four terraform brushes and a spawn tool behind a
scene2d HUD; four species that wander, eat, age, breed, starve and die; the
villages they found, with territory that grows with population and borders
drawn on the ground; the wars they fight over those borders; and six disasters
to ruin all of it.

See `PLAN.md` for the build order this followed.

### Disasters

| | What it does |
|---|---|
| Meteor | Digs a crater ringed by its own spoil, sets the surrounding forest alight, kills everything at the bottom |
| Lightning | Pinpoint and lethal; starts a fire where it lands |
| Fire | Spreads through forest, burns out, leaves grass behind |
| Quake | Throws the ground up and down; survivable, so the aftermath is a limping population |
| Flood | Drowns ground already low enough to drown, so it finds the coast and the valleys by itself |
| Plague | Infects a crowd, spreads by proximity, kills about 40% and leaves the rest immune |

Two of them keep going after the click, and both are built so that they must
stop. Fire consumes the forest that carries it, so the fuel on the map only
ever decreases and a fire cannot cross ground it has already burned. A plague
leaves its survivors immune, so the pool it can spread into only ever shrinks.
Neither termination depends on the tuning being generous — they are properties
of the rules, which is what keeps "the fire burns out" from being a promise
rather than a guarantee.

Measured by setting light to an entire forested island: 1,300 to 1,650 tiles
burning at once, out after 71 to 107 ticks, 190 to 250 units lost.

One trap worth recording: infection state lives in a signed byte alongside its
susceptible and immune sentinels, and the duration constant was 220 for a
while. The cast clamped it to 127 and nothing said so, so the config named one
number while the game played another. `DisasterSystem` now refuses to load if
that constant goes out of range.

### War and diplomacy

Every pair of species holds a relation between -1 and +1 that drifts over time.
What moves it is geography: territories that interlock share hundreds of border
tiles and sour fast, while two species on opposite coasts drift around neutral
forever. Cross -0.55 and it is war; climb back past -0.15 and it is over.

Wars end on their own three different ways, which is the point. Weariness
pushes relations back up every pass, battlefield deaths push back down so a
bloody war outlasts a quiet one, and a hard cap imposes a truce on anything
still going after 4,000 ticks - so "wars end" is a property of the design and
not of the tuning. A pair that has just made peace is left alone by border
friction for a while, or the same two would be back at war within two passes
and peace would never read as peace.

There are no armies and no orders. Units are in danger when enemies share their
patch of ground, and in more danger when they are the ones standing on enemy
territory. That puts the fighting where two colours meet and lets a front move
when one side starts winning, without a single pathfinding call. Danger comes
from enemies and never from ground alone: an earlier version had territory hurt
trespassers outright, which killed a defeated species everywhere at once - 214
humans to extinct in 2,500 ticks - because a beaten side had nowhere left to
stand.

Measured over five 40,000-tick runs: 9 to 26 wars each, 1,300 to 4,400
battlefield dead, and all four species still alive at the end of every one.

### Villages and territory

Settled adults found villages where their own kind is already clustered and no
other village is too close. A village's radius follows its population, and
every tile in reach is claimed by whichever village pushes hardest on it -
population over distance. That is recomputed from scratch each village update
rather than incrementally claimed and released, which keeps the map free of the
ordering artefacts an incremental version leaves behind when two villages
contest the same ground.

Claimed ground is tinted toward the owning species' colour and border tiles are
tinted harder, so nations read as shapes from a god's-eye zoom. Those four
species colours are picked for contrast against the terrain, not for
naturalism: the first set had green orcs, whose territory was invisible on
grassland.

### Population dynamics

Units breed only when fed and only when their surroundings have room. Crowding
is measured per species and bites harder within a species than between them -
without that, the fastest breeder simply takes the whole map (measured: orcs at
91% by tick 30,000, elves at 1%). With it, all four hold steady together.

### Performance

The renderer packs every unit into two batched meshes, rebuilt on the
simulation tick rather than per frame - units move ten times a second, so
rebuilding at 60fps would redraw the same geometry five times out of six for
nothing. Measured at the 2000-unit target: 1.4 ms per rebuild, ten times a
second, in two draw calls.

Combat costs nothing in peacetime - with no war anywhere the whole pass is one
boolean check - and diplomacy runs once every 60 ticks rather than every tick,
because a border dispute does not need re-evaluating six times a second. Fire
and plague are the same: a world that is neither alight nor sick skips both
passes on a single comparison.

Flames are their own geometry rather than a tint on the terrain. Colouring
burning tiles in the terrain mesh would re-mesh whole 16x16 chunks every time
the fire front moved, which during a spreading fire is most ticks; drawn
separately, the terrain is only re-meshed once per tile, when it burns down to
grass.

The worst case the game can be put in - 2,000 units and a full-map fire at the
same time - costs 1.4 ms of unit geometry and 1.1 ms of flames per simulation
tick. At ten ticks a second that is about 2.5% of a 60fps frame budget.
