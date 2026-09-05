rootProject.name = "godsim"

// Three subprojects rather than one flat project, so the architecture rule
// is enforced by the build graph instead of by convention: :sim declares no
// libGDX dependency, which makes a stray `import com.badlogic.gdx...` in the
// simulation a compile error rather than something a reviewer has to catch.
// Dependencies point one way only: lwjgl3 -> core -> sim.
include("sim", "core", "lwjgl3")
