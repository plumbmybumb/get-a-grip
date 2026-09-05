// The engine: a pure Kotlin/JVM library with ZERO Android dependencies, exactly as
// `Shared/` on iOS may import nothing but Foundation. Everything in here is a
// translation of a file in `Shared/Engine` (same file name, same type names, the
// comments carried over because the comments are the spec), and every rule it encodes
// is pinned twice: by the translated XCTest matrix and by the fixtures in `Fixtures/`
// that the Swift suite asserts from the same files.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Compiled for JVM 17 bytecode with whatever JDK runs Gradle (17–26). No toolchain
// provisioning: a toolchain would download a second JDK for no gain, and Android
// Studio's own JBR must be able to build this module as-is.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(false)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The shared fixtures live at the REPO root, beside the iOS tests that read the
    // same files. Passed as a system property so a test never guesses a relative path.
    systemProperty("getagrip.fixtures", rootProject.projectDir.resolve("../Fixtures").canonicalPath)
    // …and declared as an INPUT, or a regenerated fixture leaves the test task
    // UP-TO-DATE and the whole cross-platform contract silently stops being checked:
    // `oracle share generate` rewrote urls.json, `./android/build.sh engine` reported
    // success without running a single assertion against it, and `oracle share verify`
    // then read the urls-android.json of an older run. Measured, 2026-09-04.
    inputs.dir(rootProject.projectDir.resolve("../Fixtures"))
        .withPropertyName("fixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Opt-in fixture regeneration (`RunnerTraceTests`): a test JVM does not inherit the
    // build's `-D`, so the one flag that WRITES into `Fixtures/` is forwarded explicitly.
    // Absent by default, which is what keeps those files an assertion rather than an echo.
    System.getProperty("getagrip.record")?.let { systemProperty("getagrip.record", it) }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
