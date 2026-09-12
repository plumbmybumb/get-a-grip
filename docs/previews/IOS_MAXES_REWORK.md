# iOS Maxes preview

This preview is isolated on `codex/ios-maxes-rework`, based on `86528f6`.
It does not change the Android app or the submitted iOS 1.0.3 (7) release.

## Flow

- A grip card's **Measure again** opens its gauge measurement immediately.
- **Edit** opens exact left/right values. Earlier records and deletion are reachable there.
- **Add a max** at the bottom chooses a grip, then offers measurement or manual entry.
- The gauge screen captures left and right independently in one visit. Save commits
  the selected hands together. Cancel changes nothing; a failed or empty retry keeps
  the preceding successful capture.
- Existing shared maxes remain separate. New max's secondary menu also supports a
  shared value or an explicit measurement with both hands together.

## Data contract

No schema, grip identity, side value, or serialization changes. All saves append
MaxRecord history. Newest remains the working max, even if a retest is lower.
Only changed manual hand values are saved. Shared fallback values never prefill
missing individual hand records. Batch validation and persistence are atomic locally;
failed saves retain the draft.

Percentage targets continue to resolve from the newest applicable hand benchmark.
Explicit weight targets remain unchanged unless the existing shared-max rescale
option is explicitly accepted. Historical records and historical export resolution
remain intact. Unit conversion is display/input only; untouched kilograms retain
full precision.

## Verification

- 713 iOS unit tests passed.
- 7 focused simulator interaction tests passed, including English/French, large text,
  pounds, two-hand capture/save, cancellation, one-hand edit, and nested creation.
- Signed device Debug build succeeded and its signature verified.

See `build/maxes-final-unit-console.log`, `build/maxes-final-ui-test.log`, and
`build/maxes-device-build.log` for this workspace's checks. Simulator interaction
checks use sample history and the mock gauge, not production training data.
Physical Bluetooth testing remains part of the on-device preview.

## Rollback

No migration is needed. Build `86528f6` (or the existing release source) and reinstall
without deleting the app. New hand records use the existing schema and remain readable
by that version. Do not uninstall the app or reset its data to roll back the interface.
