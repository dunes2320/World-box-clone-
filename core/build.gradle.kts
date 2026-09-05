plugins {
    `java-library`
}

val gdxVersion: String by project

dependencies {
    // api, not implementation: the launcher legitimately constructs both the
    // game and its libGDX configuration, so there is nothing to hide here.
    api(project(":sim"))
    api("com.badlogicgames.gdx:gdx:$gdxVersion")

    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // gdx-platform natives only: libGDX's Matrix4 maths is native-backed, so
    // even a Camera needs the library loaded. No backend and no GL context -
    // the render tests cover pure geometry (see TilePickerTest).
    testRuntimeOnly("com.badlogicgames.gdx:gdx-platform:$gdxVersion:natives-desktop")
}
