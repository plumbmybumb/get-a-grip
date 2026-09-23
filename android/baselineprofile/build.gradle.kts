// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

// The baseline-profile GENERATOR: a test APK that drives the app's real journeys on a
// device or emulator and records what they run, so the shipped profile describes what a
// climber actually does — open the app, switch tabs, open the builder — rather than a
// guess. Nothing here ships; `:app` consumes only the profile it writes.
//
//   ./gradlew :app:generateBaselineProfile   # needs an emulator (API 33+) or a device
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "run.nuri.getagrip.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // Macrobenchmark's profile capture needs API 28+; the app's own floor is 31.
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

// Any attached device or running emulator. Gradle-managed devices would download a system
// image on every clean machine, which is a poor default for a task somebody runs by hand.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.junit)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
