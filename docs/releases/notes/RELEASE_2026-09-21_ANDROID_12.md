# Android 1.0.2 (12) — 21 September 2026

Submitted to the closed Alpha track (100% rollout). Play Console showed **Changes
in review**, with automated quick checks running. The record retains the requested
21 September release label; the upload and submission occurred on 20 September.

| Item | Value |
| --- | --- |
| Source | tag `android-v1.0.2-12`, commit `7f2dc3e88ad7fec4021172ac2d20f5c10db1d57c` |
| Version | `run.nuri.getagrip` versionCode 12, versionName 1.0.2, target SDK 36 |
| Bundle | `app-release.aab`, 6,773,262 bytes, SHA-256 `76a5ff285adda5c97961559f2719277a94894dcca3e8bcbd3f8a1aa6ec6b0d96` |
| APK (sideload) | `app-release.apk`, 5,369,063 bytes, SHA-256 `2095c086fd23af240c06e7d4d27867e89699eec50790165861ef92bc4ede3bbd` |
| Signer | upload key, SHA-256 `DA:6D:B7:CC:2E:5D:20:21:69:55:FB:94:68:2C:00:BC:C3:27:BA:E4:4E:BA:8D:EB:CC:13:44:E5:7A:44:CF:24` |
| Upload | accepted in Play Console on 2026-09-20, approximately 12:35 CEST (UTC+02:00); exact server completion second not exposed |
| Submission | 2026-09-20, approximately 12:38 CEST (UTC+02:00); Changes in review |
| Tests | Prior green results at `7f2dc3e`: iOS 809, Android engine and app suites; supplied in the release handoff, not rerun in this release pass |
| Build | Private upload-signing script; BUILD SUCCESSFUL, bundleRelease and assembleRelease |
| Console warnings | one, missing native debug symbols from dependencies; ReTrace mapping included |
| Device support | unchanged from build 11 |

What's in it: the 04:00 training-day rollover and launch repair of session days,
routine deletion cascading to its sessions with Undo restoring both, the live gauge
button on Today, the all-time History card, and the gauge picker first in Settings.
Android's runner and gauge retain their existing appearance. Release notes are the
exact text in `ANDROID_1.0.2_WHATS_NEW.txt`.

Artifacts are retained in `build/releases/android-1.0.2-12/`. The APK uses APK Signature
Scheme v2; `keytool -printcert -jarfile` reported "Not a signed jar file" because there
is no v1 JAR signature. Android SDK `apksigner verify --verbose --print-certs` verified
the v2 signature and the expected certificate above. No signing settings were changed.

Only Closed testing - Alpha was submitted, at 100%. Managed publishing is off;
testers receive the update after successful checks and approval. No production,
pricing, listing, or privacy changes were made in this release pass.
