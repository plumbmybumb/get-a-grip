# Fixtures — the cross-platform contract

Language-neutral test fixtures asserted by BOTH the iOS engine (through the Swift oracle in
`tools/oracle/`, and later `Tests/FixtureConformanceTests.swift`) and the Android engine suite
(`android/engine/src/test/kotlin/.../*FixtureTests.kt`). The two engines share no code; these
files are what keep them from drifting. **A rule change in `Shared/Engine` is not done until the
fixture and the Kotlin twin change with it**, and vice versa.

Every file is plain JSON (or Markdown for export documents). "Canonical JSON" always means the
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

One file per gauge: `{gauge, cases:[{name, answering?, frames:[hex…], expect}]}`.
- `frames` are hex strings (uppercase, no spaces), fed IN ORDER to ONE decoder instance (the
  Progressor decoder is stateless; the framed gauges reassemble across frames).
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
  handSequence:[[side…] per set]}`. `plan` is a STRING of canonical `SessionPlan` JSON.

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

- `<scenario>.json` — `{input}` mirroring `AnalysisExport.Input` (`sessions`, `maxes`, `today`,
  `generatedOn`, `sessionsPerDayTarget`; instants as ISO-8601 UTC strings, days as epoch-day
  ints, enum raws as strings, reps as canonical `RepSummary` JSON objects), and `<scenario>.md`
  — the expected document, byte for byte, produced by the Swift oracle.

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
