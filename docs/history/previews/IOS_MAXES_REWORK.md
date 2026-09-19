# iOS Maxes preview

This preview is isolated on `codex/ios-maxes-rework`, based on `86528f6`.
The initial preview left Android and the submitted iOS 1.0.3 (7) release unchanged.
The approved redesign was ported to Android (1.0.1 build 10) and shipped as the iOS 1.0.3 (8) replacement build; see `docs/releases/notes/RELEASE_2026-09-12_1.0.3.md`.

## Flow

- A grip card's **Measure again** opens its gauge measurement immediately.
- **Edit** opens exact left/right values. Earlier records and deletion are reachable there.
- The **+** in the top-right toolbar chooses a grip, then offers measurement or manual entry.
- The gauge screen captures left and right independently in one visit. Save commits
  the successfully captured hands together; capturing only left saves only left. Cancel changes nothing; a failed or empty retry keeps
  the preceding successful capture.
- Existing shared maxes remain separate. New max's secondary menu also supports a
  shared value or an explicit measurement with both hands together.
- **Adjust values** corrects captured hand values before the first save; Cancel leaves
  the capture untouched. Corrections are labeled manual and do not alter the trace.
- **Record another test** explicitly logs an unchanged value for the selected hand.
  Simply opening Edit and saving an untouched form never creates duplicate records.
- New max includes quick choices for grips already used in routines.
- A successful save shows the affected routines' old and new percentage targets when
  they change. A shared max can offer to rescale eligible typed weight bands; nothing
  is rescaled without selecting that action.

## Data contract

No schema, grip identity, side value, or serialization changes. All saves append
MaxRecord history. Newest remains the working max, even if a retest is lower.
Only changed or explicitly selected repeat manual hand values are saved. Shared fallback values never prefill
missing individual hand records. Batch validation and persistence are atomic locally;
failed saves retain the draft.

Percentage targets continue to resolve from the newest applicable hand benchmark.
Explicit weight targets remain unchanged unless the existing shared-max rescale
option is explicitly accepted. Historical records and historical export resolution
remain intact. Unit conversion is display/input only; untouched kilograms retain
full precision.

The post-save receipt compares the previous table with the final committed table,
including when both hands are saved together. Shared rescale proposals become invalid
if their reviewed routine or applicable benchmarks change. Ambiguous duplicate routine
IDs from sync are excluded from proposals. Dismissing a receipt keeps the saved maxes;
a failed rescale keeps the original typed targets and offers retry.

## Original preview verification

- 713 iOS unit tests passed.
- 7 focused simulator interaction tests passed, including English/French, large text,
  pounds, two-hand capture/save, cancellation, one-hand edit, and nested creation.
- Signed device Debug build succeeded and its signature verified.

See `build/maxes-final-unit-console.log`, `build/maxes-final-ui-test.log`, and
`build/maxes-device-build.log` for this workspace's checks. Simulator interaction
checks use sample history and the mock gauge, not production training data.
Physical Bluetooth testing remains part of the on-device preview.

## Restored workflow verification

- 733 unit tests passed, including batch target receipts, explicit rescale consent,
  duplicate routine IDs, failed persistence, per-hand retests, and correction provenance.
- All eight Maxes workflow UI tests passed. Two focused typing checks then passed on
  the final input implementation: Save and Apply commit the active numeric field;
  Cancel never writes a record. An unstable focused callback caught in the first
  keyboard check was replaced with a stable field identity before these final passes.
- English and French accessibility screenshots were reviewed. Example captures are
  `build/maxes-preview/target-changes-restored.png`, `repeat-test-restored.png`, and
  `recent-grips-restored.png` in that same folder.
- Logs: `build/maxes-restored-final-unit-console.log`, `build/maxes-restored-ui.log`,
  and `build/maxes-restored-typing-stable-ui.log`.

The restored preview was built, signature-verified, installed over the existing app,
and launched on Nuri's connected iPhone on 2026-09-12. Version remains 1.0.3 (7).
No mock or data-seeding arguments were used. It has not been published to either store.

## Rollback

No migration is needed. Build `86528f6` (or the existing release source) and reinstall
without deleting the app. New hand records use the existing schema and remain readable
by that version. Do not uninstall the app or reset its data to roll back the interface.
