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

/// Headless UI verification: `adb` cannot tap through a five-step builder, so the states
/// worth screenshotting are reachable by launch extra.
///
/// ```
/// adb shell am start -n run.nuri.getagrip/.MainActivity --ez seedTwoRoutines true --ez seedHistory true
/// ```
///
/// **This is the ONLY thing in the app that inserts a routine without a user asking**, and
/// it stays behind `BuildConfig.DEBUG` and behind an explicit extra for the same reason it
/// does on iOS: a silent seed at launch leaves routines that read as a sync bug.
///
/// TRANSLATION NOTE (`DoigtApp.applyLaunchSeeding`): iOS seeds BEFORE the store is built,
/// so `TemplateStore.init`'s first `syncDerived()` already sees the seeded world. Android's
/// store has no synchronous init to race — the Application seeds and then calls the first
/// `syncDerived()` itself, in that order, which is the same guarantee.
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

        // Mutually exclusive, and "no routines" wins: a run that asks for the empty
        // first-run state must never get a seeded one because both extras were passed.
        if (wants("seedNoRoutines")) {
            db.routines().deleteAll()
        } else if (wants("seedRoutine")) {
            db.routines().deleteAll()
            db.routines().upsert(SessionTemplateEntity.from(RoutineDraft.starter.normalized, 0))
        } else if (wants("seedTwoRoutines")) {
            db.routines().deleteAll()
            seedTwoRoutines(db)
        }

        // History has nothing to draw until sessions exist, and driving three weeks of
        // them by hand is not verification, it is typing.
        if (wants("seedHistory")) {
            db.logs().deleteAll()
            db.maxes().deleteAll()
            seedHistory(db)
            seedMaxes(db)
        }
    }

    /// The deck state: the ritual plus a max-day routine beside it — the same pair
    /// `seedHistory` logs sessions for, so the two extras together give a coherent world.
    private suspend fun seedTwoRoutines(db: GetAGripDatabase) {
        // A percent band on the daily, so recording a max demonstrates the "targets that
        // followed" half of the impact receipt…
        var daily = RoutineDraft.starter
        daily = daily.copy(
            plan = daily.plan.copy(targetLoPercent = 0.18, targetHiPercent = 0.22)
        )
        // The taper Nuri actually trains — the last crimp sets drop to 10 mm — so the demo
        // also exercises the edge SPAN ("20–10 mm") and the compact glyphed stat row the
        // span forces, not just the single-edge case.
        if (daily.plan.sets.size >= 2) {
            val sets = daily.plan.sets.toMutableList()
            for (index in sets.size - 2 until sets.size) {
                sets[index] = sets[index].copy(grip = sets[index].grip.withEdgeMM(10))
            }
            daily = daily.copy(plan = daily.plan.copy(sets = sets))
        }
        db.routines().upsert(SessionTemplateEntity.from(daily.normalized, 0))

        // The C4 ladder as a WHENEVER routine — never owed, never reminded — with one
        // TYPED kilogram band swapped onto a ramp set so the same seed also demonstrates
        // the scale-with-the-new-max offer.
        var maxDay = RoutineDraft.maxDay
        val maxSets = maxDay.plan.sets.toMutableList()
        maxSets[1] = maxSets[1].copy(
            targetLoPercent = null, targetHiPercent = null,
            targetLoKg = 25.0, targetHiKg = 30.0,
        )
        maxDay = maxDay.copy(plan = maxDay.plan.copy(name = "Max pulls", sets = maxSets))
        db.routines().upsert(SessionTemplateEntity.from(maxDay.normalized, 1))
    }

    /// Eight weeks of plausible sessions: mostly twice a day, a few single days, a rest
    /// day each week, and a load that drifts upward slowly — enough to exercise the month
    /// grid, the trend line and the "holding steady" copy without pretending to be real
    /// data.
    private suspend fun seedHistory(db: GetAGripDatabase) {
        val plan = RoutineDraft.starter.normalized.plan.executable
        val slots = PlanMath.sequence(plan)
        val today = DayStamp.today()
        // ATTACHED to the routines seeded a moment ago, by name. They used to be written
        // with a null `templateID`, which quietly made the seeded world incoherent in
        // three ways at once: Today's "0 of 2" counts completions BY ID and so could never
        // move, History's trend deck fell back to grouping by name and never exercised the
        // id path at all, and a rename could not be seen to propagate because there was no
        // routine for a log to resolve against. Anything unmatched stays null, which is
        // still a state worth having — it is what a deleted routine leaves.
        val ids = idsByName(db)

        // 55 days, not 35: the month grid pages by 5-week windows, and a seed that fits
        // inside one window could never demonstrate the swipe.
        for (daysAgo in 55 downTo 0) {
            val day = today - daysAgo
            if (daysAgo % 7 == 3) continue                  // a rest day each week
            val sessions = if (daysAgo % 5 == 1) 1 else 2   // some days only once
            for (session in 0 until sessions) {
                // A slow upward drift plus a little day-to-day variation, so the trend has
                // a direction without looking like a straight line.
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

        // A SECOND routine on the SAME grip at max intensity, every fourth day. This is
        // the exact case the per-routine trend scope exists for: before the scope, these
        // 30 kg sessions averaged into the 20 kg dailies and the "trend" was a zigzag
        // tracking which routine ran, not how strong the fingers were getting.
        val maxPlan = SessionPlan(
            name = "Max pulls",
            sets = listOf(SetPlan(repsPerSide = 3)),
            handMode = HandMode.alternateEachRep,
            holdSeconds = 5,
            restSeconds = 90,
            // The prescription its name claims — and what makes the demo show the rung's
            // intensity ladder: a near-max band paints this card's mark alarm red while
            // the untargeted daily stays bleu.
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

    /// The routines just seeded, by name, so the logs above can point at them. Names are
    /// unique within a seed by construction; a real store cannot promise that, but this
    /// runs only behind a launch extra on a store the seed itself just wrote.
    private suspend fun idsByName(db: GetAGripDatabase): Map<String, UUID> {
        val out = HashMap<String, UUID>()
        for (template in db.routines().all()) out.putIfAbsent(template.name, template.id)
        return out
    }

    /// The Maxes tab's world: a both-hands curve on the main grip, a hands-split pair on
    /// a second, and the benchmark day the newest test landed on. The newest record is
    /// exactly four weeks old, which is the soft nudge's threshold — so a seeded launch
    /// demonstrates the pulsing icon too.
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

        // The day the newest test landed reads as a benchmark day — grid filled, History
        // row present — exactly what `recordMax` would have written live.
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
