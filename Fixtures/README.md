# Fixtures — the cross-platform contract

Language-neutral test fixtures. The two engines share no code; these files are what keep them
from drifting. **A rule change in `Shared/Engine` is not done until the fixture and the Kotlin
twin change with it**, and vice versa.

Who asserts what is NOT symmetric, and it is worth knowing before trusting a green run:

- **Kotlin reads every family.** `android/engine/src/test/kotlin/.../*FixtureTests.kt` covers
  `blob/` (`BlobFixtureTests`, `JsonFixtureTests`), `codec/` (`CodecFixtureTests`), `keys/`
  (`KeyFixtureTests`), `planmath/` (`PlanMathFixtureTests`), `export/` (`ExportFixtureTests`),
  `runner/` (`RunnerFixtureTests`) and `share/` (`ShareFixtureTests`).
- **Swift reads three**, through the oracle in `tools/oracle/`: `runner verify`, `share verify`
  and `export verify`. There is no Swift reader for `blob/`, `codec/`, `keys/` or `planmath/` —
  the iOS side asserts those rules in its own XCTest files against the same values, not against
  these files. (An earlier version of this README promised a
  `Tests/FixtureConformanceTests.swift`; it was never written. Adding one is the obvious way to
  close the gap, and until then a change to those four families is only checked on Kotlin.)

Every file is plain JSON, except the export documents, which are Markdown and CSV. "Canonical JSON" always means the
byte-exact output of `BlobCodec` (Foundation `JSONEncoder([.sortedKeys])`): compact, sorted keys,
whole doubles as integers, absent optionals omitted — see `blob/json-writer.json`. Where a
fixture embeds a JSON document as a value, it is a **string** of canonical JSON, so a decode →
re-encode round-trip is a free byte check.

## `blob/`

- `json-writer.json` — `{cases:[{name, swiftType, escapeSlashes?, input, output}]}`. Parse
  `input`, re-encode, compare to `output` byte for byte.
- `lenient-scalars.json` — `{cases:[{type, json, result|null}]}`. What a typed scalar decode
  accepts; `null` = throws / falls back.
- `<Type>.json` (`GripSpec`, `SetPlan`, `SetPlanArray`, `SessionPlan`, `RepSummary`,
  `ReminderTime`, `RoutineDraft`) — `{type, description, cases:[{name, input, canonical|null,
  key?}]}`. Decode `input` with the lenient decoder, re-encode, expect `canonical`;
  `canonical: null` means decode must FAIL (nil/null); for `SetPlanArray` the input is a JSON
  array decoded element-wise (`decodeArray`) and `canonical` lists the survivors. `key` (GripSpec)
  pins `GripSpec.key` of the decoded value. Note the two array rules: a TOP-LEVEL array drops
  only its broken elements, but a NESTED array read through `value(.sets, or: [])` is
  all-or-nothing — one bad element empties the key.
  `SetPlan` accepts 0–100 pulls per side; zero remains representable and is removed only
  from the executable plan. The 36- and 100-pull cases guard against the old 20-pull cap.

## `keys/`

- `grip-keys.json` — a bare array of `{edgeMM, fingers, position, key, maxKey:{left,right,both},
  name, shortName, line, displayName, shortForm}`. `fingers` is the token (`"IMRL"`); `name` and
  `shortName` are the **FingerSet's** English display strings ("4 fingers", "4F"); `line`,
  `displayName` and `shortForm` are the whole **GripSpec's** (`line` sentence case, `displayName`
  title case, `shortForm` = `GripSpec.shortName`, e.g. "20mm 4F HC"). `L10n` unset.
- `starter-keys.json` — `{description, starter:[key…], maxDay:[key…]}`: the seed routines' grip
  keys, in order.
- `reminder-slots.json` — a bare array of `{minutesFromMidnight, slot, hour, minute}`, in-range
  values only (the clamp is pinned in `blob/ReminderTime.json`).

## `codec/`

One file per gauge: `{gauge, cases:[{name, answering?, decoder?, frames:[hex…], expect}]}`.

**The file name, the `gauge` field and `GaugeKind.rawValue` are ONE string.**
`CodecFixtureTests` asserts the first two match ("a fixture file must be named for the gauge it
describes") and resolves the third with `GaugeKind.fromRaw(gauge)`, so `codec/climbro.json` must
say `"gauge": "climbro"` and there must be a `GaugeKind` whose raw value is `climbro`. A second
test, `everyGaugeKindHasAFixtureFile`, walks `GaugeKind.entries` and fails when one has no file —
adding a device cannot quietly skip this contract.

- `frames` are hex strings (uppercase, no spaces), fed IN ORDER to ONE decoder instance (the
  Progressor decoder is stateless; the framed gauges reassemble across frames).
- `decoder` — `{coefficient, tareSamples?}`, REQUIRED for a gauge whose capabilities say
  `requiresRemoteCalibration` (the Frez Dyno; `codec/frezdyno.json` is the example). Those
  gauges stream raw counts, so `makeFrameDecoder()` returns nil and the case has to supply the
  per-device slope the app would normally fetch by serial. `tareSamples` is how many samples the
  zero is averaged from — the fixtures pass **9** instead of the shipping default so a case is
  two frames long rather than hundreds; omit it to get the default. A case without `decoder` on
  such a gauge fails with "no frame decoder, and the case carries no 'decoder' object", and a
  `decoder` on a gauge that reports kilograms fails with "this gauge takes no coefficient".
- Progressor: `answering` is the pending command (`"getBatteryVoltage"`, `"getAppVersion"`, …
  or absent) and `expect` is `{events:[…]}` with these shapes, one per `ProgressorEvent` case:
  `{"type":"sample","kg","micros","batchStart"}`, `{"type":"battery","millivolts"}`,
  `{"type":"batteryFraction","fraction"}`, `{"type":"appVersion","text"}`,
  `{"type":"errorInformation","text"}`, `{"type":"rfdPeak", …}`, `{"type":"lowPowerWarning"}`,
  `{"type":"commandResponse","payload":hex}` (a tag-0 reply with nothing pending),
  `{"type":"raw","tag","payload":hex}` (an unknown tag). `CodecFixtureTests.kt` is the reference
  reader.
- Ported gauges: `expect` is `{readings:[{kg, micros|null}], battery|null}`.
- Broadcast (WH-C06): `frames` are manufacturer-data blobs (company id included) and `expect`
  is `{kg|null}` per frame.

## `planmath/`

- `sequences.json` — a bare array of `{name, plan, maxes:[{grip:key, side, kg}], slots:[{setIndex,
  repIndex, side, grip:key, holdSeconds, leadInBefore, restAfter, targetLo|null, targetHi|null,
  isFirstOfSet, isLastOfSet}], totalSeconds, tensionSeconds, totalReps, setCount,
  handSequence:[[side…] per set]}`. `plan` is a STRING of canonical `SessionPlan` JSON. The two
  right-first cases pin `startingHand`: the same rows mirrored, and the reset at every set
  boundary landing on the right.

## `share/`

- `urls.json` — `[{name, url, outcome, envelope|null}]`. `outcome` ∈ `ok`, `notARoutineLink`,
  `unreadable`, `newerVersion`, `emptyRoutine`, `tooLarge`. For `ok`, `envelope` is a string of
  the canonical JSON of `{v, plan, sessionsPerDay, isOnDemand}` the URL must decode to, with
  every `SetPlan.id` ZEROED (`00000000-0000-0000-0000-000000000000`) — import remints row
  identity by design, so the ids are the one part of the plan the two engines must NOT agree
  on. `v` is `RoutineShare.currentVersion`; the string is `BlobCodec`'s default escaping
  (`.sortedKeys`, slashes escaped), the same canonical form as every other fixture here.
  **Cross-decode is the contract, not byte-identical URLs**: zlib output legitimately differs
  between Apple's and Java's deflate, so each side must decode the OTHER side's URLs. URLs in
  this file are produced by the Swift oracle (`tools/oracle`, i.e. the real iOS encoder);
  `urls-android.json` is the same shape, written by `ShareFixtureTests.kt` from the Kotlin
  encoder and read back by `oracle share verify`.
- `roundtrip.json` — `[{name, draft:{plan, sessionsPerDay, isOnDemand}}]` (`plan` a canonical JSON
  string): encode → decode must yield the same canonical envelope, and the URL must honour the
  documented caps. The 36- and 100-pull drafts must retain their counts across the Swift and
  Kotlin encoders; the share link's 50-set cap is unchanged.

## `export/`

**A scenario is EIGHT files**, and all eight are compared byte for byte — 18 scenarios, 144
files, of which 108 are CSV:

| File | What it is |
| --- | --- |
| `<scenario>.json` | The input |
| `<scenario>.md` | The expected Markdown document |
| `<scenario>.{all,recent,workout}.{pulls,summary}.csv` | The expected CSV, one per scope × detail |

- `<scenario>.json` is `AnalysisExport.Input` flattened at the TOP LEVEL — `{generatedOn, maxes,
  sessions, sessionsPerDayTarget, today}`, pretty-printed with sorted keys. There is no `{input}`
  wrapper. Instants are ISO-8601 UTC strings, days are epoch-day ints, enum raws are strings and
  reps are canonical `RepSummary` JSON objects. `ExportInputDTO` in `tools/oracle/OracleExport.swift`
  and `AnalysisExport.Input.fromJson` in Kotlin are the two halves of that contract.
- The six CSVs are every `CSVScope` × `CSVDetail` pair. The `workout` scope is generated from the
  FIRST session alone, which is what makes the "one workout" share path testable from the same
  input as the whole history.
- **Adding a scenario means adding all eight files, through the oracle** — write the builder in
  `scenarios()`, run `oracle export scenarios <dir>` to write the `.json`, then
  `oracle export generate <dir>` to write the `.md` and the six `.csv` from the real
  `AnalysisExport`. Hand-writing any of the outputs defeats the point: `generate` reads the JSON
  back off disk, so the document is made from exactly the bytes Kotlin will read.
- **`csv-v2-timing-summary` is an ORPHAN.** Its eight files are on disk and both sides verify
  them, but `scenarios()` in `OracleExport.swift` has no builder for it (17 builders, 18
  scenarios on disk). `oracle export scenarios` will therefore never rewrite its input, so the
  day the DTO shape changes it goes stale by hand or not at all. It needs a builder restoring or
  the eight files deleting — a decision for the maintainer, recorded here rather than tidied
  away.

## `runner/`

- `<scenario>.json` — `{name, plan, maxes:[{grip:key, side, kg}], timerOnly, steps:[{t, event,
  cues}], final:{phase, completedRepCount, results:[canonical RepSummary…]}}` (`plan` a canonical
  JSON string).
  Events: `{"type":"start"}`, `{"type":"sample","kg","micros","batchStart"}`, `{"type":"tick"}`,
  `connectionLost`, `connectionRestored`, `tareCommitted`, `streamRestarted`, `pause`, `resume`,
  `skipRep`, `skipSet`, `abort`. Cues: `{"type":"leadInTick","seconds"}`, `{"type":"armed","side"}`,
  `repStarted`, `repHalfway`, `{"type":"repEnded","completed"}`, `{"type":"restTick","seconds"}`,
  `{"type":"setCompleted","setIndex"}`, `sessionCompleted`, `dropoutWarning`, `connectionLost`.
  `phase` is `idle|leadIn:<slot>|armed:<slot>|working:<slot>|releasing:<slot>|resting:<slot>|paused(<inner>)|finished`.
  Traces are RECORDED by one engine and REPLAYED by the other (`tools/oracle runner verify` on
  the Swift side, `RunnerFixtureTests.kt` on the Kotlin side); either side may produce them.

## `tools/oracle/` — the Swift engine as a command-line tool

`Shared/Engine/*.swift` and `Shared/BlobCodec.swift` import only Foundation, so `swiftc` on a Mac
compiles them straight into a command-line binary together with the generators here. The oracle
reads the iOS working tree at `GETAGRIP_IOS_TREE` (default: this repository’s root)
and never modifies it. `./tools/oracle/build.sh` builds `tools/oracle/build/oracle`; run it with
no arguments for the command list. One mode is not in that list because it is authoring rather
than checking: `oracle export scenarios <dir>` rewrites `export/*.json` from the Swift-side
scenario builders, and `oracle export generate` then produces the `.md` **by reading those JSON
files back**, so the document is always made from exactly the bytes Kotlin will read.

## Verifying

From the repository root:

```sh
./Fixtures/tools/oracle/build.sh
./Fixtures/tools/oracle/build/oracle runner verify Fixtures
./Fixtures/tools/oracle/build/oracle share  verify Fixtures
./Fixtures/tools/oracle/build/oracle export verify Fixtures
./android/build.sh engine    # the Kotlin half — every family, including the four the oracle does not read
```

`share verify` reads BOTH `share/urls.json` and `share/urls-android.json`, so it is the command
that proves the iOS decoder accepts Kotlin's URLs. `export verify` walks the `.json` files on
disk and re-derives the `.md` and all six `.csv` for each.

## Regenerating

**Never regenerate a fixture to make a failing test pass.** That is the rule in `AGENTS.md`, and
it is the one that keeps these files evidence rather than an echo: a fixture rewritten to match
a regression records the regression as the contract, and the other engine then "agrees" with it.
Regenerate when you have decided to change the contract, say so in the pull request, and read
the diff — both engines' expectations move in the same commit or neither does.

There is no `--force`, no confirmation and no dry run: **the authoring commands overwrite in
place on plain invocation.** (`oracle` below is `./Fixtures/tools/oracle/build/oracle`.)

```sh
oracle share  generate Fixtures   # rewrites share/urls.json and share/roundtrip.json
oracle export scenarios Fixtures  # rewrites export/<scenario>.json from the Swift builders
oracle export generate Fixtures   # rewrites every export/<scenario>.md and .csv
oracle runner record   Fixtures   # rewrites runner/*.json from the Swift engine
```

Kotlin's recorder is the exception, and deliberately so: `RunnerTraceTests` re-derives every
trace and compares it byte for byte unless `-Dgetagrip.record=1` is passed, so a Kotlin
regression cannot quietly rewrite the evidence against itself.

```sh
./android/gradlew :engine:test --tests '*RunnerTrace*' -Dgetagrip.record=1
```

That flag reaches the test JVM only because `android/engine/build.gradle.kts` forwards it
explicitly — a test JVM does not inherit the build's `-D`. The same file is where two other
things are wired, and both are load-bearing:

- `systemProperty("getagrip.fixtures", …)` passes the absolute path of this directory, so no
  test ever guesses a relative path from its working directory. `Fixtures.root` fails loudly
  when the property is missing or the directory is not there.
- `inputs.dir(…Fixtures)` declares this directory an INPUT of the test task. Without it a
  regenerated fixture leaves the task UP-TO-DATE and the whole cross-platform contract silently
  stops being checked: `oracle share generate` rewrote `urls.json`, `./android/build.sh engine`
  reported success without running one assertion against it, and `oracle share verify` then read
  the `urls-android.json` of an older run. Measured, 2026-09-04.
