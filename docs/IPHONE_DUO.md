# iPhone Duo — the device, what apps do with the fold, and the Get a Grip dream build

Researched 2026-10-20, three days before the Duo ships. Everything in §1–§4 is sourced
(links at the end); §5 is an audit of this repo as it stands; §6 is the proposal.
Point sizes marked ≈ assume a @3x scale, which Apple has not published.

## 1. The device

| Fact | Value |
|---|---|
| Announced / pre-order / ships | 2026-09-09 / 2026-10-16 / **2026-10-23** (70+ countries; 28 more 10-30) |
| Price | $1,999 (256 GB) · $2,199 · $2,599 · $3,199 (2 TB) |
| OS | ships on **iOS 27.1**; the fold APIs are in the iOS 27.1 SDK (Xcode 27.1) |
| Outer display | 5.4", 1398 × 2034, 460 ppi — ≈466 × 678 pt |
| Inner display | 7.6", 1878 × 2670, 430 ppi — ≈626 × 890 pt |
| Aspect | **both 1 : 1.4; the outer is exactly half the inner**. Wider and SHORTER than any iPhone (17 Pro is 402 × 874 pt) |
| Panels | 120 Hz ProMotion, Always-On (1 nit), 1000 / 1600 HDR / 3000 outdoor nits, nano-texture inner |
| Closed / open | 84.1 × 117.8 × 11.3 mm / 164.6 × 117.8 × 5.2 mm, 254 g, Grade 5 titanium, IP68 |
| Biometrics | **Touch ID side button** (works open or closed). No Face ID / TrueDepth. Watch unlock. |
| Outer camera | 12 MP punch-hole in the corner, **always visible; it expands into a VERTICAL Dynamic Island for Live Activities** |
| Inner camera | **under-display**, invisible until the camera is active (then an "occlusion" region) |
| Silicon / radio | A20 Pro, 12 GB, 4,883 mAh, N1 (Wi-Fi 7, **Bluetooth 6**), C2 modem, eSIM only |
| Battery | 44 h video on the outer, 31 h on the inner |
| Crease | visible at an angle, felt by a finger, "barely there" vs Samsung |

## 2. Poses — Apple's own vocabulary, and what iOS does in each

The HIG illustrates six poses and names three families: **closed**, **open**, and
**partially folded**, the last subdivided into *holding it like a book*, *placed down on a
surface* (one half flat, the other raised — "tabletop"), and *standing on its edges*
(tent-like, outer display facing you).

| Pose | Display | Size class | What the system does |
|---|---|---|---|
| Closed, portrait | outer | compact × regular | Island, status bar, toolbar and tab bar stack VERTICALLY down the trailing edge (trailing safe inset 84 pt, leading 0). Honors `UISupportedInterfaceOrientations`. |
| Closed, landscape | outer | compact × compact | Same vertical bars. Apple *recommends* supporting landscape here "because people may set the phone down like a tent". |
| Open, portrait | inner | regular × regular | Normal horizontal bars (the one pose with enough height). **Ignores supported orientations** entirely. |
| Open, landscape | inner | regular × regular | Bars vertical on the trailing edge, same height as the outer, so controls stay put across displays. |
| Book (half-folded, held) | inner | regular × regular | The fold becomes an ACTIVE division region (40 pt frame = 20 pt margin either side of a zero-width line). Alerts/sheets/menus/split views move off it on their own; alerts go to the trailing side "closer to where they'll be when it closes". |
| Tabletop (half-folded, flat) | inner | regular × regular | Same region, horizontal. Apple's placement rule: **top region = what you read at a distance (alerts, video); bottom region = what you touch (media controls, keyboard)**. Netflix: video on top, controls below. |
| Tent (on its edges) | outer | — | **StandBy, even unplugged**: clock, widgets, music, weather, "glow" alarm. Not your app's screen. |

Other system behaviour that matters:

- **Closing resizes the running app onto the outer display in place.** No relaunch; text
  editing sessions survive the fold. The app crosses size-class boundaries but "is still
  an iPhone app".
- **Split View**: two apps, or two windows of one app, each EXACTLY half the inner display
  (≈313 pt wide), not resizable; pairs can be saved; each app puts its vertical bars on
  its own outer edge. All apps take part without opting in. There is also a layout that
  stacks a video over an app.
- **Third-party apps cannot draw on the outer display while open.** Only system-owned
  *scene accessories* can: `CameraCaptureAccessory` (needs a live capture session and a
  full-screen inner scene) and `ExternalNonInteractiveAccessory` (external displays). PiP
  moves to the outer only when the device closes, and iOS drives that. New windows can
  only be created on the inner display.
- SDK gate: a build against the iOS 27 SDK runs on the Duo but uses only the space LEFT of
  the status bar on the inner display; **only an iOS 27.1 SDK build goes edge-to-edge and
  gets the vertical bars**.

## 3. The APIs (iOS 27.1 SDK — verified in the headers installed on this Mac)

| Need | SwiftUI | UIKit |
|---|---|---|
| Hinge state, live | `.onHingeChange(isEnabled:) { old, new in new.hinge?.status / .angle }` — `DeviceHinge.status` ∈ `.closed / .partiallyOpen / .fullyOpen`, `.angle` is 180° flat; `hinge` nil off-device | `UIHingeInteraction(updateHandler:)` on a view; `UIHinge.status` (`UIHingeStatus` adds `.unknown`), `.angle: CGFloat` |
| Fold / camera geometry | `GeometryProxy.reservedRegions(kind: .division | .occlusion, options: [.includeInactive], layoutDirectionBehavior:)` → `ReservedRegion.frame / .margins / .isActive` | `UIView.reservedRegions(kind:options:)` → `UIViewReservedRegion` |
| Two views across the fold | `ArrangementView { primary } secondary: { … }` + `.arrangementViewStyle(.split.axes(…))` or `.overlay`; `splitArrangementLayoutRatio`, `overlayArrangementEdge`, `@Environment(\.overlayArrangementZIndex)` | `UIArrangementViewController`, `setViewController(_:for: .primary/.secondary)`, `updateArrangement(.split.axes(…))` |
| Vertical bars | `@Environment(\.toolbarVerticalEdge)` (.leading/.trailing/nil), `.toolbarVerticalBehavior(.disabled)`, `ToolbarItem.axisBehavior / .visibilityPriority`, `ToolbarOverflowMenu`, `.topBarPinnedTrailing` | `verticalBarEdge` trait, `preferredVerticalBarBehavior` |
| Sheet side | `.presentationPlacement(.leading/.trailing/.centered)` | `preferredPlacement` |
| Motion per display | — | `CMBodyIdentifiable` / `deviceMotionBody` |

Apple's rules, verbatim where it matters:

- "Hinge data is observed live, and is ideal for driving interactions or effects. **For
  layout, use the arrangement and region APIs.**" Rate and precision are system policy.
- Split arrangement: horizontal when wider than tall, vertical when taller than wide,
  **follows the fold in book and tabletop pose on its own**; if it cannot split along an
  allowed axis it shows the primary alone. No navigation containers inside it; never
  inside a `ScrollView`/`List`.
- Division region: active only while partially folded; query with `.includeInactive` for
  decisions like "prefer an even column count". Regions arrive AFTER the first layout
  pass — read them in the body, never cache.
- "Avoid referencing the main screen" — `UIScreen.main` "will be deprecated". Never
  idiom or orientation for layout. `UIRequiresFullScreen` is honored but deprecated.
- "Safe areas are often asymmetric. This is especially true on iPhone Duo."
- Sample from the Tech Talk: a guitar whose pitch bend follows the hinge angle.

Toolchain on this Mac (2026-10-20, morning): **Xcode 27.1 (27A9275) is installed and the
`iPhone Duo` simulator device type exists — but the iOS 27.1 simulator RUNTIME was not
installed** (27.0 was the newest), so no Duo device could be created. (Done the same
afternoon — see "Simulator first look" below.) First step:

```bash
xcodebuild -downloadPlatform iOS
```

Then create the device and `SIM_UDID=<duo> ./build.sh run`. Poses are driven from
Xcode's **Device Hub** (open / close / rotate / fold, drag-by-home-indicator for Split
View). No `simctl` posture command was found — treat fold verification as GUI-driven
until one turns up (the Maestro project is asking Apple for the same thing).

## 4. What apps actually do with the half-fold

- **Netflix** ("Built for Duo", one of Apple's three launch partners with Zoom and
  Slack): the short-form Clips feed on the outer display; open it and the same title
  continues on the 7.6" without restarting, no black bars; **half-open on a table it
  splits — video in the upper half, playback controls in the lower**; pairs with another
  app in Split View.
- **Apple's own demos**: Podcasts player + transcript as a split arrangement (inline when
  taller than wide); Reminders two columns at 50/50; Notes narrowing its sidebar as the
  fold closes; Mail list + message; Quick Look as a two-page spread; Calculator spanning
  the full outer width as a 5 × 4 grid; Safari two windows side by side; Accessibility
  Reader as an overlay arrangement.
- **Camera / FaceTime**: unfolded, the OUTER display shows the subject their own shot
  (rear cameras for selfies); FaceTime mirrored to the outer so the people across the
  table join the call; "Smart Take" fires when everyone is posed; "Kid Cue" plays Peanuts
  on the outer display to make a child look at the lens; 4K Dolby Vision time-lapses with
  the phone propped half-folded as its own tripod.
- **StandBy in tent pose, unplugged** — bedside clock, widgets, glow alarm.
- **Third-party adaptive updates** (no fold-specific feature published): Tripsy
  (vertical toolbar items, icon-less items hidden), Bear, Drafts, Fantastical, iScreen
  (4 × 6 widget), Detail (records the outer + rear cameras together in "Book Mode").
- **Fitness**: nothing fold-specific found anywhere yet. A training app with a real
  tabletop story would be early.
- Precedent from Samsung's Flex Mode (same geometry, six years of apps): video up /
  controls down, viewfinder up / shutter down, hands-free selfies, the "Flex panel"
  of touch controls on the flat half. Apple formalised exactly that split as its
  tabletop guidance.

## 5. Where Get a Grip stands on a Duo today (audit of this tree)

Runs as-is (iOS 26 target, no recompile needed). Specifics:

- **Carries over from the iPad pass**: size-class/aspect layout rule, `CardGrid` on
  Today and Maxes, History's two-pane, and the runner's wide layout
  (`sizeClass == .regular && width > height`, [RunnerView.swift:268](../Sources/Runner/RunnerView.swift:268))
  — so open-landscape already gets the two-column runner.
- **Open PORTRAIT is a phone in a frame**: regular width but only ≈626 pt, so
  `CardGrid` (phone-width columns) fits one column and the runner stacks at
  `Metrics.maxContentWidth`. Nuri's exact iPad complaint, on the pose people will use
  most.
- **Outer display is shorter than any iPhone** (≈678 pt vs 874): Today's
  `fitsOnePage` will overflow and scroll; the runner's 440-measured stack is tight.
- **Orientation**: iPhone is portrait-only in `project.yml`. The outer honours that
  (so no tent/landscape outer); the inner ignores it regardless.
- **`IslandHand.isSupported`** reads top safe inset ≥ 55
  ([IslandHand.swift:117](../Sources/Runner/IslandHand.swift:117)). Outer: the island is
  on the TRAILING edge, top inset small → hand off, original glyph layout (correct by
  the "without an island the ORIGINAL layout stands" rule). Inner: no island at rest →
  hand off. The hand simply never appears on a Duo.
- **`GripIslandPanel` is NOT gated** and draws its palm at a top-centre island that
  exists on neither display; on the outer the real cutout is on the right edge. The notch
  verdict ("the black hides the cutout") no longer applies because the cutout is not on
  the top edge. Gate it on `IslandHand.isSupported`, or anchor it to the `.occlusion`
  region frame.
- **Live Activity**: all presentations exist
  ([SessionLiveActivity.swift](../Widget/SessionLiveActivity.swift)); how compact
  leading/trailing read on a VERTICAL island is unverified.
- **`UIScreen.main.maximumFramesPerSecond`** in [DoigtApp.swift:102](../Sources/DoigtApp.swift:102)
  (diagnostics only) → scene screen. The two `userInterfaceIdiom == .pad` reads
  (reminders default, Settings copy) are not layout decisions; a Duo is idiom `.phone`.
- **Asymmetric insets**: the runner pads `.top` for the island and assumes symmetric
  sides; with trailing 84 / leading 0 every full-bleed surface (trace, dock, builder
  cover) needs an inset audit.
- **BLE and the session**: unaffected. Better than unaffected — closing the phone
  moves the live runner to the outer display *in the foreground*, so a session should
  survive a fold with no background grace, no reconnect. Must be verified on hardware.
- `UIApplicationSupportsMultipleScenes: false` is fine: Split View needs no opt-in, and
  two windows of one gauge app make no sense (one gauge, one central).

## 6. The dream build

**Thesis: the Duo is the first iPhone that can stand on its own under a hangboard.**
Every pose maps onto a moment of the ritual, and almost all of it falls out of one
structural change.

### A. The bench pose — tabletop runner (the headline)

Half-open on the floor, a chair or the bench below the board: bottom half flat, top half
raised toward the climber.

- **Top region = the far view**: hero countdown numeral, phase word, target lane +
  trace, grip glyph, hand mark — scaled up (`wideScale` or beyond) and readable from
  1.5–2 m while hanging. Whole-face `StatusTint` colour, the watch-face trick already
  built: amber "waiting on you", bleu while the clock runs, steel in rest.
- **Bottom region = what you touch, during REST**: Skip, Tare, Pause, hold-to-end, the
  live kg readout. Nothing you'd need mid-hang lives down there — mid-hang you touch
  nothing.
- **Implementation**: the runner becomes
  `ArrangementView { FaceView } secondary: { ControlsView }` with `.split` on both
  axes. Taller than wide → vertical split, **and the split follows the fold in tabletop
  pose for free**; wider than tall → side by side, which IS today's iPad two-column
  layout, so `wideContent` becomes the secondary/primary pair rather than a third
  layout. The planned 2026-09-19 "face + controls pair" lands here.
- `onHingeChange` picks the far-view **scale** (status `.partiallyOpen` and taller
  than wide → bigger type, fewer words), never the layout. Hinge data never reaches the
  engine — same rule as cues.

### B. Closed — the pocket runner, and the fold as a feature

The normal compact runner with vertical bars. Close the phone during a rest and the
session keeps running on the outer display; the Live Activity takes the vertical island
when the app is backgrounded. Decision owed: unlock landscape on iPhone so the outer
can lie like a tent (Apple's recommendation) — cheap now that the island hand is
portrait-only by gate, not by plist.

### C. Open flat — a real 7.6" dashboard, and a 626 pt builder

Today at ≈626 pt: routine card and consistency strip side by side (two ~300 pt
columns, even count per the HIG), not one 560 column. Maxes two to a row at ~300.
History's two-pane as on iPad. The builder stays one document (its thesis) but at
regular width the set list can sit beside the expanded set.

### D. Split View beside the video — the thesis made spatial

Get a Grip in one half (≈313 pt, compact) next to YouTube/Netflix in the other, saved
as a pair that launches together. "Never disturb what you're listening to" was an
audio-session rule; on a Duo it becomes a layout. Work: the runner must survive 313 pt
(hero, trace, dock were measured at 402 and capped at 440), and the stacked
video-over-app layout wants the same.

### E. Book pose, landscape — the max test

Held half-folded like a book: trace on the left page, hero result and the dashed max
rule on the right. The same `ArrangementView` split, horizontal axis. Also the natural
pose for the critical-force test's long trace.

### F. Nightstand mode — the phone holds itself up (Nuri, 2026-10-20)

Tent pose is the one pose where no bench, chair or prop is needed: the phone stands on
its own edges on the floor under the board, outer display facing up at you. iOS owns
that pose as **StandBy**, so there are three routes in, ranked by cost:

1. **The session's Live Activity in StandBy** — free if it works. StandBy already shows
   Live Activities full-screen on a charging iPhone (timers, scores); the Duo enters
   StandBy in tent pose unplugged. Our activity already carries the countdown, the phase
   word, the hand and the colour ladder (steel / amber / bleu), so the tented phone would
   be a watch-face-sized countdown readable across the room, with StandBy's own red
   night mode for the 11 p.m. no-hang. No app work beyond what ships. **Owed: a hardware
   check that tent StandBy shows Live Activities, and how big.**
2. **The runner itself, in tent** — the real dream. If tapping out of StandBy hands the
   outer display to the foreground app (as it does on a charging iPhone), the runner runs
   on a 5.4" outer display in LANDSCAPE (compact × compact) while the phone stands on its
   own. That is the "far view when `verticalSizeClass == .compact`" from the 2026-09-19
   plan: one giant numeral, phase word and lane, controls to the trailing edge beside the
   vertical bars. Needs landscape unlocked on iPhone (Apple recommends it for exactly this
   pose) and a compact-height runner layout. **Owed: whether iOS lets an app hold the
   outer display in tent at all** — a community wiki claims apps cannot run on the INNER
   display standing/tented, and says nothing about the outer.
3. **A StandBy widget for Today** — `systemSmall`, routine name + "1 of 2" + the
   consistency dots, so the tented phone on the nightstand is the morning ritual's
   reminder before a session exists. An App Intent "Start" is not worth it: starting needs
   the gauge and the foreground app, which route 2 covers.

Design rule for all three: the tent face is read at 1–2 m with chalk on your hands, so
it carries ONE number and ONE word, whole-face colour, nothing to tap mid-hang.

### Explicitly out

Camera scene accessory (no capture session; the outer display is closed to us
otherwise), hinge-angle gimmicks in the engine, a Duo-specific layout per pose (Apple's
rule and ours: size classes, regions, arrangements).

### Order of work

0. **Toolchain** — download the iOS 27.1 runtime, create the Duo sim, screenshot the six
   poses from Device Hub. Fold verification is GUI-only until a `simctl` seam exists.
1. **27.1 SDK build + hygiene** — build against 27.1 (deployment target stays 26.0,
   `#available(iOS 27.1)` guards); audit asymmetric insets, `UIScreen.main`, the
   `GripIslandPanel` gate, the Live Activity on the vertical island, the one-page Today
   on a 678 pt outer. Edge-to-edge and vertical bars come free.
2. **Runner `ArrangementView`** (A, B, E) — replaces the iPad wide layout; far-view
   scale on `.partiallyOpen`.
3. **Inner-portrait grid + 313 pt runner** (C, D). Grid, History panes and the grip
   panel gate done 2026-10-31 ("Unfolded layouts"); the 313 pt runner is still owed.
4. **Nightstand** (F) — landscape on iPhone, a compact-height far-view runner, a
   `systemSmall` Today widget; gated on the two hardware answers below.
5. **Hardware** — session continuity across a fold, whether tent StandBy shows the Live
   Activity and whether an app can hold the outer display in tent, Touch ID side button,
   Bluetooth 6 with the Progressor.

Tests: the existing rotating UI tests cover open-landscape; the fold has no programmatic
seam yet, so pose screenshots are the gate for 1–3.

## Sources

- Apple HIG, [Designing for iPhone Duo](https://developer.apple.com/design/human-interface-guidelines/designing-for-iphone-duo)
- Apple Tech Talks: [Prepare your app for iPhone Duo](https://developer.apple.com/videos/play/tech-talks/111461/) · [Strike a pose with adaptive layouts](https://developer.apple.com/videos/play/tech-talks/111463/) · [Leverage multiple displays and scenes](https://developer.apple.com/videos/play/tech-talks/111464/)
- Apple Developer Forums, [DTS summary of the iPhone Duo group lab](https://developer.apple.com/forums/thread/847644)
- iOS 27.1 SDK headers on this Mac: `UIHinge.h`, `UIHingeInteraction.h`, `UIViewReservedRegion.h`, `UIView.h`, SwiftUICore swiftinterface
- [iPhone-Duo-by-Examples](https://github.com/artemnovichkov/iPhone-Duo-by-Examples) (simulator observations: 40 pt division frame, 84/0 insets, arrangement behaviour)
- [MacRumors roundup](https://www.macrumors.com/roundup/iphone-duo/) · [MacRumors hands-on](https://www.macrumors.com/2026/09/09/iphone-duo-hands-on/) · [Macworld, five things](https://macworld.com/article/3230936/iphone-duo-the-five-most-important-things-you-need-to-know.html) · [Tom's Guide hands-on](https://www.tomsguide.com/phones/iphones/iphone-duo-hands-on-apple-nailed-it-and-just-put-everyone-else-on-notice) · [sarunw, design notes](https://sarunw.com/posts/design-for-iphone-duo/)
- [WorksOnDuo: Netflix](https://worksonduo.com/apps/netflix) · [iOS App Lists: which apps use the fold](https://iosapplists.com/iphone-duo-apps-which-apps-use-the-foldable-screen/) · [choose.tv on Netflix's redesign](https://www.choose.tv/us/news/netflix-redesigned-the-app-for-the-i-phone-duo-it-will-use-both-screens_DNY)
- [Maestro issue on simulator fold postures](https://github.com/mobile-dev-inc/maestro/issues/3617)

## Simulator first look (2026-10-20)

Step 0 of the build order, done headlessly. What ran, what it measured, what it could not
reach. Screenshots live in `docs/screenshots/duo/` (PNG, `<screen>-<pose>.png`, all under
300 KB); the survey itself is `scripts/duo_survey.sh`.

### Toolchain

- `xcodebuild -downloadPlatform iOS` fetched the **iOS 27.1 simulator runtime (24A94425,
  7.85 GB)**; `xcrun simctl list runtimes` now lists `iOS 27.1`. The Duo device was created
  with `xcrun simctl create "iPhone Duo" com.apple.CoreSimulator.SimDeviceType.iPhone-Duo
  com.apple.CoreSimulator.SimRuntime.iOS-27-1` (udid `C09E63E9-5FD7-407E-A1BB-D7D64EA42C99`
  on this Mac) and booted from `simctl`.
- **SDK: `iPhoneSimulator27.1.sdk`, with no change to `build.sh` or `project.yml`.**
  Xcode 27.1 ships only the 27.1 SDKs (`xcodebuild -showsdks`), so `SIM_UDID=<duo>
  ./build.sh run` picked it on its own. The build is therefore the edge-to-edge,
  vertical-bars variant; the "iOS 27 SDK uses only the space left of the status bar"
  fallback never came up. Two warnings are Duo-relevant: `UIScreen.main` is deprecated
  ([DoigtApp.swift:102](../Sources/DoigtApp.swift:102)) and `IslandHand.islandInset` is read
  from a nonisolated context ([IslandHand.swift:41](../Sources/Runner/IslandHand.swift:41)).
- **There is no Simulator.app in Xcode 27.1.** The device window is `Device Hub`
  (`/Applications/Xcode.app/Contents/Applications/DeviceHub.app`, menu Xcode › Open
  Developer Tool › Device Hub). `build.sh`'s `open -a …/Simulator.app` fails harmlessly and
  everything else runs headless.
- **The Duo boots CLOSED**: `xcrun devicectl device info displays` reports display 1
  `LCD` (outer, 1398 × 2034, active, backlight on) and display 3 `LCD-1` (inner,
  2007 × 2853, inactive, backlight off). **`simctl io screenshot` defaults to the INNER
  display**, which is black while closed — pass `--display=1` for the outer,
  `--display=3` for the inner (names `LCD` / `LCD-1` also work).

### Sizes, as reported

| Display | Simulator pixels | Scale | Points | Doc §1 said |
|---|---|---|---|---|
| Outer (`LCD`, display 1) | 1398 × 2034 | @3x | **466 × 678** (UIWindow frame, from `-dumpInteractions`) | 466 × 678 ✔ |
| Inner (`LCD-1`, display 3) | 2007 × 2853 | @3x | **669 × 951** (from the profile; not yet seen lit) | 1878 × 2670 → 626 × 890 ✘ |

The inner number comes from the device type's `capabilities.plist` and `devicectl`, not from
a screenshot; it disagrees with the press figure in §1 by ~40 pt each way. Treat the
simulator's as the working number and re-measure from the first open-pose screenshot.

Closed-portrait insets, read from the UIKit view tree: navigation bar at y = 24 (top inset
24 pt, no island up there), `UITabBar` frame `397,0 69 × 678` — a **vertical bar on the
trailing edge** — and the page content column **381 pt wide from x = 0**, i.e. ≈85 pt
reserved on the trailing side and 0 leading, which is the HIG's 84/0.

### Poses captured

| Pose | Display | Screens | Method |
|---|---|---|---|
| Closed portrait | outer | Today, runner (mock session), builder + grip panel, History, Benchmarks, Settings | `scripts/duo_survey.sh` |
| Closed "landscape" | outer | Today, runner | `xcrun devicectl device orientation set --device <udid> landscapeLeft` — accepted, **but the outer honours `UISupportedInterfaceOrientations` and stayed portrait** (`devicectl` still reports `Main display orientation: portrait`); the captures are identical to the portrait ones and are kept as the record of that fact |
| Live Activity on the vertical island | outer | one capture | session started, app backgrounded by launching Settings — **no compact presentation appeared** (see findings) |

### Poses NOT captured — Device Hub by hand

Open portrait, open landscape, book (half-fold held), tabletop (half-fold flat), tent, and
Split View were **not** captured. Explicitly: **no programmatic way to fold the
simulator was found**, and this is a measured absence, not a guess:

- `xcrun simctl help` / `simctl ui` / `simctl io`: appearance, contrast, content size,
  screenshots — nothing for the hinge. `device.plist` stores no hinge state.
- `xcrun devicectl device …`: `orientation get|set|rotate` works on simulators (the one
  headless seam gained today); `simulate` covers biometrics, location and the status bar
  only; `info displays` reads the fold's *result* (`--stream` would show the switch).
- Device Hub itself (`DeviceHub.app`, `DeviceKit.framework`, `IDEDeviceInteraction`) and
  `CoreSimulator.framework` carry no hinge/posture/fold strings; the 27.1 runtime's
  `SpringBoard` does (`SBHingeMotion…`, a `HingeReplay` debug loop) but exposes no CLI.
- The desktop-control tool could not drive Device Hub's GUI either: the app's accessibility
  tree is unreadable to it, so every click is refused. The Duo's pose is a hand job.

To finish the survey: open Device Hub (Xcode › Open Developer Tool › Device Hub), select
the booted iPhone Duo, use its Device/Controls menu to open, fold and rotate, then
`DISPLAY_ID=3 scripts/duo_survey.sh <udid> open-portrait` (and `open-landscape`, `book`,
`tabletop`). `xcrun devicectl device orientation set` handles the rotations headlessly.

### Findings (nothing changed, all recorded)

1. **IslandHand is OFF on the outer display**, as predicted: the runner's hero card draws
   the in-content glyph, nothing hangs from the top, top inset is 24 pt. Not yet seen on the
   inner display.
2. **`GripIslandPanel` on the outer is anchored to an island that is not there.** Its black
   palm runs to y = 0 and the four finger bars hang from the top-centre, where the outer
   display has no cutout (the camera/island is on the trailing edge, next to the clock). It
   reads as a floating black card. It also hides the status bar, so the vertical clock
   column disappears while the picker is open, and the panel spans the full 466 pt — it
   ignores the ≈85 pt trailing column every other surface respects. Usable, not broken;
   gate it on `IslandHand.isSupported` or anchor it to the `.occlusion` region as §5 says.
   **FIXED 2026-10-31** — gated on a top cutout; see "Unfolded layouts".
3. **Today fits one page on the outer**: the content column ends at y = 604 of 678, no
   scroll, large title intact. With two routines the deck renders as the horizontal rail.
4. **The horizontal rails painted UNDER the vertical bars — FIXED the same day**
   (`Sources/UI/Components/ClearOfSideBars.swift`, applied to Today's routine deck and
   History's trend and month decks). Today's deck (`HostingScrollView 0,138 466 × 327`)
   and History's "Load per grip" rail were full-window-width scrollers inside the 381 pt
   page column, so the second card slid under the tab bar and the gauge button
   (`today-closed-portrait.png`, `history-closed-portrait.png`). Three measured facts
   decided the modifier: in a `List` row `ignoresSafeArea(.horizontal)` alone stops the
   nested scroll view extending (466 → 382); inside the scaffold's vertical `ScrollView`
   it does not, and padding the rail by the window inset came out 298 (subtracted inside
   the column, the rail never grows) — a 1 pt trailing pad, so the scroll view's edge no
   longer coincides with the safe-area boundary, gave exactly 381; and Today's deck,
   unclipped for the context-menu lift, still drew the neighbour past the corrected
   frame, so a sideways-only clip with 1000 pt of vertical slack cuts the neighbour at
   the column edge and leaves the lift intact. The hairline and the clip are active only
   where the window reports a side inset, re-read on every size change; the phone
   measures byte-identically (deck `0,243 402 × 327`), unit tests and
   `LongPressLiftUITests` pass.
5. **Runner, closed portrait**: hero card, trace and dock all live inside the 381 pt column;
   the trace is full-bleed on the left and stops at the column edge, so the picture is
   asymmetric but nothing is clipped. The hero card starts ~8 pt below the top edge (there
   is no status bar up there now). In one capture during a running session a solid black
   dot appeared above the clock in the island column (`runner-closed-landscape.png`) —
   unexplained; it may be the island's camera region or a compact activity, check by hand.
6. **No landscape on the outer** while iPhone stays portrait-only, so no tent-style lying
   down for the app itself. Unlocking landscape is the decision §6-B already names.
7. **Live Activity: not reachable in this simulator.** After the mock session was
   backgrounded the island column showed only the clock and the status glyph
   (`live-activity-island-closed-portrait.png`); the mock gauge is a timer, so the process
   suspends on backgrounding and the activity, if it started at all, never rendered a
   compact presentation. The vertical-island compact leading/trailing layout stays
   unverified until a real Progressor session on a Duo.
8. **Builder cover** (behind the panel): the document column keeps the 381 pt width and the
   large controls fit; a full grip-panel-closed pass is still owed on the inner display.
9. Benchmarks and Settings lay out cleanly in the column; the blue outline on Settings'
   Gauge card is pre-existing focus styling, not a Duo effect.

## Unfolded layouts (2026-10-31)

The nightstand half of the same day's work — landscape for the live runner, the
compact-height runner (the far view, since replaced by the wide layout), the regular-portrait runner and the StandBy probe — is in
[IPHONE_DUO_NIGHTSTAND.md](IPHONE_DUO_NIGHTSTAND.md).

Step 3 of the build order (§6-C, the inner-portrait grid) plus the `GripIslandPanel` gate
from §5 / finding 2. Screenshots in `docs/screenshots/duo/`: `*-open-portrait.png`
(669 × 951), `*-open-landscape.png` (951 × 669), `*-split-half.png` (313 × 678),
`*-closed-portrait.png` (outer, refreshed), and `builder-grip-closed-portrait-before.png`
beside the new `builder-grip-closed-portrait.png`.

### The verification seam: `devicectl appResize`

The fold still cannot be driven, but the INNER display's size can be: `appResize` moves
the frontmost app onto a "Resizable" display at any point size, and that is enough to
lay out every unfolded width headlessly.

```bash
UDID=C09E63E9-5FD7-407E-A1BB-D7D64EA42C99            # the Duo, booted closed
xcrun simctl launch $UDID run.nuri.doigt -mockDevice -seedTwoRoutines -seedHistory -tab 0
sleep 4                                               # the app must be FRONTMOST first, or:
                                                      # "There is no foreground application"
nohup xcrun devicectl device appResize start --device $UDID --preferred-size 669x951 &
sleep 7
xcrun simctl io $UDID screenshot --display=5 today.png   # display 5 = "Resizable"
xcrun devicectl device appResize set --device $UDID --preferred-size 313x678  # live resize
kill %1                                               # ends the session
```

- 669 × 951 reports **regular** width (the Settings app shows its sidebar split there).
- 313 × 678 (a Split View half) reports compact.
- **951 × 669 does NOT give landscape**: the Resizable display honours
  `UISupportedInterfaceOrientations`, and iPhone is portrait-only, so it hands back a
  **501 × 669 portrait, compact** window (itself a useful width to have seen: the phone
  layout, fine). The real inner display ignores supported orientations. The landscape
  captures were taken from a MEASUREMENT-ONLY copy of the built `.app` with landscape
  added to its `Info.plist` with `plutil` and re-signed ad hoc — no source or
  `project.yml` change. That window has a bottom tab bar; the real open-landscape pose
  stands its bars on the trailing edge, so those captures are wider at the bottom than
  hardware will be.
- `-dumpInteractions` dumps 3 s and 6 s after launch, so start the resize ~3 s after
  launch to have the 6 s dump report the resized window (`UIWindow 669 × 951` confirmed).
- The resizable window has no hinge, no division region and no vertical bars. It answers
  "does this width lay out", nothing about poses.

### The grid rule, and why

`Metrics.cardMinWidth` **440 → 280**, and `CardGrid` is now a two-column `Layout`
(`CardGridLayout`), not `LazyVGrid(.adaptive)`:

| Window | Content | Columns | Card |
|---|---|---|---|
| Duo inner, simulator | 669 − 40 = 629 | 2 | **303.5** |
| Duo inner, press figure | 626 − 40 = 586 | 2 | 282 |
| iPad 13" portrait / landscape cap | 992 / 1080 | 2 | 485 / 529 |
| Split View half, outer, any phone | — | compact: no grid | — |

- Two columns need `2 × 280 + 22 = 582` of content. 280 rather than 300 so the press
  figure's 586 still splits; the simulator's 669 is the working number, but the margin
  costs nothing.
- **Why not just lower the adaptive minimum**: an adaptive grid at 280 gives an iPad's
  1080 column THREE cards (3 × 345 + 44), and no single minimum yields "two at 629" and
  "two at 1080". The cap at two is the HIG's even count, and the iPad keeps exactly the
  pair it had.
- **At accessibility text sizes the minimum is 440 again** (`CardColumns.count`): every
  card was measured at 303 at default sizes, and big type in 303 wraps every line. The Duo
  then gets one card per row; the iPad keeps its pair.
- `UnfoldedLayoutTests` pins all of the above.

### Today at 669 — the strip is a grid CELL

Measured: **two columns of 303.5 with the 22 gap**; the page ends at y ≈ 789 against a
tab bar at 868 — one page, large title intact. Every card lays out at 303: the plan row
keeps its glyphed stats (`ViewThatFits` falls to the compact form), Start / Connect and
start / Start without a gauge sit on one line each, the strip's 14 dots at 12 pt fit
with room (the 22 pt dot cap only bites at accessibility sizes, where the grid is one
column anyway).

Order: `routines…, strip, ghost` — one routine reads `[routine | strip] [new]`, two
`[A | B] [strip | new]` (`today-open-portrait.png`), i.e. what you do, how it went, make
another. The same grid now runs on every regular-width window, so on an iPad the strip is
a 529 pt cell beside the ghost instead of a 1080 pt band under the routines.

**Tried first and thrown out (built, measured):** the phone's paged deck in the leading
column and the strip beside it, as §6-C sketched it. The deck's card came to 275 pt (the
peek has to live INSIDE the column, or it lands on the strip), and the lower half of a
951 pt screen stood empty — a phone layout in a bigger frame, which is the complaint.
The grid shows every routine at once and still fits.

With three or more routines Today scrolls at 669 (the phone's deck does not), as it
already did on an iPad. Open landscape at 951 × 669 lays out the same grid at 444.5 and
its second row runs under the bottom tab bar of the resizable window; on hardware the bars
move to the trailing edge, which gives the row back but takes ~85 pt of width
(content ≈ 826, still two columns). Before this change the landscape window stacked the
strip as a full-width band below the grid, so it was taller, not shorter.

**Closed outer, re-checked**: unchanged — compact width, deck + strip, the column now ends
at y ≈ 597 (604 last survey), the deck still clipped at the column edge by
`clearOfSideBars`. `LongPressLiftUITests` passes.

### Benchmarks at 669

Two cards per row at 303 through `CardGrid`, collapsed and expanded (`-expandBenchmarks`):
title wraps to two lines, Left/Right + Measure on one row, the expanded chart and
critical-force block fit. At 313 (compact, phone layout) the per-hand value wraps to
"20. / 5 kg" — a Maxes-card large-number issue, not a grid one; left to the Maxes owner.

### History — two panes at 669 in portrait

The gate was `regular && wider-than-tall`, which kept the inner display (and an iPad in
portrait — the same shape) on the phone `List`, where the month grid's cells came out as
wide bars. Now `regular && (landscape || width ≥ 622)`
(`HistoryView.twoPanePortraitWidth`, two `cardMinWidth` cards and margins). The runner's
wide layout still keys on landscape alone and is untouched.

Pane widths at 669: **summaries 294, sessions 375**. The summary floor dropped from 420 to
`cardMinWidth`: at 420 it hit the half cap (334 / 334) and every session row truncated
its load ("23.2…"); at 0.44 × 669 the log gets a 375 pt phone's row and the month grid
keeps square cells. Windows where 0.44 × width ≥ 420 (every iPad landscape) are
unchanged. The trend chart drew "Oct 12" into "Oct 31" at 262 pt of plot, so under 300 pt
it labels three dates instead of four. Landscape (951 × 669): summaries 418, sessions 533.

### `GripIslandPanel` — gated on a top cutout

Gated on the window's top safe inset ≥ 44 (`GripIslandPanel.cutoutTopInset`), read in
`onAppear` before `shown` flips so the card's first frame is already the right shape — NOT
on `IslandHand.isSupported` (≥ 55) as §5 first proposed: that would also have changed
NOTCH phones (inset 47–48), which the 2026-08-22 notch verdict validated on hardware and
says not to touch. Island and notch therefore keep the original panel exactly — verified on
a temporary iPhone 17 Pro simulator (created for the check and deleted): black to y = 0,
hand off the island, status bar hidden. With no top cutout — both Duo displays (outer
inset 24), a home-button phone (20), an iPad (24) — it is a top-anchored black card inside
the safe area: 8 pt margins, `Metrics.radiusSheet` corners all round, status bar left
alone, width ≤ 520, and the fingers as `FingerPips` (which states the pinch rule itself).
On the outer display the card sits in the 381 pt column beside the vertical status column
(`builder-grip-closed-portrait.png` vs `-before.png`); at 669 it is a centred 520 card
(`builder-grip-open-portrait.png`). The iPad's "centred black tab from the top edge"
becomes the same floating card: there is no cutout up there for it to meet either.

### Builder at 669 — one document, decided

The builder already caps at `maxContentWidthRegular` (560) on regular width; at 669 that is
a readable centred document (`builder-open-portrait.png`). "Set list beside the expanded
set" is NOT built: it is a second layout of the most complex screen for a width only the
Duo and an iPad in portrait have, and the three-page builder already puts one page's
worth on screen. Revisit with hardware use.

### Split View half (313 × 678)

Compact, so every screen is the phone's. Today does not fit one page there (the strip runs
under the tab bar — it scrolls); the strip's "Log a session" capsule wrapped to "Log a /
session" in the HStack's real layout even though its ideal width fit. It is now set on one
line inside a width-only `ViewThatFits`, falling back to the glyph alone (same spoken
label) when even that does not fit; at accessibility sizes the title still wraps, words
over a glyph. The runner at 313 (§6-D) was not in scope.

### iPad, re-checked

On a temporary iPad Pro 13-inch simulator (iOS 26.5, created for the check and deleted):
portrait (1032) Today is two 485 pt routine cards with the strip and the ghost as the
second row, and History is now two panes in portrait too (summaries 454) — the same shape
as the open Duo, so the same answer. `WideLayoutUITests` is iPad-only: on the Duo both
cases skip; on the fresh iPad simulator the run hung at test launch for 20 minutes
(simulator-side, after a clean build) and was stopped, so the iPad runner rotation tests
were NOT re-run here. Nothing in the runner changed.

### Still owed on hardware

- Fold continuity: unfold with Today / History / the builder open and confirm the grid and
  the two panes appear without a relaunch, and that a deck position or a scroll survives.
- Split View pairing (Get a Grip beside a video), saved pairs, and the stacked
  video-over-app layout.
- Open landscape with the real trailing bars (Today's second row), and the book /
  tabletop poses — none reachable through `appResize`.
- Whether the real inner display is 669 or 626 wide (both split into two columns).
