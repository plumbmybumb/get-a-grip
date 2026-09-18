# Weight units and workout borders — 8 September 2026

This update replaces the withdrawn iOS **1.0.2 (5)** submission with **1.0.2 (6)**
and updates Android's closed Alpha track from **1.0.1 (7)** to **1.0.1 (8)**.

## Changes

- A persistent kg/lb preference in Settings applies to weight displays and entry,
  including live readings, targets, maxes, history, share images and system workout
  surfaces. Stored values, gauge calculations and machine-readable exports remain
  kilograms. Switching units does not rewrite history or untouched max records.
- Android reconciles native display outlines with the logical window's dimensions,
  rotation and offset. Invalid or contradictory geometry falls back to the
  window's rounded corners, addressing borders drawn inside the workout screen.
- Both apps place REST / SET BREAK below the hero numbers, between set and pull
  counts, and show a quiet, steady gray border during active rest. The graph keeps
  its available height; paused sessions have no active border.
- Countdown rounding no longer briefly invents an extra second at floating-point
  boundaries. Measured pull-time accumulation and actual deadlines are unchanged.

Release notes: [iOS](IOS_1.0.2_BUILD6_WHATS_NEW.txt) and
[Android](ANDROID_1.0.1_BUILD8_WHATS_NEW.txt).

## Source and verification

| Platform | Artifact source | Public tag |
| --- | --- | --- |
| Android | `590c94e51f28937a353d0aa4b51e7f0f7ba79c31` | `android-v1.0.1-8` |
| iOS | `7cb054121825a8fa093e1a841c8704b53344091e` | `ios-v1.0.2-6` |

The second commit adjusts only the compact-iPhone rest layout and its UI test.
Android and fixture trees are identical between the two source commits. Both
commits and tags were pushed before store submission.

- **671 iOS unit tests** and **five focused UI tests** passed, covering unit entry,
  persistence, French accessibility layouts, hold cancellation and compact-iPhone
  counter placement.
- **1,006 Android tests** passed: 602 app and 404 engine tests, with no failures or
  skips. Native render tests and emulator checks cover borders, resolution changes,
  large French text, pause behavior and touch handling.
- The independent Swift oracle passed **39 runner scenarios / 4,130 steps**,
  **70 routine URLs** and **18 export scenarios**. One expected rest cue changed at
  `4.400000000000001` seconds; inputs, recorded reps and final state are unchanged.
- Both public CI jobs passed in
  [run 34275971868](https://github.com/plumbmybumb/get-a-grip/actions/runs/34275971868)
  for iOS's exact source commit and Android's identical source tree.
- Independent code review found no release blockers. The public-source secret scan
  found no leaks. Bluetooth transport source is unchanged on both platforms.
- Android APK/AAB signatures match build 7. Permissions, device requirements and
  SDK bounds are unchanged. APK ZIP and native ELF alignment satisfy 16 KiB page
  requirements. ReTrace mapping and required notices are included.
- The iOS app and widget both carry 1.0.2 (6). Distribution signatures verify,
  debugger access is disabled, APNs is production and CloudKit is Production.
  Existing bundle, App Group and CloudKit identities are preserved.

The new visuals and unit flows were verified on simulators/emulators, including
changed Android display resolution and compact iPhone layouts. **The reporting
OnePlus device was not available for a physical check.** Earlier hardware findings
remain documented in [Android build 6](RELEASE_2026-09-08.md) and
[iOS build 5](RELEASE_IOS_1.0.2.md); these are not new-build hardware verification.

## Artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Android AAB | 6,574,457 | `2d6d412e22ab151d14c1fed5be7120f0fb150f1e3d5fcde8b6deef293c790e51` |
| Android APK | 5,172,298 | `2507f083f488fa1ae4ea9cd97acf428a4d1b363fdf9e7553666fe3df0e6ec04a` |
| Local iOS IPA | 4,401,731 | `d9b475ed8c36b50c72107db44b2bbe2885e3f9b64ec0c337e6fed19f4d05ffe2` |

The iOS hash identifies the verified local export. Xcode uploaded from the same
archive while preserving build 6, but may re-sign or repackage during upload.

## Store submissions

On **8 September 2026**, Play Console accepted Android build 8 for a **100% rollout
to the existing closed Alpha track**. It displayed **Changes in review**, with
automated quick checks still running. Managed publishing remains off, allowing
publication after approval. Tester membership, countries and supported-device
coverage are unchanged.

Play's sole upload warning concerns optional native debug symbols. All eight
native binaries are byte-identical to build 7, which had the same warning. The
upstream AARs do not provide a complete matching native-debug-symbol archive;
the Java/Kotlin ReTrace mapping is included.

Apple finished processing build 6, it replaced build 5 in the 1.0.2 version, and
App Store Connect confirmed **Waiting for Review** for **1.0.2 (6)**, submitted at
**20:57 UTC**. Automatic release after approval remains selected, with existing
ratings preserved. The old build 5 submission was withdrawn by the developer.

These statuses record submission, not store approval or confirmed availability.
