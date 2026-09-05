# Training export v2 — 2026-09-05

Both apps default to **Summary**, with **Every pull** available for detailed analysis. The range remains Last 8 weeks (56 inclusive calendar days) or All history. The leading Share swipe on a history row opens the same choices for one workout, including older workouts. Share creates an immutable UTF-8 CSV attachment; Copy remains available. Column names and the guide stay English; the surrounding interface is localized.

## Stable references and context

`workout` is the stored workout UUID, lowercased, independent of sort order, range, or subsequent workouts. `(workout, pull)` identifies a pull by its position in the immutable saved results. Detail rows include local date, routine name, grip and hand. A workout's routine display name can reflect a later rename; its UUID remains stable.

Summary groups recorded results by set, exact grip, hand, target hold duration and exact frozen target band, before display rounding. `recorded` includes every outcome, `completed` counts completed outcomes, `outcome` preserves counts for completed, earlyRelease, skipped and aborted. Planned/held seconds are sums. Peak is the largest measured peak, and average is weighted by credited held seconds among measured non-skipped pulls. Timer-only and skipped force is blank; real measured zero remains zero. Unattempted pulls remain represented in the workout's planned count, without inventing result rows.

Workouts, set/pull rows and max rows remain in one self-describing file. Do not add set/pull totals to workout totals. Single-workout exports exclude unrelated max rows, while lookup still considers every historical max at or before session start, including records outside the selected range. `max_reference` distinguishes hand_specific, both_hands, both_hands_fallback and no_recorded_max_at_start. Missing does not prove the grip was never benchmarked.

## Time and saved prescription

New RepSummary blobs optionally store `startedElapsedSeconds` and `endedElapsedSeconds`. These are host-monotonic observations from RunnerSession.begin, including connection waiting and pauses; start records entry into working, and end records the outcome. End is not necessarily physical release. BLE packet batching limits their onset precision. They never participate in force-credit, countdown, or device timestamp ordering decisions. Unstarted skipped pulls have an end observation but no invented start. Old blobs decode with absent timing.

The CSV exposes start/end elapsed seconds and gap_before_s (including pauses and waiting, not a pure rest measurement). Unknown historical timing stays blank with not_recorded; known unstarted outcomes say not_started. Summary spans are included only when every performed pull in a group has timing. Frozen SessionPlan slots supply planned_rest_s (including set breaks) and planned_lead_in_s; these are sums on summary rows. Missing saved plans remain unknown.

RunnerSession freezes finishedAt on the first finished event, derived from the start date plus monotonic elapsed time. iOS passes this to SessionSummaryView and storage; waiting before Save no longer inflates new workout duration. ended_utc on older workouts is labeled legacy_save_or_end because historical iOS records may include that wait. Manual duration logs do not claim a known end instant. No old timing is reconstructed.

## Format and verification

Formatting and byte counting run off the UI thread from a frozen model snapshot. The sheet shows range, detail, counts and actual file size. Formula-like text is apostrophe-protected; Unicode, commas, quotes and LF/CR/CRLF are preserved. No raw sensor trace is included. File names distinguish summary and pulls. The old Markdown formatter remains a tested reference with no UI entry point.

The attached 43-workout history reduces from 1,028 pull rows to 340 summary rows. A temporary size evaluation using placeholder UUIDs (the old CSV omitted real IDs), retaining only available historical fields, produced about 72 KB for v2 Summary versus 91 KB for the original CSV and 198 KB for v2 Every pull. Actual new exports also carry stored finish times and saved prescriptions. This measures bytes, not model tokens; the evaluation was not imported into either app.

Validation covers stable references, weighted summaries, distinct prescriptions including rounding collisions, historical max provenance, frozen rest, absent legacy timing, monotonic pause accounting and completion frozen before delayed Save. Shared fixtures compare 18 histories × 3 ranges × 2 detail modes (108 CSVs) byte for byte; an independent CSV parser checks every row width. All 39 runner scenarios / 4,130 steps retain their original decisions and force measurements; fixture changes contain only new timing metadata. iOS and Android simulator export controls were visually checked. Third-party AI ingestion is not automated by these checks.
