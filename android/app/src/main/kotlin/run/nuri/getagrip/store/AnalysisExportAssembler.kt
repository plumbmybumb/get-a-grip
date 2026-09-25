// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import run.nuri.getagrip.data.CriticalForceRecordEntity
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.AnalysisExport
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.SessionKind
import java.time.ZoneId

/// Rows → the values `AnalysisExport` consumes, and nothing else. Here rather than
/// `:engine` because it is the only half that sees database rows, which keeps the formatter
/// pure.
///
/// **It never decodes a rep blob itself**: the caller hands in History's cached decode,
/// since re-decoding would put a whole-history JSON walk on a tap path that slows every
/// week somebody trains.
object AnalysisExportAssembler {

    /// - logs: every session, any order — the formatter sorts.
    /// - maxRecords: every `MaxRecordEntity` ever written, any order.
    /// - reps: the caller's cached decode, one entry per log.
    /// - displayName: the live routine name, falling back to the frozen one (History's
    ///   resolution; passed in like `reps`).
    /// - today: from `DayClock`, never `Instant.now()` — the 8-week boundary is from the
    ///   day being lived.
    fun input(
        logs: List<WorkoutLogEntity>,
        maxRecords: List<MaxRecordEntity>,
        criticalForceRecords: List<CriticalForceRecordEntity> = emptyList(),
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

        val tests = criticalForceRecords.map { record ->
            AnalysisExport.CriticalForceEntry(
                id = record.id, grip = record.grip, side = record.side,
                day = DayStamp.of(record.recordedAt, zone),
                recordedAt = record.recordedAt, protocolKey = record.protocolKey,
                criticalForceKg = record.criticalForceKg, wPrimeKgS = record.wPrimeKgS,
                peakKg = record.peakKg, endForceKg = record.endForceKg, repsRun = record.repsRun,
                restsKept = record.restsKept, restsTotal = record.restsTotal,
                bodyMassKg = record.bodyMassKg, maxAtTestKg = record.maxAtTestKg,
                reps = record.reps,
            )
        }

        // Frozen per session, so the CURRENT figure is the newest session's, keeping this
        // file free of the routine table.
        val newestTarget = logs.maxByOrNull { it.startedAt }?.sessionsPerDayTarget

        return AnalysisExport.Input(
            sessions = sessions,
            maxes = maxes,
            criticalForceTests = tests,
            today = today,
            generatedOn = today,
            sessionsPerDayTarget = maxOf(1, newestTarget ?: 1),
        )
    }

    /// No column records "was there a gauge", so it is INFERRED, and the document's legend
    /// says so.
    ///
    /// A hand-logged session has no reps. A gauge-free runner session
    /// (`SessionRunner(timerOnly:)`) has real reps and held seconds but every kilogram
    /// field exactly zero, unlike a measured session, where some pull registered load.
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
