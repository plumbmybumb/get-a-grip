# iOS 1.0.2 — 8 September 2026

**Superseded:** build 5 was withdrawn before release and replaced by
[1.0.2 (6)](RELEASE_2026-09-08_UNITS_AND_BORDERS.md). The evidence below records
the original build 5 submission.

Get a Grip **1.0.2 (5)** was archived and uploaded to App Store Connect from
source commit `ff3d162591e5d13f7dc53ed97bc3eccb214964b1`. The app and its widget
both carry marketing version 1.0.2 and build number 5.

Release notes: [English and French](IOS_1.0.2_WHATS_NEW.txt).
The source and tag `ios-v1.0.2-5` have been pushed to the public repository.
[GitHub Actions run 34230335892](https://github.com/plumbmybumb/get-a-grip/actions/runs/34230335892)
passed.

## Verification

- All **660 unit tests** and **12 UI tests** passed with no failures. UI coverage
  includes exact numeric entry, session logging, first launch, max controls,
  French runner controls and hold gestures, and review of peaks by hand.
- The Release archive and App Store Connect export both succeeded.
- The exported app and widget passed strict code-signature verification.
  Debugger access is disabled in both distribution entitlements.
- The app's push entitlement is **production** and its CloudKit environment is
  **Production**. Existing bundle IDs, App Group and CloudKit container are
  preserved.
- The development-signed app from the Release archive was installed on a physical
  iPhone 17 Pro running iOS 26.6.1. The device's installed-app listing confirmed
  **1.0.2 (5)**. With a real WH-C06, the user confirmed prompt weight tracking and
  return to zero after a gentle pull and release.
- **The combined physical recovery/background check remains pending:** scale
  power-off/on, Cancel followed by Connect, and measurement after switching apps.
  Sustained response has not been established by the short initial check. These
  physical findings are user-confirmed, not remotely observed UI results;
  simulator tests do not establish real radio behavior.

## Artifact

The verified local App Store export is `Doigt.ipa`, **4,357,470 bytes**.

SHA-256:
`bd378a07af53dc313ec5edc440ef17497bef10d72031806f9d3764d08af03a42`

The upload was exported from the same archive using Xcode's authenticated
App Store Connect upload flow. Xcode may re-sign or repackage during upload, so
the hash above identifies the verified local IPA, not an asserted hash of
Apple's uploaded package.

## Distribution status

At **2026-09-08 13:05:55 UTC**, Xcode reported **Upload succeeded** and
**Uploaded package is processing**. The archive's upload event records
**Uploaded to Apple**, with no errors or warnings.

At **2026-09-08 13:30 UTC**, App Store Connect showed version **1.0.2** as
**Waiting for Review**, confirming submission. This is not release approval or
availability on the App Store. The remaining physical checks above are still
outstanding.
