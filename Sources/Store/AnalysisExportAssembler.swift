// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import Foundation
import SwiftData

/// Copy model fields on their actor; decode the unbounded history on a worker.
/// Data is a value copy, so dismissing the sheet or deleting a log cannot invalidate
/// the detached export's input. No SwiftData model crosses the actor boundary.
enum AnalysisExportAssembler {
    struct SessionSnapshot: Sendable {
        let metadata: AnalysisExport.Session
        let planData: Data
        let resultsData: Data
    }

    struct Snapshot: Sendable {
        let sessions: [SessionSnapshot]
        let maxes: [AnalysisExport.MaxEntry]
        let today: DayStamp
        let sessionsPerDayTarget: Int

        func input() throws -> AnalysisExport.Input {
            var decoded: [AnalysisExport.Session] = []
            decoded.reserveCapacity(sessions.count)
            for snapshot in sessions {
                try Task.checkCancellation()
                var session = snapshot.metadata
                session.reps = BlobCodec.decodeArray(RepSummary.self, from: snapshot.resultsData)
                session.plan = BlobCodec.decode(SessionPlan.self, from: snapshot.planData)
                session.timing = session.kind == .hang && !session.reps.isEmpty
                    ? (session.reps.contains { $0.peakKg > 0 } ? .gauge : .timerOnly)
                    : .logged
                decoded.append(session)
            }
            return AnalysisExport.Input(sessions: decoded, maxes: maxes, today: today,
                generatedOn: today, sessionsPerDayTarget: sessionsPerDayTarget)
        }
    }

    @MainActor
    static func snapshot(logs: [WorkoutLog], maxRecords: [MaxRecord],
                         displayName: (WorkoutLog) -> String, today: DayStamp,
                         calendar: Calendar = .current) -> Snapshot {
        let sessions = logs.map { log in
            SessionSnapshot(metadata: AnalysisExport.Session(
                id: log.id, day: log.day, startedAt: log.startedAt,
                routineName: displayName(log), kind: log.kind, minutes: log.sessionMinutes,
                rpe: log.grade, fingerStrain: log.fingerStrain, peakKg: log.peakKg,
                avgKg: log.avgKg, totalHeldSeconds: log.totalHeldSeconds,
                plannedReps: log.plannedReps, completedReps: log.completedReps,
                notes: log.notes, sessionsPerDayTarget: log.sessionsPerDayTarget,
                finishedAt: log.finishedAt),
                planData: log.planData, resultsData: log.resultsData)
        }
        let maxes = maxRecords.map { record in
            AnalysisExport.MaxEntry(grip: record.grip, side: record.side, kg: record.kg,
                day: DayStamp(date: record.recordedAt, calendar: calendar),
                recordedAt: record.recordedAt, source: record.source)
        }
        let target = logs.max(by: { $0.startedAt < $1.startedAt })?.sessionsPerDayTarget ?? 1
        return Snapshot(sessions: sessions, maxes: maxes, today: today, sessionsPerDayTarget: max(1, target))
    }
}

/// One worker per open export. Rapid scope/detail changes queue only current work;
/// the decoded history is reused instead of walking every JSON blob on every tap.
actor AnalysisExportWorker {
    private let snapshot: AnalysisExportAssembler.Snapshot
    private var prepared: AnalysisExport.Input?

    init(snapshot: AnalysisExportAssembler.Snapshot) { self.snapshot = snapshot }

    func document(scope: AnalysisExport.CSVScope, detail: AnalysisExport.CSVDetail) throws -> AnalysisExport.CSVDocument {
        try Task.checkCancellation()
        if prepared == nil { prepared = try snapshot.input() }
        try Task.checkCancellation()
        let result = AnalysisExport.csv(prepared!, scope: scope, detail: detail)
        try Task.checkCancellation()
        return result
    }
}
