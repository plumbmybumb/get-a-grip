# Get a Grip iOS 1.0.4 (9) release

Prepared 18 September 2026 from the canonical public checkout, main at `5a0c043`.

- iOS: 1.0.4 (9), app and widget.
- Store copy: `docs/releases/1.0.4/ios-en-US.txt` and `docs/releases/1.0.4/ios-fr-FR.txt` (What's New).
- Android: 1.0.1 (11) was submitted to the Alpha track earlier the same day with the
  same features; see `RELEASE_2026-09-18_ANDROID_11.md`.

## Changes since 1.0.3 (8)

- Frez Dyno support: a one-time note from Frez on first selection, a per-unit
  calibration lookup that is the app's only network request, and the legal texts
  revised to `2026-09-18` for it (`docs/FREZ_DYNO.md`).
- Start an alternating routine on the right hand (Swap under Hands in the builder).
- WH-C06 set to pounds no longer reads 2.2× too heavy; Diagnostics name the unit.
- Builder performance on older iPhones: rows and sections compare on values and the
  builder's cards are flat fills instead of live blurs (`docs/NEXT_RELEASE.md`).
- One App Store rating request after the fifth saved session, on Today, plus a
  permanent "Rate on the App Store" link in Settings › Support.

## Validation before packaging

- 770 iOS unit tests pass (`./build.sh test`), including the review-request policy
  and the store's session count.
- Builder edits re-measured on the pinned simulator: 3 bodies per keystroke (was
  15), 14 per band-drag frame (was 25); flat card fills within 1.3 RGB units of the
  material in both schemes.
- The rating prompt was exercised on the simulator with seeded history: it appears
  on Today one second after the fifth session is saved, once.
- No hardware validation claim is made for the Frez Dyno; the note in the app says so.

## Archive

`xcodebuild archive` with the ignored `project.local.yml` spec (team and Frez key),
Release configuration, generic iOS destination:

- `~/Library/Developer/Xcode/Archives/2026-09-18/Get a Grip 1.0.4 (9).xcarchive`
- Executable SHA-256: `7d6062e0c08c140653b0dc1215362a944a801d5ff0eaba4c2da51c3f2a51f370`
- Signed with the development identity under team `N96JH9BCQ9`, like every previous
  archive; Xcode re-signs for distribution and sets `aps-environment` to production
  during the App Store export. dSYMs for the app and the widget are in the archive.

Release tag `ios-v1.0.4-9` pins `5a0c043`; this record is a documentation-only commit.

## App Store Connect, this submission

1. Organizer › Distribute App › App Store Connect › Upload, automatic signing.
2. App Privacy: the app can no longer be "Data Not Collected". Add **Other Data
   Types** — "the serial number of a connected Frez Dyno force gauge, sent to the
   gauge's manufacturer to fetch its calibration" — purpose App Functionality, not
   linked to identity, not used for tracking. The bundled privacy manifest already
   declares it.
3. Version 1.0.4: paste the What's New texts; in the review notes, say the Frez note
   is a disclosure the hardware maker requires with no purchase link, that demo mode
   works without hardware, and that the app requests a rating once through the
   system prompt after the fifth session.
4. Description: add the Frez Dyno to the list of supported gauges (descriptive use,
   with the maker's permission); keep it out of the keywords.
5. Privacy Policy URL is unchanged; the pages at nuri.run carry the 2026-09-18 text.
6. Export compliance is unchanged (standard HTTPS, `ITSAppUsesNonExemptEncryption`
   false), and the CloudKit schema needs no deployment: no model change since 1.0.3.

## App Store Connect state, 18 September 2026 (evening)

- Build 9 uploaded through Xcode's signed-in account (`xcodebuild -exportArchive`
  with `destination: upload`), processed, "Ready to Submit", visible to the internal
  TestFlight group (one tester). The same archive was installed directly on the
  developer's iPhone with `devicectl`.
- Version 1.0.4 created and saved: What's New (English), promotional text, the
  description naming the Frez Dyno, the v2 screenshot set in both the 6.9" and
  6.5" slots in the playbook order, review notes for the Frez note and the rating
  prompt, build 9 attached. The listing has no French localization, so the French
  What's New in `docs/releases/1.0.4/` is unused until one is added.
- App Privacy: "Other Data Types" (App Functionality, not linked, no tracking)
  entered up to the dialog's Publish button and left there, because publishing
  changes the live product page at once and the developer had not asked for that.
- Not submitted for review; the developer tests the build first.
