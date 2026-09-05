# Get a Grip — training export

Generated 2026-08-28. Loads are KILOGRAMS (kg); durations are SECONDS (s) unless the column says otherwise. Dates are YYYY-MM-DD.

## Legend

This document is written in English whatever language the app is set to, so that one fixed schema is being read every time. `—` in any cell means the value was never recorded; it is never a zero and never a guess.

- **Pull** — one rep: a single hold on one grip with one hand (or with both). **Set** — a run of pulls sharing one grip. A session is a sequence of sets.
- **Plan s** is the hold the routine asked for. **Held s** is what was actually accrued while the load was over the rep's own threshold — so a pull that came off the edge halfway reads short rather than being rewritten.
- **How held time was measured** is stated on every session header. `gauge` means the seconds came from the force gauge's own sample timestamps. `timer-only` means the session ran with no gauge attached and the seconds came off the WALL CLOCK; those sessions carry no kilograms at all. `logged` means the session was written down after the fact — no reps, only a duration.
- **Peak kg / Avg kg are stored PER REP** — the numbers in the rep tables are that pull's own peak and its mean while engaged, not the session's. The session header's peak and average are the session-level figures kept beside them; for a session whose rep blob is missing or unreadable, the header figures are all that survives.
- **% max** is that rep's own PEAK divided by the max on file for that grip AND hand **as it stood on the day of the session** — a max recorded later never rewrites what an older session was pulling at. Resolution is specific-beats-general: a left or right pull uses that hand's max and falls back to a both-hands max; a BOTH-hands pull resolves only against a both-hands max, never against the two hands added together. No max on file means a blank, never a number.
- **Target kg** is what the rep was ASKED to pull for that hand, frozen at the time. Blank where the routine set no target or the grip had no max to take a percentage of.
- **Outcome** is one of `completed`, `earlyRelease` (came off the edge), `skipped` (deliberately passed over — skipped pulls ARE recorded, and they count toward the planned total but never toward the completed one; their kilogram cells are blank because the pull never happened, not zero), `aborted` (the session or the link ended mid-rep).
- **Hands**: `L` left, `R` right, `B` both.
- **A climbing day counts as training.** A day at the gym is more finger load than the hangboard session it displaced, so `climbVolume` and `climbLimit` sessions settle a day the same way a routine session does. `benchmark` is a max-testing day, logged automatically the first time a gauge-measured max lands. `hangManual` is a weighted or max hang done away from the gauge.

**Grip notation** — `20mm 4F HC` is a 20 mm edge, four fingers, half crimp.

Edge is the depth in millimetres. Then the digits on the hold:

- `4F` — all four fingers

Then how the hand is set on it:

- `HC` — half crimp

**The two effort axes** are graded by hand after a session and are optional — either or both may be blank. They are stored as 1–5 integers, not as a Borg CR-10 score:

- **Effort** (systemic — how hard the session felt overall): 1 easy · 2 comfortable · 3 solid · 4 hard · 5 all I had.
- **Fingers** (local — how much it asked of the fingers specifically, the stronger signal in the climbing session-RPE literature): 1 nothing · 2 light · 3 worked · 4 taxed · 5 wrecked.

## Current maxes

The newest record for each grip and hand. `measured` means the gauge watched it happen; `typed` means it was entered by hand.

| Grip | Hand | kg | Date | Source |
| --- | --- | ---: | --- | --- |
| 20mm 4F HC | B | 50.0 | 2026-08-28 | measured |

## Max history

Every record ever written, oldest first within each grip and hand. Records are append-only, so this is the progression itself.

### 20mm 4F HC · both hands

| Date | kg | Source |
| --- | ---: | --- |
| 2026-07-24 | 40.0 | measured |
| 2026-08-28 | 50.0 | measured |

## Sessions, last 8 weeks

Every session on record — the history begins 2026-07-29. Newest first.

### 2026-07-29 · Daily no-hangs · hang · 20 min · held 10s · gauge

Pulls: 1 completed of 1 planned. Session peak 20.0 kg, session average 18.0 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 10.0 | 20.0 | 18.0 | — | 50% | completed |

## Older than 8 weeks, by week

Nothing older than 2026-07-04.

## Consistency

Target: 2 sessions a day. (Each session also carries the target that was in force when it was logged, so a change to the target never re-scores days already lived.)

Days trained per week across the whole history — a day counts if ANY session landed on it, climbing days included. Newest week first. A week's denominator counts only its days inside the recorded history, so the first and the current week are usually partial — a day before the history began, or still in the future, is not scored as a miss.

| Week of | Days trained | Sessions |
| --- | ---: | ---: |
| 2026-07-27 | 1 of 5 | 1 |

## Questions worth asking

1. Is one hand falling behind the other — in maxes, in held seconds, or in % max at the same prescription?
2. Are the two effort axes diverging? Fingers climbing while overall effort stays flat is the early warning this schema exists to expose.
3. How fast are the maxes actually moving per grip, and is the training load moving with them or ahead of them?
4. What does the adherence pattern look like — which days and which weeks get missed, and does a missed day follow a hard one?

