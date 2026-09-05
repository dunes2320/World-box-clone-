// Shared configuration only. Each subproject applies its own plugins and
// declares its own dependencies, which keeps the ":sim has no libGDX" rule
// visible in one place (sim/build.gradle.kts) rather than buried in a
// cross-project configuration block here.
allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.add("-Xlint:all,-serial,-this-escape")
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
            }
        }
    }
}
