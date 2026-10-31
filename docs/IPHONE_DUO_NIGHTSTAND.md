# iPhone Duo — nightstand mode (the runner on its side)

Built 2026-10-31 from `docs/IPHONE_DUO.md` §6-F route 2: the phone stood up like a tent
on the floor under the hangboard, OUTER display facing the climber, read from one to two
metres with chalk on the hands. Everything below was driven headlessly on the iPhone Duo
simulator (iOS 27.1, Xcode 27.2) and an iPhone 17 Pro Max; what only Nuri's hardware can
answer is listed at the end. Screenshots: `docs/screenshots/duo/` (prefix `nightstand-`).

## What was built

### 1. Landscape on iPhone — the LIVE runner only (`Sources/Runner/RunnerOrientation.swift`)

- **The plist stays portrait-only for iPhone** (`project.yml`), unchanged. iPad keeps all
  four orientations, untouched.
- A `UIApplicationDelegateAdaptor` (`DoigtAppDelegate`, wired in `DoigtApp`) answers
  `supportedInterfaceOrientationsFor` from `RunnerOrientation`: portrait, plus both
  landscapes while a live runner holds the lock. Never upside down.
- **The runner turns the screen itself, by request.** While the lock is held,
  `RunnerOrientation` observes `UIDevice.orientationDidChangeNotification` and calls
  `UIWindowScene.requestGeometryUpdate` for the phone's orientation (mirrored: device
  left → interface right); it also asks once on opening, so a phone ALREADY on its side
  when Start is tapped (the tent pose) turns the runner without being turned again. Flat
  and unknown readings change nothing.
- **Released the moment the session finishes**, not when the cover goes: the summary is
  a portrait form you fill in with the phone in your hand, and releasing at the finish
  means the cover never slides away to reveal a sideways Today. Released again on
  `onDisappear` (every other exit path). Each runner holds its own token, so a late
  release cannot clear a newer runner's hold (`RunnerOrientationLockTests`).
- Why requests and not the usual "plist lists everything, delegate narrows" pattern —
  measured on the Duo simulator, three ways:
  1. Portrait-only plist, delegate widened: SpringBoard logged the new preferences
     (`( Pu ) -> ( Pu Ll Lr )`) and still never turned the cover when the device turned.
  2. Landscapes added to the plist: SpringBoard turned the SCENE with the device even on
     Today, the portrait app was drawn counter-rotated inside it, and when the runner
     then widened the mask the window STAYED counter-rotated — the runner sideways on a
     sideways phone.
  3. Portrait-only plist + explicit `requestGeometryUpdate`: correct, every time.
- The island hand (`IslandHand`) is turned off on its side (`islandHandShown` =
  `hasIsland && verticalSizeClass != .compact`): the island is then beside the content,
  not above it. Re-read when the phone comes back upright.

### 2. On its side: the wide layout at a phone's scale (`RunnerView.wideContent`)

**The far view is gone** (replaced the same day, 2026-10-31). It was a third layout for
compact height: a face with one numeral sized to the height (the "one number readable at
2 m" idea), the phase word and a time bar, beside a column of controls. Nuri, on his Duo:

> "duo landscape looks like shit to me. Like the two boxes are pushed so high and there's
> so much dead space below. There's dead space to the right. The loading blue bar is cut
> off — the graph just cuts off... the countdown is HUGE and covers the whole graph — huge
> issue. We have to fit it back into the boxes on the right. Please rethink this."

So a phone on its side now draws the HOUSE layout — the iPad's, which is the portrait
runner's language turned sideways — and the reading-distance numeral is dropped. One
layout fewer, not a variant: `live(_:)` picks `.wide` whenever the window is wider than
tall (it used to also require regular width), and `farContent`, the giant numeral, its
time bar, `farPlot` and the face/column split are deleted.

- **The trace is the screen**, as on the iPad: `backgroundTrace` draws it full-bleed
  (`ignoresSafeArea`), under the column and under the system's side columns, so no strip
  reads as dead space; the target lane and the threshold rule span the whole canvas. The
  curve's HEAD stops 8 pt inside the trailing SAFE edge (`WideColumn.headClearance` =
  trailing inset + 8): on the Duo's outer display the bars and the camera cutout share a
  trailing 84 pt column in one landscape, and the newest point must never sit under the
  camera. The phase wash runs from the leading edge to the column's trailing edge, as on
  the iPad.
- **One column on the LEADING side**: the existing `infoPanel` (identity row, hand word,
  kg beside the countdown, time bar and pills, set/pull counters) top-aligned, the dock
  (`controls`) bottom-aligned, the newest seconds of the curve in the clear on the
  trailing side. Timer-only: `timerOnlyIdentity` + `timerProgress` in the column, the dial
  where the graph was — the iPad's timer-only layout.
- **Width**: `min(440, 0.6 × safe width)` — 440 on an iPad and a Pro Max (832 safe), 356
  on the Duo's outer display (594 safe).
- **Padding**: 0 of our own on a side that carries a system inset (the Duo's bars, a Pro
  Max's island side), 20 on a bare side — the rule the far view's last build introduced
  after the 105 pt of nothing on Nuri's Duo. Top 8, bottom 12 above the system's own
  bottom inset; at least 12 between panel and dock.
- **Scale, from the height** (`RunnerView.phoneScale`): the largest of 1.2 / 1.1 / 1.0
  whose MEASURED panel + 12 + dock fits the column, at the default text size and smaller
  (the iPad, at regular height, keeps 1.4). Measured with `-dumpRunnerLayout
  -runnerWideScale <s>`: panel 264 / 284 / 304 pt at 1.0 / 1.1 / 1.2, dock 120 (two rows:
  Pause · Tare, then Skip pull · Skip set · Hold to end — Hold to end fits the shared
  row at 356 and at 440, so no third row). The Pro Max's column is 400 pt and the Duo's
  412, against 396 at 1.0 and 416 at 1.1: **both phones draw 1.0**; 1.1 would engage only
  on a compact-height window at least 4 pt taller than the Duo's.
- **To fit at 1.0 at all**, three things on a phone on its side only (`sidewaysPanel` —
  compact height, below the accessibility sizes): the grip glyph sits BESIDE the name at
  0.7 instead of above it (one identity row: the stacked glyph cost ~40 pt and ran the
  dock off the bottom of the Pro Max), the identity block's spacing is 8 instead of 12,
  and the panel's vertical padding is 10/10 instead of 14/12. Before those the panel was
  321 pt and panel + dock overflowed the 420 pt safe height with no padding at all. The
  type is never drawn below 1.0. The rest summary still fits inside the hidden identity
  block (panel 264 in a rest, measured).
- **The column scrolls only when it must**: on a phone on its side it sits in a
  `ScrollView` (`.basedOnSize`, shadow unclipped) whose content has the column's height as
  its minimum — at the default size it fits and never moves; at XXXL the panel grows to
  293 and the column scrolls by ~25 pt rather than clip. Accessibility sizes keep the
  runner's existing fallback, the scrolling stack. The iPad's column is unchanged (no
  scroll view, 1.4, 22 pt bottom).
- **The glass container's blend distance is 6 on a phone** (24 on the iPad): the panel and
  dock can sit 12–16 pt apart there, and at 24 they melt into one blob with a waist.
- The island hand stays off on its side; the panel draws the glyph (above).
- **The grip shows while CONNECTING too** (kept from the far view): `.idle` has no slot,
  so `RunnerSession.publish` names the plan's first slot — grip and hand — until the
  session goes live (`RunnerConnectingIdentityTests`).
- Identifiers: `runner.column` (new: the column, a scroll view on a phone), `runner.panel`,
  `runner.dock`, `runner.graph` (the open region right of the column), and — DEBUG, with
  `-exposeRunnerTrace` — `runner.trace`, the full-bleed canvas, so a UI test can measure
  that it runs edge to edge. Without the argument the canvas stays out of the
  accessibility tree.

### 3. Regular-width portrait (unfolded inner display, iPad upright)

The stacked column now takes `Metrics.maxContentWidthRegular` (560) on a regular-width
window and draws the identity block at the wide scale (1.4×) — not at accessibility
sizes, where the type size already is the scale. Phones are byte-for-byte the old
layout (compact width → 440 cap, scale 1).

A window narrower than any iPhone (< 360 pt — Split View's 313 pt half) gives Hold to end
its own dock row; the narrowest iPhone is 375, so every phone keeps the measured two-row
dock.

### 4. Odds and ends

- `-dumpRunnerLayout` (DEBUG): `Sources/Runner/RunnerLayoutProbe.swift` rewrites
  `Documents/runner-layout.txt` every second with the window size, safe-area insets,
  interface orientation, size classes and the runner's column/panel/graph/hero/dock/trace
  frames. Release builds compile the modifier to nothing. **Frames recorded before a
  rotation are not refreshed by it** — an app launched portrait and then turned (always
  the case on the Duo, see below) keeps stale frames; launch with the device already
  turned (the Pro Max), or measure the screenshot's pixels.
- `-runnerWideScale <s>` (DEBUG) forces the phone-on-its-side panel scale, to re-measure
  `RunnerLayout.measuredPanel` at each step. `-exposeRunnerTrace` (DEBUG) puts the trace
  canvas in the accessibility tree as `runner.trace`.
- Two build warnings fixed on the way: `UIScreen.main` in the DEBUG diagnostics now reads
  the scene's screen; `IslandHand.islandInset` is `nonisolated`.

## Measured sizes

Points, from `-dumpRunnerLayout` (window frame, `safeAreaInsets`, trait collection).

| Pose / window | Window | Safe area (top, leading, bottom, trailing) | Size class (h × v) | Runner |
|---|---|---|---|---|
| Duo outer, closed portrait | 466 × 678 | 0, 0, 34, 84 | compact × regular | stacked: panel 342 × 301, graph 342 × 168, dock 342 × 120 at y 502 |
| Duo outer, closed landscape (interface left) | 678 × 466 | 0, 0, 34, 84 | compact × compact | wide at 1.0: column 356 × 412 at x 20, y 8; panel 356 × 264 at y 8, dock 356 × 120 at y 300 (pixels — the probe's frames go stale across the turn); open graph right of the column to the bars; trace canvas 678 × 466 |
| Duo outer, closed landscape (interface right) | 678 × 466 | 0, **84**, 34, 0 | compact × compact | wide, mirrored: same column, flush against the bars (0 pt of our padding; `RunnerWideColumnTests`) — not screenshotted this round, the simulator would only take one turn |
| Duo inner, 669 × 951 (`appResize`) | 669 × 951 | 82, 0, 20, 0 | regular × regular | stacked at 560: panel 520 × 386 (scale 1.4), hero 488 × 127, graph 520 × 288, dock 520 × 120 |
| Split View half, 313 × 678 (`appResize`) | 313 × 678 | 20, 0, 20, 0 | compact × regular | stacked at 273 wide: panel 273 × 328, graph 273 × 126, dock in three rows |
| iPhone 17 Pro Max, landscape (device turned before launch) | 956 × 440 | 0, 62, 20, 62 | **regular** × compact | wide at 1.0: column 440 × 400 at x 62, y 8; panel 440 × 264 at y 8 (284 at 1.1, 304 at 1.2); dock 440 × 120 at y 288; graph 370 × 400 at x 524; trace 956 × 440 at 0, 0 |

The Pro Max on its side is REGULAR width — so the scale is keyed to the height class, not
the width: the first build gave it the iPad's 1.4 and the panel ran 75 pt off the top.

At XXXL on the Pro Max: panel 293, the column scrolls by ~25 pt. At accessibility sizes a
phone on its side draws the scrolling stack (on the Pro Max 520 wide, centred).

## Screenshots (`docs/screenshots/duo/`)

| File | What |
|---|---|
| `nightstand-runner-wide-closed-landscape.png` | Duo outer, on its side (bars trailing), mid-pull: the column leading, the trace full-bleed, its head clear of the bars |
| `nightstand-runner-closed-portrait.png` | Duo outer, upright: the stacked runner, unchanged |
| `nightstand-today-closed-phone-sideways.png` | Today launched with the Duo on its side: still portrait |
| `nightstand-runner-open-portrait-669.png` | unfolded inner (669 × 951 via `appResize`): the 560 column at 1.4× |
| `nightstand-runner-split-313.png` | Split View half (313 × 678): Hold to end on its own row; the graph shrinks to ~80 pt |
| `nightstand-promax-wide-working.png`, `-rest.png`, `-connecting.png`, `-timer-only.png` | Pro Max on its side: a mock session mid-pull, a rest (summary in the panel, the iPad's rest countdown in the open graph), connecting (grip named), no gauge (dial where the graph was) |
| `nightstand-promax-portrait-stack.png` | Pro Max upright at `large`: the stack, pixel-identical to the previous build outside the live trace band |
| `nightstand-promax-summary-portrait.png`, `nightstand-promax-today-after-session.png` | after Hold to end on a sideways phone: summary and Today both portrait |

## Verification

- `NightstandRunnerUITests` (iPhone): on its side, the panel and dock in one LEADING
  column beside the open graph, panel top-aligned, dock bottom-aligned without scrolling,
  nothing outside the window, every dock action hittable, and the trace canvas
  (`-exposeRunnerTrace`) running from the leading edge to the trailing edge under the
  glass; the stack again upright; the same column through a REST, and timer-only with the
  dock leading; Today never drawn in landscape, the runner opening sideways on a phone
  already sideways, the summary portrait, Today portrait after Save. **All three pass on
  the iPhone 17 Pro Max.** They SKIP on the Duo simulator — see the next section; the
  layout there was screenshotted instead.
- `RunnerWideColumnTests` (unit): the column's width, padding, scale and head clearance on
  the Duo (both landscapes), the Pro Max and an iPad; the scale ladder's thresholds.
- Wide-layout pass (2026-10-31, the far view's replacement): `./build.sh test` 968 / 968;
  `NightstandRunnerUITests` 3 / 3, `RunnerDockUITests` 2 / 2, `RunnerActionMarginsUITests`
  2 / 2 on the Pro Max; `WideLayoutUITests` 2 / 2 on the iPad Pro 11" (M5) — and its
  landscape screenshot unchanged (440 column, 1.4).
- `RunnerOrientationLockTests` (new, unit): the mask arithmetic, the token rule, the
  device→interface mirror.
- First build (the far view): `./build.sh test`: 951 tests, 0 failures (Pro Max). `WideLayoutUITests`: 2 / 2 on an
  iPad Pro 11" (M5), iOS 27.0 — the iPad's wide layout and its portrait stack (now 560
  wide at 1.4×) both hold. `RunnerDockUITests` 2 / 2, `RunnerActionMarginsUITests` 2 / 2,
  `RestFocusUITests` 13 / 13 on the Pro Max: the phone's stack is unchanged.

## The Duo simulator's orientation seams (iOS 27.1) — measured, not fixed

- **`XCUIDevice.shared.orientation` never turns the closed Duo's outer display**: three
  tests on a freshly booted device, the cover stayed portrait and the app was never told.
  On the Pro Max the same tests pass. Hence the skip.
- **`xcrun devicectl device orientation set` lags by one**: `get` reports the previous
  `set`. And once the closed Duo has been turned to landscape, setting portrait does not
  take until the device is rebooted (seen three times). The wide-layout pass saw the
  mirror image: after a reboot, three `set landscapeLeft` calls and a `rotate left` were
  all "accepted" while `dtdeviceinfod` kept reporting portrait; the FIRST call with a
  different value (`set landscapeRight`) turned the device at once, and the running
  runner followed it (interface landscapeLeft). The recipe that worked: launch the
  session, then `set` a landscape the device is not already reported in.
- **The app's `UIDevice.orientation` is read once, at launch**, on the Duo: no further
  "Setting device orientation" log lines arrive in the app as the device turns. The
  runner's ask-on-opening is what turned it in the simulator (device turned first, then
  the session started).
- Turning the closed Duo to landscape makes SpringBoard log
  `Ambient magic pose event detected [tabletopPose : Amb ; hingeState : Cl ;
  deviceOrientation : Lr ; deviceOrientationForCover: Lr …]` — the StandBy (Ambient)
  machinery watching the tent pose, with a separate device orientation for the COVER.
- `appResize` returns "Resizing is not available on this device right now" while the
  closed Duo is stuck in landscape; it worked after a reboot (portrait).

## StandBy probe

`xcrun devicectl device settings standby --show --device <duo>` and
`devicectl device info standby`, in four states:

| State | `info standby` | `--show` |
|---|---|---|
| Closed, portrait, home screen | "StandBy is currently unavailable" | error 1001 |
| Session running, portrait | unavailable | error 1001 |
| Session running, device landscape | unavailable | error 1001 |
| Session backgrounded (Settings frontmost), landscape | unavailable | error 1001 |
| `--show --duration 8` | — | error 1001 |

Error 1001, verbatim: "This device can't show StandBy in its current physical
configuration. StandBy needs the lock screen to be showing, and the device to be in a
pose that supports StandBy." There is no headless lock seam (`simctl` has none;
`devicectl device info lockState` reads the state but nothing sets it), so the lock-screen
precondition could not be met and **the Live Activity was never reachable in StandBy**.
Backgrounding the session into Settings in landscape again showed no compact
presentation in the island column (as in `IPHONE_DUO.md` finding 7: the mock gauge is a
timer, the app suspends). Settings on the Duo simulator does carry a StandBy row.

## Not built

- **The StandBy `systemSmall` Today widget** (§6-F route 3): the widget target has no App
  Group data path today (it renders only the Live Activity from `ContentState`), so per
  the brief it was skipped rather than half-built.

## Hardware findings (Nuri's Duo, 2026-10-31)

- The far view runs on the outer display in landscape with the phone on its side; first
  build installed over USB (iOS 27.2).
- **In nightstand landscape the camera cutout is at the BOTTOM RIGHT**, inside the
  system's reserved side column. So there is nothing for an island hand to hang from in
  this pose; the panel's own glyph stays. The trace's head is kept inside the safe area
  for the same reason (`headClearance`) — the newest point never under the camera. The
  sideways hand for CLOSED PORTRAIT (the
  island is a vertical pill at the top of the right edge; fingers would reach in
  horizontally) is an open design question, to be anchored on the occlusion reserved
  region rather than a top inset.
- Nuri's read of the first far view: dead space right of the controls column (the 84 pt
  reserved column plus our 20 pt margin) — fix queued: margin dropped against a system
  column, column grows, trace full-bleed under the cards; the identity row (glyph + grip)
  must also show while CONNECTING.
- Nuri's read of the SECOND far view (boxes pushed high, dead space below and right, the
  time bar cut off, a countdown covering the graph — quoted in §2): the far view was
  dropped for the wide layout at a phone's scale. **To check on the Duo**: the column
  reads as one with the iPad's, the dock sits at the bottom with no dead band under it,
  and whether the 84 pt bar column — trace canvas, lane and threshold rule, but no curve
  past the head — still reads as dead space beside the newest point.

## Owed to Nuri's hardware

1. **Does iOS give the foreground app the outer display in tent pose?** Or does StandBy
   (the Ambient pose detector above) take it as the phone is stood up? If StandBy wins,
   does tapping through return to the runner, and does the runner then turn?
2. **Does a turn of the closed Duo reach the app?** The runner follows
   `UIDevice.orientationDidChangeNotification` and also leaves SpringBoard free to turn it
   (the delegate mask is wide). The simulator delivered neither; a real device should
   deliver both. If neither, the fallback that already works is turning the phone BEFORE
   tapping Start.
3. **Which landscape is the tent?** The column is always LEADING (flush against the bars
   when they are leading, 20 pt in when they are trailing) — check the camera does not
   sit over the panel or the trace's head in either.
4. **The Live Activity on the vertical island, and in StandBy** (route 1) — a real
   Progressor session, backgrounded, phone stood up and locked.
5. **Reading distance**: the panel at 1.0 — the hand word, kg and countdown — from 1–2 m,
   under the board's lighting, in both schemes. The far view's one-number-at-2-m idea is
   gone on purpose; if the panel is too small from the floor, the lever is the scale
   ladder (`RunnerLayout.measuredPanel`), which needs height the Duo does not have at
   1.1 by 4 pt.
6. **The session across the stand-up**: a connected Progressor streaming through
   portrait → landscape → portrait without a reconnect or a paused rep.
