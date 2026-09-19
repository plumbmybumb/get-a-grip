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
- **The force trace measures "now" from the WALL clock (`Date()`), never from the
  `TimelineView` schedule's date.** The animation clock stops while the device sleeps or
  the app is suspended, and the trace's sample timestamps come from `Date()`. On an iPad
  that had been locked mid-session the two diverged by the whole sleep gap (~25 s), and
  because the renderer positions every point by `now − t`, the lagging clock threw the
  entire buffer into the "future" and the graph drew nothing — while the phone, never
  suspended in the same session, was fine. Nuri's "no line on iPad, works on iPhone"
  (2026-09-19). The buffer, the BLE pipeline and the store's playback clock were all
  healthy; the bug was one line reading the wrong clock. `-dumpDiagnostics` writes the
  Settings › About › Diagnostics report — including the trace's last-draw decision — to
  `Documents/diagnostics.txt` in DEBUG, which is how it was caught on the device without
  a paste. **Never position the trace against `timeline.date`; it is only a redraw tick.**
  The DEBUG report's first line states the display environment (Reduce Motion, Reduce
  Transparency, Low Power, max refresh). **Reduce Motion no longer pauses the trace's
  timeline**: it used to, so the graph redrew only when a packet landed — two points of
  step on a card, a 37 pt lurch of the whole picture five times a second on an iPad's
  full-screen canvas (Nuri's iPad has Reduce Motion on; "so laggy", 2026-09-19). A slow,
  steady slide is the gentlest way a graph of time can move; the setting now drops only the
  decoration (the trace's 0.85 opacity), never the tick.
- **The trace draws the raw data as it arrives; its only smoothing is visual and lag-free.**
  The real Progressor stream reaches the app as ~15 samples every ~190 ms with p95 300 ms and
  max 420 ms gaps (both of Nuri's devices, diagnostics of 2026-09-19). Three renderings were
  tried that day: drawn as it arrived with the clock slewed toward wall time, the line
  grew in chunks and the head stepped ("the line getting written feels kinda jittery");
  glided through a jitter buffer it was smooth and sat a packet plus a margin behind the
  hand ("slightly behind my actual pull"); a live pen with a straight connector back to a
  buffered body looked wrong ("that small straight part of the line"). Nuri's verdict —
  "just take the raw data and feed it in", "as smooth as you can visually without
  oversmoothing" — is the rule. `DeviceStore.playbackTime` targets wall time LESS half a
  packet, so a packet's newest reading is stamped at its arrival and nothing sits in the
  future (the old pause-and-lurch, and every waiting scheme, came from future stamps);
  `ForceTraceView` draws every known point at its true place, fades a freshly arrived
  packet's segment in over 120 ms instead of popping it, and eases the head dot toward the
  newest reading over ~60 ms. Positions are never delayed; nothing is extrapolated. The
  buffer keeps two seconds more than the window so a full buffer's start stays off the
  left edge (the fill's start ramp otherwise jitters on screen). Reproduce the radio on the
  mock with `-mockClumpMS 190 -mockJitterMS 120` and judge from `-traceHeadLog`'s per-frame
  rows. The Dyno's 250 Hz and a broadcast scale's 8–10 Hz go through the same clock; the
  buffer capacity is sized from the gauge's rate.
- **On the iPad the trace runs UNDER the glass column and off the left edge.** The phone
  keeps its trace in the open region (only the phase wash sits under the panel and dock);
  the iPad's canvas is the whole screen. iOS re-blurs a glass backdrop every frame the
  layer beneath changes, so this is measured on the M4 rather than assumed.
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
- **The face turns toward the hand on the watch hand's pulls.** Pulling a block in
  front of you, palm down with the forearm level, puts the watch under your eyes with
  the hand past the crown edge, so the text runs along the arm; `FaceFlipPolicy` turns
  the face a quarter (clockwise on the left wrist, anticlockwise on the right), laid out
  for the screen's long axis, and never during a rest, a pause or on the controls page.
  The turn snaps rather than animates — a spinning face stuttered on the wrist. A toggle
  on the controls page turns it off for a different posture.
- **Always On dims the face and no app can stop it.** Once the wrist leaves the
  raise-to-wake pose — palm down on a block counts — watchOS drops to reduced
  luminance at one redraw a second; Apple's Workout app dims the same way, and there is
  no API for full brightness or full refresh with the wrist down (checked against
  Apple's Always On documentation, 2026-09-19: a workout session is what earns the
  once-a-second redraw at all; without one it is once a minute). Wake Duration 70 s in
  the watch's Display settings keeps a tap lit through a set. So the face is designed
  for that state rather than against it — the two rules below.
- **The whole face is the colour of the state** (`WatchFaceMood`, `WatchFacePalette` in
  `Shared/Engine`, Nuri's ask 2026-09-19): blue to PULL, green while the clock runs,
  red to RE-GRIP, gray to rest (and paused), orange when the next grip is a different
  one (the rest and count-in before it), amber for LESS — EASE OFF above the band and
  LET GO at the release gate are one instruction and the opposite of RE-GRIP's, so they
  cannot share red. A lost link is red like re-grip; `.idle` is never red, because
  CONNECTING on the first second is not an alarm. The fill is a full-bleed background
  behind the face (measured to the top edge under the clock), not turned with the face,
  and nothing else on the face animates on a state change — the word, the hand and the
  counters cut on the beat (an animation on the whole face cross-dissolved the prompt
  word). The colour itself takes the house state curve with the wrist up as an EXPLICIT
  cross-fade over a base painted the outgoing colour: a `Color` view does not
  interpolate on watchOS (it cut in one frame inside an animated transaction), and
  SwiftUI does not promise which of two crossing layers is on top (the first try faded
  one change in three), so the base is what makes either order blend monotonically.
  Verified frame by frame from `simctl io recordVideo`: awake, ~10 in-between frames
  per change; dimmed, none. Dimmed it CUTS — at one redraw a second a 0.3 s fade is one
  frame of the wrong colour. Under reduced luminance the fill drops to a dimmed shade
  of the same hue (Apple's rule for
  large areas of colour in Always On, and the system sets its brightness from the ratio
  of lit pixels), every ink goes white and the small print hides; every shade clears
  4.5:1 against its ink, and `WatchFaceMoodTests` measures that rather than trusting an
  eye. Two departures from the phone's ladder, both deliberate: ARMED is blue, not
  amber, because the wrist's useful glance is "pull now" versus "it is counting"; and
  green is a plain green rather than moss, because it means the clock is running, not
  "easy on the fingers".
- **The clock does not roll when the face is dimmed or the battery rationed**
  (`NumeralRoll`, shared with the phone): a roll caught at one redraw a second is a
  smear, and Low Power Mode on either device cuts the digits the same way (`PowerState`
  observes it). The load readout never rolled — measurements snap.
- **Clocks roll with `RollingNumeral`, never `.contentTransition(.numericText())`.** The
  system transition renders every animated frame of the roll through a CPU Gaussian blur:
  on the phone's runner — the hero seconds and the big rest numeral, rolling once a
  second all session — that was 14 points of a core, 27 % of the app's time in
  `vSepConvolve…` (Time Profiler over USB, real session path, 2026-09-19; with the
  digits snapping the blur vanished and the app fell from 51 % to 37 % of a core). It was
  hunted through the panel shadow and the glass first; neither moved the number. The
  shared numeral keys the text by its value and slides the old one out and the new one
  in — offset and opacity, GPU-composited — and bypasses the keying when it must not roll.
- **The live load is throttled on the wrist** (`WatchForceReadout`, five updates a
  second, rounded to a tenth): a readout that followed every sample re-rendered the face
  eighty times a second and lagged. The engine still sees every sample.
- The watch target builds with `ENABLE_DEBUG_DYLIB: NO`: the Debug stub-plus-dylib
  layout is what the phone's Watch app refuses to install ("integrity could not be
  verified"), and installing through the phone is the route that works when Xcode
  cannot reach the watch.
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
  `-noWorkoutSession` leaves the session out for screenshots). The simulator has no
  wrist to lower either, so `-previewDimmed` forces the Always On environment,
  `-previewLowPower` stands in for Low Power Mode, and `-mockProfile shaky` scripts a
  gauge that drops mid-hold so RE-GRIP can be screenshotted.

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
7. The coloured face under real Always On: the dimmed shades after the system's own
   luminance reduction (the simulator has no wrist to lower, so `-previewDimmed` only
   shows the shades before it), whether the hue still reads from a bench, and what a
   fill costs the battery over a session against the old black face.

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
