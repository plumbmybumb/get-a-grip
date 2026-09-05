// The Android app. Jetpack Compose over the pure `:engine`; Material 3 chrome wearing
// the app's own palette (dynamic colour off — see ui/theme/Tokens.kt).
//
// AGP 9 compiles Kotlin itself (built-in Kotlin), so there is no
// `org.jetbrains.kotlin.android` here; the Compose compiler plugin is applied through
// Kotlin's own Gradle plugin, which AGP's built-in support recognises.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Room's annotation processor. KSP2 runs on the Kotlin Analysis API rather than as a
    // compiler plugin, which is what lets KSP 2.3.11 process sources compiled by AGP 9's
    // built-in Kotlin 2.4.10 — the two are not required to match, and this combination
    // was verified against `:app:kspDebugKotlin` before anything was written on top of it.
    alias(libs.plugins.ksp)
}

// Upload credentials live in private Gradle properties, never in source control.
val uploadKeys = listOf("keystore", "keyAlias", "storePassword", "keyPassword")
val uploadValues = uploadKeys.associateWith { providers.gradleProperty("getagrip.$it").orNull }
require(uploadValues.values.all { it == null } || uploadValues.values.all { !it.isNullOrBlank() }) {
    "Supply all four getagrip upload-signing properties, or none for a local debug-signed release."
}

android {
    signingConfigs {
        if (uploadValues["keystore"] != null) {
            create("upload") {
                storeFile = file(uploadValues.getValue("keystore")!!)
                keyAlias = uploadValues.getValue("keyAlias")
                storePassword = uploadValues.getValue("storePassword")
                keyPassword = uploadValues.getValue("keyPassword")
            }
        }
    }
    namespace = "run.nuri.getagrip"
    // Compiled against the newest platform the SDK manager installed (Android 17 /
    // API 37); targeting 36 (Android 16), the release the Live Update notification and
    // the plan were written for. minSdk 31: the Bluetooth permission model without a
    // location prompt.
    compileSdk = 37

    defaultConfig {
        // FROZEN. The application id is the app's identity on Google Play and on every
        // phone it is installed on; renaming it after the first upload is a new app.
        applicationId = "run.nuri.getagrip"
        minSdk = 31
        targetSdk = 36
        // **THE VERSION LIVES IN `gradle.properties`,** not here: an upload to Play is
        // rejected outright for a `versionCode` that is not strictly greater than the last
        // one, and a number buried in a build script is a number somebody forgets to bump.
        // One place, one line, and CI can override it with `-Pgetagrip.versionCode=…`.
        versionCode = (project.findProperty("getagrip.versionCode") as String?)?.toInt() ?: 1
        versionName = project.findProperty("getagrip.versionName") as String? ?: "1.0"
    }

    buildFeatures {
        compose = true
        // `BuildConfig.DEBUG` gates the launch-argument seeders and the
        // `persistAndSync(maxesChanged = false)` audit — the twins of iOS's `#if DEBUG`.
        // AGP stopped generating BuildConfig by default in 8.0.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // **R8 ON.** `proguard-android-optimize.txt` is AGP's own base; `proguard-rules.pro`
            // is only what R8 cannot see from the bytecode — see its header. `:engine` is
            // reflection-free by construction (its one JSON door is hand-written over
            // `JsonElement`, not over `@Serializable` classes), so nothing in the engine needs
            // a keep rule and R8 is free to shrink it.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Community builds need no private key. Official uploads explicitly configure one.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    testOptions {
        unitTests {
            // JUnit 5, the same runner `:engine` uses, so one test style spans both
            // modules. `isReturnDefaultValues` keeps a stubbed `android.jar` call from
            // throwing: the BLE logic and the pure store folds are what most of these
            // tests exercise, and none of those should need Robolectric to run.
            isReturnDefaultValues = true
            // The store tests DO: Room needs a real SQLite and a real `Context`, so
            // `TemplateStoreTests` runs under Robolectric — a JUnit 4 runner, carried by
            // the Vintage engine on the same platform (see the catalogue note).
            isIncludeAndroidResources = true
            all {
                it.useJUnitPlatform()
                // `StringCatalogTests` reads the GENERATED strings.xml as text — the
                // escaping rules it checks (an apostrophe, a preserved double space) are
                // invisible once aapt2 has decoded them. Passed as a system property so
                // the test never guesses a relative path, and declared as an input so a
                // regenerated catalog cannot leave the task UP-TO-DATE — the same trap
                // `:engine`'s fixtures hit.
                it.systemProperty("getagrip.res", projectDir.resolve("src/main/res").canonicalPath)
                it.inputs.dir(projectDir.resolve("src/main/res"))
            }
        }
    }
}

// Room writes its schema JSON here so a future migration can be diffed against the
// version that shipped. `exportSchema = true` without this is a build warning and a
// schema nobody can see.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":engine"))

    // `BlobCodec.decode`/`decodeAll` take a `(JsonElement) -> T?` reader, so every blob
    // column named in `data/` mentions the type. `:engine` keeps kotlinx-serialization as
    // an `implementation` dependency (it is not `:app`'s to change), so `:app` names it
    // too rather than leaning on a transitive classpath that is deliberately not exported.
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("androidx.compose.ui:ui-test-junit4")

    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    // Settings pushes the gauge picker and the live gauge onto a NavHost scoped to that
    // tab, which is also what makes predictive back work with no code of our own.
    implementation(libs.navigation.compose)
    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // The BLE transport. Nordic's library owns the ATT-level request queue — one GATT
    // operation in flight, writes paced against the stack's readiness — which is the half
    // of `LiveProgressorClient`'s hand-rolled queue that was about the radio. Everything
    // protocol-shaped (the serialized tag-0 query channel, the tare-integrity latch) stays
    // in `ble/ControlPointQueue.kt`.
    implementation(libs.nordic.ble.ktx)

    // The three tables — routines, sessions, maxes — in the CloudKit-safe shape the iOS
    // models froze: every column defaulted, nothing unique, no relationships, blobs as
    // TEXT. See `data/GetAGripDatabase.kt`.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // The settings surface: the gauge kind `DeviceStore` reads before its first client
    // exists, plus everything `SettingsStore` grew for the store layer.
    implementation(libs.datastore.preferences)

    // Both encoding and camera decoding run locally using Apache-licensed ZXing.
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded)

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.junit.vintage.engine)
}
