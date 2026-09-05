plugins {
    application
}

val gdxVersion: String by project

dependencies {
    implementation(project(":core"))
    implementation("com.badlogicgames.gdx:gdx-backend-lwjgl3:$gdxVersion")
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
}

application {
    mainClass.set("com.game.lwjgl3.Lwjgl3Launcher")
}

// `./gradlew lwjgl3:run` should behave like double-clicking the game: run
// from the project root so any relative paths resolve predictably, and let
// stdin through so the process can be interrupted from a terminal.
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
