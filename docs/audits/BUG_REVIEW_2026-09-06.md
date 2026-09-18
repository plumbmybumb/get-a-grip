# Current-source review of Fable's findings

Reviewed all 106 findings in the supplied full report against the canonical combined
repository at `f09c408` and the local fixes from the first high/medium pass. Fable's
report identifies its base as private `main` at `5cc5f41`; that is not the source of
the current store builds. Each finding is tracked in the
[complete disposition ledger](FABLE_AUDIT_DISPOSITION_2026-09-06.md), separating
changes, issues already resolved, outdated claims and intentionally deferred work.

## Supplied high and medium findings

| Finding | Current-source outcome |
| --- | --- |
| Session tare can anchor to a buffered pre-tare epoch | Fixed on both platforms: arm the existing bounded, single-use stale-batch recovery before the actual stream restart. Reject ineligible tare before changing runner state. |
| Completing a day permanently removes reminders | Fixed: suppress today's satisfied slots while scheduling future days. Android defers the next firing; iOS queues dated future reminders within the system budget. |
| Live Activity survives session completion | Fixed on both platforms: end at engine completion, while the summary remains available. |
| Killed-process orphan activity duplicates next session | Fixed iOS cleanup on launch and new session, targeting captured activity IDs. Android already clears stale notifications on launch and uses one notification ID. |
| Rest countdown fails to re-anchor after LET GO | Fixed on both platforms: releasing and resting have distinct published phases. |
| Phantom countdown during releasing | Fixed on both platforms: releasing, armed and paused carry no running countdown. |
| Paused hero shows the hold length | Already fixed in current source; paused display uses the frozen engine phase/countdown. |
| Timer-only session ticks in background with a gauge connected | Already fixed in current source; background policy checks timer-only mode independently of the connected gauge. |
| Ending while paused loses the current rep | Already fixed in current source; abort preserves the paused in-flight work. |
| Failed save closes summary and loses reps | Already fixed in current source; failed persistence keeps the summary open for retry. |
| Routine undo loses on-demand status | Already fixed and regression-tested on both platforms. |
| One-hand max fabricates an old max/rescale ratio from both-hands fallback | Fixed: only an exact same-side benchmark can establish an old max. Target impact uses the pre-save table and affected hands; shared typed kg bands are not guessed from a one-hand change. A valid shared-fallback rescale still works for alternating routines. |
| Yesterday's manual log displays today's insertion date | Fixed on both platforms: history display uses the selected stored training day for hand-entered sessions. |
| Grip picker permits thumb-only selection | Fixed both picker variants on both platforms: at least one non-thumb finger remains selected. |
| Start without a gauge has an 18 pt tap target | Fixed the iOS label's full 44 pt hit area. Android already has a full clickable 44 dp minimum. |
| Tour Back from History strands spotlight | Fixed on both platforms: introductory Today steps explicitly restore the Today tab. |
| Disconnect hides Use this max after a completed attempt | Fixed on both platforms: the completed result remains usable; a new attempt requires reconnection. |
| Second mock session can read a large negative load after tare | Fixed on both platforms: tare is taken against the same profile timeline; stream restart no longer resets that profile under an existing offset. |
| CLAUDE.md prescribes removed palette/typed detents | The canonical public repo has no CLAUDE.md. Its AGENTS.md is authoritative; private predecessor instructions are not the current implementation contract. |

## Reminder tradeoff

iOS does not provide a first-fire date separate from a daily repeating calendar
trigger. Dated requests let today's completed slots be suppressed without silencing
tomorrow. The planner fills the remaining budget up to 64 pending requests, earliest
first, preserves other features' requests, and refreshes during normal app activity.
For two daily reminder slots and no other requests, that is roughly 32 days. This is
a finite horizon, not indefinite scheduling if the app remains unopened for months.
Android continues scheduling subsequent days through its alarm receiver.

## Legal entry flow

At the user's request, routine, timer-only and max measurement screens now open
directly. There is no pre-training agreement screen and app use does not create an
acceptance record. Offline Terms and Privacy remain in Settings. Genuine records
from earlier builds stay readable/shareable; no empty receipt prompt is displayed.
The published immutable legal bundle is unchanged. Loaded-tare confirmation,
stale-reading handling and measurement safeguards remain separate and active.

Research checked official public material on September 6, 2026:

- [Frez's app guide](https://www.frez.app/en/start/frez-app) describes install/sign-in,
  connection, tare, recording and reviewing a test. Its
  [Terms](https://www.frez.app/en/terms) describe acceptance through downloading or
  using the app.
- [Tindeq's user instructions](https://tindeq.com/p/) put equipment risks and usage
  guidance alongside connection instructions.

Neither guide documents a separate pre-training agreement screen. These public
documents do not verify every screen in current competitor binaries, and copying
another app's presentation does not establish legal enforceability.

## Full-report follow-up

The remaining pass covers Bluetooth lifecycle and command ordering, exact target-line
engagement, reminder validation, calendar consistency, max receipts/readouts,
accessibility and UI feedback, share-file lifetimes, build failure reporting, and
stale documentation. Historical serialized values and intentionally staged pure
utilities remain readable and tested. No database schema or gauge protocol was
replaced to satisfy an outdated cleanup suggestion.

The iOS Progressor control queue now has an executable transport seam. Tests exercise
serialization, control-command priority, tare acknowledgements, timeout handling,
late replies and clearing link-local state. A missing ATT acknowledgement ends the
ambiguous link rather than retrying a command whose completion cannot be known.
CoreBluetooth delegate delivery and real radio behavior still require hardware.

Explicitly retained limitations:

- Simultaneous benchmark recording on separate CloudKit devices can merge duplicate
  day markers; preventing that requires a separate synchronization design. The local
  failed-read insertion bug is fixed.
- Foreground reminder banners and automatic routine deep links were not added:
  presenting reminder sound over an active workout needs a deliberate product rule.
- Completed CloudKit uploads cannot be proven from container creation. Settings now
  describes the actual local storage and conditional sync configuration accurately.
- Active Bluetooth workouts retain their existing background support and manual
  recovery policy; an arbitrary workout timeout would change that behavior.

## Verification

Full-report automated rerun:

- iOS: **598 unit tests passed**, no failures. Includes transport-queue ordering,
  write/reply deadlines, stream boundaries, runner timing, maxes/reminders, data
  decoding, draft preparation, and real CoreTransferable PNG/CSV byte handoffs.
- Android: **487 app tests and 401 engine tests passed**, no failures or skipped
  tests; debug APK assembled. Includes virtual-time draft coalescing and injected
  transport, storage, lifecycle and trace-gap regressions.
- Shared Swift runner oracle: **39 scenarios, 4,130 steps passed**. The two synthetic
  blob fixtures for unknown/absent rep outcome were intentionally updated from
  completed to aborted; known historical outcome fixtures are unchanged.
- Build script: injected Simulator-open, missing-plist, boot, install and launch
  failures all exit unsuccessfully without a false launch-success message. Normal
  launch targets the actual built bundle identifier.
- Icon generator: light output matches the existing icon pixel for pixel; all
  1,048,576 tinted pixels have equal RGB channels. Invalid style exits 64 without
  creating an output file.
- `git diff --check` and English/French catalog parsing pass. Android translations
  regenerated from the shared catalog. The shared Leave demo mode translation was
  moved out of Android-only extras, and both app builds passed again after that
  final resource-only change.

The first full runs exposed stale tests that injected readings before starting a
stream, old duplicate-reminder expectations, and an Android test-runner annotation
mismatch. These fixtures were corrected, then both complete suites passed. The
production stopped-stream guard and reminder-count preservation were kept intact.

iOS simulator UI: **all six tests passed**. Covered direct routine entry on first
launch/relaunch, large-text max measurement controls, exact percentage entry, Maxes
management navigation, compact manual-session logging, and summary rating tap/drag/
clear with Save and Discard remaining reachable.

Android emulator: updated APK installed. Connect and start entered the mock runner
directly with no agreement gate. Skip set advanced from set 1 to set 2 without a
crash. Hold to end reached a summary with recorded force and time under tension;
Save and Discard were both visible. This was a functional check, not a claim of
measured hardware frame rate or Bluetooth latency. The synthetic workout was
discarded afterward and the Android test emulator was closed. The iOS test devices
are shut down; the pre-existing Flow Preview was left as it was.

Physical Bluetooth, long-running OS notification delivery, ActivityKit process
recovery and VoiceOver/TalkBack announcement behavior still require device checks
beyond the pure and simulator cases. These changes are local source and simulator
builds, not a newly published store release.
