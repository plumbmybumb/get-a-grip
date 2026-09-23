// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import run.nuri.getagrip.engine.BlobCodec
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.FingerStrain
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RPE
import run.nuri.getagrip.engine.RepOutcome
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.engine.SessionPlan
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

/// One finished session, frozen.
///
/// Everything that could come from a `SessionTemplateEntity` is a SNAPSHOT: the name, the
/// plan, every rep's own `GripSpec`. Editing a routine must never rewrite history, which is
/// what makes edits cheap enough to offer without a dialog. Deleting a routine takes its
/// sessions, and the same Undo restores them (`TemplateStore.delete`, Nuri 2026-09-20).
///
/// Same schema rules as `SessionTemplateEntity`: every column defaulted or nullable, no
/// uniqueness beyond the primary key, no relationships, additive changes only.
@Entity(tableName = "WorkoutLog")
data class WorkoutLogEntity(
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val startedAt: Instant = storedNow(),
    val finishedAt: Instant = storedNow(),
    /// Epoch day FROZEN at save — the TRAINING day (turns at `DayStamp.ROLLOVER_HOUR`), so
    /// a 00:30 session stays on its evening. The cheap Int join for "2 of 2 today".
    /// Rewritten once by `TemplateStore.repairTrainingDays` for rows stamped under the old
    /// midnight rule.
    val dayKey: Int = 0,
    /// Best-effort grouping ONLY. The routine may be gone; nothing here needs it back.
    val templateID: UUID? = null,
    /// FROZEN: renaming a routine must not retro-rename the history it produced.
    val templateName: String = "",
    val planData: String = "",      // SessionPlan (already .executable), write-once
    val resultsData: String = "",   // [RepSummary], write-once
    /// What "a full day" meant when logged; changing sessions-a-day must not rescore days
    /// already lived.
    val sessionsPerDayTarget: Int = 1,
    val totalHeldSeconds: Double = 0.0,
    /// Denormalized at save so History can draw a list without decoding a blob per row.
    val peakKg: Double = 0.0,
    val avgKg: Double = 0.0,
    val completedReps: Int = 0,
    val plannedReps: Int = 0,
    /// null until the user grades the session; `RPE`'s raw value when they do.
    val rpe: Int? = null,
    /// The LOCAL strain axis; `rpe` is reused as the systemic one. null = not answered.
    val fingerStrainRaw: Int? = null,
    /// Length of a session logged BY HAND. null for runner sessions, which have a real span
    /// — see `sessionMinutes`.
    val durationMinutes: Int? = null,
    val notes: String = "",
    /// What kind of training this was — see `SessionKind`. **Defaulted to "hang"**, what
    /// every pre-climbing row means, so the migration is additive with no backfill.
    val kindRaw: String = SessionKind.hang.rawValue,
) {

    /// An unknown kind from a newer build reads as `hang` — see `SessionKind.fallback`.
    val kind: SessionKind get() = SessionKind.fallback(kindRaw)

    /// Write-once, so read-only: nothing may re-encode a session after it happened.
    val reps: List<RepSummary> get() = BlobCodec.decodeArray(resultsData) { RepSummary.fromJson(it) }

    /// null when the snapshot is missing or unreadable; History then uses the denormalized
    /// columns.
    val plan: SessionPlan? get() = BlobCodec.decode(planData) { SessionPlan.fromJson(it) }

    val day: DayStamp get() = DayStamp(dayKey)

    /// The date History shows: the TRAINING day, for every kind of row. A runner session
    /// used to show its start instant, which for small-hours sessions disagreed with the
    /// grid and tally (19th vs 20th). One date per row, the one every surface counts by.
    @Suppress("UNUSED_PARAMETER")
    fun historyDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate = day.localDate()

    /// null for an ungraded session, or for a scale value from a future build.
    val grade: RPE? get() = rpe?.let { RPE.fromRaw(it) }

    val fingerStrain: FingerStrain? get() = fingerStrainRaw?.let { FingerStrain.fromRaw(it) }

    /// One duration for every kind: a hand-entered one wins; runner sessions have a real
    /// span, so no backfill or migration.
    val sessionMinutes: Int?
        get() {
            durationMinutes?.let { if (it > 0) return it }
            val elapsed = (finishedAt.toEpochMilli() - startedAt.toEpochMilli()) / 1000.0
            if (elapsed <= 0) return null
            val minutes = (elapsed / 60).roundToInt()
            return if (minutes > 0) minutes else null
        }

    val wasCompleted: Boolean get() = plannedReps > 0 && completedReps >= plannedReps

    /// What this session asked of each grip, by CANONICAL key (the join between March and
    /// December). Folded from the frozen plan, not the reps, so the per-grip line matches
    /// the builder's formatter. First key wins on a collision: a malformed blob must not
    /// take History down.
    fun totalsByGripKey(): Map<String, PlanMath.GripTotals> {
        val frozen = plan ?: return emptyMap()
        val out = LinkedHashMap<String, PlanMath.GripTotals>()
        for (totals in PlanMath.gripTotals(frozen)) out.putIfAbsent(totals.grip.key, totals)
        return out
    }

    companion object {
        /// The twin of `WorkoutLog.init(plan:…)` and the only place the denormalized
        /// columns are derived — see `undoDeleteSession`.
        fun from(
            plan: SessionPlan,
            templateID: UUID?,
            templateName: String,
            sessionsPerDayTarget: Int,
            reps: List<RepSummary>,
            startedAt: Instant,
            finishedAt: Instant,
            day: DayStamp,
        ): WorkoutLogEntity {
            // `.executable` rather than trusting the caller: `RepSummary.setIndex` indexes
            // THIS list. Idempotent, so an already-executable plan costs nothing.
            val frozen = plan.executable
            val held = reps.sumOf { it.heldSeconds }
            return WorkoutLogEntity(
                id = UUID.randomUUID(),
                startedAt = startedAt,
                finishedAt = finishedAt,
                dayKey = day.raw,
                templateID = templateID,
                templateName = templateName,
                planData = BlobCodec.encode(frozen) ?: "",
                resultsData = BlobCodec.encodeAll(reps) ?: "",
                sessionsPerDayTarget = maxOf(1, sessionsPerDayTarget),
                totalHeldSeconds = held,
                peakKg = reps.maxOfOrNull { it.peakKg } ?: 0.0,
                // Time-weighted, not a mean of means: a rep dropped after one second must
                // not weigh like a full hang.
                avgKg = if (held > 0) reps.sumOf { it.avgKg * it.heldSeconds } / held else 0.0,
                // `.completed` only: an early release happened but did not count, and this
                // is what "the session is done" is measured against.
                completedReps = reps.count { it.outcome == RepOutcome.completed },
                plannedReps = PlanMath.totalReps(frozen),
                rpe = null,
                notes = "",
            )
        }

        /// A logged session with no plan, reps or gauge. `.benchmark` (written by
        /// `recordMax`) uses it too: this is about the row's SHAPE.
        ///
        /// The same table as a hangboard session, not a model of its own: History is one
        /// list and the grids fold one stream. The blob columns are empty, which every
        /// reader tolerates (`plan` null, `reps` []) because a corrupt snapshot had to be
        /// survivable anyway.
        fun logged(
            kind: SessionKind,
            day: DayStamp,
            at: Instant,
            sessionsPerDayTarget: Int,
            minutes: Int? = null,
            rpe: RPE? = null,
            fingerStrain: FingerStrain? = null,
            notes: String = "",
        ): WorkoutLogEntity = from(
            plan = SessionPlan(sets = emptyList()),
            templateID = null,
            templateName = kind.displayName,
            sessionsPerDayTarget = sessionsPerDayTarget,
            reps = emptyList(),
            startedAt = at,
            finishedAt = at,
            day = day,
        ).copy(
            kindRaw = kind.rawValue,
            durationMinutes = minutes,
            rpe = rpe?.rawValue,
            fingerStrainRaw = fingerStrain?.rawValue,
            notes = notes,
        )
    }
}

// MARK: - Folds over a collection of logs

/// The climb logged on `day` — **hardest first**, so a limit session names the day even if
/// an easy evening followed.
///
/// ONE implementation for Today's strip and History's 5-week grid; two copies would
/// eventually draw two calendars from the same rows.
fun Collection<WorkoutLogEntity>.climb(on: DayStamp): SessionKind? {
    val kinds = filter { it.dayKey == on.raw && it.kind.isClimb }.map { it.kind }
    return if (kinds.contains(SessionKind.climbLimit)) SessionKind.climbLimit else kinds.firstOrNull()
}

/// Whether anything on `day` SETTLES it — a climb or a benchmark. Grid and tally fill on
/// this; the notch and gym copy key on `climb(on:)`, since a benchmark day is not a
/// climbing day.
fun Collection<WorkoutLogEntity>.settled(on: DayStamp): Boolean =
    any { it.dayKey == on.raw && it.kind.settlesDay }

/// The benchmark logged on `day`, if any — at most one exists by construction
/// (`TemplateStore.recordMax` upserts).
fun Collection<WorkoutLogEntity>.benchmark(on: DayStamp): Boolean =
    any { it.dayKey == on.raw && it.kind == SessionKind.benchmark }

/// A lifetime of sessions — see `lifetime` and History's `LifetimeCard`. Hang sessions (run
/// or hand-logged) count as sessions; a climb is a gym day; a benchmark day is a day
/// trained and nothing else.
data class LifetimeStats(
    val sessions: Int = 0,
    /// Completed pulls only — a skipped pull is a pull that did not happen.
    val pulls: Int = 0,
    /// Every second on the edge, across every completed or partial hold.
    val heldSeconds: Double = 0.0,
    /// Load × pulls, summed — the number a lifter calls volume.
    val volumeKg: Double = 0.0,
    /// Distinct days with a climb logged — two climbs on one day are one day at the gym.
    val climbDays: Int = 0,
    /// Distinct training days with anything on them, climbs and benchmarks included.
    val daysTrained: Int = 0,
    val heaviestPullKg: Double = 0.0,
    /// The earliest training day on record.
    val since: DayStamp? = null,
) {
    val isEmpty: Boolean get() = sessions == 0 && climbDays == 0 && daysTrained == 0
}

/// The all-time tally in History (Nuri, 2026-09-20). Folded from the DENORMALIZED columns
/// only, never the rep blobs, so it is cheap enough to recompute on every feed refresh.
val Collection<WorkoutLogEntity>.lifetime: LifetimeStats
    get() {
        var sessions = 0
        var pulls = 0
        var held = 0.0
        var volume = 0.0
        var heaviest = 0.0
        var since: DayStamp? = null
        val days = HashSet<Int>()
        val climbDays = HashSet<Int>()
        for (log in this) {
            days.add(log.dayKey)
            since = since?.let { minOf(it, log.day) } ?: log.day
            when (log.kind) {
                SessionKind.hang, SessionKind.hangManual -> {
                    sessions += 1
                    pulls += log.completedReps
                    held += log.totalHeldSeconds
                    // Load × reps from the time-weighted mean and completed count; exact
                    // when every completed hold ran its full length.
                    volume += log.avgKg * log.completedReps
                    heaviest = maxOf(heaviest, log.peakKg)
                }
                SessionKind.climbVolume, SessionKind.climbLimit -> climbDays.add(log.dayKey)
                SessionKind.benchmark -> Unit
            }
        }
        return LifetimeStats(
            sessions = sessions, pulls = pulls, heldSeconds = held, volumeKg = volume,
            climbDays = climbDays.size, daysTrained = days.size, heaviestPullKg = heaviest,
            since = since,
        )
    }
