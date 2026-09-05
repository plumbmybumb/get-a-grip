# Get a Grip

Native iOS and Android apps for climbing finger training with Bluetooth force gauges.
Build a routine once, then open the app and start pulling. Grips live inline in each
set: edge depth, fingers, and position. There is no grip library to maintain.

- Timed pulls, rest, hand changes, target bands, and live force feedback.
- Half crimp, open, full crimp, drag, pinch, and finger curl.
- Workout history, per-grip maxes, reminders, and CSV analysis exports.
- Cross-platform routine sharing with QR codes and links.
- Demo gauge and timer-only training without hardware.
- English and French; native accessibility, sound, and reduced-motion support.

The complete app source, original bundled assets, test fixtures, and build scripts
are available under **MPL 2.0**, except third-party material identified in
[THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt). Get a Grip is not affiliated with
or endorsed by any gauge manufacturer.

## Build

### iOS

Requires a Mac, Xcode 26 or newer with an iOS 26+ simulator, Python 3, and
[XcodeGen](https://github.com/yonaskolb/XcodeGen) (`brew install xcodegen`).
The current development toolchain is Xcode 26.6 / Swift 6 / iOS 26.5 simulator.

```sh
./build.sh          # Generate the Xcode project and build
./build.sh test     # XCTest suite
./build.sh run      # Launch with a deterministic demo gauge
```

The script automatically selects an installed iPhone simulator. Set `SIM_UDID` to
choose one explicitly. Simulator builds need no signing credentials and use local
storage. The simulator cannot verify real Bluetooth transport.

For your own iPhone, copy `project.local.yml.example` to `project.local.yml`, enter
your team and unique app/group/CloudKit identifiers, and generate with
`XCODEGEN_SPEC=project.local.yml ./build.sh`. Open `Doigt.xcodeproj`, select your
phone, and use your own signing account. CloudKit and App Groups need the relevant
Apple Developer capabilities. See [BUILDING.md](BUILDING.md).

### Android

Requires JDK 17–26 (JDK 21 recommended), Android SDK platform 37, and Build Tools.
Minimum supported device: Android 12 / API 31. Android Studio can install the SDK.
Set `ANDROID_HOME`, or copy `android/local.properties.example` to
`android/local.properties` and set `sdk.dir`.

```sh
./android/build.sh test       # Kotlin engine and Android app tests
./android/build.sh app        # Debug APK
./android/build.sh release    # Optimized APK, locally debug-signed
./android/build.sh run        # Install and launch a debug build on a connected device
```

Runtime operation and QR scanning work offline without Google Play services.
Camera permission is requested only when scanning. Frames are not saved or uploaded.
The first build downloads the open-source build tools and libraries.

## Project map

| Path | Contents |
| --- | --- |
| `Shared/` | Swift engine, codecs, persistence values, and design tokens |
| `Sources/` | SwiftUI app, Bluetooth clients, stores, and runner |
| `Widget/` | iOS Live Activity |
| `Tests/` | iOS regression tests |
| `android/engine/` | Pure Kotlin engine and codec tests |
| `android/app/` | Jetpack Compose app and Android tests |
| `Fixtures/` | Synthetic cross-platform fixtures and Swift replay tool |
| `docs/` | Feature and export-format documentation |

## Devices and privacy

The app includes Tindeq Progressor support and ported protocols for WH-C06,
Entralpi, Climbro, Motherboard, ForceBoard, and CTS500. Hardware verification varies;
the device registry and Settings distinguish verified support from unverified ports.
PB-700BT is deliberately not selectable: its measurements are RPM, not force.

Android stores training locally. iOS supports local storage and private CloudKit
sync. Apple frameworks, iOS, and CloudKit are proprietary platform dependencies;
this repository opens the app source, not those platform services. There is no
Get a Grip account or app-operated training backend.

## Contributing and releases

See [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md), and
[BRANDING.md](BRANDING.md). Changes are reviewed before entering the official app.
Community builds use their own signing identities. The release source commit should
be tagged whenever an official store version is shipped; this initial public source
snapshot is not a claim of an App Store or Google Play release.
