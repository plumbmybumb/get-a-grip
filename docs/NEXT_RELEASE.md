# Release record and the queue behind it

**iOS 1.1.2 (12)** and **Android 1.1.0 (13)** were submitted on **23 September 2026** and are
**both live** since 24 September (App Store; Google Play production, the first public
Android release) ([iOS record](releases/notes/RELEASE_2026-09-23_IOS_1.1.2.md),
[Android record](releases/notes/RELEASE_2026-09-23_ANDROID_13.md)). They must be live before **30 September 2026
00:00 UTC**, when Frez retires the old Dyno coefficient endpoint the released builds call.

The released builds are **iOS 1.1.1 (11)** (App Store) and **Android 1.0.2 (12)** (Closed
testing - Alpha), both approved. Android production access was applied for after the
two-week closed test and is awaiting Google's decision. The development plan is kept in
[history/RELEASE_1.1.0_PLAN.md](history/RELEASE_1.1.0_PLAN.md).

## iOS 1.1.2 (12) and Android 1.1.0 (13) — the Frez endpoint, reliability, speed

Status: **released on both stores, 24 September 2026.** What's New: [docs/releases/1.1.2/](releases/1.1.2/) (iOS,
English and French) and [ANDROID_1.1.0_WHATS_NEW.txt](releases/notes/ANDROID_1.1.0_WHATS_NEW.txt).

- **Frez Dyno calibration moves to `/functions/v1/dyno-coefficient`.** Frez retires
  `/v1/dyno/coefficient` on 30 September 2026 00:00 UTC; the key, header, query and
  response are unchanged. A unit calibrated before the cutoff keeps its stored
  coefficient, so on an old build only a Dyno connecting for the FIRST time fails.
- **A finished session survives the app dying on its summary.** It is written to disk at
  the finish and offered back on the next launch (both platforms).
- **The session lets go at the finish** — stream, heartbeat, idle-timer lock, cue output —
  instead of when the summary is dismissed.
- **The 04:00 training day, finished properly:** reminders suppress by training day (a
  session finished at 00:30 no longer silenced the next day), sessions are stamped by the
  day they started, and the repair runs once per device rather than on every launch.
- **Android cues match the iPhone's:** the output is kept fed between cues (it slept during
  rests and clipped the next tick), and haptics use the vibrator's own tuned primitives.
- **Speed:** saves no longer re-render every tab; History and Export stop decoding the
  whole history on the main thread; the Android builder redraws only the row you edit;
  Android ships a baseline profile.
- **Reliability:** Progressor reconnect after a Bluetooth power cycle (iOS); direct
  reconnect with the screen off and a shared scan budget (Android); rotation keeps open
  work and a double tap cannot save twice (Android); the Live Activity stops counting
  while the hold clock is stopped and goes stale if the app dies.
- **Audio diagnostics:** whether other audio was playing when a session started, and
  whether it still was two seconds later — evidence for the "a session paused my podcast"
  report, which the code does not explain.

Owed on hardware before release: a Frez Dyno first-time calibration against the new
endpoint; Bluetooth off/on with a Progressor (iOS); screen-locked reconnect (Android);
the recovery prompt after killing the app on a summary; Android cues on speaker and
Bluetooth.

## iOS 1.1.1 (11) and Android 1.0.2 (12) — the training day, deleted routines, the gauge as a screen

Status: **approved and released.** iOS **1.1.1 (11)** is on the App Store, with What's New
from [docs/releases/1.1.1/](releases/1.1.1/). Android **1.0.2 (12)** is live on Closed
testing - Alpha at 100%.
The previous iOS 1.1.0 (10) was approved and released, so 1.1.1 is its own version.
Android carries the day rule, the cascading delete, the odometer in History, the rimmed
gauge picker and the gauge button on Today; its working screens (runner and gauge) keep
their current look by Nuri's call.

- **A training day turns at 04:00, not midnight.** A hang started at 23:47 and finished
  44 seconds past midnight was filed under the morning after, so one evening scored as
  two days (Nuri's own history, 2026-09-20). `DayStamp.today()` is the training day,
  `DayClock` wakes itself at the rollover, and `SessionLedger.repairTrainingDays` re-files
  every session the app timed under the day it started in, once, on launch. History dates
  every row by that day, so the row, the grid and the tally agree.
- **Deleting a routine deletes its sessions**, restorable together from the same ten-second
  Undo, and the bar says how many went. A "Load per grip" card no longer outlives its
  routine; sessions of a routine deleted before this rule still list under the frozen
  name, but get no trend card.
- **The live gauge is the runner's screen without a routine** — the trace as the screen,
  the numbers on one glass panel, the actions on one glass dock — and it is one tap from
  Today through the gauge button on the bar, which replaces the Settings row. The watch
  got the same door: the gauge row on its list opens a live gauge screen. Android keeps
  its gauge as it is.
- **History gets the odometer**, between the trend deck and the sessions: sessions, pulls,
  time under tension, volume (load × pulls), days trained, climbing days and the heaviest
  pull, all time, with the date they count from.
- **Settings opens on the gauge picker**, rimmed in bleu with a line saying it is where you
  choose among the gauges the app drives.

## 1.1.0 — the session screen

Status: iOS **1.1.0 (10)** approved and released on the App Store. The Android side
ships with **1.0.1 (11)**; see [its release record](releases/notes/RELEASE_2026-09-18_ANDROID_11.md).

- The force trace fills the whole screen, and the numbers sit on one Liquid Glass panel
  that takes the colour of the moment: blue while the clock runs, amber when it is
  waiting on you. During a rest the countdown fills the screen, big enough to read from
  the wall.
- iPad: in landscape the graph takes the whole display, with the grip, the next pull and
  the controls in one column beside it.
- Apple Watch: the whole face is the colour of the state — blue to pull, green while the
  clock runs, red to re-grip, orange before a different grip.
- Low Power Mode on either device stops the clocks rolling.
- A gauge that delivers its readings in bursts no longer empties the graph.

## App-wide weight units — iOS and Android

Status: implemented and submitted for iOS **1.0.2 (6)** and Android **1.0.1 (8)**.
See [release verification and submission status](releases/notes/RELEASE_2026-09-08_UNITS_AND_BORDERS.md).

- [x] Add a persistent **Weight units: kg / lb** preference in Settings, keeping
  kilograms as the existing default. Switching should update the app immediately.
- [x] Apply the chosen unit to every weight display: live readings, graph axes,
  target bands, routine cards, maxes, workout summaries, history, charts, tare and
  warning messages, iOS Live Activities, and Android workout notifications.
- [x] Apply the same preference to all weight entry fields and sliders, including
  typed targets and manual maxes. Labels, precision, presets and slider steps must
  make sense in the selected unit.
- [x] Review exports and share surfaces for consistent, explicit units. Preserve
  existing machine-readable contracts and cross-platform routine compatibility;
  never put pound values into fields labeled kilograms.
- [x] Keep stored measurements, gauge decoding and engine calculations in their
  canonical kilograms. Convert at display/input boundaries so toggling units does
  not rewrite history, alter targets, change thresholds or accumulate rounding
  drift. The app preference is independent of the scale's own display setting.
- [x] Verify existing workouts and per-hand maxes, kg/lb entry round trips,
  repeated toggling, relaunch persistence, English/French formatting, and routine
  sharing between users with different preferences on both platforms.

This setting affects weights; grip edge sizes remain in millimeters and percentage
targets retain their existing meaning.

## Countdown rounding — iOS and Android

Status: fixed, verified and included in the submitted iOS **1.0.2 (6)** and Android
**1.0.1 (8)** builds.

- [x] Fix countdown-to-whole-seconds rounding in both engines without changing
  actual countdown deadlines or measured pull-time accumulation.
- [x] Use consistent rounding for displayed seconds and countdown cues, including
  lead-in, rest, set breaks, and pause/resume.
- [x] Verify the iOS Live Activity deadline does not inherit an extra second.
- [x] Add deterministic floating-point boundary regression tests on both
  platforms; retain the existing exact 30-second expectation.

The calculation `ceil((start + duration) - start)` can display one extra second
because of floating-point rounding. For example, with `start = 226.004` and
`duration = 30`, the subtraction gives `30.00000000000003`, which rounds up to 31.
The main display corrects on the next tick, but the iOS Live Activity can retain
the extra second because its deadline is anchored at the phase change. The actual
30-second rest still ends at its scheduled deadline; measured force timing is
unaffected.

Evidence: [failed CI run 34232722184](https://github.com/plumbmybumb/get-a-grip/actions/runs/34232722184),
`RunnerTrainingGuidanceTests.testSetBoundaryShowsActualNextSetHandAndKeepsSetBreakCountdown`
(31 displayed, 30 expected). The failure occurred on a documentation-only commit
after the release source passed CI. The same arithmetic exists in both engines.

Implementation entry points:

- `Shared/Engine/SessionRunner.swift`: `tick(at:)`, `secondsRemaining(at:)`, and
  countdown adjustment on resume.
- `android/engine/src/main/kotlin/run/nuri/getagrip/engine/SessionRunner.kt`:
  corresponding countdown paths.
- `Sources/Runner/RunnerSession.swift`: Live Activity deadline construction.

Do not fix this by loosening the assertion or repeatedly rerunning a failing test.
Cover both initial countdown rounding and integer boundaries after pause/resume.


## Screen borders and rest layout

- [x] Reconcile Android's native display outline with logical window dimensions,
  including resolution changes, rotation and window offsets. Contradictory shape
  dimensions fall back to window-relative rounded corners.
- [x] Move the rest / set-break label below the hero numbers, centered between set
  and pull counts, with more legible typography on both platforms.
- [x] Add a steady, quieter gray screen border during active rest. Blue pulling,
  orange release and red warning cues retain their meaning; paused states have no
  active border. Keep the graph's available height stable across these phases.

The countdown fixture update changes one expected rest tick at
`4.400000000000001` seconds. Inputs, timestamps, recorded reps and final state are
unchanged. The Swift oracle verifies all 39 scenarios / 4,130 steps, plus 70 routine
share URLs and 18 export scenarios. Bluetooth transport code is unchanged.


## WH-C06 set to pounds read 2.2× too heavy — iOS and Android

Status: fixed, unreleased. Field report 2026-09-18 (Android 1.0.1 (9), Pixel 8): every
reading from a WH-C06 crane scale arrived "doubled".

- The scale broadcasts hundredths of whatever unit its display is set to, and the low
  nibble of its status byte names that unit. Both codecs divided by 100 and called the
  result kilograms, so a scale switched to pounds was read as kilograms — 2.2× too
  heavy, and 2.2× again on a phone showing pounds.
- Two firmwares are known and agree on kilograms. The maker's own reference
  (`ScaleWatcher.java`, Weiheng's SDK): 1 kg, 2 lb, 3 st, 4 jin. TheLastKiwi/Dyna,
  written against a US unit: 1 in kilograms, **0 in pounds**. `WHC06Codec` now reads
  1 as kilograms, 0 and 2 as pounds, 3 as stone, 4 as jin, and anything else — a code
  neither firmware uses, or a frame too short to carry the byte — as kilograms, exactly
  as before. The capacity window applies after conversion.
- Both broadcast clients name the unit and the raw count in Diagnostics once per link
  and on unit change ("scale unit: pounds (raw count 3500)"), with no payload bytes in
  the report. A second number from the same reporter — 7.2 lb shown for a 35 lb
  dumbbell with the scale in kg — is not explained by any code path in the build he
  runs; that diagnostic line is what will explain it.
- The cross-platform codec fixture gains the pounds, stone, jin, unknown-code and
  capacity cases; unit tests on both platforms pin the same table.

## Start an alternating routine on the right hand — iOS and Android

Status: implemented on the Frez Dyno branch, unreleased.

- [x] A routine-level **starting hand** (`SessionPlan.startingHand`, left by default)
  mirrors both alternating modes: L R L R becomes R L R L, and L L L R R R becomes
  R R R L L L. Alternation still resets at every set boundary, to that hand. Both
  hands ignore it.
- [x] One control writes it: a small **Swap** button on the hand-order strip in the
  builder's REST & HANDS card, hidden under Both hands. The strip and its spoken
  sentence flip with it, and the routine overview says which hand leads.
- [x] Persisted as a raw column beside the hand mode (`startingHandRaw`; Room schema
  version 2 by auto-migration), carried by the undo snapshot, the share link and the
  workout log's frozen plan. Every blob written before the field reads as left; `both`
  and unknown raws read as left too, never as a failure.
- [x] Cross-engine fixtures: the blob, sequence and share fixtures carry the field,
  and a right-first runner trace (`hands-alternate-right-first`) is recorded by the
  Kotlin engine and replayed by the Swift oracle.

## Builder lag while editing during an animation — iOS first

Status: implemented on iOS, unreleased. Android to follow.

A tester on an iPhone 13 mini reported the UI "lagging a bit" whenever they were
editing something while an animation played. Counted on the pinned simulator with
`_printChanges()` in the builder, editing the seeded six-set routine:

| Edit | Views re-evaluated before | after |
| --- | --- | --- |
| One keystroke in the routine name | 15 | 3 |
| One detent of the set-break dial | 14 | 7 |
| One frame of a target-band drag (the trimmer writes every frame) | 25 | 14 |
| Expanding a set | 28, all six rows | 12, that row only |

What remains per edit is the document body (it owns the draft), the child whose value
changed, and that child's own controls. A keystroke also re-runs each value row on
screen once, through its focus state, which is the keyboard's doing rather than the
draft's.

- [x] **Rows and sections compare themselves on values.** Every child of the builder
  used to take the whole draft or plan as a binding, so any edit re-ran all of them.
  `SetRowView`, `RhythmSection`, `FineTuningSection` and `EveryDaySection` now take
  one binding to write through and values for everything they draw
  (`SessionPlan.routineLevel`, `RoutineDraft.schedule` — `BuilderInputs.swift`), are
  `Equatable` on those values and are wrapped in `.equatable()`. The set actions are
  keyed on the set's id rather than a captured index, because a row whose neighbour
  was removed keeps its old closures.
- [x] **The builder's cards are flat fills, not materials.** Nine `.regularMaterial`
  cards sat on one scrolling screen over a static, low-frequency background, each a
  live blur re-rendered every frame it moved or resized. `CardSurface.flat` is a
  translucent fill fitted from screenshot pixels to the material's rendered colour
  in both schemes (`CardFill.swift`), opaque under Reduce Transparency. Real glass
  stays on chrome that content scrolls under. Verified by sampling the same six
  patches of the edit screen before and after in each scheme: every card patch is
  within 1.3 RGB units of the material, and the margins are unchanged.
- [ ] Android: the Compose builder has the same shape; port both changes.
- [ ] Ask the tester to retry on the next TestFlight build, and, if any lag remains,
  to try Reduce Transparency, which separates blur cost from layout cost.

## Ask for a rating once, after the fifth session — iOS first

Status: implemented on iOS for 1.0.4, unreleased. Android to follow.

What Apple allows, from the Human Interface Guidelines (Ratings and reviews), the
StoreKit `RequestReviewAction` reference and App Store Review Guidelines 1.1.7 and
3.2.2:

- Only the system prompt. Custom rating UI is disallowed and the prompt's wording is
  Apple's. A persistent link to the write-a-review page is allowed on a settings
  screen.
- Ask after demonstrated engagement, such as a completed task, never on first launch
  or during onboarding, at a natural stopping point, and never in response to a tap,
  because the prompt may not appear at all.
- The system shows it at most three times per 365 days per device, not at all to
  people who opted out, always in development builds and never in TestFlight.
- No incentives, no gating of features on a rating, no manipulation.

- [x] `ReviewRequestPolicy`: once per device, from the fifth saved runner session.
  Today notices, from the store's save counter, that the runner cover it just closed
  saved a session, checks the policy and calls `requestReview` a second later, on the
  settled screen. Climbs and hand-logged hangs do not count; only sessions the app
  ran. A discarded session never triggers it.
- [x] Settings › Support: "Rate on the App Store" opens the write-review page directly,
  under the line "Get a Grip is free and open source. A rating helps other climbers
  find it." The open-source framing lives here because the system prompt cannot
  carry it.
- [ ] Android: the same policy over Google Play's in-app review API, which has its own
  quota and forbids incentives in the same way.
