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

### 2. The far view — compact height (`RunnerView.farContent`)

`verticalSizeClass == .compact` is checked FIRST in `live(_:)`, at every text size.

- **The face** (no glass): the grip, small — `RunnerGripGlyph` at 0.7 beside `nameRow`
  (badge, name, target chip); the phase word (`prompt`, single line, 1.6×); **one numeral
  as large as the height allows**; `RunnerTimeBar` under it; the no-signal notice when
  there is one. **The trace is the SCREEN** (2026-10-31, Nuri on his Duo: confined to
  the numeral's width it left the room beside it empty): `backgroundTrace` draws it
  full-bleed, into the system columns and under the glass, as on the iPad. It still
  PLOTS in the numeral's vertical band (`farPlot`, from the measured numeral frame), so
  it never crosses the phase word or the time bar, and its head stops at the face's
  trailing edge — newest seconds in the clear. The phase colour washes the whole screen
  (`PhaseWash`, full height), timer-only included — from two metres the colour is read
  before the word.
- **The grip shows while CONNECTING too** (2026-10-31): `.idle` has no slot, so
  `RunnerSession.publish` names the plan's first slot — grip and hand — until the
  session goes live. Connecting, lead-in, armed, working and rest all draw the glyph
  and name in the same place, the top of the face.
- **The numeral**: `@ScaledMetric farHeroSize` (340, relative to largeTitle), clamped to
  `height / 0.8` — sized to the DIGITS (≈0.72 em), with the line box's empty ascender and
  descender allowed to overhang the region. Sizing by the line box left it at ~60 % of
  its room. Medium weight (the watch face's reading-distance choice), rolls like every
  clock via `RollingNumeral` (the house replacement for `.numericText()` on countdowns),
  amber when the rep is stalled, hidden from VoiceOver like the hero.
- **The column** beside the face: one glass panel (live kg via `LiveForceReadout`,
  `RoutinePills`, `counters` — or `timerProgress` when timer-only) and the dock
  (`controls(narrow: true)`: Pause/Tare, Skip pull/Skip set, then **Hold to end on a row
  of its own** — three-up at 250 pt wrapped "Skip pull" and truncated "Hold…"). Every
  dock capsule keeps ONE line (`AdaptiveActionRow(oneLinePerCell:)`): an icon steps
  aside first (`DockActionLabel` — "Con- / nect" hyphenated beside its glyph on the
  Duo at the default size), then the row drops a column. It
  scrolls (`scrollBounceBehavior(.basedOnSize)`), so every action stays reachable at any
  text size. `GlassEffectContainer(spacing: 6)`: at the stack's 24 the panel and dock,
  12 pt apart, melted into one blob with a waist.
- **The column follows the system's vertical bars**, read from the root reader's
  `safeAreaInsets`: on the Duo's outer display the bars are TRAILING in one landscape
  (insets 0 / 84) and LEADING in the other (84 / 0). Symmetric insets (a Pro Max on its
  side) keep it trailing.
- Width: 45 % of the safe width, 250 pt at the least; HALF the width at accessibility
  sizes. **No margin of our own beside a system column** (2026-10-31): a side with a
  safe-area inset (the Duo's bars, a Pro Max's island) gets 0 pt of our padding, since
  the system's column already is the margin — the fixed 250 pt plus 20 pt beside the
  bars left ~105 pt of nothing on Nuri's Duo. A side without one keeps the 20 pt.
- **Accessibility sizes**: the face stops growing at xxxLarge (`dynamicTypeSize` on the
  face, plus `promptSize`/`nameSize` clamped by hand to their xxxLarge values 40/21 —
  a modifier only reaches the views below it, not RunnerView's own `@ScaledMetric`s).
  Measured at AX3 before the clamp: the grip row and the word left the numeral a third of
  the face. The column is unclamped and scrolls; its counters gained a third
  `ViewThatFits` candidate (Set over Pull) that only engages where the pair overflowed.
- Same identifiers as the other layouts (`runner.graph` is the face, `runner.hero` the
  numeral, `runner.panel`, `runner.dock`, `runner.farColumn`), so tests measure it the
  same way. `wide` and `stacked` are unchanged.

### 3. Regular-width portrait (unfolded inner display, iPad upright)

The stacked column now takes `Metrics.maxContentWidthRegular` (560) on a regular-width
window and draws the identity block at the wide scale (1.4×) — not at accessibility
sizes, where the type size already is the scale. Phones are byte-for-byte the old
layout (compact width → 440 cap, scale 1).

A window narrower than any iPhone (< 360 pt — Split View's 313 pt half) gives Hold to end
its own dock row, as the far column does; the narrowest iPhone is 375, so every phone
keeps the measured two-row dock.

### 4. Odds and ends

- `-dumpRunnerLayout` (DEBUG): `Sources/Runner/RunnerLayoutProbe.swift` rewrites
  `Documents/runner-layout.txt` every second with the window size, safe-area insets,
  interface orientation, size classes and the runner's panel/graph/hero/dock frames.
  Release builds compile the modifier to nothing.
- Two build warnings fixed on the way: `UIScreen.main` in the DEBUG diagnostics now reads
  the scene's screen; `IslandHand.islandInset` is `nonisolated`.

## Measured sizes

Points, from `-dumpRunnerLayout` (window frame, `safeAreaInsets`, trait collection).

| Pose / window | Window | Safe area (top, leading, bottom, trailing) | Size class (h × v) | Runner |
|---|---|---|---|---|
| Duo outer, closed portrait | 466 × 678 | 0, 0, 34, 84 | compact × regular | stacked: panel 342 × 301, graph 342 × 168, dock 342 × 120 at y 502 |
| Duo outer, closed landscape (interface left) | 678 × 466 | 0, 0, 34, 84 | compact × compact | far: face 282 × 402 at x 20, numeral 282 × 278, column 250 × 402 at x 324 (panel 250 × 179; dock 250 × 130, measured before Hold to end got its own row) |
| Duo outer, closed landscape (interface right) | 678 × 466 | 0, **84**, 34, 0 | compact × compact | far, mirrored (final build): column 250 × 402 at x 104 beside the bars (panel 250 × 179, dock 250 × 176 at y 199), face 282 × 402 at x 376, numeral 282 × 278 |
| Duo inner, 669 × 951 (`appResize`) | 669 × 951 | 82, 0, 20, 0 | regular × regular | stacked at 560: panel 520 × 386 (scale 1.4), hero 488 × 127, graph 520 × 288, dock 520 × 120 |
| Split View half, 313 × 678 (`appResize`) | 313 × 678 | 20, 0, 20, 0 | compact × regular | stacked at 273 wide: panel 273 × 328, graph 273 × 126, dock in three rows |
| iPhone 17 Pro Max, landscape | 956 × 440 (device spec; not probed — `devicectl` turns never reached the app there either) | symmetric side insets | compact × compact | far, column trailing (UI test: dock beside the face, numeral over 40 % of the height) |

At AX3 on the Duo's outer landscape: column 301 wide (half), numeral 235 × 266, the dock
below the panel inside the column's scroll.

## Screenshots (`docs/screenshots/duo/`)

| File | What |
|---|---|
| `nightstand-runner-far-closed-landscape.png` | Duo outer, on its side, mid-pull: column beside the bars (leading here), face on the clear side |
| `nightstand-runner-far-closed-landscape-ax3.png` | the same at AX3: column at half width, counters stacked, dock below the fold of its scroll |
| `nightstand-runner-closed-portrait.png` | Duo outer, upright: the stacked runner, unchanged |
| `nightstand-today-closed-phone-sideways.png` | Today launched with the Duo on its side: still portrait |
| `nightstand-runner-open-portrait-669.png` | unfolded inner (669 × 951 via `appResize`): the 560 column at 1.4× |
| `nightstand-runner-split-313.png` | Split View half (313 × 678): Hold to end on its own row; the graph shrinks to ~80 pt |
| `nightstand-promax-far-working.png`, `-rest.png`, `-timer-only.png` | Pro Max on its side (XCUITest captures, rotated upright): pull with target lane, rest, no gauge |
| `nightstand-promax-summary-portrait.png`, `nightstand-promax-today-after-session.png` | after Hold to end on a sideways phone: summary and Today both portrait |

## Verification

- `NightstandRunnerUITests` (new, iPhone): the far view's shape on its side and the stack
  again upright; the far view through a REST and timer-only; Today never drawn in
  landscape, the runner opening sideways on a phone already sideways, the summary
  portrait, Today portrait after Save. **All three pass on the iPhone 17 Pro Max.** They
  SKIP on the Duo simulator — see the next section; the far view there was screenshotted
  instead.
- `RunnerOrientationLockTests` (new, unit): the mask arithmetic, the token rule, the
  device→interface mirror.
- `./build.sh test`: 951 tests, 0 failures (Pro Max). `WideLayoutUITests`: 2 / 2 on an
  iPad Pro 11" (M5), iOS 27.0 — the iPad's wide layout and its portrait stack (now 560
  wide at 1.4×) both hold. `RunnerDockUITests` 2 / 2, `RunnerActionMarginsUITests` 2 / 2,
  `RestFocusUITests` 13 / 13 on the Pro Max: the phone's stack is unchanged.

## The Duo simulator's orientation seams (iOS 27.1) — measured, not fixed

- **`XCUIDevice.shared.orientation` never turns the closed Duo's outer display**: three
  tests on a freshly booted device, the cover stayed portrait and the app was never told.
  On the Pro Max the same tests pass. Hence the skip.
- **`xcrun devicectl device orientation set` lags by one**: `get` reports the previous
  `set`. And once the closed Duo has been turned to landscape, setting portrait does not
  take until the device is rebooted (seen three times).
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

## Owed to Nuri's hardware

1. **Does iOS give the foreground app the outer display in tent pose?** Or does StandBy
   (the Ambient pose detector above) take it as the phone is stood up? If StandBy wins,
   does tapping through return to the runner, and does the runner then turn?
2. **Does a turn of the closed Duo reach the app?** The runner follows
   `UIDevice.orientationDidChangeNotification` and also leaves SpringBoard free to turn it
   (the delegate mask is wide). The simulator delivered neither; a real device should
   deliver both. If neither, the fallback that already works is turning the phone BEFORE
   tapping Start.
3. **Which landscape is the tent?** The column follows the bars whichever it is — check
   the bars are on the side you expect and the camera does not sit over the face.
4. **The Live Activity on the vertical island, and in StandBy** (route 1) — a real
   Progressor session, backgrounded, phone stood up and locked.
5. **Reading distance**: the numeral and the phase word from 1–2 m, under the board's
   lighting, in both schemes; and amber (stalled) on the light field at that size.
6. **The session across the stand-up**: a connected Progressor streaming through
   portrait → landscape → portrait without a reconnect or a paused rep.
