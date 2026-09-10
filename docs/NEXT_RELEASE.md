# Next release fixes and features

Active development has moved to the [1.1.0 plan](RELEASE_1.1.0_PLAN.md) on
`codex/release-1-1-0`. The completed items below document the preceding release.

## App-wide weight units — iOS and Android

Status: implemented and submitted for iOS **1.0.2 (6)** and Android **1.0.1 (8)**.
See [release verification and submission status](RELEASE_2026-09-08_UNITS_AND_BORDERS.md).

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
