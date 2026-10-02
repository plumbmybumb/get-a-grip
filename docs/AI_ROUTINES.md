# Create with AI

Added 2026-10-02. Today's new-routine card and empty card offer **Create with AI** beside
**Scan a routine**: copy a fixed set of instructions into any AI chat, describe the
routine in your own words, paste the reply back, check the preview, add it.

## Why a format of its own

The share code (`getagrip://routine#…`) is compressed binary; no chat model can write it.
`SessionPlan`'s own JSON carries row ids, fractions written as 0.7 and fields nobody would
describe. So the assistant writes a small vocabulary instead: whole numbers, listed words,
one routine-wide target, a `fineTuning` object (pause the clock, rest on release, pull
threshold, lead-in) and up to 50 sets. Targets are a percentage of the climber's own
max, of the peak or of a timed max (`"max": 10`), or kilograms. The full text is
`AgentRoutine.instructions` (English whatever the UI language, like the analysis export).

## How the reply is read

`Shared/Engine/AgentRoutine.swift`, twinned in `android/engine/.../AgentRoutine.kt`:

- **Forgiving about form.** The object is found inside prose and code fences; typographic
  quotes, comments, trailing commas, single quotes and bare keys are accepted; keys match
  ignoring case, `_`, `-` and spaces, with aliases (`reps`, `edge_mm`, `hold`); numbers
  may arrive as `"7 s"` or `"2 min"`; a percentage as 70 or 0.7; settings filed under
  `fineTuning` or at the top level.
- **Strict about meaning, and never silent.** Anything out of range is clamped and an
  unrecognised word becomes the default, and each such change is a `Note`, listed first
  in the preview under "Changed to fit the app". Nothing is refused that can be read.
- **Our own JSON reader** (`Lenient`), not Foundation's JSON5 or kotlinx's lenient mode:
  those accept different things, and both platforms must read every reply the same way.
- The result goes through the share-link import inbox and preview, so nothing is saved
  until "Add to my routines", reminders arrive off, and the routine gets fresh identity.

## Evidence

`Fixtures/agent/inputs.json` holds real replies from Claude Haiku, Sonnet and Opus to four
requests (a 7:3 recovery routine on a 40 mm edge, timed-max hangs at 90 %, the six-grip
daily no-hangs, the same repeaters asked in French), plus hand-written edge cases.
All twelve real replies were valid; the one interpretive miss (Haiku wrote "alternate"
for "one hand at a time") is the kind the preview exists to catch. Haiku also put
`leadInSeconds` inside `fineTuning`, which is why every routine-wide key is looked up in
both places. `oracle agent generate` writes `cases.json` from the iOS reader; `oracle
agent verify` (CI) and `AgentRoutineFixtureTests.kt` hold both readers to it, and the
instructions text to `instructions.txt`.

When the format changes, bump `get-a-grip-routine/N`: a reply naming a newer version
reads as "update the app", never as damage.
