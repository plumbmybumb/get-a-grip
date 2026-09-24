# iOS 1.1.2 (12) — 23 September 2026

Built from `80b4008` (GitHub `main`). Carries the Frez Dyno endpoint move (Frez retires
`/v1/dyno/coefficient` on 30 September 2026 00:00 UTC) and the 23 September reliability,
speed and code-tidy work. What's New: [docs/releases/1.1.2/](../1.1.2/).

## Verification

- iOS suite: 847 tests, 0 failures, on the pinned iPhone 17 Pro simulator; the watch app
  builds. Android at the same commit: 746 app + 450 engine tests, debug and release
  assemble.
- The comment trim (`6bd3597..80b4008`) was checked with a comment-stripping lexer: 222
  source files, 0 with code changes.
- Frez: the new endpoint answers (unauthenticated probe returns the same
  `invalid_access_key` shape as the old one). A keyed first-time calibration against it
  has NOT been run.
- No hardware verification in this release pass. Owed: a Dyno first-time calibration;
  Bluetooth off/on with a Progressor; the unsaved-session recovery prompt after killing
  the app on a summary; the Live Activity stopped-clock and stale cards; watch finish and
  hold-to-discard.

## Archive

`xcodegen generate --spec project.local.yml`, `xcodebuild archive` (Release, generic iOS),
then `xcodebuild -exportArchive` with `destination: upload`; options copied unchanged from
`build/releases/ios-1.1.1-11/`.

- `~/Library/Developer/Xcode/Archives/2026-09-23/Get a Grip 1.1.2 (12).xcarchive`
- Executable SHA-256: `403d8554cbb3331bc4556f95d77f3d3c5a759d111854b57d82c6e840e70baf48`
- Upload succeeded **2026-09-23 14:00:02 CEST (UTC+02:00)**; package processing.
- Local logs and options: `build/releases/ios-1.1.2-12/`.

## App Store Connect

Version 1.1.2 created; What's New (English) from `docs/releases/1.1.2/ios-en-US.txt`;
promotional text restored (the new version's field came up blank, as on 1.1.1); build 12
attached; screenshots, description, keywords and review notes carried over. Release:
automatic, all users at once (unchanged). **Submitted for review 2026-09-23 15:43 CEST; approved and released 2026-09-24.**
Build 12 is also on TestFlight in the internal "Test" group.
