# Release record — iOS 1.1.0 (10), 20 September 2026

The session-screen release: the force trace fills the display, the numbers sit on one
Liquid Glass panel that takes the colour of the phase, the iPad gets a landscape column
and the Apple Watch face takes the state's colour. Source commit `2db7b60`, tagged
`ios-v1.1.0-10`; this record is a documentation-only commit on top of it.

## What shipped since 1.0.4 (9)

- The runner redesign: trace as the screen's background, one glass panel and one glass
  dock, the phase wash beneath them, the ambient rest countdown in the open graph, the
  enlarged grip-change hand pushing the panel down with an orange rim.
- The trace draws the raw data as it arrives; the only smoothing is visual and lag-free
  (a fresh packet's stroke fades in over 120 ms, the head dot eases ~60 ms). Three other
  renderings were tried on the day and rejected — the reasons are in `docs/PLATFORMS.md`.
- The playback clock stamps a packet's newest reading at its arrival; nothing sits in the
  future. Measured "now" from the wall clock, so a locked iPad no longer draws nothing.
- The trace buffer keeps two seconds more than the window shows (the fill's start ramp
  no longer jitters on screen). Reduce Motion no longer pauses the trace's timeline.
- Clocks roll without `.contentTransition(.numericText())` — `RollingNumeral` slides,
  GPU-composited. The system transition blurred on the CPU every animated frame: a
  quarter of the app's time on the phone (51 % → 37 % of a core, 0 hitches).
- iPad: the trace runs under the glass column and off the left edge (measured on the M4,
  +4 points of a core, 0 hitches). Reduce Motion note: the setting now only dims the trace.
- Today: the deck glow was removed (decorative colour on a screen where colour should
  mean something); the routine card keeps its glass rim.
- The contributor surface (docs triage, CONTRIBUTING, templates, `./build.sh uitest`),
  the app-layer audit (RunnerView split, shared prompt words and hand geometry), the
  watch-face colour work (branch `watch-face-colour`), the test-hygiene batch.
- DEBUG flags for screenshots and diagnostics: `-previewGauge`, `-mockJitterMS`,
  `-traceHeadLog`; the diagnostics dump leads with the display environment and the
  trace clock's alignment.

## Verification

- 801 unit tests green; the full UI suite green except
  `LegalAgreementUITests.testMaxMeasurementControlsRemainReachableWithLargeText`, which
  fails on 1.0.4's source too (stale flow, named in BUILDING.md).
- Phone (iPhone 17 Pro, USB, Instruments): real session path 60 fps, 0 hitches, 38 % of a
  core; main before this release 52 %. iPad (M4): trace under glass 52 % vs 48 % beside
  it, 0 hitches. Both devices ran every build of the day.
- Not verified on hardware: the Frez Dyno at 250 Hz and the WH-C06 crane scale go
  through the same trace clock (capacity sized from the rate) but were not connected.

## Archive

`xcodebuild archive` with the ignored `project.local.yml` spec, Release configuration,
generic iOS destination, then `xcodebuild -exportArchive` with `destination: upload`
(export options copied from `build/releases/ios-1.0.3-7`, destination changed):

- `~/Library/Developer/Xcode/Archives/2026-09-19/Get a Grip 1.1.0 (10).xcarchive`
- Executable SHA-256: `c358cfb36cd6c0feff34f86dba1a91c43706037fa91bc5c2ed47d210883964f9`
- Upload succeeded 2026-09-19 23:45:41 local; "uploaded package is processing".

## App Store Connect, this submission

- Version 1.1.0 created ("Prepare for Submission"). Screenshots v4 (`appstore/out_v4/`,
  see `appstore/SUBMISSION.md`): iPhone 6.9" × 7 in order (the 6.5" size uses them),
  iPad 13" × 3, Apple Watch Ultra 3 (422 × 514) × 5. What's New (English) from
  `docs/releases/1.1.0/ios-en-US.txt`; promotional text unchanged from 1.0.4; saved.
- Build 10 to be attached once processed. Not submitted: the developer submits.
- The French What's New in `docs/releases/1.1.0/` stays unused until a French listing exists.
