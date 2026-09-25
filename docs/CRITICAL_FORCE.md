# Critical Force (2026-09-25)

The research that shaped the critical force test, then what was built. §6 records the
decisions Nuri made; §7 is the as-built summary.

## 1. What critical force is

Endurance sport has a known pattern. Time to exhaustion falls along a hyperbola as intensity rises,
and the curve flattens toward an asymptote. For cycling that asymptote is **critical power**. For an
isometric finger flexor it is **critical force (CF)**: the highest intermittent force you can hold at a
metabolic steady state. Above CF you spend a finite reserve, **W′** (kg·s of impulse above CF). When W′
runs out, you are pumped and you let go. Below CF you can keep going for a long time.

- CF marks the upper edge of the heavy-intensity domain. Oxygen delivery and use limit it. W′ is limited
  by phosphocreatine and metabolite build-up (Giles 2019, after Monod & Scherrer / Jones & Vanhatalo).
- Forearm ischaemia starts at about 20 % of max and blood flow is fully occluded at roughly 45–75 %. In a
  hard pull, most of the oxygen therefore arrives *during the rests*. That is why the rest structure
  matters so much (Baláš 2024).

## 2. Why it is worth adding

- **It predicts route climbing better than max strength does.** In 129 climbers, CF as a % of body mass
  explained **61 % of sport grade** and 26 % of bouldering grade. W′/kg explained 34 % of bouldering.
  Together they explained 66 % of sport grade and 44 % of bouldering grade (Giles 2021).
- **The ratio is diagnostic.** CF averages about 40 % of max: 41 ± 6 % (Giles 2019) and 39 ± 9 %
  (Baláš 2024). Reported values run from under 30 % (boulderers) to about 55–60 % (strong route
  climbers). A low ratio means endurance is the limiter. A high one means strength is.
- **It trains and it tracks.** Test–retest reliability is excellent in familiarised climbers: CF and W′
  both ICC 0.97 (Boklaschuk 2026) and ICC 0.96 (Giles 2021). CF moved **+22 % in 5 weeks** of training
  (Perrin 2026). One caveat: with unfamiliarised climbers who lowered the arm during rests,
  McClean 2023 measured ICC 0.85 with a 21 % CV. Treat the first test as practice.
- **It fits Nuri's own routine.** The daily Emil-style no-hang sits at 20–30 % of max. The *truly*
  sustainable line is about 0.8 × CF (see the caveat below), which lands around 30–35 % of max. So the
  daily routine really does sit below threshold, in the aerobic, capillarity-building domain that
  low-intensity protocols aim for. CF is what turns that from a claim into a measurement.

**The caveat that shapes the product:** the 4-minute all-out test **overestimates** the load you can
actually sustain.
- Boklaschuk 2026: all-out CF was 122 N, but the constant-load CF was 98 N (about 80 %). The authors
  conclude that it is "useful for tracking changes" but "likely unsuitable for exercise prescription".
- Baláš 2024: at the all-out CF, time to failure was only about 7 minutes. The load people actually held
  for 12 minutes averaged 16.1 kg against a CF of 20.1 kg. The **end-force of the last 3 reps**
  (16.8 kg) matched that sustainable load and predicted climbing grade better (r 0.70 vs 0.66).

So **CF is a progress number, not a prescription.** Show it and trend it. Do not silently set daily
loads from it.

## 3. The protocol, and the 7:3 question

The standard all-out test (Giles 2021, and the one Tindeq and Frez implement): **24 × (7 s all-out pull :
3 s rest) = 4 minutes**, one hand, half crimp on a 20 mm edge, arm overhead. Force decays and then
levels off. The papers used a standardised warm-up and **audio and visual cues from a device**, so the
cadence was set externally, never by the climber.

**Is the rigid 7-3-7-3 cadence part of the science? Yes. Rest-on-release would break the test.**
- **CF is only defined for a given duty cycle.** Changing the work-to-rest ratio changes both CF and W′
  (Giles 2019, citing the critical-power literature). How big is that effect? For elbow flexors, critical
  torque was 28 % of max for sustained contractions and 41 % for 3 s-on/2 s-off (Herold & Sommer 2020).
  The forearm tests that do separate sustainable from unsustainable work use very short contractions
  (1:2 s, 1.5:1.5 s). The 7:3 test is the one that overestimates, and Baláš names the work-to-rest ratio
  as a probable reason.
- If rest waited for release, a 9-second hang would still get 3 seconds of rest. The duty cycle would
  drift from 70 % toward 75 %, less blood would get back in, the result would drop, and it would drop by
  a different amount every test. The test's standard error is already about 2 kg (Baláš 2024). A timing
  rule that adds its own noise defeats the point, which is comparing today with six weeks ago.
- Even small protocol drift moves the number. Lowering the arm during rests raised CF enough to
  change the test's reliability (McClean 2023, discussed in Baláš 2024).

**So the apps are right to lock the clock. What they get wrong is what they measure against it.**
Giles 2019 admits climbers "struggled with the perfect execution of the seven-seconds of work". They
recommend future work "consider the recording and calculation of actual work done". Baláš measured
actual contraction time as **6.6 s, not 7**. **Our improvement:** keep the metronome fixed, but build
every number from the force *inside each 7-second window*. Force pulled after the bell counts for
nothing (you only spent your own rest). That rep is marked "late off" and the summary reports how many
reps landed on the beat. This is honest, and it cannot be gamed.

**Starting.** Frez's auto-start is the best idea in either app: the first pull over threshold starts rep 1.
(Its 4.9 kg threshold is 7 % of the 70 kg body weight typed in beforehand.) It maps onto our existing
`armed` phase. After that one anchor, the 10-second metronome is locked.

**Stopping early.** Giles 2021 found CF stable after the last-six average at about **159 s**. That is
16 reps, and it is exactly Frez's "complete 16 sets to finish early". Allowing a hold-to-end after rep 16
is supported by the data. The default stays at 24.

**One test is one hand** in every validation paper. Tindeq and Frez also allow both hands, and that
works as long as it is kept consistent. Hand becomes part of the record's identity, exactly as with
maxes.

## 4. Which number to call "critical force"

| Definition | Source | Status |
|---|---|---|
| Mean force of the **last 6 reps** | Giles 2021, Lattice, Tindeq | The published convention; the norms are built on it |
| Mean force of the last 3 reps / last 30 s | Kellawan 2014, Baláš 2024 | Similar, slightly higher |
| **End-force** of the last 3 reps | Baláš 2024 (CF_min) | Closest to what is actually sustainable |
| W′ = ∫ max(0, F − CF) dt over the work windows | Giles, Baláš | Standard; less reliable than CF |

Recommendation: the headline is **last-6 mean**, labelled on the result ("mean of reps 19–24"), so the
number is comparable with Tindeq, Lattice and the norms. Store the per-rep peak, mean, end-force and
impulse, so any definition can be recomputed later.

The science has changed its mind three times in five years. The field has not settled, and hard-coding
one definition without keeping the evidence would repeat Frez and Tindeq's mistake. Nuri's screenshots
show it: two tests five minutes apart gave **15.46 / 513** in Tindeq and **18.53 / 1048** in Frez.
Undocumented definitions plus about 2 kg of test noise produce exactly that gap.

## 5. How it would sit in the app

**Keep from the other apps:** auto-start on the first pull, the fixed metronome, the live trace, and one
result line drawn across the whole test.
**Leave out:** four-way RFD tables, the Abs/%BW and Overall/Rep toggles, a body-weight modal every time, a
grip-library sheet with Confirm, the "new record" share poster, and a disabled "finish early" button
nagging from the bottom.

**Engine.** This is a new *fixed-cadence* mode, not a routine. It is a deliberate, documented exception
to "a rep never ends itself": in a test the clock *is* the protocol, and coming off the edge is simply
recorded as low force. That is the truthful value in an all-out test. Other differences from the runner:
- Rest never waits for release.
- Backgrounding or losing the link voids the test rather than pausing it. W′ recovers during a pause
  (roughly 20–25 min to full), so a resumed test measures something else.
- The work is pure and testable: it lives in `Shared/Engine`, like `MaxAttempt`, and consumes device-µs
  deltas only.

**Screen.** A single glass surface. The hero is the countdown of the current window (7 s bleu, 3 s calm).
Under it: the live trace plus a short tick per finished rep showing that rep's mean, so you can watch the
plateau form. Above that: "Pull 9 of 24". At 7 s a distinct LET GO cue fires (haptic and tone), then
3-2-1 ticks into the next pull. Nothing else is on screen.

**Result.** One number: CF in kg for that grip and hand, with "52 % of your max" underneath, the trace
with the CF line across it, and W′ in secondary ink. It is saved like a max, without a Confirm step, and
Undo is available.

**Model.** A new append-only `CriticalForceRecord`, shaped like `MaxRecord`: a grip value plus a hand,
with cfKg, wPrimeKgS, peakKg, endForceKg, per-rep blob, protocol and recordedAt. It is not a
`SessionKind` of its own. **`.benchmark` already means "a testing day"**: it completes the day and has
its own calendar mark (the bore). A test therefore stamps the day as a benchmark exactly as `recordMax`
does. The calendar needs no new shape, only a legend rename from "Max testing" to "Testing".

**Today.** Today is the ritual, and a test happens about every 4–8 weeks, so it must not become a daily
card. Options are in section 6.

**Ripple checklist**, from the code map:
- `SessionResult.swift` `SessionKind` (unchanged if the test reuses `.benchmark`)
- `TemplateStore` benchmark stamping (1109–1234)
- `HistoryView` legend (570–596)
- `ConsistencyCard` `DayMark` (no change)
- CSV `AnalysisExport.swift:678–838`: new `record` values `cf_test` / `cf_rep` with columns *appended*
  (cf_kg, w_prime_kg_s, end_force_kg, window_mean_kg, window_impulse_kg_s, on_beat); update the guide
  row and `docs/CSV_EXPORT.md`
- The Markdown analysis export (a CF trend per grip × hand) and `AnalysisExportAssembler`
- The Live Activity phase enum, and the Watch mirror if the test should run there
- **Android parity:** `SessionResult.kt`, `AnalysisExport.kt`, `TemplateStore.kt`, `DayLedger.kt`,
  `HistoryScreen.kt`, `ConsistencyCard.kt`, `ui/maxes/*`, plus a new entity
- The copy in `TRAINING_SAFEGUARDS.md`: this is a maximal test, and the papers limited it to climbers
  at about 7a sport / 6C boulder and above

## 6. Decisions (Nuri, 2026-09-25)

1. **Today gets one line**, under the routines: the latest CF, "% of max", and an amber
   "Retest due" after 42 days. It is the door to the test. Not a card.
2. **A downsampled trace is stored per test** (20 Hz, centi-kg, about 10 KB), the one
   place the app keeps raw force, so any definition can be recomputed later.
3. **Body weight is asked once**, on the first test, and afterwards changed only in
   Settings. Every test freezes its own copy.
4. **The test's hardest pull is OFFERED as a max** when it beats the exact hand's max on
   file. Never saved silently.
5. **The test is the runner's screen** (Nuri, mid-build): the trace is the screen, the
   panel is Liquid Glass with the runner's hero (live force and clock side by side, equal
   weight), the 24 pulls float in their own glass pill, and the controls are a glass
   dock. The pull in progress draws a LIVE bar, its running average, which locks at the
   bell.

6. **The hands, in the routine builder's words** (Nuri: "Both to me is like alternating"):
   - **One at a time** is the default. All 24 pulls on one hand, then all 24 on the
     other; between the hands the screen waits for the first pull, with "Finish with
     left hand only" on offer. There is one result screen with both hands (tap a hand
     to see its pulls), and one Save.
   - **Both hands** means both pulling together through one gauge, on a hangboard or a
     two-handed block.
   - **One hand** tests a single hand.
   - **No alternating each pull.** L R L R gives each hand 7 s on and 13 s off, a
     different duty cycle, and would read far above the published test.
7. **Critical force lives IN each grip's card**, beside the max it is a share of, on a
   tab renamed **Benchmarks**:
   - The card shows "Max" and "Critical force" rows, with the CF rows giving % of max per
     hand.
   - One chart carries both: max in bleu, CF in steel, hands still told apart by dash.
   - "All tests" and "Test critical force" appear only on grips already tested.
   - A testing day reads "Testing day" on Today.

## 7. As built

- **Engine** `Shared/Engine/CriticalForce.swift`, pure and tested
  (`Tests/CriticalForceTests.swift`):
  - `CriticalForceTest` is the fixed metronome, armed by the first pull over 4 kg.
  - `CriticalForceAnalysis` computes the numbers: trapezoid integrals clipped to each
    7 s window, holes over 0.25 s never interpolated, and CF as the mean of the last six
    windows (at least four with data).
  - `CriticalForceTrace` and `CriticalForceRepsCodec` are the blobs.
- **Stopping and interruptions:**
  - Stopping by hand before pull 16 voids the test; after it, the test keeps the pulls
    already run.
  - Losing the gauge or leaving the app follows the same rule. The background rule is
    the runner's `BackgroundPausePolicy`: a connected Progressor keeps the app alive, so
    the test runs on.
- **Model:** `CriticalForceRecord` is a NEW CloudKit entity. **Deploy the CloudKit schema
  to Production before shipping.**
- **Store:** the testing day is the existing `.benchmark` log (`stampBenchmarkDay`, shared
  with measured maxes), and delete comes with Undo.
- **UI:**
  - `Sources/UI/CriticalForce/` holds the test cover, the Today line, the per-grip
    history sheet with swipe-to-delete and Undo, and the result.
  - `MaxesTab` (the Benchmarks tab) draws CF inside each grip's card.
  - Settings has a new body weight card.
  - The calendar legend reads "Testing".
- **Demo:** the mock gauge switches to `MockForceProfile.allOut` while a test runs, so
  demo mode and App Review see a real plateau.
- **Export:** CSV v3 appends columns `critical_force_kg … protocol` and adds
  `record=cf_test` rows, plus `cf_pull` rows in pulls detail. The Markdown export gains
  a "Critical force tests" section. The shared fixtures were regenerated with the oracle
  (new scenario `critical-force-tests`).
- **DEBUG:** `-previewCriticalForce` opens the test from Today, and `-startCriticalForce`
  runs a whole test headlessly with `-mockDevice`. `-seedHistory` seeds two tests.
- **Not in v1:** the Watch and the Live Activity, and a CF line on the routine runner's
  trace.

## Sources

- Giles et al. 2019, *The determination of finger-flexor critical force in rock climbers*, IJSPP 14(7) — https://eprints.glos.ac.uk/6380/
- Giles et al. 2021, *An all-out test to determine finger flexor critical force in rock climbers*, IJSPP 16(7) — https://pubmed.ncbi.nlm.nih.gov/33647876/
- Baláš et al. 2024, *Measuring critical force in sport climbers: a validation study of the 4 min all-out test*, EJAP — https://pmc.ncbi.nlm.nih.gov/articles/PMC11365833/
- Boklaschuk, MacDougall & MacInnis 2026, *The validity and utility of the all-out test for forearm flexor critical force*, EJAP — doi:10.1007/s00421-026-06291-w
- McClean et al. 2023, *Test-retest reliability of a 4-minute all-out critical force test in rock climbers*, IJES — PMC10449326
- Kellawan & Tschakovsky 2014, *The single-bout forearm critical force test*, PLOS ONE — https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0093481
- Herold & Sommer 2020, *A model-based estimation of critical torques…* (sustained 28 % vs intermittent 41 %)
- Perrin et al. 2026, *Low-load BFR training improves finger flexor function in experienced climbers*, MSSE (CF +22 %)
- Javorský et al. 2023, *BFR vs high-intensity finger training…*, Front Sports Act Living — PMC10570524
- Practitioner: CAMP4 summary of Giles 2021 — https://www.camp4humanperformance.com/research/critical-force-climbing-test ; StrengthClimbing CF calculator — https://strengthclimbing.com/critical-force-calculator/
