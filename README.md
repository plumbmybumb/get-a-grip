# Get a Grip

[![Tests](https://github.com/plumbmybumb/get-a-grip/actions/workflows/tests.yml/badge.svg)](https://github.com/plumbmybumb/get-a-grip/actions/workflows/tests.yml)

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

Requires a Mac, Xcode 26 or newer with **both** the iOS 26+ and watchOS 26+ simulator
platforms installed, Python 3, and [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`). The watch platform is not optional: the iOS app embeds the
watch app, so a plain build needs it.

```sh
./build.sh          # Generate the Xcode project and build
./build.sh test     # XCTest suite
./build.sh uitest   # XCUITest suite
./build.sh run      # Launch with a deterministic demo gauge
./build.sh watch-run   # Build the Apple Watch app and launch it on a watch simulator
```

The newest installed iOS 26+ iPhone simulator is chosen automatically; set `SIM_UDID`
to choose one — an iPad's udid builds and runs the iPad layouts. The watch verbs pick
the newest Apple Watch simulator; set `WATCH_UDID` to choose. See
[docs/PLATFORMS.md](docs/PLATFORMS.md) for how the iPad and the watch fit. Simulator
builds need no signing credentials and use local storage. The simulator cannot verify
real Bluetooth transport.

For your own iPhone, copy `project.local.yml.example` to `project.local.yml`, enter
your team and unique app/group/CloudKit identifiers, and generate with
`XCODEGEN_SPEC=project.local.yml ./build.sh`. Open `Doigt.xcodeproj`, select your
phone, and use your own signing account. CloudKit and App Groups need the relevant
Apple Developer capabilities. See [BUILDING.md](BUILDING.md).

### Android

Requires JDK 17–26 (JDK 21 recommended), Android SDK platform 37 (`platforms;android-37.0`), and Build Tools 36.0.0.
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
| `Watch/` | The Apple Watch app, embedded in and shipped with the iOS app |
| `Widget/` | iOS Live Activity |
| `Tests/` | iOS regression tests |
| `UITests/` | XCUITest suites, run by `./build.sh uitest` |
| `android/engine/` | Pure Kotlin engine and codec tests |
| `android/app/` | Jetpack Compose app and Android tests |
| `Fixtures/` | Synthetic cross-platform fixtures and Swift replay tool |
| `Legal/` | The Terms and Privacy texts both apps bundle byte-for-byte, current and archived |
| `LICENSES/` | Upstream license texts kept with the binaries |
| `scripts/` | App icon generator and the dependency-notice tools |
| `docs/` | Feature and export-format documentation |

Code comments cite the maintainer and a date — "(Nuri, 2026-08-03)". Those are dated
design decisions taken from his own training with the app, recorded so a later change
knows what it is overruling.

## Devices and privacy

The app includes Tindeq Progressor support, the Frez Dyno protocol as published by
Frez, and ported protocols for WH-C06, Entralpi, Climbro, Motherboard, ForceBoard, and
CTS500. Hardware verification varies; the device registry and Settings distinguish
verified support from unverified ports and from maker-documented protocols not yet
tried here. PB-700BT is deliberately not selectable: its measurements are RPM, not
force.

The Frez Dyno streams raw sensor counts, so the first time a unit connects the app
asks Frez's coefficient API for that unit's calibration, by serial number, using a
Frez Developer Program access key that is never committed (see
[BUILDING.md](BUILDING.md)). The answer is cached on the device and the request is
never repeated for that unit. It is the only network request the apps make to a
server other than Apple's; no other gauge involves one.

Android stores training locally. iOS supports local storage and private CloudKit
sync. Apple frameworks, iOS, and CloudKit are proprietary platform dependencies;
this repository opens the app source, not those platform services. There is no
Get a Grip account or app-operated training backend.

## Contributing and releases

See [CONTRIBUTING.md](CONTRIBUTING.md), [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md),
[SECURITY.md](SECURITY.md), and [BRANDING.md](BRANDING.md).
[CHANGELOG.md](CHANGELOG.md) indexes the shipped builds. Changes are reviewed before entering the official app.
Community builds use their own signing identities. The release source commit should
be tagged whenever an official store version is shipped; this initial public source
snapshot is not a claim of an App Store or Google Play release.
