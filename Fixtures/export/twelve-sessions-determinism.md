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
- `F2` — index and middle
- `F3` — index, middle and ring

Then how the hand is set on it:

- `FC` — full crimp
- `HC` — half crimp
- `OH` — open hand

**The two effort axes** are graded by hand after a session and are optional — either or both may be blank. They are stored as 1–5 integers, not as a Borg CR-10 score:

- **Effort** (systemic — how hard the session felt overall): 1 easy · 2 comfortable · 3 solid · 4 hard · 5 all I had.
- **Fingers** (local — how much it asked of the fingers specifically, the stronger signal in the climbing session-RPE literature): 1 nothing · 2 light · 3 worked · 4 taxed · 5 wrecked.

## Current maxes

The newest record for each grip and hand. `measured` means the gauge watched it happen; `typed` means it was entered by hand.

| Grip | Hand | kg | Date | Source |
| --- | --- | ---: | --- | --- |
| 14mm F3 OH | L | 22.0 | 2026-07-29 | measured |
| 20mm 4F HC | B | 40.0 | 2026-06-09 | measured |
| 20mm 4F HC | R | 36.0 | 2026-07-19 | measured |
| 20mm F2 FC | B | 24.0 | 2026-08-23 | measured |

## Max history

Every record ever written, oldest first within each grip and hand. Records are append-only, so this is the progression itself.

### 14mm F3 OH · left

| Date | kg | Source |
| --- | ---: | --- |
| 2026-07-29 | 22.0 | measured |

### 20mm 4F HC · both hands

| Date | kg | Source |
| --- | ---: | --- |
| 2026-06-09 | 40.0 | measured |

### 20mm 4F HC · right

| Date | kg | Source |
| --- | ---: | --- |
| 2026-07-19 | 36.0 | measured |

### 20mm F2 FC · both hands

| Date | kg | Source |
| --- | ---: | --- |
| 2026-08-23 | 24.0 | measured |

## Sessions, last 8 weeks

Every session on record — the history begins 2026-07-05. Newest first.

### 2026-08-28 · Routine 0 · hang · 20 min · held 30s · gauge

Pulls: 3 completed of 3 planned. Session peak 20.0 kg, session average 18.0 kg. Effort 1/5, fingers 1/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 10.0 | 12.0 | 10.8 | — | 30% | completed |
| 2 | R | 14mm F3 OH | 10 | 10.0 | 10.0 | 9.0 | — | — | completed |
| 3 | B | 20mm F2 FC | 10 | 10.0 | 20.0 | 18.0 | — | 83% | completed |

### 2026-08-19 · Routine 1 · hang · 20 min · held 30s · gauge

Pulls: 3 completed of 3 planned. Session peak 21.0 kg, session average 18.9 kg. Effort 2/5, fingers 2/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 10.0 | 13.0 | 11.7 | — | 33% | completed |
| 2 | R | 14mm F3 OH | 10 | 10.0 | 11.0 | 9.9 | — | — | completed |
| 3 | B | 20mm F2 FC | 10 | 10.0 | 21.0 | 18.9 | — | — | completed |

### 2026-08-10 · Routine 2 · hang · 20 min · held 30s · gauge

Pulls: 3 completed of 3 planned. Session peak 22.0 kg, session average 19.8 kg. Effort 3/5, fingers 3/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 10.0 | 14.0 | 12.6 | — | 35% | completed |
| 2 | R | 14mm F3 OH | 10 | 10.0 | 12.0 | 10.8 | — | — | completed |
| 3 | B | 20mm F2 FC | 10 | 10.0 | 22.0 | 19.8 | — | — | completed |

### 2026-08-01 · Routine 0 · hang · 20 min · held 30s · gauge

Pulls: 3 completed of 3 planned. Session peak 23.0 kg, session average 20.7 kg. Effort 4/5, fingers 4/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 10.0 | 15.0 | 13.5 | — | 38% | completed |
| 2 | R | 14mm F3 OH | 10 | 10.0 | 13.0 | 11.7 | — | — | completed |
| 3 | B | 20mm F2 FC | 10 | 10.0 | 23.0 | 20.7 | — | — | completed |

### 2026-07-23 · Routine 1 · hang · 20 min · held 30s · gauge

Pulls: 3 completed of 3 planned. Session peak 24.0 kg, session average 21.6 kg. Effort 5/5, fingers 5/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 10.0 | 16.0 | 14.4 | — | 40% | completed |
| 2 | R | 14mm F3 OH | 10 | 10.0 | 14.0 | 12.6 | — | — | completed |
| 3 | B | 20mm F2 FC | 10 | 10.0 | 24.0 | 21.6 | — | — | completed |

### 2026-07-14 · Routine 2 · hang · 20 min · held 30s · gauge

Pulls: 3 completed of 3 planned. Session peak 25.0 kg, session average 22.5 kg. Effort 1/5, fingers 1/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 10.0 | 17.0 | 15.3 | — | 43% | completed |
| 2 | R | 14mm F3 OH | 10 | 10.0 | 15.0 | 13.5 | — | — | completed |
| 3 | B | 20mm F2 FC | 10 | 10.0 | 25.0 | 22.5 | — | — | completed |

### 2026-07-05 · Routine 0 · hang · 20 min · held 30s · gauge

Pulls: 3 completed of 3 planned. Session peak 26.0 kg, session average 23.4 kg. Effort 2/5, fingers 2/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 10.0 | 18.0 | 16.2 | — | 45% | completed |
| 2 | R | 14mm F3 OH | 10 | 10.0 | 16.0 | 14.4 | — | — | completed |
| 3 | B | 20mm F2 FC | 10 | 10.0 | 26.0 | 23.4 | — | — | completed |

## Older than 8 weeks, by week

Everything before 2026-07-04, one row per week (weeks start on Monday). Median effort axes are over the sessions in that week that were graded.

| Week of | Sessions | Climb days | Pulls done/planned | Time under tension | Median effort | Median fingers |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| 2026-06-22 | 1 | 0 | 3/3 | 30s | 3 | 3 |
| 2026-06-15 | 1 | 0 | 3/3 | 30s | 4 | 4 |
| 2026-06-08 | 1 | 0 | 3/3 | 30s | 5 | 5 |
| 2026-05-25 | 1 | 0 | 3/3 | 30s | 1 | 1 |
| 2026-05-18 | 1 | 0 | 3/3 | 30s | 2 | 2 |

## Consistency

Target: 2 sessions a day. (Each session also carries the target that was in force when it was logged, so a change to the target never re-scores days already lived.)

Days trained per week across the whole history — a day counts if ANY session landed on it, climbing days included. Newest week first. A week's denominator counts only its days inside the recorded history, so the first and the current week are usually partial — a day before the history began, or still in the future, is not scored as a miss.

| Week of | Days trained | Sessions |
| --- | ---: | ---: |
| 2026-08-24 | 1 of 5 | 1 |
| 2026-08-17 | 1 of 7 | 1 |
| 2026-08-10 | 1 of 7 | 1 |
| 2026-07-27 | 1 of 7 | 1 |
| 2026-07-20 | 1 of 7 | 1 |
| 2026-07-13 | 1 of 7 | 1 |
| 2026-06-29 | 1 of 7 | 1 |
| 2026-06-22 | 1 of 7 | 1 |
| 2026-06-15 | 1 of 7 | 1 |
| 2026-06-08 | 1 of 7 | 1 |
| 2026-05-25 | 1 of 7 | 1 |
| 2026-05-18 | 1 of 4 | 1 |

## Questions worth asking

1. Is one hand falling behind the other — in maxes, in held seconds, or in % max at the same prescription?
2. Are the two effort axes diverging? Fingers climbing while overall effort stays flat is the early warning this schema exists to expose.
3. How fast are the maxes actually moving per grip, and is the training load moving with them or ahead of them?
4. What does the adherence pattern look like — which days and which weeks get missed, and does a missed day follow a hard one?

