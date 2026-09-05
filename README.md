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

## Controls

| Input | Action |
|---|---|
| Left-drag | Apply the current tool to the world |
| Shift + left-drag | Lower terrain instead of raising it |
| Middle-drag *or* Alt + left-drag | Rotate the camera |
| Right-drag | Pan |
| `W` `A` `S` `D` / arrows | Pan |
| Scroll wheel | Zoom |
| `[` `]` | Shrink / grow the brush |
| Space | Pause and unpause |
| Escape | Quit |

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

Phase 1 of 6 is complete: terrain generation, chunked meshing, the RTS camera,
ray picking and the raise/lower terraform brush. The world is deliberately
lifeless until the spawn tool arrives in phase 3. See `PLAN.md` for the full
build order.
