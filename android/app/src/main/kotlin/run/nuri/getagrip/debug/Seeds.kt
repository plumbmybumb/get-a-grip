// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.debug

import android.content.Intent
import run.nuri.getagrip.BuildConfig
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.Side
import java.util.UUID

/// Headless UI verification: `adb` cannot tap through a five-step builder, so
/// screenshot-worthy states are reachable by launch extra.
///
/// ```
/// adb shell am start -n run.nuri.getagrip/.MainActivity --ez seedTwoRoutines true --ez
/// seedHistory true
/// ```
///
/// **The ONLY thing that inserts a routine without a user asking**, so it stays behind
/// `BuildConfig.DEBUG` and an explicit extra: a silent seed reads as a sync bug.
///
/// TRANSLATION NOTE (`DoigtApp.applyLaunchSeeding`): iOS seeds BEFORE building the store so
/// its first `syncDerived()` sees the seed. Here the Application seeds, then calls the
/// first `syncDerived()` itself — the same guarantee.
object Seeds {

    fun requested(intent: Intent?): Boolean {
        if (!BuildConfig.DEBUG || intent == null) return false
        return flags.any { intent.getBooleanExtra(it, false) }
    }

    private val flags = listOf(
        "seedNoRoutines", "seedRoutine", "seedTwoRoutines", "seedHistory",
    )

    suspend fun apply(db: GetAGripDatabase, intent: Intent?) {
        if (!requested(intent)) return
        val wants = { name: String -> intent?.getBooleanExtra(name, false) == true }

        // Mutually exclusive, and "no routines" wins: the empty first-run state must never
        // get a seed.
        if (wants("seedNoRoutines")) {
            db.routines().deleteAll()
        } else if (wants("seedRoutine")) {
            db.routines().deleteAll()
            db.routines().upsert(SessionTemplateEntity.from(RoutineDraft.starter.normalized, 0))
        } else if (wants("seedTwoRoutines")) {
            db.routines().deleteAll()
            seedTwoRoutines(db)
        }

        // History has nothing to draw without sessions, and typing three weeks of them is
        // not verification.
        if (wants("seedHistory")) {
            db.logs().deleteAll()
            db.maxes().deleteAll()
            seedHistory(db)
            seedMaxes(db)
        }
    }

    /// The ritual plus a max-day routine — the pair `seedHistory` logs for, so the two
    /// extras make a coherent world.
    private suspend fun seedTwoRoutines(db: GetAGripDatabase) {
        // A percent band on the daily, so recording a max shows the "targets that followed"
        // half of the receipt…
        var daily = RoutineDraft.starter
        daily = daily.copy(
            plan = daily.plan.copy(targetLoPercent = 0.18, targetHiPercent = 0.22)
        )
        // The taper Nuri trains (last crimp sets at 10 mm), exercising the edge SPAN
        // ("20–10 mm") and its compact stat row.
        if (daily.plan.sets.size >= 2) {
            val sets = daily.plan.sets.toMutableList()
            for (index in sets.size - 2 until sets.size) {
                sets[index] = sets[index].copy(grip = sets[index].grip.withEdgeMM(10))
            }
            daily = daily.copy(plan = daily.plan.copy(sets = sets))
        }
        db.routines().upsert(SessionTemplateEntity.from(daily.normalized, 0))

        // The C4 ladder as a WHENEVER routine, with one TYPED kg band on a ramp set to
        // demonstrate the scale-with-new-max offer.
        var maxDay = RoutineDraft.maxDay
        val maxSets = maxDay.plan.sets.toMutableList()
        maxSets[1] = maxSets[1].copy(
            targetLoPercent = null, targetHiPercent = null,
            targetLoKg = 25.0, targetHiKg = 30.0,
        )
        maxDay = maxDay.copy(plan = maxDay.plan.copy(name = "Max pulls", sets = maxSets))
        db.routines().upsert(SessionTemplateEntity.from(maxDay.normalized, 1))
    }

    /// Eight weeks of plausible sessions (mostly twice a day, a weekly rest day, slowly
    /// rising load) — enough for the month grid, trend line and "holding steady" copy.
    private suspend fun seedHistory(db: GetAGripDatabase) {
        val plan = RoutineDraft.starter.normalized.plan.executable
        val slots = PlanMath.sequence(plan)
        val today = DayStamp.today()
        // ATTACHED to the seeded routines by name. With a null `templateID`, Today's "0 of
        // 2" (counted BY ID) could never move, History's trend deck never exercised the id
        // path, and renames could not propagate. Unmatched stays null — what a deleted
        // routine leaves.
        val ids = idsByName(db)

        // 55 days, not 35: the month grid pages in 5-week windows, and the seed must
        // demonstrate the swipe.
        for (daysAgo in 55 downTo 0) {
            val day = today - daysAgo
            if (daysAgo % 7 == 3) continue                  // a rest day each week
            val sessions = if (daysAgo % 5 == 1) 1 else 2   // some days only once
            for (session in 0 until sessions) {
                // Slow upward drift plus daily variation: a direction, not a straight line.
                val drift = (20 - daysAgo) * 0.08
                val wobble = ((daysAgo * 7 + session * 3) % 5) * 0.2
                val reps = slots.map { slot ->
                    RepSummary(
                        setIndex = slot.setIndex, repIndex = slot.repIndex,
                        side = slot.side, grip = slot.grip,
                        targetSeconds = slot.holdSeconds,
                        heldSeconds = slot.holdSeconds.toDouble(),
                        peakKg = 21 + drift + wobble,
                        avgKg = 19.5 + drift + wobble,
                    )
                }
                val started = day.startOfDay().toInstant()
                    .plusSeconds(if (session == 0) 8 * 3600L else 19 * 3600L)
                db.logs().upsert(
                    WorkoutLogEntity.from(
                        plan = plan, templateID = ids[plan.name], templateName = plan.name,
                        sessionsPerDayTarget = 2, reps = reps,
                        startedAt = started,
                        finishedAt = started.plusSeconds(PlanMath.totalSeconds(plan).toLong()),
                        day = day,
                    )
                )
            }
        }

        // A SECOND routine on the SAME grip at max intensity, every fourth day — the case
        // the per-routine trend scope exists for: without it these 30 kg sessions averaged
        // into the 20 kg dailies as a zigzag.
        val maxPlan = SessionPlan(
            name = "Max pulls",
            sets = listOf(SetPlan(repsPerSide = 3)),
            handMode = HandMode.alternateEachRep,
            holdSeconds = 5,
            restSeconds = 90,
            // The prescription its name claims, so the demo shows the intensity ladder: a
            // near-max band paints this card's mark red while the untargeted daily stays
            // bleu.
            targetLoPercent = 0.85,
            targetHiPercent = 1.0,
        )
        val maxSlots = PlanMath.sequence(maxPlan.executable)
        for (daysAgo in 19 downTo 1 step 4) {
            val day = today - daysAgo
            val drift = (20 - daysAgo) * 0.1
            val reps = maxSlots.map { slot ->
                RepSummary(
                    setIndex = slot.setIndex, repIndex = slot.repIndex,
                    side = slot.side, grip = slot.grip,
                    targetSeconds = slot.holdSeconds,
                    heldSeconds = slot.holdSeconds.toDouble(),
                    peakKg = 31.5 + drift,
                    avgKg = 29.8 + drift,
                )
            }
            val started = day.startOfDay().toInstant().plusSeconds(13 * 3600L)
            db.logs().upsert(
                WorkoutLogEntity.from(
                    plan = maxPlan, templateID = ids[maxPlan.name], templateName = maxPlan.name,
                    sessionsPerDayTarget = 2, reps = reps,
                    startedAt = started,
                    finishedAt = started.plusSeconds(PlanMath.totalSeconds(maxPlan).toLong()),
                    day = day,
                )
            )
        }
    }

    /// The routines just seeded, by name. Names are unique within a seed by construction
    /// (not in a real store, but this runs only on a store the seed just wrote).
    private suspend fun idsByName(db: GetAGripDatabase): Map<String, UUID> {
        val out = HashMap<String, UUID>()
        for (template in db.routines().all()) out.putIfAbsent(template.name, template.id)
        return out
    }

    /// The Maxes tab's world: a both-hands curve, a hands-split pair on a second grip, and
    /// the benchmark day of the newest test — exactly four weeks old, the soft nudge's
    /// threshold, so the pulsing icon shows too.
    private suspend fun seedMaxes(db: GetAGripDatabase) {
        val plan = RoutineDraft.starter.normalized.plan.executable
        val slots = PlanMath.sequence(plan)
        val today = DayStamp.today()
        val mainGrip = slots.firstOrNull()?.grip ?: GripSpec()

        listOf(24.0, 26.0, 27.5, 29.0, 30.5).forEachIndexed { index, kg ->
            val day = today - (56 - index * 7)
            db.maxes().upsert(
                MaxRecordEntity.from(
                    grip = mainGrip, kg = kg, source = MaxSource.measured, side = Side.both,
                    recordedAt = day.startOfDay().toInstant().plusSeconds(10 * 3600L),
                )
            )
        }

        val split = slots.map { it.grip }.firstOrNull { it.key != mainGrip.key }
        if (split != null) {
            listOf(19.0 to 21.0, 20.5 to 21.8).forEachIndexed { index, pair ->
                val day = today - (49 - index * 21)
                val at = day.startOfDay().toInstant().plusSeconds(10 * 3600L)
                db.maxes().upsert(
                    MaxRecordEntity.from(split, pair.first, MaxSource.measured, Side.left, at)
                )
                db.maxes().upsert(
                    MaxRecordEntity.from(
                        split, pair.second, MaxSource.measured, Side.right, at.plusSeconds(600),
                    )
                )
            }
        }

        // The newest test's day reads as a benchmark day, as `recordMax` would write live.
        val benchmarkDay = today - 28
        db.logs().upsert(
            WorkoutLogEntity.logged(
                kind = SessionKind.benchmark,
                day = benchmarkDay,
                at = benchmarkDay.startOfDay().toInstant().plusSeconds(10 * 3600L),
                sessionsPerDayTarget = 2,
            )
        )
    }
}
