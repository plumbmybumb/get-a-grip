# iPad and Apple Watch

Status: iPad and Apple Watch builds implemented on `ipad-watch` (2026-09-19); simulator
verified, hardware checks owed (below). The iPhone Duo poses and the wrist Live
Activity are scoped in the same pass and deliberately parked.

The app was one target on one device. It is now the same code on three: the phone, the
iPad, and a watch that runs a session on its own. Nothing was forked. The engine, the
models, the gauge clients and the runner session compile verbatim into every target;
where a platform lacks a framework, the seam is an `#if os(watchOS)` block inside the
same file, so a rule fixed once is fixed everywhere.

## One gauge, one central

A Progressor accepts a single Bluetooth connection. With three devices on one account
the rule is: **whichever device pressed Start owns the gauge.** No device connects at
launch — the phone never did, and the iPad and watch keep that — so a watch on the
wrist cannot take the gauge from a phone that is mid-session. Sessions are device-local;
history syncs through CloudKit; a live session never moves between devices.

## iPad

- `TARGETED_DEVICE_FAMILY` is `1,2` on the app, the Live Activity, the tests and the UI
  tests. iPad supports all four orientations (`UISupportedInterfaceOrientations~ipad`);
  the iPhone stays portrait — the island hand's fixed geometry and the one-page Today
  are portrait facts.
- Layouts switch on **size class and aspect, never on the idiom**, which is also
  Apple's rule for the foldable. The regular-width column is 560 pt
  (`Metrics.maxContentWidthRegular`); sheets keep 440.
- **Cards go in a grid on a wide window** (`CardGrid`: as many phone-width columns as
  fit, two on an iPad, top-aligned). Today lays its routines out side by side with the
  consistency strip spanning both, and Maxes is two cards to a row; the column those
  screens open up to is `Metrics.maxContentWidthGrid`. A single 560 column on a
  13-inch iPad was a phone in a frame (Nuri, 2026-09-19). History's wide pane stacks
  the trend cards and stops the month deck peeking, because a sliver of card cut by a
  pane edge reads as a glitch.
- The runner has two forms: the phone's stack, and a two-column layout when the window
  is regular-width and wider than tall — the numbers and controls on the left at the
  phone's own column width, the graph (or the timer dial) taking the height on the
  right. Every view is shared; nothing was drawn for the iPad alone.
- The four tabs use `.sidebarAdaptable`: the top tab bar on iPad, the ordinary bar on
  the phone. History goes two-pane in the same wide condition.
- Reminders are per device (`SettingsStore.remindsOnThisDevice`): the phone defaults on,
  an iPad defaults off, either can be flipped in Settings. Off contributes an empty
  plan, which retires that device's pending reminders on the next replan. Without this,
  every device on the account would fire the same 19:30.
- `SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD` is `NO` until someone has run the app on a
  Mac with a gauge. Apple also offers iPad apps on Macs from App Store Connect by
  default; untick that box for the same reason.
- Headless verification: `SIM_UDID=<an iPad's udid> ./build.sh run` for portrait, with
  `-previewBuilder` to open the first routine's editor and `-previewGripPanel` to open
  its first set's grip panel (DEBUG arguments, because `simctl` cannot tap).
  `simctl` cannot rotate a device and iPadOS 26 refuses programmatic orientation
  changes, so the wide layouts are checked by `UITests/WideLayoutUITests.swift`, which
  turns the simulator and asserts where the graph and the controls landed; the rotation
  outlives the test, so a `simctl io screenshot` afterwards shows the same layouts.

## Apple Watch

The watch is a **standalone gauge host, not a remote.** Raise wrist, Start, the watch
connects to the gauge itself, runs `SessionRunner` itself, and writes the `WorkoutLog`
itself through `SessionLedger` — the write path extracted from `TemplateStore` so a
session the watch ran is saved by exactly the code the phone uses. The phone learns
about it the way it learns about anything: CloudKit. There is no WatchConnectivity
anywhere, which is why a session works with the phone in a bag.

- Three screens and no builder: routines are made on the phone and arrive by sync. The
  wrist gets the ritual, never the library.
- **Haptics first.** Mid-hang the watch faces the ceiling, so `WatchCuePlayer` maps
  every engine cue to a system haptic; the face is for between pulls — phase word,
  countdown, hand, grip, the kilogram readout. No trace.
- **The face flips for the watch hand.** Pulling a block in front of you, palm down
  with the forearm level, puts the watch under your eyes reading upside down;
  `FaceFlipPolicy` turns the face 180° on the watch hand's pulls (the device reports
  which wrist it is on), and never during a rest, a pause or on the controls page. A
  toggle on the controls page turns it off for a different posture.
- `WorkoutKeeper` runs every session inside an `HKWorkoutSession`
  (`functionalStrengthTraining`, indoor): that is what keeps the app and the Bluetooth
  stream alive with the wrist down, and it lands in Fitness as a workout. Health access
  refused → the runner still runs, the face says the screen has to stay on.
- The watch never pauses on leaving the foreground (the workout session keeps it
  alive); it re-kicks the stream on return, as the phone does.
- Same SwiftData schema, same container name, same CloudKit database. No App Group on
  the watch — groups are per platform and nothing on the wrist reads one.
- Info.plist: `WKBackgroundModes: workout-processing`, `UIBackgroundModes:
  bluetooth-central`, the Bluetooth and Health usage strings. Entitlements: HealthKit
  and CloudKit. The companion iOS app also carries the HealthKit entitlement and the
  Health usage strings, which Apple requires of a companion whose watch app uses
  HealthKit; the phone itself never touches Health.
- Bundle id `<companion>.watchkitapp`, from `GETAGRIP_COMPANION_BUNDLE_ID`; forks set
  it beside their app id in `project.local.yml`.
- `./build.sh watch` builds it; `./build.sh watch-run -seedTwoRoutines
  -previewWatchRunner -noWorkoutSession` launches it on a watch simulator with the demo
  gauge straight into a session (DEBUG arguments: the watch simulator has no CloudKit
  and no Bluetooth, and it cannot answer the Health sheet a workout session raises, so
  `-noWorkoutSession` leaves the session out for screenshots).

## Owed on hardware

Simulator builds cannot answer these; each needs a real watch and a real gauge:

1. The Progressor's connection interval against the watch's minimum, and ten
   notifications a second for twenty minutes with the wrist down.
2. Battery cost of a full session on the wrist.
3. That the first Start's Health permission sheet and the Bluetooth prompt arrive in a
   sensible order, once each.
4. That a watch session's log appears in the phone's History after sync, counts toward
   "2 of 2 today", and silences that evening's reminder.
5. Broadcast gauges (WH-C06 and the other scanned scales) on the watch: continuous
   scanning is a battery question; the watch is scoped to connected gauges first.
6. iPad: the grip panel and the keyboard shortcuts (space pauses, S skips) on a real
   keyboard case; Live Activities on the iPad lock screen.

## Store

- iPad screenshots (the 13-inch set) and an App Review pass on iPad.
- The watch app ships inside the iOS binary; App Store Connect needs watch screenshots
  and the HealthKit usage answered in App Privacy (workouts, written, not linked).
- App Privacy: the iPad and watch add no data collection.

## Parked

- The wrist Live Activity (`supplementalActivityFamilies([.small])` plus an alerting
  update on PULL): Nuri passed on it for now.
- iPhone Duo poses (the fold split, the far view on the outer display, the island read
  from `reservedRegions`): after the device ships; needs the Xcode 27.1 beta.
