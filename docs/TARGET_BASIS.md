# Target basis — a percentage of max, or of critical force (2026-10-31)

Support request (Android 1.4.0, WH-C06): "Choose target load based on Critical Force as well
as MVC", for the aerobic protocols at strengthclimbing.com/protocols/force-measured-protocols/,
which prescribe submaximal work as a fraction of CF. CF is roughly 40 % of max
([CRITICAL_FORCE.md](CRITICAL_FORCE.md)), so "80 %" is two very different loads depending on
one word. This file is the spec the Android port builds from; the iOS engine is the
reference and `Fixtures/target-basis/` is the contract.

## 1. The model

`enum TargetBasis: String { case max, criticalForce }` (`Shared/Engine/SessionPlan.swift`).

| Where | Swift property | JSON key | Type | Absent / default |
| --- | --- | --- | --- | --- |
| `SetPlan` (a set's OWN percentage) | `targetBasisRaw` | `targetBasis` | String, optional | nil → `.max` |
| `SessionPlan` (the routine's percentage) | `targetBasisRaw` | `targetBasis` | String, optional | nil → `.max` |
| `SessionTemplate` (SwiftData column) | `targetBasisRaw` | — (CloudKit `CD_targetBasisRaw`) | `String?` | nil |

Raw values: exactly `"max"` and `"criticalForce"`.

**Decoding** (lenient, like every other field):
- key absent → nil (reads `.max`);
- a string → kept VERBATIM, including one this build does not know (`"wPrime"`), so a newer
  build's basis survives a round trip; an unknown raw READS as `.max`;
- the empty string → nil;
- a non-string (number, bool, object) → nil.

**Encoding**: the raw is written whenever it is non-nil — including `"max"` once a user
chose it — and omitted when nil. A plan nobody chose a basis for therefore encodes byte for
byte as it did before the field existed (pinned by `testAnOldPlanEncodesExactlyAsItDidBefore`
and by every pre-existing share/runner/planmath fixture staying unchanged).

The computed `targetBasis` getter is `TargetBasis(rawValue: raw) ?? .max`; the setter writes
`newValue.rawValue` (so setting `.max` writes `"max"`).

### `RoutineDraft.normalized` (every save)

In this order, per set:
1. A set with no percentage of its own, or with a kg band, drops `targetBasisRaw` (and
   `targetMaxSeconds`) — a basis with no percentage says nothing.
2. A set whose basis is `.criticalForce` drops `targetMaxSeconds` — a CF has no length.

Then the routine-level band is DEMOTED onto every set with neither a kg nor a percentage
band of its own, **carrying the routine's `targetBasisRaw` with it** (and the routine's
`targetMaxSeconds` only when that basis is not `.criticalForce`). The routine's band, length
and basis are then cleared. So after any save the routine-level column is nil and the basis
lives per set, inside `setsData`.

`RoutineDraft.normalized` is what share import, the builder's Save and `SessionTemplate.apply`
all run; `RoutineShare` itself carries the plan VERBATIM (routine-level basis included).

## 2. Resolution

`PlanMath` (`Shared/Engine/PlanMath.swift`). Precedence is unchanged:

1. kilograms typed on the set (basis ignored entirely);
2. the set's own percentage, **of the set's own basis**;
3. the routine's percentage, **of the routine's basis**;
4. nothing.

`PlanMath.targetBasis(set, in: plan)` = the set's basis when the set has its own percentage
band, else the routine's — the basis always travels with the band it belongs to (a set with
its own band and no basis is `.max` even under a CF routine).

`PlanMath.referenceKg(set, in:, side:, maxes:, criticalForces:)`:
- `.max` → `MaxTable.max(grip:side:seconds:)` with `PlanMath.maxSeconds` (peak or timed);
- `.criticalForce` → `CriticalForceTable.criticalForce(grip:side:)`.

**Neither table ever stands in for the other**: no CF on file is no target, never "80 % of
the max instead". `PlanMath.maxSeconds` returns nil for a CF set so a stray length can never
send it to a timed max.

Band: `lo = round½(ref × pctLo)`, `hi = round½(ref × pctHi)`, normalized lo ≤ hi, where
`round½(x) = (x·2).rounded() / 2` — Swift's `.rounded()` is half AWAY from zero (all values
here are positive, so plain half-up). A lone endpoint is a line (`lo…lo`).

### `CriticalForceTable` (`Shared/Engine/CriticalForceTable.swift`)

A `MaxTable` inside (peak slots only), so the hand rules are literally `MaxTable`'s:
- key `gripKey|side` (`side` raw `left|right|both`);
- `.left`/`.right` use that hand, falling back to `both`;
- `.both` resolves ONLY against `both` (never the hands summed, never one hand);
- `exact(grip:side:)` has no fallback;
- zero, negative and non-finite values are dropped.

**The fold** `CriticalForceTable.folding(entries, asOf:)`, over raw tests
`{gripKey, side, protocolKey, criticalForceKg, recordedAt}`:
1. skip any test whose `protocolKey != "7:3x24"` (`CriticalForceProtocol.standard.key`);
2. skip any test whose CF is not finite and > 0 — BEFORE choosing the newest, so a bad test
   never masks an older good one;
3. with `asOf`, skip tests recorded after it (`recordedAt <= asOf` counts);
4. per `gripKey|side`, keep the newest by `recordedAt`; an exact tie goes to the LATER entry
   in input order.

The store (`TemplateStore.currentCriticalForces`), the watch runner
(`[CriticalForceRecord].criticalForceTable()`) and the export (`asOf` = session start) all
fold with this one function.

### Where it is threaded

- **Runner**: `SessionRunner(plan:maxes:criticalForces:…)` and `RunnerSession(…criticalForces:)`
  resolve every rep once at construction, from tables captured at session start
  (`templates.maxTable`, `templates.currentCriticalForces`). `RepSummary.targetLoKg/HiKg` keeps
  freezing the resolved band — unchanged. The band gates the clock exactly as before.
- **Counts**: `untargetedGripCount` and `missingBenchmarkGripCount` take the CF table; a CF set
  counts a hand with no CF, never a hand with no max. `PlanMath.missingReferences` lists each
  `(grip, hand, basis, maxSeconds)` a percentage set needs and cannot resolve, in plan order,
  deduped, hands in `handSequence` order.
- **Intensity (the EdgeMark rung)**: `peakIntensity(of:maxes:criticalForces:)`. A CF set is
  restated against the PEAK max of each hand it trains, `pctHi × CF / peakMax`, and
  contributes ONLY when both are on file; otherwise it contributes nothing, so a CF routine
  with no tests is `unknown` → the bleu rung, as before. **Decision**: colouring the raw
  "80 %" as 80 % of max would paint a moderate aerobic routine red. The import preview passes
  empty tables (it always has), so a shared CF routine previews `unknown`.
- **Max receipt** (`MaxReceiptMath`): CF sets are skipped — a new max never moves them.

## 3. Storage and sync

- iOS: `SessionTemplate.targetBasisRaw: String? = nil` (additive, optional; in
  `DeletedRoutine` so routine Undo restores it). **CloudKit schema deploy owed.**
- The set-level basis is inside the `setsData` blob — Codable forward-compatible, no schema
  change.
- Watch: `CriticalForceRecord` joined the watch's SwiftData schema (additive entity).
- Android: the Room equivalent is one nullable `targetBasisRaw` column on the routine table
  (migration), the set field inside the sets JSON.
- **Older builds ignore the key and read a CF band as % of MAX** — about twice the load. Ship
  both platforms together.

## 4. Export (CSV v5, Markdown)

See [CSV_EXPORT.md](CSV_EXPORT.md) §v5. Summary: appended columns `target_basis`
(`critical_force`|`max`, pull and set rows), `cf_at_start_kg`, `cf_reference`
(`hand_specific`|`both_hands`|`both_hands_fallback`|`no_recorded_cf_at_start`). On a CF row the
three max columns are blank; on a max row the CF columns are blank. The pull's basis comes
from the plan frozen in the workout (`AnalysisExport.targetBasis(of:in:)`; a missing plan or
out-of-range set index reads `max`). The CF table is folded per session with `asOf` = the
session's `startedAt`. Markdown rep table: `| … | Target kg | % max | % CF | Outcome |`, `% CF`
= `peak / CF` rounded to a whole percent, `—` when blank.

## 5. UI (iOS) and copy

**Set row** (`TargetBandRow`): the old two-way segmented "% of max | kg" picker inside Custom
became a three-chip UNIT row — **"% of max" · "% of critical force" · "kg"/"lb"** — shown
whenever the set has a target (or Custom is open), so a preset can be made a percentage of CF
without opening Custom. Presets keep the current basis. Choosing "% of critical force" keeps
the percentage numbers (the kilograms on screen move) and clears any timed length; choosing kg
seeds from the kilograms on screen; clearing the target clears the basis. The timed-max chips
("Peak max · 10 s max") show only under "% of max".

**Routine row** (`RoutineTargetRow`, Rhythm page): two chips under the presets, "% of max" ·
"% of critical force" (kilograms stay per set). Under a CF band the page names every grip and
hand with no test, two at most.

| Where | English | French |
| --- | --- | --- |
| unit chip | % of critical force | % de la force critique |
| header subtitle | of your critical force | de ta force critique |
| spoken header | {value}, of your critical force | {value}, de ta force critique |
| trimmer unit (VoiceOver) | percent of critical force | pour cent de la force critique |
| chip row label (VoiceOver) | Target load unit | Unité de la charge cible |
| no CF, either hand | No critical force for this grip yet. Test it in Benchmarks. | Pas encore de force critique pour cette prise. Teste-la dans Tests. |
| no CF, one hand | No critical force for your {left} hand yet. Test it in Benchmarks. | Pas encore de force critique pour ta main {gauche}. Teste-la dans Tests. |
| hands differ | {20–30 %} of each hand's critical force · L … · R … kg | {…} de la force critique de chaque main · … |
| shut set row | {70–80 %} of critical force | {…} de la force critique |
| inherited, resolves | Routine target: {range} of critical force, so {load}. | Cible de la routine : {…} de la force critique, soit {…}. |
| inherited, no CF | Routine target: {range} of critical force. No critical force for this grip yet. | Cible de la routine : {…} de la force critique. Pas encore de force critique pour cette prise. |
| routine page, per missing | No critical force for {20 mm · 3 fingers · Half crimp} · {left} (no hand for both-hands) | Pas de force critique pour {…} · {gauche} |
| routine page, overflow | and {n} more without a critical force test. (plural) | et {n} autre(s) sans test de force critique. |
| routine page, footer | Their sets have no target until you test them in Benchmarks. | Leurs séries n'ont pas de cible tant que tu ne les as pas testées dans Tests. |
| import / overview | {lo}–{hi} % of critical force · {hi} % of critical force | {…} % de la force critique |
| import note | Some targets use your critical force. Sets on a grip you haven't tested have no target. | Certaines cibles utilisent ta force critique. Les séries sur une prise que tu n'as pas testée n'ont pas de cible. |
| spoken set override | target {lo} to {hi} percent of your critical force | cible de {lo} à {hi} pour cent de ta force critique |

The runner, Live Activity and summary are unchanged: they show the resolved kilograms.

## 6. Fixtures

`Fixtures/target-basis/` (written and verified by `oracle target-basis generate|verify`):

- `plan.json` — (a) `{description, plan}`: one canonical `SessionPlan` JSON string using both
  bases at routine AND set level (a CF routine band inherited by two sets, an explicit-max set,
  a CF set, a set with its own band and no basis, a kg set carrying a stray CF basis, an
  unknown raw, a timed-max set). Execute it AS WRITTEN with `PlanMath.sequence`.
- `tables.json` — (b) `{description, maxes:[{grip, side, kg, seconds?}], criticalForces:[{grip, side, kg}]}`.
- `expected.json` — (c) `{slots:[{setIndex, repIndex, side, grip, basis, targetLo|null,
  targetHi|null}], untargetedGripCount, missing:[{grip, side, basis, maxSeconds|null}],
  peakIntensity|null}`. `peakIntensity` is a double — compare with a tolerance (1e-9).
- `cases.json` — `fold` (raw tests → lookups with and without fallback, four `asOf`
  variants), `codable` (input → canonical re-encode + what the basis reads as), and
  `resolution` (more plans in the `plan/tables/expected` shape: both-hands rules, a
  one-handed CF, nothing on file).

Also regenerated: `Fixtures/share/*` (new `criticalForceBasis` round trip) and every
`Fixtures/export/*` output, plus the new `critical-force-target-basis` scenario.
