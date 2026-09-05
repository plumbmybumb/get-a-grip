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
- `F2+T` — index and middle, thumb also on the hold
- `F3` — index, middle and ring

Then how the hand is set on it:

- `HC` — half crimp
- `OH` — open hand
- `PN` — pinch (the thumb is always on a pinch)
- `SLOPER` — sloper — a position this build does not name

**The two effort axes** are graded by hand after a session and are optional — either or both may be blank. They are stored as 1–5 integers, not as a Borg CR-10 score:

- **Effort** (systemic — how hard the session felt overall): 1 easy · 2 comfortable · 3 solid · 4 hard · 5 all I had.
- **Fingers** (local — how much it asked of the fingers specifically, the stronger signal in the climbing session-RPE literature): 1 nothing · 2 light · 3 worked · 4 taxed · 5 wrecked.

## Current maxes

The newest record for each grip and hand. `measured` means the gauge watched it happen; `typed` means it was entered by hand.

| Grip | Hand | kg | Date | Source |
| --- | --- | ---: | --- | --- |
| 20mm 4F HC | B | 18.0 | 2026-03-31 | measured |
| 20mm 4F HC | L | 21.6 | 2026-08-08 | measured |
| 20mm 4F HC | R | 19.4 | 2026-05-30 | measured |
| 20mm F3 HC | B | 22.0 | 2026-03-31 | measured |
| 20mm F3 HC | L | 25.6 | 2026-08-08 | measured |
| 20mm F3 HC | R | 23.4 | 2026-05-30 | measured |
| 20mm F2 OH | B | 26.0 | 2026-03-31 | measured |
| 20mm F2 OH | L | 29.6 | 2026-08-08 | measured |
| 20mm F2 OH | R | 27.4 | 2026-05-30 | measured |
| 35mm F2+T PN | B | 30.0 | 2026-03-31 | measured |
| 35mm F2+T PN | L | 33.6 | 2026-08-08 | measured |
| 35mm F2+T PN | R | 31.4 | 2026-05-30 | measured |
| 45mm 4F SLOPER | B | 34.0 | 2026-03-31 | measured |
| 45mm 4F SLOPER | L | 37.6 | 2026-08-08 | measured |
| 45mm 4F SLOPER | R | 35.4 | 2026-05-30 | measured |

## Max history

Every record ever written, oldest first within each grip and hand. Records are append-only, so this is the progression itself.

### 20mm 4F HC · both hands

| Date | kg | Source |
| --- | ---: | --- |
| 2026-03-31 | 18.0 | measured |

### 20mm 4F HC · left

| Date | kg | Source |
| --- | ---: | --- |
| 2026-05-30 | 20.0 | measured |
| 2026-08-08 | 21.6 | measured |

### 20mm 4F HC · right

| Date | kg | Source |
| --- | ---: | --- |
| 2026-05-30 | 19.4 | measured |

### 20mm F3 HC · both hands

| Date | kg | Source |
| --- | ---: | --- |
| 2026-03-31 | 22.0 | measured |

### 20mm F3 HC · left

| Date | kg | Source |
| --- | ---: | --- |
| 2026-05-30 | 24.0 | typed |
| 2026-08-08 | 25.6 | measured |

### 20mm F3 HC · right

| Date | kg | Source |
| --- | ---: | --- |
| 2026-05-30 | 23.4 | measured |

### 20mm F2 OH · both hands

| Date | kg | Source |
| --- | ---: | --- |
| 2026-03-31 | 26.0 | measured |

### 20mm F2 OH · left

| Date | kg | Source |
| --- | ---: | --- |
| 2026-05-30 | 28.0 | measured |
| 2026-08-08 | 29.6 | measured |

### 20mm F2 OH · right

| Date | kg | Source |
| --- | ---: | --- |
| 2026-05-30 | 27.4 | measured |

### 35mm F2+T PN · both hands

| Date | kg | Source |
| --- | ---: | --- |
| 2026-03-31 | 30.0 | measured |

### 35mm F2+T PN · left

| Date | kg | Source |
| --- | ---: | --- |
| 2026-05-30 | 32.0 | typed |
| 2026-08-08 | 33.6 | measured |

### 35mm F2+T PN · right

| Date | kg | Source |
| --- | ---: | --- |
| 2026-05-30 | 31.4 | measured |

### 45mm 4F SLOPER · both hands

| Date | kg | Source |
| --- | ---: | --- |
| 2026-03-31 | 34.0 | measured |

### 45mm 4F SLOPER · left

| Date | kg | Source |
| --- | ---: | --- |
| 2026-05-30 | 36.0 | measured |
| 2026-08-08 | 37.6 | measured |

### 45mm 4F SLOPER · right

| Date | kg | Source |
| --- | ---: | --- |
| 2026-05-30 | 35.4 | measured |

## Sessions, last 8 weeks

Every session on record — the history begins 2026-07-04. Newest first.

### 2026-08-28 · Gym · climbLimit · 85 min · logged

Pulls: 0 completed of 0 planned. Effort 3/5, fingers 5/5.

### 2026-08-27 · Daily no-hangs · hang · 27 min · held 13s · gauge

Pulls: 4 completed of 5 planned. Session peak 18.1 kg, session average 16.3 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 35mm F2+T PN | 10 | 4.3 | 15.9 | 14.3 | — | 47% | completed |
| 2 | R | 35mm F2+T PN | 10 | 2.5 | 10.6 | 9.5 | — | 34% | completed |
| 3 | L | 35mm F2+T PN | 10 | 3.2 | 18.1 | 16.3 | — | 54% | completed |
| 4 | R | 35mm F2+T PN | 10 | 0.0 | — | — | — | — | skipped |
| 5 | L | 35mm F2+T PN | 10 | 2.8 | 11.4 | 10.3 | — | 34% | completed |

### 2026-08-26 · Daily no-hangs · hang · 23 min · held 24s · gauge

Pulls: 7 completed of 7 planned. Session peak 20.3 kg, session average 18.3 kg. Effort 1/5, fingers —.

Note: Felt strong on the 45mm 4F SLOPER / second set kept the shoulders down.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 45mm 4F SLOPER | 10 | 3.5 | 8.1 | 7.3 | 9.5–13.0 | 22% | completed |
| 2 | R | 45mm 4F SLOPER | 10 | 1.7 | 17.3 | 15.6 | — | 49% | completed |
| 3 | L | 45mm 4F SLOPER | 10 | 6.0 | 20.3 | 18.3 | — | 54% | completed |
| 4 | R | 45mm 4F SLOPER | 10 | 3.0 | 15.5 | 14.0 | — | 44% | completed |
| 5 | L | 45mm 4F SLOPER | 10 | 2.5 | 9.5 | 8.6 | — | 25% | completed |
| 6 | R | 45mm 4F SLOPER | 10 | 4.5 | 17.1 | 15.4 | — | 48% | completed |
| 7 | L | 45mm 4F SLOPER | 10 | 3.0 | 11.1 | 10.0 | — | 30% | completed |

### 2026-08-26 · Daily no-hangs · hang · 17 min · held 24s · gauge

Pulls: 7 completed of 7 planned. Session peak 20.3 kg, session average 18.3 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 45mm 4F SLOPER | 10 | 3.5 | 8.1 | 7.3 | 9.5–13.0 | 22% | completed |
| 2 | R | 45mm 4F SLOPER | 10 | 1.7 | 17.3 | 15.6 | — | 49% | completed |
| 3 | L | 45mm 4F SLOPER | 10 | 6.0 | 20.3 | 18.3 | — | 54% | completed |
| 4 | R | 45mm 4F SLOPER | 10 | 3.0 | 15.5 | 14.0 | — | 44% | completed |
| 5 | L | 45mm 4F SLOPER | 10 | 2.5 | 9.5 | 8.6 | — | 25% | completed |
| 6 | R | 45mm 4F SLOPER | 10 | 4.5 | 17.1 | 15.4 | — | 48% | completed |
| 7 | L | 45mm 4F SLOPER | 10 | 3.0 | 11.1 | 10.0 | — | 30% | completed |

### 2026-08-25 · Gym · climbVolume · 87 min · logged

Pulls: 0 completed of 0 planned. Effort 2/5, fingers 2/5.

### 2026-08-24 · Weighted hangs · hangManual · 25 min · logged

Pulls: 0 completed of 0 planned. Effort 4/5, fingers —.

### 2026-08-23 · Daily no-hangs · hang · 26 min · held 9s · gauge

Pulls: 2 completed of 4 planned. Session peak 20.6 kg, session average 18.5 kg. Effort —, fingers 4/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 45mm 4F SLOPER | 10 | 4.3 | 13.3 | 12.0 | 9.5–13.0 | 35% | aborted |
| 2 | R | 45mm 4F SLOPER | 10 | 0.0 | — | — | — | — | skipped |
| 3 | L | 45mm 4F SLOPER | 10 | 4.4 | 19.7 | 17.7 | — | 52% | completed |
| 4 | R | 45mm 4F SLOPER | 10 | 0.6 | 20.6 | 18.5 | 9.5–13.0 | 58% | completed |

### 2026-08-22 · Gym · climbLimit · 84 min · logged

Pulls: 0 completed of 0 planned. Effort 2/5, fingers 3/5.

### 2026-08-21 · Daily no-hangs · hang · 19 min · held 28s · gauge

Pulls: 6 completed of 6 planned. Session peak 21.5 kg, session average 19.4 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 6.2 | 8.5 | 7.7 | — | 33% | completed |
| 2 | R | 20mm F3 HC | 10 | 5.8 | 8.0 | 7.2 | — | 34% | completed |
| 3 | L | 20mm F3 HC | 10 | 3.9 | 17.6 | 15.8 | — | 69% | completed |
| 4 | R | 20mm F3 HC | 10 | 1.7 | 21.5 | 19.4 | — | 92% | completed |
| 5 | L | 20mm F3 HC | 10 | 5.0 | 13.3 | 12.0 | — | 52% | completed |
| 6 | R | 20mm F3 HC | 10 | 5.1 | 13.0 | 11.7 | 9.5–13.0 | 56% | completed |

### 2026-08-21 · Daily no-hangs · hang · 17 min · held 28s · gauge

Pulls: 6 completed of 6 planned. Session peak 21.5 kg, session average 19.4 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 6.2 | 8.5 | 7.7 | — | 33% | completed |
| 2 | R | 20mm F3 HC | 10 | 5.8 | 8.0 | 7.2 | — | 34% | completed |
| 3 | L | 20mm F3 HC | 10 | 3.9 | 17.6 | 15.8 | — | 69% | completed |
| 4 | R | 20mm F3 HC | 10 | 1.7 | 21.5 | 19.4 | — | 92% | completed |
| 5 | L | 20mm F3 HC | 10 | 5.0 | 13.3 | 12.0 | — | 52% | completed |
| 6 | R | 20mm F3 HC | 10 | 5.1 | 13.0 | 11.7 | 9.5–13.0 | 56% | completed |

### 2026-08-20 · Daily no-hangs · hang · 26 min · held 16s · gauge

Pulls: 3 completed of 5 planned. Session peak 20.5 kg, session average 18.4 kg. Effort —, fingers 5/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F2 OH | 10 | 4.4 | 20.3 | 18.3 | — | 69% | completed |
| 2 | R | 20mm F2 OH | 10 | 2.7 | 8.1 | 7.3 | — | 30% | completed |
| 3 | L | 20mm F2 OH | 10 | 4.9 | 20.5 | 18.4 | — | 69% | aborted |
| 4 | R | 20mm F2 OH | 10 | 0.0 | — | — | — | — | skipped |
| 5 | L | 20mm F2 OH | 10 | 3.6 | 15.6 | 14.0 | 9.5–13.0 | 53% | completed |

### 2026-08-19 · Gym · climbVolume · 64 min · logged

Pulls: 0 completed of 0 planned. Effort 5/5, fingers 1/5.

### 2026-08-17 · Daily no-hangs · hang · 18 min · held 22s · gauge

Pulls: 6 completed of 7 planned. Session peak 21.8 kg, session average 19.6 kg. Effort 2/5, fingers 4/5.

Note: Felt strong on the 20mm 4F HC / second set kept the shoulders down.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 2.8 | 14.8 | 13.3 | — | 69% | completed |
| 2 | R | 20mm 4F HC | 10 | 1.8 | 12.0 | 10.8 | — | 62% | completed |
| 3 | L | 20mm 4F HC | 10 | 5.1 | 21.8 | 19.6 | 9.5–13.0 | 101% | completed |
| 4 | R | 20mm 4F HC | 10 | 6.1 | 10.7 | 9.6 | — | 55% | completed |
| 5 | L | 20mm 4F HC | 10 | 1.1 | 8.1 | 7.3 | 9.5–13.0 | 37% | completed |
| 6 | R | 20mm 4F HC | 10 | 4.6 | 19.7 | 17.7 | — | 102% | completed |
| 7 | L | 20mm 4F HC | 10 | 0.0 | — | — | 9.5–13.0 | — | skipped |

### 2026-08-16 · Daily no-hangs · hang · 18 min · held 14s · gauge

Pulls: 4 completed of 5 planned. Session peak 18.5 kg, session average 16.7 kg. Effort —, fingers 4/5.

Note: Felt strong on the 20mm F2 OH / second set kept the shoulders down.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F2 OH | 10 | 2.5 | 9.7 | 8.7 | 9.5–13.0 | 33% | completed |
| 2 | R | 20mm F2 OH | 10 | 3.3 | 8.8 | 7.9 | — | 32% | completed |
| 3 | L | 20mm F2 OH | 10 | 0.0 | — | — | 9.5–13.0 | — | skipped |
| 4 | R | 20mm F2 OH | 10 | 1.7 | 12.0 | 10.8 | — | 44% | completed |
| 5 | L | 20mm F2 OH | 10 | 6.0 | 18.5 | 16.7 | — | 63% | completed |

### 2026-08-16 · Daily no-hangs · hang · 17 min · held 14s · gauge

Pulls: 4 completed of 5 planned. Session peak 18.5 kg, session average 16.7 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F2 OH | 10 | 2.5 | 9.7 | 8.7 | 9.5–13.0 | 33% | completed |
| 2 | R | 20mm F2 OH | 10 | 3.3 | 8.8 | 7.9 | — | 32% | completed |
| 3 | L | 20mm F2 OH | 10 | 0.0 | — | — | 9.5–13.0 | — | skipped |
| 4 | R | 20mm F2 OH | 10 | 1.7 | 12.0 | 10.8 | — | 44% | completed |
| 5 | L | 20mm F2 OH | 10 | 6.0 | 18.5 | 16.7 | — | 63% | completed |

### 2026-08-16 · Daily no-hangs · hang · 21 min · held 1m 24s · gauge

Pulls: 11 completed of 12 planned. Session peak 0.0 kg, session average 0.0 kg. Effort 3/5, fingers 3/5.

No rep detail survives for this session.

### 2026-08-15 · Daily no-hangs · hang · 18 min · held 6s · gauge

Pulls: 2 completed of 4 planned. Session peak 17.5 kg, session average 15.8 kg. Effort —, fingers 3/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 0.0 | — | — | — | — | skipped |
| 2 | R | 20mm F3 HC | 10 | 1.0 | 11.5 | 10.3 | 9.5–13.0 | 49% | completed |
| 3 | L | 20mm F3 HC | 10 | 3.8 | 17.5 | 15.8 | — | 68% | completed |
| 4 | R | 20mm F3 HC | 10 | 0.8 | 9.7 | 8.7 | — | 41% | aborted |

### 2026-08-15 · Daily no-hangs · hang · 17 min · held 6s · gauge

Pulls: 2 completed of 4 planned. Session peak 17.5 kg, session average 15.8 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 0.0 | — | — | — | — | skipped |
| 2 | R | 20mm F3 HC | 10 | 1.0 | 11.5 | 10.3 | 9.5–13.0 | 49% | completed |
| 3 | L | 20mm F3 HC | 10 | 3.8 | 17.5 | 15.8 | — | 68% | completed |
| 4 | R | 20mm F3 HC | 10 | 0.8 | 9.7 | 8.7 | — | 41% | aborted |

### 2026-08-14 · Gym · climbVolume · 102 min · logged

Pulls: 0 completed of 0 planned. Effort 5/5, fingers 3/5.

### 2026-08-13 · Daily no-hangs · hang · 26 min · held 10s · gauge

Pulls: 4 completed of 4 planned. Session peak 18.4 kg, session average 16.6 kg. Effort 3/5, fingers 4/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F2 OH | 10 | 1.2 | 16.3 | 14.7 | 9.5–13.0 | 55% | completed |
| 2 | R | 20mm F2 OH | 10 | 2.8 | 15.6 | 14.0 | — | 57% | completed |
| 3 | L | 20mm F2 OH | 10 | 5.2 | 17.9 | 16.1 | — | 60% | completed |
| 4 | R | 20mm F2 OH | 10 | 0.8 | 18.4 | 16.6 | — | 67% | completed |

### 2026-08-13 · Daily no-hangs · hang · 17 min · held 10s · gauge

Pulls: 4 completed of 4 planned. Session peak 18.4 kg, session average 16.6 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F2 OH | 10 | 1.2 | 16.3 | 14.7 | 9.5–13.0 | 55% | completed |
| 2 | R | 20mm F2 OH | 10 | 2.8 | 15.6 | 14.0 | — | 57% | completed |
| 3 | L | 20mm F2 OH | 10 | 5.2 | 17.9 | 16.1 | — | 60% | completed |
| 4 | R | 20mm F2 OH | 10 | 0.8 | 18.4 | 16.6 | — | 67% | completed |

### 2026-08-12 · Daily no-hangs · hang · 27 min · held 29s · gauge

Pulls: 5 completed of 7 planned. Session peak 20.5 kg, session average 18.4 kg. Effort 3/5, fingers 5/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F2 OH | 10 | 6.2 | 12.4 | 11.2 | — | 42% | completed |
| 2 | R | 20mm F2 OH | 10 | 5.5 | 12.3 | 11.1 | — | 45% | completed |
| 3 | L | 20mm F2 OH | 10 | 3.7 | 15.6 | 14.0 | — | 53% | aborted |
| 4 | R | 20mm F2 OH | 10 | 2.0 | 20.5 | 18.4 | 9.5–13.0 | 75% | completed |
| 5 | L | 20mm F2 OH | 10 | 5.7 | 16.8 | 15.1 | — | 57% | completed |
| 6 | R | 20mm F2 OH | 10 | 1.6 | 14.0 | 12.6 | — | 51% | completed |
| 7 | L | 20mm F2 OH | 10 | 3.8 | 10.2 | 9.2 | 9.5–13.0 | 34% | earlyRelease |

### 2026-08-11 · Daily no-hangs · hang · 21 min · held 13s · gauge

Pulls: 4 completed of 4 planned. Session peak 18.4 kg, session average 16.6 kg. Effort 1/5, fingers 1/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 5.9 | 10.7 | 9.6 | 9.5–13.0 | 50% | completed |
| 2 | R | 20mm 4F HC | 10 | 2.5 | 11.1 | 10.0 | 9.5–13.0 | 57% | completed |
| 3 | L | 20mm 4F HC | 10 | 2.4 | 13.0 | 11.7 | — | 60% | completed |
| 4 | R | 20mm 4F HC | 10 | 2.0 | 18.4 | 16.6 | 9.5–13.0 | 95% | completed |

### 2026-08-10 · Gym · climbVolume · 112 min · logged

Pulls: 0 completed of 0 planned. Effort 3/5, fingers 2/5.

### 2026-08-09 · Daily no-hangs · hang · 22 min · held 20s · gauge

Pulls: 6 completed of 6 planned. Session peak 21.3 kg, session average 19.2 kg. Effort —, fingers 5/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 2.3 | 18.2 | 16.4 | — | 71% | completed |
| 2 | R | 20mm F3 HC | 10 | 6.4 | 11.5 | 10.3 | — | 49% | completed |
| 3 | L | 20mm F3 HC | 10 | 4.9 | 21.3 | 19.2 | — | 83% | completed |
| 4 | R | 20mm F3 HC | 10 | 3.3 | 14.7 | 13.2 | — | 63% | completed |
| 5 | L | 20mm F3 HC | 10 | 2.0 | 19.9 | 17.9 | — | 78% | completed |
| 6 | R | 20mm F3 HC | 10 | 1.1 | 14.9 | 13.4 | — | 64% | completed |

### 2026-08-08 · Daily no-hangs · hang · 23 min · held 15s · gauge

Pulls: 3 completed of 6 planned. Session peak 18.7 kg, session average 16.8 kg. Effort 2/5, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 45mm 4F SLOPER | 10 | 2.6 | 10.8 | 9.7 | 9.5–13.0 | 29% | completed |
| 2 | R | 45mm 4F SLOPER | 10 | 3.7 | 9.6 | 8.6 | 9.5–13.0 | 27% | earlyRelease |
| 3 | L | 45mm 4F SLOPER | 10 | 4.6 | 11.3 | 10.2 | 9.5–13.0 | 30% | completed |
| 4 | R | 45mm 4F SLOPER | 10 | 0.0 | — | — | — | — | skipped |
| 5 | L | 45mm 4F SLOPER | 10 | 3.6 | 18.5 | 16.7 | — | 49% | aborted |
| 6 | R | 45mm 4F SLOPER | 10 | 0.9 | 18.7 | 16.8 | 9.5–13.0 | 53% | completed |

### 2026-08-07 · Daily no-hangs · hang · 26 min · held 20s · gauge

Pulls: 5 completed of 6 planned. Session peak 21.1 kg, session average 19.0 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 1.5 | 19.9 | 17.9 | — | 83% | completed |
| 2 | R | 20mm F3 HC | 10 | 3.7 | 18.2 | 16.4 | — | 78% | completed |
| 3 | L | 20mm F3 HC | 10 | 2.0 | 19.1 | 17.2 | — | 80% | aborted |
| 4 | R | 20mm F3 HC | 10 | 6.0 | 14.4 | 13.0 | — | 62% | completed |
| 5 | L | 20mm F3 HC | 10 | 1.1 | 17.8 | 16.0 | — | 74% | completed |
| 6 | R | 20mm F3 HC | 10 | 6.0 | 21.1 | 19.0 | — | 90% | completed |

### 2026-08-06 · Weighted hangs · hangManual · 25 min · logged

Pulls: 0 completed of 0 planned. Effort 1/5, fingers —.

### 2026-08-05 · Daily no-hangs · hang · 21 min · held 13s · gauge

Pulls: 3 completed of 5 planned. Session peak 18.8 kg, session average 16.9 kg. Effort 2/5, fingers 4/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 35mm F2+T PN | 10 | 4.7 | 13.4 | 12.1 | 9.5–13.0 | 42% | completed |
| 2 | R | 35mm F2+T PN | 10 | 2.8 | 18.8 | 16.9 | — | 60% | completed |
| 3 | L | 35mm F2+T PN | 10 | 0.6 | 8.6 | 7.7 | — | 27% | aborted |
| 4 | R | 35mm F2+T PN | 10 | 1.6 | 9.9 | 8.9 | — | 32% | completed |
| 5 | L | 35mm F2+T PN | 10 | 3.0 | 18.0 | 16.2 | 9.5–13.0 | 56% | earlyRelease |

### 2026-08-05 · Daily no-hangs · hang · 17 min · held 13s · gauge

Pulls: 3 completed of 5 planned. Session peak 18.8 kg, session average 16.9 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 35mm F2+T PN | 10 | 4.7 | 13.4 | 12.1 | 9.5–13.0 | 42% | completed |
| 2 | R | 35mm F2+T PN | 10 | 2.8 | 18.8 | 16.9 | — | 60% | completed |
| 3 | L | 35mm F2+T PN | 10 | 0.6 | 8.6 | 7.7 | — | 27% | aborted |
| 4 | R | 35mm F2+T PN | 10 | 1.6 | 9.9 | 8.9 | — | 32% | completed |
| 5 | L | 35mm F2+T PN | 10 | 3.0 | 18.0 | 16.2 | 9.5–13.0 | 56% | earlyRelease |

### 2026-08-04 · Daily no-hangs · hang · 24 min · held 15s · gauge

Pulls: 4 completed of 6 planned. Session peak 20.9 kg, session average 18.8 kg. Effort 3/5, fingers 1/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 5.8 | 20.9 | 18.8 | — | 87% | completed |
| 2 | R | 20mm F3 HC | 10 | 0.0 | — | — | — | — | skipped |
| 3 | L | 20mm F3 HC | 10 | 2.6 | 19.9 | 17.9 | — | 83% | completed |
| 4 | R | 20mm F3 HC | 10 | 1.8 | 20.3 | 18.3 | 9.5–13.0 | 87% | earlyRelease |
| 5 | L | 20mm F3 HC | 10 | 1.6 | 18.6 | 16.7 | — | 78% | completed |
| 6 | R | 20mm F3 HC | 10 | 3.6 | 11.7 | 10.5 | — | 50% | completed |

### 2026-08-03 · Daily no-hangs · hang · 26 min · held 10s · gauge

Pulls: 1 completed of 5 planned. Session peak 19.2 kg, session average 17.3 kg. Effort 5/5, fingers —.

Note: Felt strong on the 20mm 4F HC / second set kept the shoulders down.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 2.2 | 10.2 | 9.2 | — | 51% | completed |
| 2 | R | 20mm 4F HC | 10 | 2.8 | 12.2 | 11.0 | — | 63% | earlyRelease |
| 3 | L | 20mm 4F HC | 10 | 0.0 | — | — | — | — | skipped |
| 4 | R | 20mm 4F HC | 10 | 2.4 | 19.2 | 17.3 | 9.5–13.0 | 99% | earlyRelease |
| 5 | L | 20mm 4F HC | 10 | 2.8 | 18.9 | 17.0 | — | 95% | earlyRelease |

### 2026-08-03 · Daily no-hangs · hang · 17 min · held 10s · gauge

Pulls: 1 completed of 5 planned. Session peak 19.2 kg, session average 17.3 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 2.2 | 10.2 | 9.2 | — | 51% | completed |
| 2 | R | 20mm 4F HC | 10 | 2.8 | 12.2 | 11.0 | — | 63% | earlyRelease |
| 3 | L | 20mm 4F HC | 10 | 0.0 | — | — | — | — | skipped |
| 4 | R | 20mm 4F HC | 10 | 2.4 | 19.2 | 17.3 | 9.5–13.0 | 99% | earlyRelease |
| 5 | L | 20mm 4F HC | 10 | 2.8 | 18.9 | 17.0 | — | 95% | earlyRelease |

### 2026-08-02 · Daily no-hangs · hang · 18 min · held 15s · gauge

Pulls: 4 completed of 5 planned. Session peak 21.4 kg, session average 19.3 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 4.1 | 21.4 | 19.3 | 9.5–13.0 | 107% | completed |
| 2 | R | 20mm 4F HC | 10 | 2.2 | 16.1 | 14.5 | 9.5–13.0 | 83% | earlyRelease |
| 3 | L | 20mm 4F HC | 10 | 4.4 | 17.6 | 15.8 | — | 88% | completed |
| 4 | R | 20mm 4F HC | 10 | 0.6 | 19.7 | 17.7 | — | 102% | completed |
| 5 | L | 20mm 4F HC | 10 | 3.3 | 18.6 | 16.7 | 9.5–13.0 | 93% | completed |

### 2026-08-01 · Weighted hangs · hangManual · 25 min · logged

Pulls: 0 completed of 0 planned. Effort 4/5, fingers —.

### 2026-07-31 · Daily no-hangs · hang · 20 min · held 18s · gauge

Pulls: 4 completed of 6 planned. Session peak 21.7 kg, session average 19.5 kg. Effort 4/5, fingers 1/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 1.4 | 9.2 | 8.3 | 9.5–13.0 | 46% | completed |
| 2 | R | 20mm 4F HC | 10 | 1.1 | 14.2 | 12.8 | 9.5–13.0 | 73% | aborted |
| 3 | L | 20mm 4F HC | 10 | 3.9 | 21.7 | 19.5 | 9.5–13.0 | 109% | completed |
| 4 | R | 20mm 4F HC | 10 | 0.0 | — | — | 9.5–13.0 | — | skipped |
| 5 | L | 20mm 4F HC | 10 | 6.4 | 11.1 | 10.0 | — | 55% | completed |
| 6 | R | 20mm 4F HC | 10 | 5.2 | 20.6 | 18.5 | 9.5–13.0 | 106% | completed |

### 2026-07-30 · Daily no-hangs · hang · 19 min · held 21s · gauge

Pulls: 4 completed of 5 planned. Session peak 20.1 kg, session average 18.1 kg. Effort 5/5, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 4.2 | 11.9 | 10.7 | — | 50% | completed |
| 2 | R | 20mm F3 HC | 10 | 3.1 | 20.1 | 18.1 | — | 86% | completed |
| 3 | L | 20mm F3 HC | 10 | 3.7 | 12.9 | 11.6 | — | 54% | aborted |
| 4 | R | 20mm F3 HC | 10 | 3.8 | 11.2 | 10.1 | — | 48% | completed |
| 5 | L | 20mm F3 HC | 10 | 6.4 | 16.5 | 14.8 | 9.5–13.0 | 69% | completed |

### 2026-07-30 · Daily no-hangs · hang · 17 min · held 21s · gauge

Pulls: 4 completed of 5 planned. Session peak 20.1 kg, session average 18.1 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 4.2 | 11.9 | 10.7 | — | 50% | completed |
| 2 | R | 20mm F3 HC | 10 | 3.1 | 20.1 | 18.1 | — | 86% | completed |
| 3 | L | 20mm F3 HC | 10 | 3.7 | 12.9 | 11.6 | — | 54% | aborted |
| 4 | R | 20mm F3 HC | 10 | 3.8 | 11.2 | 10.1 | — | 48% | completed |
| 5 | L | 20mm F3 HC | 10 | 6.4 | 16.5 | 14.8 | 9.5–13.0 | 69% | completed |

### 2026-07-29 · Gym · climbLimit · 106 min · logged

Pulls: 0 completed of 0 planned. Effort 4/5, fingers 1/5.

### 2026-07-28 · Daily no-hangs · hang · 22 min · held 13s · gauge

Pulls: 5 completed of 6 planned. Session peak 21.1 kg, session average 19.0 kg. Effort 1/5, fingers 1/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 0.0 | — | — | — | — | skipped |
| 2 | R | 20mm 4F HC | 10 | 0.8 | 10.0 | 9.0 | 9.5–13.0 | 52% | completed |
| 3 | L | 20mm 4F HC | 10 | 5.5 | 10.1 | 9.1 | — | 51% | completed |
| 4 | R | 20mm 4F HC | 10 | 3.4 | 11.5 | 10.3 | — | 59% | completed |
| 5 | L | 20mm 4F HC | 10 | 0.6 | 21.1 | 19.0 | — | 106% | completed |
| 6 | R | 20mm 4F HC | 10 | 2.8 | 13.7 | 12.3 | — | 71% | completed |

### 2026-07-27 · Daily no-hangs · hang · 23 min · held 16s · gauge

Pulls: 4 completed of 5 planned. Session peak 21.6 kg, session average 19.4 kg. Effort 2/5, fingers 3/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 5.2 | 13.5 | 12.2 | — | 56% | completed |
| 2 | R | 20mm F3 HC | 10 | 0.0 | — | — | — | — | skipped |
| 3 | L | 20mm F3 HC | 10 | 1.8 | 10.2 | 9.2 | — | 43% | completed |
| 4 | R | 20mm F3 HC | 10 | 4.1 | 10.1 | 9.1 | — | 43% | completed |
| 5 | L | 20mm F3 HC | 10 | 5.2 | 21.6 | 19.4 | — | 90% | completed |

### 2026-07-27 · Daily no-hangs · hang · 17 min · held 16s · gauge

Pulls: 4 completed of 5 planned. Session peak 21.6 kg, session average 19.4 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 5.2 | 13.5 | 12.2 | — | 56% | completed |
| 2 | R | 20mm F3 HC | 10 | 0.0 | — | — | — | — | skipped |
| 3 | L | 20mm F3 HC | 10 | 1.8 | 10.2 | 9.2 | — | 43% | completed |
| 4 | R | 20mm F3 HC | 10 | 4.1 | 10.1 | 9.1 | — | 43% | completed |
| 5 | L | 20mm F3 HC | 10 | 5.2 | 21.6 | 19.4 | — | 90% | completed |

### 2026-07-26 · Daily no-hangs · hang · 18 min · held 18s · gauge

Pulls: 4 completed of 4 planned. Session peak 19.2 kg, session average 17.3 kg. Effort 4/5, fingers 3/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 35mm F2+T PN | 10 | 6.4 | 14.4 | 13.0 | 9.5–13.0 | 45% | completed |
| 2 | R | 35mm F2+T PN | 10 | 0.8 | 13.1 | 11.8 | — | 42% | completed |
| 3 | L | 35mm F2+T PN | 10 | 6.4 | 19.2 | 17.3 | 9.5–13.0 | 60% | completed |
| 4 | R | 35mm F2+T PN | 10 | 4.0 | 13.0 | 11.7 | 9.5–13.0 | 41% | completed |

### 2026-07-25 · Daily no-hangs · hang · 24 min · held 13s · gauge

Pulls: 3 completed of 4 planned. Session peak 16.8 kg, session average 15.1 kg. Effort 4/5, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F2 OH | 10 | 4.7 | 16.5 | 14.8 | — | 59% | completed |
| 2 | R | 20mm F2 OH | 10 | 0.7 | 15.1 | 13.6 | — | 55% | completed |
| 3 | L | 20mm F2 OH | 10 | 4.5 | 10.4 | 9.4 | — | 37% | aborted |
| 4 | R | 20mm F2 OH | 10 | 3.1 | 16.8 | 15.1 | — | 61% | completed |

### 2026-07-25 · Daily no-hangs · hang · 17 min · held 13s · gauge

Pulls: 3 completed of 4 planned. Session peak 16.8 kg, session average 15.1 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F2 OH | 10 | 4.7 | 16.5 | 14.8 | — | 59% | completed |
| 2 | R | 20mm F2 OH | 10 | 0.7 | 15.1 | 13.6 | — | 55% | completed |
| 3 | L | 20mm F2 OH | 10 | 4.5 | 10.4 | 9.4 | — | 37% | aborted |
| 4 | R | 20mm F2 OH | 10 | 3.1 | 16.8 | 15.1 | — | 61% | completed |

### 2026-07-24 · Daily no-hangs · hang · 24 min · held 14s · gauge

Pulls: 5 completed of 5 planned. Session peak 19.7 kg, session average 17.7 kg. Effort —, fingers 1/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 2.9 | 19.1 | 17.2 | — | 96% | completed |
| 2 | R | 20mm 4F HC | 10 | 2.6 | 19.5 | 17.6 | 9.5–13.0 | 101% | completed |
| 3 | L | 20mm 4F HC | 10 | 2.0 | 9.6 | 8.6 | 9.5–13.0 | 48% | completed |
| 4 | R | 20mm 4F HC | 10 | 6.2 | 19.7 | 17.7 | 9.5–13.0 | 102% | completed |
| 5 | L | 20mm 4F HC | 10 | 0.7 | 9.3 | 8.4 | — | 47% | completed |

### 2026-07-23 · Daily no-hangs · hang · 26 min · held 20s · gauge

Pulls: 5 completed of 7 planned. Session peak 18.9 kg, session average 17.0 kg. Effort 1/5, fingers 3/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 0.6 | 13.1 | 11.8 | — | 55% | completed |
| 2 | R | 20mm F3 HC | 10 | 4.3 | 13.7 | 12.3 | — | 59% | earlyRelease |
| 3 | L | 20mm F3 HC | 10 | 4.8 | 12.0 | 10.8 | — | 50% | completed |
| 4 | R | 20mm F3 HC | 10 | 2.7 | 18.9 | 17.0 | — | 81% | completed |
| 5 | L | 20mm F3 HC | 10 | 2.2 | 11.8 | 10.6 | — | 49% | earlyRelease |
| 6 | R | 20mm F3 HC | 10 | 4.7 | 16.3 | 14.7 | 9.5–13.0 | 70% | completed |
| 7 | L | 20mm F3 HC | 10 | 0.6 | 9.9 | 8.9 | — | 41% | completed |

### 2026-07-22 · Daily no-hangs · hang · 20 min · held 15s · gauge

Pulls: 6 completed of 6 planned. Session peak 19.1 kg, session average 17.2 kg. Effort —, fingers 4/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 45mm 4F SLOPER | 10 | 3.4 | 16.0 | 14.4 | 9.5–13.0 | 44% | completed |
| 2 | R | 45mm 4F SLOPER | 10 | 1.2 | 19.1 | 17.2 | 9.5–13.0 | 54% | completed |
| 3 | L | 45mm 4F SLOPER | 10 | 4.9 | 13.2 | 11.9 | — | 37% | completed |
| 4 | R | 45mm 4F SLOPER | 10 | 1.8 | 18.2 | 16.4 | — | 51% | completed |
| 5 | L | 45mm 4F SLOPER | 10 | 2.7 | 9.2 | 8.3 | — | 26% | completed |
| 6 | R | 45mm 4F SLOPER | 10 | 0.8 | 14.9 | 13.4 | 9.5–13.0 | 42% | completed |

### 2026-07-21 · Daily no-hangs · hang · 22 min · held 20s · gauge

Pulls: 3 completed of 5 planned. Session peak 13.1 kg, session average 11.8 kg. Effort 1/5, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F2 OH | 10 | 5.6 | 9.4 | 8.5 | — | 34% | aborted |
| 2 | R | 20mm F2 OH | 10 | 6.0 | 13.1 | 11.8 | — | 48% | completed |
| 3 | L | 20mm F2 OH | 10 | 2.4 | 11.9 | 10.7 | — | 43% | completed |
| 4 | R | 20mm F2 OH | 10 | 5.8 | 11.7 | 10.5 | — | 43% | completed |
| 5 | L | 20mm F2 OH | 10 | 0.0 | — | — | 9.5–13.0 | — | skipped |

### 2026-07-20 · Gym · climbLimit · 87 min · logged

Pulls: 0 completed of 0 planned. Effort 1/5, fingers 2/5.

### 2026-07-19 · Gym · climbLimit · 116 min · logged

Pulls: 0 completed of 0 planned. Effort 5/5, fingers 4/5.

### 2026-07-18 · Weighted hangs · hangManual · 25 min · logged

Pulls: 0 completed of 0 planned. Effort 5/5, fingers —.

### 2026-07-17 · Gym · climbVolume · 81 min · logged

Pulls: 0 completed of 0 planned. Effort 4/5, fingers 3/5.

### 2026-07-16 · Daily no-hangs · hang · 18 min · held 18s · gauge

Pulls: 3 completed of 6 planned. Session peak 18.6 kg, session average 16.7 kg. Effort —, fingers 5/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 35mm F2+T PN | 10 | 0.6 | 18.6 | 16.7 | — | 58% | completed |
| 2 | R | 35mm F2+T PN | 10 | 0.8 | 13.5 | 12.2 | — | 43% | earlyRelease |
| 3 | L | 35mm F2+T PN | 10 | 5.4 | 17.7 | 15.9 | 9.5–13.0 | 55% | earlyRelease |
| 4 | R | 35mm F2+T PN | 10 | 4.9 | 18.0 | 16.2 | — | 57% | completed |
| 5 | L | 35mm F2+T PN | 10 | 3.8 | 14.3 | 12.9 | 9.5–13.0 | 45% | completed |
| 6 | R | 35mm F2+T PN | 10 | 2.9 | 17.8 | 16.0 | 9.5–13.0 | 57% | earlyRelease |

### 2026-07-16 · Daily no-hangs · hang · 17 min · held 18s · gauge

Pulls: 3 completed of 6 planned. Session peak 18.6 kg, session average 16.7 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 35mm F2+T PN | 10 | 0.6 | 18.6 | 16.7 | — | 58% | completed |
| 2 | R | 35mm F2+T PN | 10 | 0.8 | 13.5 | 12.2 | — | 43% | earlyRelease |
| 3 | L | 35mm F2+T PN | 10 | 5.4 | 17.7 | 15.9 | 9.5–13.0 | 55% | earlyRelease |
| 4 | R | 35mm F2+T PN | 10 | 4.9 | 18.0 | 16.2 | — | 57% | completed |
| 5 | L | 35mm F2+T PN | 10 | 3.8 | 14.3 | 12.9 | 9.5–13.0 | 45% | completed |
| 6 | R | 35mm F2+T PN | 10 | 2.9 | 17.8 | 16.0 | 9.5–13.0 | 57% | earlyRelease |

### 2026-07-15 · Gym · climbLimit · 75 min · logged

Pulls: 0 completed of 0 planned. Effort 4/5, fingers 4/5.

### 2026-07-14 · Weighted hangs · hangManual · 25 min · logged

Pulls: 0 completed of 0 planned. Effort 4/5, fingers —.

### 2026-07-13 · Daily no-hangs · hang · 26 min · held 21s · gauge

Pulls: 6 completed of 7 planned. Session peak 21.9 kg, session average 19.7 kg. Effort —, fingers 2/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 1.3 | 9.2 | 8.3 | — | 46% | completed |
| 2 | R | 20mm 4F HC | 10 | 6.0 | 21.9 | 19.7 | — | 113% | completed |
| 3 | L | 20mm 4F HC | 10 | 2.7 | 9.9 | 8.9 | — | 50% | completed |
| 4 | R | 20mm 4F HC | 10 | 2.2 | 15.5 | 14.0 | — | 80% | completed |
| 5 | L | 20mm 4F HC | 10 | 1.2 | 19.9 | 17.9 | — | 99% | completed |
| 6 | R | 20mm 4F HC | 10 | 2.7 | 15.0 | 13.5 | — | 77% | completed |
| 7 | L | 20mm 4F HC | 10 | 4.9 | 8.5 | 7.7 | — | 43% | earlyRelease |

### 2026-07-13 · Daily no-hangs · hang · 17 min · held 21s · gauge

Pulls: 6 completed of 7 planned. Session peak 21.9 kg, session average 19.7 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 1.3 | 9.2 | 8.3 | — | 46% | completed |
| 2 | R | 20mm 4F HC | 10 | 6.0 | 21.9 | 19.7 | — | 113% | completed |
| 3 | L | 20mm 4F HC | 10 | 2.7 | 9.9 | 8.9 | — | 50% | completed |
| 4 | R | 20mm 4F HC | 10 | 2.2 | 15.5 | 14.0 | — | 80% | completed |
| 5 | L | 20mm 4F HC | 10 | 1.2 | 19.9 | 17.9 | — | 99% | completed |
| 6 | R | 20mm 4F HC | 10 | 2.7 | 15.0 | 13.5 | — | 77% | completed |
| 7 | L | 20mm 4F HC | 10 | 4.9 | 8.5 | 7.7 | — | 43% | earlyRelease |

### 2026-07-12 · Daily no-hangs · hang · 20 min · held 10s · gauge

Pulls: 3 completed of 6 planned. Session peak 19.3 kg, session average 17.4 kg. Effort —, fingers 5/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 0.0 | — | — | — | — | skipped |
| 2 | R | 20mm F3 HC | 10 | 6.4 | 12.3 | 11.1 | — | 53% | completed |
| 3 | L | 20mm F3 HC | 10 | 0.0 | — | — | — | — | skipped |
| 4 | R | 20mm F3 HC | 10 | 1.2 | 19.3 | 17.4 | — | 82% | completed |
| 5 | L | 20mm F3 HC | 10 | 2.6 | 12.7 | 11.4 | — | 53% | completed |
| 6 | R | 20mm F3 HC | 10 | 0.0 | — | — | 9.5–13.0 | — | skipped |

### 2026-07-11 · Daily no-hangs · hang · 26 min · held 19s · gauge

Pulls: 3 completed of 5 planned. Session peak 20.6 kg, session average 18.5 kg. Effort 1/5, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 5.8 | 8.4 | 7.6 | 9.5–13.0 | 42% | aborted |
| 2 | R | 20mm 4F HC | 10 | 1.3 | 11.3 | 10.2 | — | 58% | completed |
| 3 | L | 20mm 4F HC | 10 | 5.3 | 17.2 | 15.5 | 9.5–13.0 | 86% | completed |
| 4 | R | 20mm 4F HC | 10 | 4.7 | 11.1 | 10.0 | — | 57% | completed |
| 5 | L | 20mm 4F HC | 10 | 1.7 | 20.6 | 18.5 | — | 103% | aborted |

### 2026-07-10 · Daily no-hangs · hang · 24 min · held 29s · gauge

Pulls: 5 completed of 6 planned. Session peak 13.2 kg, session average 11.9 kg. Effort 2/5, fingers 1/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 6.3 | 9.6 | 8.6 | — | 40% | completed |
| 2 | R | 20mm F3 HC | 10 | 2.1 | 11.6 | 10.4 | — | 50% | completed |
| 3 | L | 20mm F3 HC | 10 | 6.2 | 11.0 | 9.9 | — | 46% | completed |
| 4 | R | 20mm F3 HC | 10 | 4.7 | 10.4 | 9.4 | — | 44% | completed |
| 5 | L | 20mm F3 HC | 10 | 3.7 | 12.5 | 11.2 | — | 52% | completed |
| 6 | R | 20mm F3 HC | 10 | 6.3 | 13.2 | 11.9 | 9.5–13.0 | 56% | earlyRelease |

### 2026-07-10 · Daily no-hangs · hang · 17 min · held 29s · gauge

Pulls: 5 completed of 6 planned. Session peak 13.2 kg, session average 11.9 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F3 HC | 10 | 6.3 | 9.6 | 8.6 | — | 40% | completed |
| 2 | R | 20mm F3 HC | 10 | 2.1 | 11.6 | 10.4 | — | 50% | completed |
| 3 | L | 20mm F3 HC | 10 | 6.2 | 11.0 | 9.9 | — | 46% | completed |
| 4 | R | 20mm F3 HC | 10 | 4.7 | 10.4 | 9.4 | — | 44% | completed |
| 5 | L | 20mm F3 HC | 10 | 3.7 | 12.5 | 11.2 | — | 52% | completed |
| 6 | R | 20mm F3 HC | 10 | 6.3 | 13.2 | 11.9 | 9.5–13.0 | 56% | earlyRelease |

### 2026-07-09 · Daily no-hangs · hang · 23 min · held 8s · gauge

Pulls: 4 completed of 6 planned. Session peak 20.7 kg, session average 18.6 kg. Effort 3/5, fingers 5/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 0.0 | — | — | 9.5–13.0 | — | skipped |
| 2 | R | 20mm 4F HC | 10 | 2.8 | 20.4 | 18.4 | — | 105% | completed |
| 3 | L | 20mm 4F HC | 10 | 0.0 | — | — | — | — | skipped |
| 4 | R | 20mm 4F HC | 10 | 2.9 | 15.9 | 14.3 | 9.5–13.0 | 82% | completed |
| 5 | L | 20mm 4F HC | 10 | 1.3 | 17.8 | 16.0 | — | 89% | completed |
| 6 | R | 20mm 4F HC | 10 | 1.4 | 20.7 | 18.6 | — | 107% | completed |

### 2026-07-09 · Daily no-hangs · hang · 17 min · held 8s · gauge

Pulls: 4 completed of 6 planned. Session peak 20.7 kg, session average 18.6 kg. Effort —, fingers —.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm 4F HC | 10 | 0.0 | — | — | 9.5–13.0 | — | skipped |
| 2 | R | 20mm 4F HC | 10 | 2.8 | 20.4 | 18.4 | — | 105% | completed |
| 3 | L | 20mm 4F HC | 10 | 0.0 | — | — | — | — | skipped |
| 4 | R | 20mm 4F HC | 10 | 2.9 | 15.9 | 14.3 | 9.5–13.0 | 82% | completed |
| 5 | L | 20mm 4F HC | 10 | 1.3 | 17.8 | 16.0 | — | 89% | completed |
| 6 | R | 20mm 4F HC | 10 | 1.4 | 20.7 | 18.6 | — | 107% | completed |

### 2026-07-08 · Daily no-hangs · hang · 27 min · held 16s · gauge

Pulls: 4 completed of 5 planned. Session peak 19.6 kg, session average 17.6 kg. Effort 1/5, fingers 4/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 35mm F2+T PN | 10 | 3.7 | 19.0 | 17.1 | — | 59% | completed |
| 2 | R | 35mm F2+T PN | 10 | 5.9 | 10.7 | 9.6 | — | 34% | completed |
| 3 | L | 35mm F2+T PN | 10 | 0.0 | — | — | 9.5–13.0 | — | skipped |
| 4 | R | 35mm F2+T PN | 10 | 2.0 | 19.6 | 17.6 | — | 62% | completed |
| 5 | L | 35mm F2+T PN | 10 | 4.0 | 14.0 | 12.6 | 9.5–13.0 | 44% | completed |

### 2026-07-07 · Gym · climbLimit · 95 min · logged

Pulls: 0 completed of 0 planned. Effort 5/5, fingers 4/5.

### 2026-07-06 · Gym · climbVolume · 66 min · logged

Pulls: 0 completed of 0 planned. Effort 1/5, fingers 3/5.

### 2026-07-05 · Daily no-hangs · hang · 27 min · held 20s · gauge

Pulls: 5 completed of 7 planned. Session peak 20.2 kg, session average 18.2 kg. Effort —, fingers 3/5.

| # | Hand | Grip | Plan s | Held s | Peak kg | Avg kg | Target kg | % max | Outcome |
| ---: | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | --- |
| 1 | L | 20mm F2 OH | 10 | 1.0 | 20.2 | 18.2 | 9.5–13.0 | 72% | completed |
| 2 | R | 20mm F2 OH | 10 | 0.0 | — | — | — | — | skipped |
| 3 | L | 20mm F2 OH | 10 | 4.3 | 10.3 | 9.3 | — | 37% | completed |
| 4 | R | 20mm F2 OH | 10 | 0.0 | — | — | — | — | skipped |
| 5 | L | 20mm F2 OH | 10 | 6.2 | 9.5 | 8.6 | — | 34% | completed |
| 6 | R | 20mm F2 OH | 10 | 6.3 | 10.8 | 9.7 | — | 39% | completed |
| 7 | L | 20mm F2 OH | 10 | 2.2 | 17.2 | 15.5 | 9.5–13.0 | 61% | completed |

### 2026-07-04 · Weighted hangs · hangManual · 25 min · logged

Pulls: 0 completed of 0 planned. Effort 5/5, fingers —.

## Older than 8 weeks, by week

Everything before 2026-07-04, one row per week (weeks start on Monday). Median effort axes are over the sessions in that week that were graded.

| Week of | Sessions | Climb days | Pulls done/planned | Time under tension | Median effort | Median fingers |
| --- | ---: | ---: | --- | ---: | ---: | ---: |
| 2026-06-29 | 6 | 0 | 29/35 | 2m 0s | 2 | 5 |
| 2026-06-22 | 9 | 1 | 33/42 | 2m 18s | 4 | 3.5 |
| 2026-06-15 | 8 | 1 | 29/39 | 1m 59s | 2 | 2 |
| 2026-06-08 | 7 | 0 | 26/32 | 2m 2s | 5 | 3 |
| 2026-06-01 | 9 | 2 | 22/36 | 1m 32s | 3 | 4 |
| 2026-05-25 | 6 | 1 | 4/6 | 17s | 4 | 2 |
| 2026-05-18 | 8 | 0 | 34/36 | 2m 4s | 1 | 3.5 |
| 2026-05-11 | 6 | 0 | 16/22 | 1m 29s | 2.5 | 1 |
| 2026-05-04 | 9 | 1 | 27/39 | 1m 53s | 2 | 3 |
| 2026-04-27 | 4 | 1 | 12/17 | 47s | 2.5 | 2 |

## Consistency

Target: 2 sessions a day. (Each session also carries the target that was in force when it was logged, so a change to the target never re-scores days already lived.)

Days trained per week across the whole history — a day counts if ANY session landed on it, climbing days included. Newest week first. A week's denominator counts only its days inside the recorded history, so the first and the current week are usually partial — a day before the history began, or still in the future, is not scored as a miss.

| Week of | Days trained | Sessions |
| --- | ---: | ---: |
| 2026-08-24 | 5 of 5 | 6 |
| 2026-08-17 | 6 of 7 | 7 |
| 2026-08-10 | 7 of 7 | 11 |
| 2026-08-03 | 7 of 7 | 9 |
| 2026-07-27 | 7 of 7 | 9 |
| 2026-07-20 | 7 of 7 | 8 |
| 2026-07-13 | 7 of 7 | 9 |
| 2026-07-06 | 7 of 7 | 9 |
| 2026-06-29 | 7 of 7 | 8 |
| 2026-06-22 | 7 of 7 | 9 |
| 2026-06-15 | 7 of 7 | 8 |
| 2026-06-08 | 6 of 7 | 7 |
| 2026-06-01 | 7 of 7 | 9 |
| 2026-05-25 | 6 of 7 | 6 |
| 2026-05-18 | 7 of 7 | 8 |
| 2026-05-11 | 6 of 7 | 6 |
| 2026-05-04 | 7 of 7 | 9 |
| 2026-04-27 | 4 of 4 | 4 |

## Questions worth asking

1. Is one hand falling behind the other — in maxes, in held seconds, or in % max at the same prescription?
2. Are the two effort axes diverging? Fingers climbing while overall effort stays flat is the early warning this schema exists to expose.
3. How fast are the maxes actually moving per grip, and is the training load moving with them or ahead of them?
4. What does the adherence pattern look like — which days and which weeks get missed, and does a missed day follow a hard one?

