# Building and releasing

## iOS configuration

`project.yml` is the source of truth; XcodeGen regenerates `Doigt.xcodeproj`.
The default bundle, App Group, and CloudKit identifiers are the official app's
identities. They are public identifiers, not credentials. Never rename them in an
update to the official app: doing so can separate users from their saved data.

Forks use `project.local.yml` (ignored by Git), based on the supplied example.
`GETAGRIP_APP_GROUP` and `GETAGRIP_CLOUD_CONTAINER` flow into both entitlements and
runtime configuration. Use your own group/container in Apple's developer portal,
and give the app and Live Activity extension the same App Group. Simulator builds
can run without these capabilities. Real CloudKit sync needs a signed device build;
deploy its schema to your own Production environment before distributing it.

Frez Dyno calibration lookups authenticate with a Frez Developer Program access key.
It is private and never committed: the checked-in `project.yml` sets
`FREZ_ACCESS_KEY` to an empty string, so source, CI and forks build an app in which
a Dyno connects and reports that its calibration cannot be fetched. The official
build sets `FREZ_ACCESS_KEY` in the ignored `project.local.yml` (see the example) and
generates with `XCODEGEN_SPEC=project.local.yml`. The value ends up in the app's
Info.plist, so treat it as a usage credential with device and rate limits that can be
extracted from a shipped binary, not as a secret that protects anyone's data.

Use `DEVELOPER_DIR` to select an Xcode installation and `SIM_UDID` to choose an
installed iOS 26+ iPhone or iPad simulator. Tests launch a simulator; close it when
finished. Do not pass `-mockDevice` for real gauge verification. The in-app demo
remains available to people without hardware.

The Apple Watch app (`DoigtWatch`, embedded in the iOS app) builds with
`./build.sh watch` and runs with `./build.sh watch-run`, on the newest installed
watchOS 26+ simulator or the one `WATCH_UDID` names. The watch simulator has neither
Bluetooth nor CloudKit, so `watch-run` always passes `-mockDevice`, and the DEBUG
seeding arguments give it routines to show. Everything the watch reads at launch is in
[DEBUG launch arguments](#debug-launch-arguments) below. Its bundle id is
`$(GETAGRIP_COMPANION_BUNDLE_ID).watchkitapp`; the watch and the companion both carry
the HealthKit capability, which the workout session needs. Real Bluetooth, Health and
CloudKit behaviour on the wrist needs a signed device build — see
[docs/PLATFORMS.md](docs/PLATFORMS.md) for what is still owed there.

The app icon's procedural source is `scripts/make_app_icon.swift`. Run it with an
output PNG path and `any`, `dark` or `tinted`. The tinted palette is grayscale for
the system's tint treatment; the script rejects unknown styles instead of silently
writing the light variant.

## DEBUG launch arguments

`simctl` cannot tap, so the states worth screenshotting or driving headlessly are
reachable by launch argument. Every one below is behind `#if DEBUG` except
`-mockDevice`, which a release build also honours because the in-app demo has to work
for people without hardware. Pass them after the app: `./build.sh run -tab 1
-seedHistory`, or `./build.sh watch-run -previewWatchRunner`.

### Seeding

Seeding is the only thing in either app that inserts a routine without a user asking.
The three routine flags are mutually exclusive and "no routines" wins, so a run that
asked for the empty first-run state can never get a seeded one.

| Argument | What it does | Where |
| --- | --- | --- |
| `-seedNoRoutines` | Deletes every routine, for the empty first-run state | iOS, watch |
| `-seedRoutine` | Replaces the routines with the starter routine alone | iOS, watch |
| `-seedTwoRoutines` | The daily ritual plus a max-day routine: a percentage band on one, a typed kilogram band on the other, and an edge span across the daily's sets | iOS, watch |
| `-seedHistory` | Three weeks of plausible sessions and maxes, attached to those routines by id, so the month grid and the trend line have something to draw | iOS, watch |
| `-previewGauge` | Opens the live gauge (Today's gauge button) directly, for screenshots | iOS |

### Previews

The `-previewRunner*` and `-previewSummary` states drive REAL runner events in an
in-memory store: the engine stops at the requested phase and only the fake gauge keeps
drawing, and nothing they write reaches the real store. The rest — the builder, grip
panel, start, tab, unit and watch arguments — drive the REAL app on the real store,
because `simctl` cannot tap; pair them with the seeding above.

| Argument | What it does | Where |
| --- | --- | --- |
| `-previewRunnerWorking` | Opens the runner mid-hold, clock running | iOS |
| `-previewRunnerWarning` | The same, with the load dropped away: RE-GRIP | iOS |
| `-previewRunnerRelease` | Stops at `.releasing` — the hold is banked, LET GO | iOS |
| `-previewRunnerRest` | Stops in the rest countdown | iOS |
| `-previewRunnerPaused` | The rest, paused | iOS |
| `-previewRunnerTimer` | Any of the above with no gauge at all (timer-only) | iOS |
| `-previewRunnerSetBreak` | Makes that rest a set break, by giving the plan a second set | iOS |
| `-previewRunnerLargeCounts` | 50 sets of 100 pulls, for the widest counters the layout can be asked to hold | iOS |
| `-previewRunnerRestSeconds N` | The rest length, default 20; a value outside the plan's own range is ignored | iOS |
| `-previewRunnerRestProgressing` | Keeps the real ticker running instead of holding the phase still | iOS |
| `-previewRunnerPauseAtTwo` | Pauses through the real event funnel when the countdown shows 2, so a test can inspect that state and tap the normal Resume | iOS |
| `-previewRunnerSignalLost` | Drops the gauge at the phase | iOS |
| `-previewRunnerTarget` | Gives every set a 4–8 kg band and pulls 6 kg inside it | iOS |
| `-previewRunnerWave` | Shapes the fake force into a rising, wobbling curve instead of the flat line geometry tests want | iOS |
| `-previewSummary` | The session summary, in memory | iOS |
| `-previewHandMaxes` | That summary with one grip pulled three times, for the per-hand maxes | iOS |
| `-previewLog` | Opens the summary's log sheet on launch | iOS |
| `-previewBuilder` | Opens the first routine's editor | iOS |
| `-previewGripPanel` | Opens the first set's grip panel, the one control on that screen a screenshot cannot reach | iOS |
| `-startFirstRoutine` | The "Connect and start" tap on Today — with `-mockDevice`, a whole measured session through the real store and runner | iOS |
| `-tab N` | Preselects a tab | iOS |
| `-previewWeightLb` | Starts in pounds | iOS |
| `-previewWatchRunner` | Opens the first routine's session on launch | watch |
| `-previewWatchTimerOnly` | With the above, on the clock alone | watch |
| `-previewWatchGauge` | Opens the live gauge screen on launch, already reading from the demo gauge | watch |
| `-previewDimmed` | Draws the face as Always On does (reduced luminance) — the simulator has no wrist to lower | watch |

### Gauge

| Argument | What it does | Where |
| --- | --- | --- |
| `-mockDevice` | The scripted demo gauge instead of CoreBluetooth. `./build.sh run` and `watch-run` always pass it: the Simulator has no Bluetooth stack | iOS, watch |
| `-mockProfile shaky\|weak\|idle` | Scripts that demo gauge, which is how RE-GRIP gets onto a screenshot — `clean` never drops. A release build's demo is always the textbook pull | iOS, watch |
| `-mockClumpMS N` | Delivers notifications in bunches every N ms instead of one every 100 ms, reproducing the late-and-together delivery that hid the trace on an iPad. Sample timestamps are untouched; only their arrival bunches | iOS, watch |
| `-mockJitterMS N` | Each delivery is late by a random 0…N ms and the batches that fell due meanwhile land together — the real radio's jittery packet gaps. Combine with `-mockClumpMS 190` for the Progressor's measured pattern | iOS, watch |
| `-traceHeadLog` | Writes every trace draw's head position to `Documents/tracehead.csv` beside `diagnostics.txt`, for an objective read of the line's smoothness | iOS |
| `-noWorkoutSession` | Runs the session without a HealthKit workout, whose permission sheet the watch simulator cannot answer | watch |

### Diagnostics

| Argument | What it does | Where |
| --- | --- | --- |
| `-flatBackground` | Swaps the five-layer background for one opaque fill. Run it on the phone when a screen scrolls badly: if the lag vanishes, the answer is compositing rather than SwiftUI | iOS |
| `-dumpInteractions` | Writes the UIKit view tree, window frames, clipping and attached `UIInteraction`s to `Documents/interactions.txt` a few seconds after launch | iOS |
| `-longUndo` | Stretches the Undo window to ten minutes, because a `simctl` round trip is slower than any human | iOS |
| `-timings` | Prints how long the off-main reminder plan took, and how many items it produced | iOS |

## Android configuration

`android/gradle/libs.versions.toml` pins library versions. The wrapper pins Gradle
and its distribution checksum. `android/gradle.properties` owns the app version.
`ANDROID_HOME` or an ignored `local.properties` supplies the SDK location.

Local release builds are optimized and signed with the local Android debug key.
They are suitable for device testing, not Play uploads. For a store release, set all
four Gradle properties below in your private `~/.gradle/gradle.properties` or supply
them through your release environment. Never commit key material or passwords.

```properties
getagrip.keystore=/absolute/path/to/your-upload-key.jks
getagrip.keyAlias=your-alias
getagrip.storePassword=your-private-password
getagrip.keyPassword=your-private-password
```

For Frez Dyno calibration lookups, add `getagrip.frezAccessKey=…` to the same
private `~/.gradle/gradle.properties`; it becomes `BuildConfig.FREZ_ACCESS_KEY`.
Leave it unset for a build in which a Dyno connects but cannot fetch its calibration.
The `INTERNET` permission exists for that one-off lookup and nothing else.

Use `./android/build.sh :app:bundleRelease` for an app bundle after configuring your
own upload key. A fork must also choose its own `applicationId` before distribution.
Do not change the official application's ID or signing key for existing users.

A test build can install beside the Play version. Two optional properties, both absent
by default, append to the application id and override the launcher label:

```sh
./android/gradlew -p android :app:assembleDebug \
  -Pgetagrip.applicationIdSuffix=.ultimate \
  -Pgetagrip.appLabel="Get a Grip Test" \
  -Pgetagrip.versionCode=15 -Pgetagrip.versionName=1.2.0-ultimate
```

The suffixed app is a separate install with its own data; it never upgrades the real one.

## Tests

```sh
./build.sh test          # iOS XCTest suite (DoigtTests)
./build.sh uitest        # iOS XCUITest suite (DoigtUITests)
./build.sh uitest RunnerAppearanceUITests   # one class, or Class/testMethod
./android/build.sh test  # Kotlin engine and Android app tests
```

`test` is the correctness gate and runs in CI. `uitest` is not in CI and is run by hand,
because it drives a booted simulator and takes minutes; it logs to
`build/last-uitest.log` and, like `test`, prints only failures and the summary.

**Known failing:** `LegalAgreementUITests.testMaxMeasurementControlsRemainReachableWithLargeText` is stale — the per-grip measure button now opens the gauge directly, so the "Measure on the gauge" step it waits for never appears, and it depends on ambient simulator state. It needs a decided flow and deterministic seeding like `MaxesFlowUITests` before it can be trusted again.
fails on `main` today. Fix it before adding `uitest` to the workflow — a suite that is
known to be red teaches everyone to ignore it.

## Cross-platform checks

```sh
./Fixtures/tools/oracle/build.sh
./Fixtures/tools/oracle/build/oracle runner verify Fixtures
./Fixtures/tools/oracle/build/oracle share  verify Fixtures
./Fixtures/tools/oracle/build/oracle export verify Fixtures
python3 android/scripts/xcstrings_to_android.py
```

CI runs the first two. `share verify` and `export verify` are the other half of the
same contract and are run by hand — `share verify` is what puts Kotlin's URLs through
the iOS decoder.

The oracle defaults to this repository's Swift source; `GETAGRIP_IOS_TREE` can
explicitly select another checkout. Fixtures are synthetic and checked into source.
Regenerate only when intentionally changing the contract, then review the results.
Do not add actual training exports, personal diagnostics, or device identifiers.

## Before a public release

What the current release ships, and the fixes and features queued behind it, are
tracked in [the release record](docs/NEXT_RELEASE.md).

Run both test suites, compare the cross-platform fixtures, inspect the merged
Android manifest, and test camera permission/scan flow and Bluetooth on hardware.
Keep MPL and dependency notices bundled with binaries. Update dependency notices
when changing libraries: `scripts/dependency-licenses.gradle` resolves the release
dependencies' POMs, and `scripts/generate-notices.py` folds them and the upstream texts
in `LICENSES/` into `THIRD_PARTY_NOTICES.txt`. Run the Gradle task first — the script
says so and stops if its inventory is missing. Tag the exact source commit used for each shipped version,
with separate platform tags if their store versions differ.

For iOS, inspect the entitlements in the **final distribution-signed export**, not
only the source file or a development-signed archive. Run
`codesign -d --entitlements :- /path/to/export/Payload/Doigt.app` and confirm
`aps-environment` is `production`, the CloudKit container environment is `Production`,
and the bundle, App Group and CloudKit identifiers match the intended application.
Do this for Organizer exports and automated `xcodebuild -exportArchive` releases.
Do not distribute an export with development push entitlements.
[Apple derives the APNs environment from the provisioning profile](https://developer.apple.com/documentation/bundleresources/entitlements/aps-environment).
The source's development default supports local signing; it is not evidence of the
environment in the exported app. SwiftData's CloudKit mirroring requires the
[Remote notifications background capability](https://developer.apple.com/documentation/SwiftData/Syncing-model-data-across-a-persons-devices).
