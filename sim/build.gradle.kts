plugins {
    `java-library`
}

// Deliberately no libGDX here, and nothing that transitively drags it in.
// The simulation must compile and run its tests on a classpath that has
// never heard of a graphics library - that is what keeps the sim
// deterministic, headless-testable, and free of render-order coupling.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.14.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
