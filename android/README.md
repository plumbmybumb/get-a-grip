# Android

The Android half of Get a Grip: two Gradle modules under one `android/` root.

- `engine/` — a pure Kotlin/JVM library with no Android dependencies, the translation
  of the iOS `Shared/Engine`. Same file names, same type names, and the comments
  carried over, because the comments are the spec. It builds with nothing but a JDK,
  which is why CI can run its tests without the SDK.
- `app/` — the Jetpack Compose application (`run.nuri.getagrip`) over that engine.

The two engines share no code. `Fixtures/` is what keeps them from drifting: a rule
change on one side is not done until the fixture and its twin change with it.

## Commands

```sh
./android/build.sh engine   # :engine tests — needs only a JDK
./android/build.sh test     # every module's tests
./android/build.sh app      # the debug APK — needs the Android SDK
```

`release`, `devices`, `install` and `run` are there too, and anything else is passed
straight to `gradlew`.

## Toolchain

JDK 17–26 (21 recommended), Android SDK platform 37 (`platforms;android-37.0`) and
Build Tools 36.0.0. The app compiles against SDK 37, targets 36, and runs on API 31
(Android 12) and newer. Set `ANDROID_HOME`, or copy `local.properties.example` to
`local.properties` and set `sdk.dir`. The Gradle wrapper pins Gradle and its
distribution checksum; `gradle/libs.versions.toml` pins the libraries.

`gradle.properties` owns the shipped version — `getagrip.versionCode` is the only line
to bump for a release.

## More

[BUILDING.md](../BUILDING.md) covers signing, the Frez access key, the store release
checks and the cross-platform fixture run. [CONTRIBUTING.md](../CONTRIBUTING.md) covers
what a change has to carry, including the generated string catalog: Android's strings
come from the iOS catalogs through
`python3 android/scripts/xcstrings_to_android.py` and are never edited by hand.
