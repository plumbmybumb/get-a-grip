# Android 1.0.1 (7) — release verification

Build 7 adds clear screen-edge cues during pulls and release, names the upcoming
hand during rest, and improves button spacing and large-text layouts. Routine
peak review uses the exact grip and hand record, with explicit hand labels before
saving. It includes the WH-C06 recovery and long-session scan renewal fixes from
build 6.

Source: `ff3d162591e5d13f7dc53ed97bc3eccb214964b1`.

## Verification

- All **982 Android tests** pass: **580 app tests and 402 engine tests**, with no
  failures, errors or skipped tests. The optimized release APK and AAB build
  successfully, including release lint checks.
- Native UI regressions cover blue pull, red warning and orange release cues,
  inactive/paused phases, timer-only operation, unchanged graph bounds and touch
  handling. French and large-text button layouts, tablet centering and native
  display-outline geometry are also covered.
- APK and AAB signatures verify and match build 6's upload certificate. APK
  **16 KiB packaging alignment** passes. Application identity, permissions and
  device requirements match build 6; the release is not debuggable.
- The AAB includes ReTrace mapping, MPL and third-party notices, and the bundled
  agreement. Independent review found no blockers; the final public-source
  secret scan reported no findings.
- The public repository's Android and iOS CI checks also passed for the exact
  tagged source in [GitHub Actions run 34230335892](https://github.com/plumbmybumb/get-a-grip/actions/runs/34230335892).
- `BroadcastGaugeClient`, `BroadcastScanTransport` and the broadcast-client tests
  are byte-for-byte unchanged from integrated hotfix commit `00d9ff3`. The
  four-minute renewal still checks scan quota before retiring a healthy scan and
  preserves the scale lock, tare, sample timeline and silence watchdog.

The hardware checks documented for [build 6](RELEASE_2026-09-08.md) therefore
exercise the unchanged Android broadcast implementation: load tracking, tare,
scale-off/on recovery, app switching and response beyond the previous slowdown
point. **Build 7 has not yet been tested on a physical device.** Its new visual
and hand-max changes have automated/native-render verification only.

## Artifacts

- Signed AAB: **6,556,553 bytes**; SHA-256
  `8db41c0cb73b6c2d6a64aefb0fd884e25935d6a966814698cd46cb4e0ae4bdee`.
- Signed APK: **5,139,574 bytes**; SHA-256
  `b6399ec4d28d2c60abd51156bbdf74e3f6003fe4cd7579adccfc4eb762aa186e`.
- Release notes: [English and French](ANDROID_1.0.1_BUILD7_WHATS_NEW.txt).

## Play submission

On 8 September 2026, the signed build 7 bundle was uploaded and submitted to
**Closed testing - Alpha**, with a **100% rollout** to that track's existing
testers. At 13:32 UTC, Play Console displayed **Changes in review** and confirmed
the changes were in review, with the automated quick-check progress no longer
shown. Managed publishing remains off, so an
approved release can become available without a separate manual publication.

Play reported no lost supported devices. The only upload warning concerned
optional native debug symbols; the ReTrace mapping file is included. The store
listing currently accepts the English release notes; a French translation is
retained alongside them in source.

This records submission, not approval or confirmed tester availability. Public
source and the `android-v1.0.1-7` tag were pushed before submission.
