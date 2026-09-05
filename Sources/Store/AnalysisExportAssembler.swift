// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import SwiftData

/// Models → the values `AnalysisExport` consumes, and nothing else.
///
/// It lives here rather than in `Shared/` because it is the only half of the feature that
/// has to see SwiftData; the formatter downstream of it is pure, deterministic and
/// testable precisely because this file exists.
///
/// **It never decodes a rep blob itself.** `WorkoutLog.resultsData` is write-once, and
/// History already keeps a decoded copy of every log it has drawn (`RepsCache`), so the
/// caller hands its own accessor in. Re-decoding here would put a JSON walk over the whole
/// history on a tap path that gets slower every week somebody trains — the house rule this
/// closure exists to obey.
enum AnalysisExportAssembler {

    /// - Parameters:
    ///   - logs: every session, in any order — the formatter sorts.
    ///   - maxRecords: every `MaxRecord` ever written, in any order.
    ///   - reps: the caller's cached decode, one entry per log.
    ///   - displayName: the routine's live name where it still exists, falling back to
    ///     the name frozen into the log. Same resolution History's own list uses, passed
    ///     in for the same reason as `reps`.
    ///   - today: from `DayClock`, never `Date.now` — the 8-week boundary is measured
    ///     from the day the user is living through.
    @MainActor
    static func input(logs: [WorkoutLog],
                      maxRecords: [MaxRecord],
                      reps: (WorkoutLog) -> [RepSummary],
                      displayName: (WorkoutLog) -> String,
                      today: DayStamp,
                      calendar: Calendar = .current) -> AnalysisExport.Input {
        let sessions = logs.map { log -> AnalysisExport.Session in
            let decoded = reps(log)
            return AnalysisExport.Session(
                id: log.id,
                day: log.day,
                startedAt: log.startedAt,
                routineName: displayName(log),
                kind: log.kind,
                minutes: log.sessionMinutes,
                rpe: log.grade,
                fingerStrain: log.fingerStrain,
                peakKg: log.peakKg,
                avgKg: log.avgKg,
                totalHeldSeconds: log.totalHeldSeconds,
                plannedReps: log.plannedReps,
                completedReps: log.completedReps,
                timing: timing(of: log, reps: decoded),
                reps: decoded,
                notes: log.notes,
                sessionsPerDayTarget: log.sessionsPerDayTarget,
                finishedAt: log.finishedAt, plan: log.plan)
        }

        let maxes = maxRecords.map { record in
            AnalysisExport.MaxEntry(
                grip: record.grip,
                side: record.side,
                kg: record.kg,
                day: DayStamp(date: record.recordedAt, calendar: calendar),
                recordedAt: record.recordedAt,
                source: record.source)
        }

        // Frozen per session, so the CURRENT figure is whatever the newest session was
        // logged under. Stating the newest rather than reading today's routine setting
        // keeps this file free of the routine table entirely.
        let newestTarget = logs.max(by: { $0.startedAt < $1.startedAt })?.sessionsPerDayTarget

        return AnalysisExport.Input(
            sessions: sessions,
            maxes: maxes,
            today: today,
            generatedOn: today,
            sessionsPerDayTarget: max(1, newestTarget ?? 1))
    }

    /// `WorkoutLog` carries no "was there a gauge" column, so it is INFERRED — and the
    /// inference is stated in the document's legend rather than presented as a fact the
    /// schema recorded.
    ///
    /// A session logged after the fact has no reps at all. A runner session that ran
    /// without a gauge (`SessionRunner(timerOnly:)`) records real reps and real held
    /// seconds off the wall clock, and every one of its kilogram fields is exactly zero —
    /// which is what separates it from a measured session, where at least one pull
    /// registered some load.
    @MainActor
    private static func timing(of log: WorkoutLog,
                               reps: [RepSummary]) -> AnalysisExport.Timing {
        guard log.kind == .hang, !reps.isEmpty else { return .logged }
        return reps.contains(where: { $0.peakKg > 0 }) ? .gauge : .timerOnly
    }
}
