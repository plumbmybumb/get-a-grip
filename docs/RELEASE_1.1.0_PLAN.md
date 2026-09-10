# Get a Grip 1.1.0 development plan

Started 10 September 2026 on `codex/release-1-1-0`, based on public commit
`333d630fa63b728335fcc7309d8f0e0f32cf005e`. This is the next shared iOS/Android
development branch. The existing release tags remain immutable. This document
tracks proposed and completed work. Checked items are implemented on this branch;
they have not been released unless explicitly noted.

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

## Rest presentation — design proposal

The proposed layout is not implemented in either app yet.

- [ ] For rests originally scheduled for at least 10 seconds, replace the measured
  weight hero with a modestly larger glyph, a prominent next-hand heading and full
  grip wording. Retain a secondary upcoming target when present.
- [ ] Center a large rest countdown over the existing graph area, with a quiet
  native material treatment. Keep the graph frame and bottom actions in place;
  make set/pull position easier to read. Avoid duplicating the grip-change banner.
- [ ] Decide the layout from the rest-owning slot's scheduled `restAfter`, including
  set breaks and per-set overrides. Do not switch it back as remaining time crosses
  10 or 3 seconds. Keep short rests in the current layout.
- [ ] Enter only during actual rest, after release. Keep LET GO and measured load
  visible while releasing. Restore the live view immediately when rest ends.
- [ ] Preserve the resting layout through pause with a clear paused timer. Signal
  recovery notices must remain visible; tare must retain its load confirmation.
- [ ] Use one restrained entry/exit transition, no frame-by-frame graph blur or
  new live-data subscriptions. Preserve reduced-motion/transparency behavior.
- [ ] Use native iOS materials and Android Material surfaces; verify long/short
  rests, set changes, hand changes, pause, disconnect, skip, timer-only sessions,
  English/French, large text and small screens before integrating.

Existing snapshots already look ahead to the upcoming grip, side, target and
set/pull position during rest. They need a coarse presentation flag or scheduled
rest duration; `secondsShown` alone cannot select this layout correctly.

## iOS nested corners — audit complete, changes proposed

Only `RunnerScreenBorder` explicitly uses `ConcentricRectangle` today. Cards use
fixed continuous radii; custom glass buttons use capsules, while other buttons
use native glass styles. There are no explicit `containerShape` declarations.

- [ ] Adopt container-aware concentric corners for appropriate nested surfaces,
  starting with the proposed graph/rest treatment. Keep capsule buttons and the
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
