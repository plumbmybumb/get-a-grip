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
/// Everything here that could have come from a `SessionTemplateEntity` is a SNAPSHOT
/// instead: the name, the plan, and every rep's own `GripSpec`. Editing a routine must
/// never rewrite history — which is what makes edits cheap enough to offer behind a swipe
/// and an undo bar rather than a dialog. Deleting one takes its sessions with it, and the
/// same Undo puts them back (`TemplateStore.delete`, Nuri 2026-09-20).
///
/// Same schema rules as `SessionTemplateEntity`: every column defaulted or nullable, no
/// uniqueness beyond the primary key, no relationships, additive changes only.
@Entity(tableName = "WorkoutLog")
data class WorkoutLogEntity(
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val startedAt: Instant = storedNow(),
    val finishedAt: Instant = storedNow(),
    /// Epoch day FROZEN at save — the TRAINING day, which turns at
    /// `DayStamp.ROLLOVER_HOUR`, so a 00:30 session stays on the evening it belonged to.
    /// It is the join for "2 of 2 today": a cheap Int predicate rather than a calendar
    /// pass over every log. Rewritten once, by `TemplateStore.repairTrainingDays`, for
    /// rows stamped by the clock that used to turn at midnight.
    val dayKey: Int = 0,
    /// Best-effort grouping ONLY. The routine may be gone; nothing here needs it back.
    val templateID: UUID? = null,
    /// FROZEN: renaming a routine must not retro-rename the history it produced.
    val templateName: String = "",
    val planData: String = "",      // SessionPlan (already .executable), write-once
    val resultsData: String = "",   // [RepSummary], write-once
    /// What "a full day" meant when this was logged — a later change to sessions-a-day
    /// must not re-score days already lived.
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
    /// Wall-clock length of a session logged BY HAND. null for runner sessions, which
    /// carry a real `startedAt`/`finishedAt` span instead — see `sessionMinutes`.
    val durationMinutes: Int? = null,
    val notes: String = "",
    /// What kind of training this was — see `SessionKind`. **Defaulted to "hang"**,
    /// which is exactly what every row written before climbing existed means, so the
    /// migration is additive with no backfill.
    val kindRaw: String = SessionKind.hang.rawValue,
) {

    /// An unknown kind from a newer build reads as `hang` — see `SessionKind.fallback`.
    val kind: SessionKind get() = SessionKind.fallback(kindRaw)

    /// Write-once, so read-only: nothing may re-encode a session after it happened.
    val reps: List<RepSummary> get() = BlobCodec.decodeArray(resultsData) { RepSummary.fromJson(it) }

    /// null when the snapshot is missing or unreadable. History then falls back to the
    /// denormalized columns, which is why they exist.
    val plan: SessionPlan? get() = BlobCodec.decode(planData) { SessionPlan.fromJson(it) }

    val day: DayStamp get() = DayStamp(dayKey)

    /// The date History shows for this row: its TRAINING day, for every kind of row. A
    /// hand log records when the entry was created, so its chosen day was always the one
    /// to show; a runner session used to show its start instant, which is a different
    /// calendar day from the training day for anything begun in the small hours — the
    /// row said the 19th while the grid and the tally credited the 20th. One date per row,
    /// the same one every other surface counts by.
    @Suppress("UNUSED_PARAMETER")
    fun historyDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate = day.localDate()

    /// null for an ungraded session, or for a scale value from a future build.
    val grade: RPE? get() = rpe?.let { RPE.fromRaw(it) }

    val fingerStrain: FingerStrain? get() = fingerStrainRaw?.let { FingerStrain.fromRaw(it) }

    /// One duration for every kind of session. A hand-entered duration wins; runner
    /// sessions already have a real span, so their existing rows gain a duration
    /// without a backfill or a migration.
    val sessionMinutes: Int?
        get() {
            durationMinutes?.let { if (it > 0) return it }
            val elapsed = (finishedAt.toEpochMilli() - startedAt.toEpochMilli()) / 1000.0
            if (elapsed <= 0) return null
            val minutes = (elapsed / 60).roundToInt()
            return if (minutes > 0) minutes else null
        }

    val wasCompleted: Boolean get() = plannedReps > 0 && completedReps >= plannedReps

    /// What this session asked of each grip, keyed by the CANONICAL key — the join
    /// between a pull made in March and one made in December.
    ///
    /// Folded out of the frozen plan rather than counted off the reps, so the per-grip
    /// line reads identically here and in the builder: one formatter, one set of totals.
    /// First key wins on a collision, because a trap here would take History down for
    /// one malformed blob.
    fun totalsByGripKey(): Map<String, PlanMath.GripTotals> {
        val frozen = plan ?: return emptyMap()
        val out = LinkedHashMap<String, PlanMath.GripTotals>()
        for (totals in PlanMath.gripTotals(frozen)) out.putIfAbsent(totals.grip.key, totals)
        return out
    }

    companion object {
        /// The twin of `WorkoutLog.init(plan:…)`, and the only place the denormalized
        /// columns are derived. Nothing else may recompute them — see `undoDeleteSession`.
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
            // `.executable` here rather than trusting the caller: `RepSummary.setIndex`
            // indexes THIS list, so the invariant has to be true by construction. It is
            // idempotent, so a runner that already froze the executable plan pays nothing.
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
                // Time-weighted, not a mean of means: a rep that dropped off after one
                // second would otherwise weigh as much as a full ten-second hang.
                avgKg = if (held > 0) reps.sumOf { it.avgKg * it.heldSeconds } / held else 0.0,
                // `.completed` only. An early release is a pull that happened, not a pull
                // that counted, and this number is what "the session is done" is measured
                // against.
                completedReps = reps.count { it.outcome == RepOutcome.completed },
                plannedReps = PlanMath.totalReps(frozen),
                rpe = null,
                notes = "",
            )
        }

        /// A logged session with no plan, no reps and no gauge behind it. `.benchmark`
        /// uses the same builder even though it is written by `recordMax`, so this is
        /// about the SHAPE of the row rather than who is allowed to create it.
        ///
        /// Deliberately the same table as a hangboard session rather than a model of its
        /// own: it is a session that happened, History is one list, and the consistency
        /// grids fold over one stream. The blob columns are simply empty, which every
        /// reader already tolerates (`plan` returns null, `reps` returns []) because a
        /// corrupt snapshot had to be survivable anyway.
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

/// The climb logged on `day` — **hardest first**, so a limit session is what a day is
/// remembered by even when an easy evening followed it.
///
/// ONE implementation. The store folds it for Today's strip and History folds its own
/// query for the 5-week grid, and two hand-written copies of this rule would eventually
/// draw two different calendars from the same rows.
fun Collection<WorkoutLogEntity>.climb(on: DayStamp): SessionKind? {
    val kinds = filter { it.dayKey == on.raw && it.kind.isClimb }.map { it.kind }
    return if (kinds.contains(SessionKind.climbLimit)) SessionKind.climbLimit else kinds.firstOrNull()
}

/// Whether anything logged on `day` SETTLES it — a climb or a benchmark. The grid and the
/// tally fill on this; the notch and the gym copy still key on `climb(on:)`, because a
/// benchmark is a full day but not a climbing day.
fun Collection<WorkoutLogEntity>.settled(on: DayStamp): Boolean =
    any { it.dayKey == on.raw && it.kind.settlesDay }

/// The benchmark logged on `day`, if any — at most one exists by construction
/// (`TemplateStore.recordMax` upserts).
fun Collection<WorkoutLogEntity>.benchmark(on: DayStamp): Boolean =
    any { it.dayKey == on.raw && it.kind == SessionKind.benchmark }

/// What a lifetime of sessions adds up to — see `Collection<WorkoutLogEntity>.lifetime` and
/// History's `LifetimeCard`. Hangboard sessions the app ran or that were logged by hand
/// count as sessions; a climb is a day at the gym; a benchmark day is a day trained and
/// nothing else.
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

/// The all-time tally in History (Nuri, 2026-09-20: "lifetime stats"). Folded from the
/// DENORMALIZED columns only — never from the rep blobs — so it costs a row per session,
/// not a decode, and can be recomputed on every refresh of the feed.
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
                    // The lifting convention — load × reps, added up — from the session's
                    // time-weighted mean and its completed count. Exact when every hold in
                    // a session ran its full length, which is what a completed pull means.
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
