# Get a Grip 1.1.0 development plan

Release update, 12 September 2026: the completed changes below are being packaged
as **iOS 1.0.3 (7) and Android 1.0.1 (9)**, at the developer’s request,
with bug fixes leading the release.
See `RELEASE_2026-09-12_1.0.3.md` for packaging and submission status.

Started 10 September 2026 on `codex/release-1-1-0`, based on public commit
`333d630fa63b728335fcc7309d8f0e0f32cf005e`. This is the next shared iOS/Android
development branch. The existing release tags remain immutable. This document
tracks proposed and completed work. Checked items are implemented in the canonical working copy (currently
`codex/hold-count-theme-recovery`); they have not been released unless explicitly noted.

## Open source and community — implemented

- [x] Extend the existing Settings source/credits area on both platforms with a
  compact “Open source & community” disclosure or section.
- [x] Link **Grip Connect** to
  <https://github.com/Stevie-Ray/hangtime-grip-connect>, crediting gauge protocol
  ports from Stevie-Ray Hartog's project. Retain the existing BSD-2-Clause
  copyright and full notices in every distributed copy.
- [x] Link **Crimpdeq** to <https://crimpdeq.com/>. Describe it as an open-source
  force sensor and thank its creator for testing Get a Grip. This is a community
  report, not new first-party hardware verification; do not change gauge flags.
- [x] Keep links optional, open them in the browser and preserve short localized
  descriptions and accessible native tap targets. Avoid partnership or broad
  compatibility claims.
- [x] Verify English/French layout, large text and link destinations on both apps.

Verified 10 September 2026: iOS simulator build and Android `assembleDebug` pass;
15 existing Android Settings tests pass. Both project links open the intended
browser destinations on both platforms. Inspected English and French layouts,
including the largest requested iOS accessibility text size and Android 200%
font size. Preview screenshots are in the ignored
`build/review/community-links/` directory. Test simulators are shut down.

## Settings support copy — implemented

- [x] Remove the iOS “Email details” disclosure and equivalent Android explanatory
  paragraph. Keep both support actions, email metadata, diagnostics consent and
  fallback behavior intact.

Verified 10 September 2026: iOS simulator build passes; Android app compilation
and all six existing `SupportTests` pass.

## Routine preparation overview — implemented, not released

12 September 2026, alongside the pending hold-count/theme fixes:

- Tapping the plan row on a Today routine opens a read-only overview on iOS and
  Android. The existing card menu still offers a direct Edit shortcut.
- Show exact planned duration (labeled estimated), total sets/pulls, hand order,
  per-set lead-in, and grip glyphs/full names in execution order. Each set shows
  its actual per-side/total counts, effective hold/rest, prescribed target and note.
  Breaks appear between sets; no nonexistent rest is shown for a one-pull set.
- Fixed targets respect the selected kg/lb display unit. Percentage targets retain
  decimal precision and remain prescriptions rather than fabricated max lookups.
- Edit replaces the overview with the existing builder after sheet dismissal.
  Pending imports wait for the overview/editor to close.
- The Last 14 days body opens History; Log a session remains its own action.
  Update the tour and English/French strings to describe those destinations.

Validation: six iOS UI checks pass across English navigation/input routes and
French accessibility text, including reaching the final set and keeping native
Edit/Close actions visible. Android passes 55 focused navigation, overview-content,
localization and tour checks; final navigation/content rerun and APK build pass.
Native previews are in `build/review/routine-overview/`. No physical-device install
or store release was performed; test simulators are closed.

## Hold counts and appearance changes — implemented, not released

12 September 2026: `codex/hold-count-theme-recovery`, based on this release branch
at `9ca4b64`. The accepted iOS rest preview is now integrated into this same
working copy; the installed iPhone preview and existing store versions have not
been replaced.

- [x] Raise the hold-count field from 20 to **100 pulls per side** on both apps.
  The editor, saved-data decoder and shared-routine decoder agree. Alternating
  hands can therefore schedule 200 actual pulls in one set. This is a practical
  ceiling while the engines retain individual pull records, not unlimited input.
- [x] Compute builder totals per set, bound hand-order previews to 12 markers,
  and avoid scanning all planned pulls for each live set-count update.
- [x] Handle Android `uiMode` changes in place. Retain the workout route, runner,
  clock scope and summary choices in an Activity ViewModel so a separate Activity
  recreation also preserves completed pulls and partial work. Explicit finish
  still ends the session. Existing background pause policies remain in force.
- [x] Verify iOS appearance changes preserve an active workout and unsaved summary.
  No equivalent appearance-dependent session ownership bug was found there.

Validation: 678 iOS unit tests and 1,016 Android tests pass, including actual
Android Activity recreation, partial-pull continuation, retained max/RPE choices,
save-after-recreation and duplicate-save prevention. Shared checks pass for 39
runner scenarios / 4,130 steps and 74 routine-share URLs, including 36/100 counts.
Both actual builder controls accept typed 36/100 counts; their stepper ceiling
remains 100. Three iOS UI tests cover these inputs and live/summary appearance.
Android emulator checks cover repeated day/night changes while running, paused
and on the summary, followed by successfully saving the workout.

The stress test starts a 50-set, 100-per-side plan (10,000 actual pulls) and skips
one 200-pull set. On the development host, average initialization was about 21 ms
in the iOS debug simulator and 3 ms on the JVM; skipping was below 0.2 ms in both.
These are engine measurements, not a frame-rate guarantee on every phone.

This change preserves in-memory sessions across configuration changes; it does
not add recovery after an OS process kill or force-quit. Older app versions still
apply their old 20-pull decoder limit to imported or reloaded routines. Upgrade
both platforms when sharing a larger routine. Release versions/builds are unchanged.

## Long-rest presentation — permanent on both apps, not released

12 September 2026: integrate the accepted iOS preview into the canonical working
copy alongside the routine overview and appearance fixes, and port it to Android.

- For measured rests originally scheduled for at least 10 seconds, replace only
  the area above the graph with a larger grip glyph, gray next-hand/full-grip
  wording, a secondary upcoming target, and a prominent REST or SET BREAK label.
  A large central countdown is flanked by readable set/pull counts.
- Keep the existing live graph fully visible with the same view identity and
  geometry at standard text sizes. There is no blurred or dimmed graph cover.
  Large accessibility text reflows into the existing scrolling workout layout.
- The rest-owning completed slot determines the scheduled duration, including
  set breaks and per-set overrides. Keep the layout through the final seconds;
  the upcoming slot supplies the next grip, hand, target and position.
- Show it only after release. Live force and LET GO remain visible while pulling
  or releasing. Short rests and timer-only sessions retain their compact layout.
- PAUSED replaces the rest label while paused. Connection/recovery guidance and
  tare confirmation remain available; rest deadlines and Bluetooth are unchanged.
- Keep changed grips orange through the rest, including the graph outline. The
  full description above the graph replaces the duplicate small graph banner.
- iOS has no Settings preview toggle or stored preference gate. Debug and Release
  use the same presentation policy; an old disabled preference is ignored.
- Use the existing platform typography, motion/reduced-motion rules and localized
  English/French strings. Android countdown digits snap as other live numerals do.

iOS validation: 15 focused policy/count tests and 14 distinct UI checks pass,
including French accessibility text, a 10,000-pull plan, final seconds, pause,
connection loss, timer-only sessions, hidden live-weight content, removed Settings
switch, old preference ignored, and unchanged graph/control geometry. The Release
configuration also builds successfully.

Android validation: 33 focused tests pass (6 policy, 10 rest-layout, 14 existing
release/rest, and 3 actual Activity-recreation tests). Coverage includes connected
stale readings after prior samples, disconnects, French 1.5×/2× text, a 10,000-pull
plan, orange grip changes through pause, and graph/control geometry at standard
text size. Strict text-overflow checks pass. `assembleDebug` passes; the final APK
was inspected on the API 36 emulator.

Native previews and iOS test results are in `build/review/rest-permanent/`; Android
previews and verification are in `build/review/android-rest-focus/`.
No version bump, physical-device installation or store submission is part of this change.

## iOS nested corners — audit complete, changes proposed

Only `RunnerScreenBorder` explicitly uses `ConcentricRectangle` today. Cards use
fixed continuous radii; custom glass buttons use capsules, while other buttons
use native glass styles. There are no explicit `containerShape` declarations.

- [ ] Adopt container-aware concentric corners for appropriate nested surfaces,
  when a nested surface benefits from it. Keep capsule buttons and the
  intentional finger-glyph geometry. Review existing card/badge pairs separately.

Apple treats fixed shapes, capsules and concentric shapes as complementary tools:
[Build a SwiftUI app with the new design](https://developer.apple.com/videos/play/wwdc2025/356/).
Use glass selectively for a distinct functional layer, with readable content:
[Materials guidance](https://developer.apple.com/design/human-interface-guidelines/materials).

## Optional developer support — decision pending

An optional “Buy me a coffee” / “Support development” action is being considered.
Existing support page: <https://buymeacoffee.com/irunnuri> (provided by Nuri).
The public page was checked on 10 September 2026: it identifies Nuri, links to
nuri.run, offers a custom contribution with a $1 USD minimum, and leaves the
“Make this monthly” option unchecked. No digital perks are advertised on that
page. Public-page inspection does not verify checkout completion or account
payout readiness.

No in-app payment feature, product, price or entitlement is configured yet.
Keep any support action separate from project attribution, with no training
features or assistance dependent on paying.

The proposed default for iOS is a native consumable tip purchase. An external
payment link needs a separate storefront/provider assessment. For Android, assess
whether a pure tip meets Google's direct-contribution exception before choosing
external payment or Play Billing. Website support is a separate option.

Policy references checked on 10 September 2026; recheck before implementation
and release:

- [Apple App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/):
  sections 2.3.10, 3.1.1, 3.1.1(a), 3.2.1(vii) and 4.2.2.
- [Google Play payment-policy FAQ](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en):
  “Do direct tips or contributions from user to creator require Play’s billing system?”

## Release preparation — later

- [ ] Add upcoming features and regression reports to this plan as scope evolves.
- [ ] Set both marketing versions to 1.1.0 and assign fresh build numbers when
  preparing test/store artifacts; current manifests still identify the preceding
  releases.
- [ ] Run relevant platform, UI, shared-fixture and physical-device checks.
- [ ] Confirm the Android border fix on the reporter's actual device if available.
- [ ] Write final release notes, verify signed artifacts, tag exact source and
  submit only after the planned feature/testing work is complete.
