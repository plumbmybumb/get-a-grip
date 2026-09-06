# Responsiveness audit — 6 September 2026

Scope: the native iOS and Android apps after the training safeguards, summary,
effort picker, compact logging form, and Maxes navigation changes. This is a source
audit plus regression and simulator verification, not a physical-device latency
benchmark. Existing unrelated work in this checkout is outside this change list.

## Findings and changes

| Area | Finding | Change |
| --- | --- | --- |
| Android routine editor | Scroll-position bookkeeping wrote observable state and invalidated the eager editor document during scrolling. | Store layout anchors outside observation; resolve coordinates only when an action needs to scroll to a control. |
| iOS numeric steppers | A drag could return inside the tap area and become an edit; cancellation could leave repeat work alive. | Latch scroll cancellation for the entire touch and cancel repeat work when gesture state ends, including interrupted gestures. |
| iOS accessible dial adjustment | Incrementing a typed value between ladder stops could skip the adjacent stop. | Select the immediate higher or lower stop. Typed precision and limits remain independent of slider presets. |
| iOS effort picker | The Clear button's visible padding was outside its tappable label. | Put the full 44-point hit area inside the button and use the existing press feedback. |
| iOS grip panel | Closing depended on an uncancelled fixed 280 ms delay and could queue repeated callbacks. | Close from the actual animation completion, with a guard against repeated dismissals. |
| iOS pull progress | Display progress was quantized into whole percentages. | Publish the exact fraction and settle the display between measured updates. A new work phase resets the presentation; Reduce Motion snaps. This does not invent measured work time. |
| Both workout screens | Countdown fractions were carried in the broad runner snapshot, updating unrelated workout UI on each timer tick. | Let a small timer-ring view observe the fraction separately. Android also settles its ring between timer updates. |
| iOS runner shutdown | Cancelled ticker sleeps could fall through into one final update; deferred audio startup could run after ending. | Return on cancellation and check session lifetime before starting cues. |
| Android routine sharing | Routine encoding, QR encoding, and bitmap creation competed with input on the UI thread. | Run encoding and bitmap work on a worker. Cancel superseded requests and never show a QR for an older payload. |
| Both image-sharing flows | PNG compression competed with UI work. Android also performed output writes on the UI path. | Compress off the UI thread; use Android's IO dispatcher for output writes. Keep platform-required rendering/capture on the UI thread. Match completed results to the current selection. |
| iOS training export | Decoding every workout blob happened before the export sheet could do background formatting; changing options repeated decoding. | Snapshot plain values on the model actor, decode and format on a per-sheet worker, and reuse the decoded input. Cancel obsolete requests and expose Share/Copy only for the current selection. |
| iOS history | Each trend lookup recomputed a whole-history signature and stale chart generations accumulated. | Check history identity once per body evaluation and keep only the current generation of derived chart results. |
| Android history | Whole-history sorting ran on the UI dispatcher and overlapping refreshes could publish stale data. | Sort on a worker, cancel superseded refreshes, and check generation before publication. Failed reads retain the last complete snapshot. |
| Android manual session/max Save | Rapid taps could enqueue multiple writes before the UI disabled the button. | Claim submission synchronously, freeze the submitted values, release the claim on completion or cancellation, and allow retry after failure. |

## Coverage

- **Typing and numeric controls:** builder value fields, time and load limits,
  exact target percentages, plus/minus tap and repeat, dial dragging and
  accessibility adjustment, target-band dragging, and gesture cancellation while
  scrolling. Existing leaf-local edit state and reusable number formatters were
  retained.
- **Selections and feedback:** grip/finger chips, effort tap/drag/Clear, units,
  session type, date and duration, tabs, routine pages, sheet dismissal, and the
  Maxes management route. Effort haptics occur on changed user selections;
  animation does not change the surrounding layout or touch regions.
- **Workout display:** force delivery, runner publications, graph ownership,
  countdown and pull progress, grip-change cues, and teardown. BLE samples remain
  separate from presentation updates.
- **History and sharing:** cached trends, overlapping refreshes, delete/undo
  ordering, QR creation, routine encoding, image output, CSV snapshotting and
  changing export options.
- **Saving:** manual entries, pending writes, cancelled scopes, summary save and
  discard behavior, and failure/retry handling. Optional ratings remain optional.

The audit did not shorten destructive hold durations or the repeat button's hold
threshold. Tare validation, stale-reading handling, timestamp accounting, release
gates, and the one-time acknowledgement remain in place.

## Verification

- iOS: all 552 unit tests passed in the final full run, including the final worker
  cache, cancellation, scope/detail changes, and frozen model values. Xcode's test
  service stalled before an intermediate rerun could start; restarting that
  service and the test simulator restored execution.
- Android: 454 app tests and 393 engine tests passed; debug APK assembled.
- iOS UI automation passed for stepper tap/hold/scroll cancellation, exact target
  entry, compact session logging, effort tap/drag/Clear, Maxes navigation, and
  large-text max measurement controls. The acknowledgement test passed on a fresh
  simulator; its initial run used a simulator that had already accepted it.
- New targeted coverage includes editor scroll recomposition, QR replacement
  while input remains usable, transparent PNG output, history refresh races,
  cancelled/repeated saves, sub-percent progress, and runner teardown.
- The rebuilt Android app also passed actual emulator checks for effort taps,
  drags and Clear, duration/type/day selections, fixed Save visibility, and
  scrolling from the builder dial without changing its value. Back and Cancel
  returned to Today; no data was saved and the crash buffer was empty. The rebuilt
  iOS compact form was visually checked, and both preview apps remain available.

## Practical limits

Simulator and Robolectric success establish behavior, not touch-to-photon latency
on a specific phone. There is no before/after physical-device frame-time capture
in this audit. Bluetooth under real radio conditions, thermal throttling, and the
feel of physical haptics still need a device pass.

Platform UI rendering/capture still has to run on its UI executor. PNG compression
and CSV formatting are synchronous operations on background workers: cancellation
prevents stale publication but cannot interrupt one already-running encoding call.
An exceptionally large export can therefore take time to finish without blocking
input. First-time chart preparation still scales with the history being displayed;
the cache prevents repeated decoding during ordinary selection changes.

No store release, physical-device installation, commit, or push is part of this
audit.
