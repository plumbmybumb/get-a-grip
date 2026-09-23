// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

// The Android side of Get a Grip. `:engine` is a pure Kotlin/JVM library — the
// translation of the iOS `Shared/Engine` — and builds with nothing but a JDK, which is
// why it is the first module and why CI for the engine never needs the Android SDK.
// `:app` is the Jetpack Compose application; it needs the SDK named in local.properties.
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GetAGrip"
include(":engine")
include(":app")
// Generates `app/src/main/generated/baselineProfiles` from real journeys on a device or
// emulator: `./gradlew :app:generateBaselineProfile`. Never part of a normal build.
include(":baselineprofile")
