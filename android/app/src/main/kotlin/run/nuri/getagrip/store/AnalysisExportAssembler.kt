// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.AnalysisExport
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.SessionKind
import java.time.ZoneId

/// Rows → the values `AnalysisExport` consumes, and nothing else.
///
/// It lives here rather than in `:engine` because it is the only half of the feature that
/// has to see the database rows; the formatter downstream of it is pure, deterministic
/// and testable precisely because this file exists.
///
/// **It never decodes a rep blob itself.** `WorkoutLogEntity.resultsData` is write-once,
/// and History already keeps a decoded copy of every log it has drawn, so the caller
/// hands its own accessor in. Re-decoding here would put a JSON walk over the whole
/// history on a tap path that gets slower every week somebody trains — the house rule
/// this parameter exists to obey.
object AnalysisExportAssembler {

    /// - logs: every session, in any order — the formatter sorts.
    /// - maxRecords: every `MaxRecordEntity` ever written, in any order.
    /// - reps: the caller's cached decode, one entry per log.
    /// - displayName: the routine's live name where it still exists, falling back to the
    ///   name frozen into the log. Same resolution History's own list uses, passed in for
    ///   the same reason as `reps`.
    /// - today: from `DayClock`, never `Instant.now()` — the 8-week boundary is measured
    ///   from the day the user is living through.
    fun input(
        logs: List<WorkoutLogEntity>,
        maxRecords: List<MaxRecordEntity>,
        reps: (WorkoutLogEntity) -> List<RepSummary>,
        displayName: (WorkoutLogEntity) -> String,
        today: DayStamp,
        zone: ZoneId = ZoneId.systemDefault(),
    ): AnalysisExport.Input {
        val sessions = logs.map { log ->
            val decoded = reps(log)
            AnalysisExport.Session(
                id = log.id,
                day = log.day,
                startedAt = log.startedAt,
                routineName = displayName(log),
                kind = log.kind,
                minutes = log.sessionMinutes,
                rpe = log.grade,
                fingerStrain = log.fingerStrain,
                peakKg = log.peakKg,
                avgKg = log.avgKg,
                totalHeldSeconds = log.totalHeldSeconds,
                plannedReps = log.plannedReps,
                completedReps = log.completedReps,
                timing = timing(log, decoded),
                reps = decoded,
                notes = log.notes,
                sessionsPerDayTarget = log.sessionsPerDayTarget,
                finishedAt = log.finishedAt, plan = log.plan,
            )
        }

        val maxes = maxRecords.map { record ->
            AnalysisExport.MaxEntry(
                grip = record.grip,
                side = record.side,
                kg = record.kg,
                day = DayStamp.of(record.recordedAt, zone),
                recordedAt = record.recordedAt,
                source = record.source,
            )
        }

        // Frozen per session, so the CURRENT figure is whatever the newest session was
        // logged under. Stating the newest rather than reading today's routine setting
        // keeps this file free of the routine table entirely.
        val newestTarget = logs.maxByOrNull { it.startedAt }?.sessionsPerDayTarget

        return AnalysisExport.Input(
            sessions = sessions,
            maxes = maxes,
            today = today,
            generatedOn = today,
            sessionsPerDayTarget = maxOf(1, newestTarget ?: 1),
        )
    }

    /// `WorkoutLogEntity` carries no "was there a gauge" column, so it is INFERRED — and
    /// the inference is stated in the document's legend rather than presented as a fact
    /// the schema recorded.
    ///
    /// A session logged after the fact has no reps at all. A runner session that ran
    /// without a gauge (`SessionRunner(timerOnly:)`) records real reps and real held
    /// seconds off the wall clock, and every one of its kilogram fields is exactly zero —
    /// which is what separates it from a measured session, where at least one pull
    /// registered some load.
    private fun timing(
        log: WorkoutLogEntity,
        reps: List<RepSummary>,
    ): AnalysisExport.Timing {
        if (log.kind != SessionKind.hang || reps.isEmpty()) return AnalysisExport.Timing.logged
        return if (reps.any { it.peakKg > 0 }) {
            AnalysisExport.Timing.gauge
        } else {
            AnalysisExport.Timing.timerOnly
        }
    }
}
