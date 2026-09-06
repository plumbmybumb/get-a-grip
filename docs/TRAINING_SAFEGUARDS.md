# Training safeguards — September 6, 2026

## This revision

- Training and max measurement now open directly, without a legal acknowledgement
  gate. Terms and Privacy remain readable offline in Settings. Historical explicit
  acknowledgement records are retained; using the app creates no new acceptance.
  The immutable `2026-09-06-r2` document bundle remains unchanged.
- iOS max measurement now uses the same loaded-tare confirmation policy as the
  runner: an unknown/stale reading wakes the stream; loaded taring requires a
  confirmation rechecked against load, sample age and connection epoch.
- Max-test live readouts show an unavailable state when the signal goes stale.
  Previously recorded peaks remain identified as peaks. Prompts no longer urge
  users to beat a previous attempt.
- Connection instructions explicitly ask for an unloaded gauge. Shared routine
  import notes distinguish sender-authored kg targets from percentage targets
  resolved against the recipient's saved maxes.
- Builders explain that saved maxes may not reflect current strength and identify
  percentage targets that cannot resolve. A fixed-kg set can no longer hide a
  later percentage set of the same grip from the missing-benchmark count.
- Pre-keyed max tables reject infinite values as well as NaN/nonpositive values.

## Verification

The Swift and Kotlin regression cases cover repeated grips, mixed fixed/percentage
loads, missing hand-specific benchmarks, skipped sets and invalid stored maxima.
Existing suites cover tare revalidation, stale samples, device-clock timing,
protocol decoding and agreement persistence/failure handling.

The iPhone UI test checks direct training entry on launch and relaunch, plus max
measurement without an agreement screen. Android checks offline legal documents and
historical receipt handling. Demo simulators do not validate physical Bluetooth
readings or calibration.

The source claims audit covered the app's training/import copy, public README,
Android listing source and the website landing, support and beta pages. No
injury-prevention or guaranteed-results claim was found there. The Android listing's
export description was checked against both implementations and names CSV. Live App Store metadata could not be
retrieved in this pass and is not represented as audited.

## Release checks

Review the simulator flows before submitting store builds. Publish the matching
website bundle before releasing apps that refer to its permanent version links.
On physical hardware, confirm unloaded startup, a loaded-tare prompt, cancelling
that prompt, connection loss during a prompt and the stale-reading state during a
max test. Simulator tests cannot establish those hardware results.

## Follow-up bug and responsiveness sweep

The follow-up reviewed the agreement gate, max-entry/measurement screens, builder
benchmark warnings, tare graph preservation, and their state/clock boundaries.

Fixed:

- Android could parse a syntactically valid but structurally damaged acceptance file
  and crash while showing its receipt in Settings. Record fields are now validated
  before they enter UI state; malformed receipts are not displayed. Current builds
  retain genuine records but do not request fresh acceptance.
- Android reparsed and hashed the same packaged legal documents on each new gate or
  Settings composition. The immutable bundle is now cached for the app process.
- The iOS live max readout scheduled a spring animation for each changing force
  value despite using an identity number transition. That unnecessary animation is
  removed; sensor readings continue to update directly.
- The new lost-reading message was absent from the measurement's spoken summary.
  VoiceOver/TalkBack now get that coarse status without exposing every live sample.
- Max tare confirmation now rechecks the actual current screen phase. iOS also
  cancels a queued re-prompt when its control disappears.
- The new Android screen test reproduced max controls being clipped on a short
  viewport. Both max screens now scroll when content exceeds the available height,
  retaining the existing graph height and normal full-height layout.
- Older max-entry text still encouraged guessing a value and implied a standard
  intensity range for every routine. Shorter copy now describes measured references
  and checking entered units, without implying a safe-load threshold.

No sleeps, smoothing, debounce, throttling or additional I/O were added to sample
processing, work/rest timing, or the force graph's per-frame drawing. The runner
continues to receive raw samples on its existing device-clock path. Setup warning
calculations use the cached max table, not database reads at sample rate.

Regression evidence includes malformed-record recovery and the Settings receipt UI,
short-screen control reachability, reconnect invalidating a loaded-tare prompt,
spoken lost-signal status with a retained peak, graph continuity across a device
counter reset, existing publication-count tests, and the full platform suites.
This is source/simulator verification, not a physical-device frame-time measurement.
Only an Android emulator was connected during the sweep. Experimental WH-C06 unit-mode
interpretation and real-device Bluetooth/tare behavior still require hardware checks.
