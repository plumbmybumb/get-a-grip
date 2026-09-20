# Release record — iOS 1.1.1 (11), 21 September 2026

The training-day and live-gauge release. Source commit
`7f2dc3e88ad7fec4021172ac2d20f5c10db1d57c`, tagged `ios-v1.1.1-11`.
The record retains the requested 21 September release label; the archive and upload
occurred on 20 September. No application code was changed for this release.

## What shipped since 1.1.0 (10)

- The training day rolls over at 04:00. Launch repair re-files timed sessions under
  the training day on which they started, so late-night sessions stay together.
- Deleting a routine also deletes its sessions; Undo restores the routine and sessions.
- Today's gauge bar opens a live gauge in the runner's shape: full-screen trace,
  one glass panel for readings and one dock for actions.
- History adds an all-time card for sessions, pulls, time under tension, volume,
  days trained, climbing days and the heaviest pull.
- Settings opens on the gauge picker.
- The watch's gauge row opens its live gauge screen.

## Verification

- Prior green results at `7f2dc3e`: iOS 809 tests and Android engine/app suites,
  supplied in the release handoff; not rerun in this release pass.
- Generated using the ignored `project.local.yml`, with
  `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.
- Release archive: **ARCHIVE SUCCEEDED**. Direct App Store Connect upload:
  **Upload succeeded**, **EXPORT SUCCEEDED**.
- Inspected the final distribution-signed app with `codesign`: `aps-environment`
  is `production`, CloudKit environment is `Production`, bundle is `run.nuri.doigt`,
  App Group is `group.run.nuri.doigt`, container is `iCloud.run.nuri.doigt`, team is
  `N96JH9BCQ9`, and `get-task-allow` is false.
- No additional hardware verification was performed in this release pass.

## Archive

`xcodebuild archive` with the private spec, Release configuration and generic iOS
destination, then `xcodebuild -exportArchive` with `destination: upload`. Export and
upload options were copied unchanged from `build/releases/ios-1.0.3-7/`.

- `~/Library/Developer/Xcode/Archives/2026-09-20/Get a Grip 1.1.1 (11).xcarchive`
- Executable SHA-256: `b8db99a1a62e4eabaa0ab9a14526f28ed445d9121089211f6bd541ee1e9a9cc7`
- Upload succeeded **2026-09-20 12:31:52 CEST (UTC+02:00)**; uploaded package processing.
- Local archive/upload logs and options: `build/releases/ios-1.1.1-11/`.

## App Store Connect, this submission

- New version 1.1.1 created for app `6804236185`.
- English What's New saved verbatim from `docs/releases/1.1.1/ios-en-US.txt` (five lines).
- Existing screenshots, description, keywords and review information carried over.
  The new version's promotional-text field was initially blank; restored the exact
  1.1.0 promotional text so the listing remains unchanged.
- Build 11 finished processing, was attached to 1.1.1, and was submitted using
  Add for Review then Submit for Review on **2026-09-20 at approximately 12:42 CEST**.
  App Store Connect confirmed **1 Item Submitted** and **Waiting for Review**.
- Inherited screenshots verified: seven iPhone, three iPad 13-inch and five Apple
  Watch Ultra 3 screenshots. Automatic release after approval remains selected.
- No export-compliance or App Privacy question blocked this submission; no privacy
  declarations were changed.
