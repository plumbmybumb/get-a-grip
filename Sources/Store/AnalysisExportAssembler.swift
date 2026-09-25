// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import Foundation
import SwiftData

/// Copy model fields on the context that owns them; decode the unbounded history on a
/// worker. Data is a value copy, so dismissing the sheet or deleting a log cannot
/// invalidate the detached export's input. No SwiftData model crosses an actor boundary:
/// the worker resolves a `Source` on a context of its own, and only values leave it.
enum AnalysisExportAssembler {
    struct SessionSnapshot: Sendable {
        let metadata: AnalysisExport.Session
        let planData: Data
        let resultsData: Data
    }

    struct Snapshot: Sendable {
        let sessions: [SessionSnapshot]
        let maxes: [AnalysisExport.MaxEntry]
        var criticalForceTests: [AnalysisExport.CriticalForceEntry] = []
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
            return AnalysisExport.Input(sessions: decoded, maxes: maxes,
                criticalForceTests: criticalForceTests, today: today,
                generatedOn: today, sessionsPerDayTarget: sessionsPerDayTarget)
        }
    }

    /// What an export covers, frozen at the tap: an address and a few small values, no
    /// model and no blob. Building a `Snapshot` read both blob columns of EVERY log on
    /// the main actor before the sheet could even open — a toolbar button that got
    /// slower every week of training. The worker resolves this instead, on its own
    /// context, while the sheet is already on screen.
    struct Source: Sendable {
        let container: ModelContainer
        /// One session, or nil for the whole history.
        let workoutID: UUID?
        /// The routines' live names at the tap, so a renamed routine exports under the
        /// name History shows — `WorkoutLog.displayName(in:)`.
        let routineNames: [UUID: String]
        let today: DayStamp
    }

    /// Fetches on a context of its own, so it is safe off the main actor: the models it
    /// reads never leave this call, and only values come out.
    static func snapshot(from source: Source, calendar: Calendar = .current) throws -> Snapshot {
        let context = ModelContext(source.container)
        var logs = FetchDescriptor<WorkoutLog>(sortBy: [SortDescriptor(\.startedAt, order: .reverse)])
        if let id = source.workoutID {
            logs.predicate = #Predicate<WorkoutLog> { $0.id == id }
            logs.fetchLimit = 1
        }
        let fetchedLogs = try context.fetch(logs)
        try Task.checkCancellation()
        let fetchedMaxes = try context.fetch(
            FetchDescriptor<MaxRecord>(sortBy: [SortDescriptor(\.recordedAt)]))
        let sessions = fetchedLogs.map { log in
            SessionSnapshot(metadata: AnalysisExport.Session(
                id: log.id, day: log.day, startedAt: log.startedAt,
                routineName: log.displayName(in: source.routineNames), kind: log.kind,
                minutes: log.sessionMinutes,
                rpe: log.grade, fingerStrain: log.fingerStrain, peakKg: log.peakKg,
                avgKg: log.avgKg, totalHeldSeconds: log.totalHeldSeconds,
                plannedReps: log.plannedReps, completedReps: log.completedReps,
                notes: log.notes, sessionsPerDayTarget: log.sessionsPerDayTarget,
                finishedAt: log.finishedAt),
                planData: log.planData, resultsData: log.resultsData)
        }
        let maxes = fetchedMaxes.map { record in
            AnalysisExport.MaxEntry(grip: record.grip, side: record.side, kg: record.kg,
                day: DayStamp(date: record.recordedAt, calendar: calendar),
                recordedAt: record.recordedAt, source: record.source)
        }
        // A single workout's export carries no tests; skip the fetch rather than filter.
        let tests = source.workoutID != nil ? [] : try context.fetch(
            FetchDescriptor<CriticalForceRecord>(sortBy: [SortDescriptor(\.recordedAt)])).map { record in
            AnalysisExport.CriticalForceEntry(
                id: record.id, grip: record.grip, side: record.side,
                day: DayStamp(date: record.recordedAt, calendar: calendar),
                recordedAt: record.recordedAt, protocolKey: record.protocolKey,
                criticalForceKg: record.criticalForceKg, wPrimeKgS: record.wPrimeKgS,
                peakKg: record.peakKg, endForceKg: record.endForceKg, repsRun: record.repsRun,
                restsKept: record.restsKept, restsTotal: record.restsTotal,
                bodyMassKg: record.bodyMassKg, maxAtTestKg: record.maxAtTestKg,
                reps: record.reps)
        }
        let target = fetchedLogs.max(by: { $0.startedAt < $1.startedAt })?.sessionsPerDayTarget ?? 1
        return Snapshot(sessions: sessions, maxes: maxes, criticalForceTests: tests,
                        today: source.today, sessionsPerDayTarget: max(1, target))
    }
}

/// One worker per open export. Rapid scope/detail changes queue only current work;
/// the decoded history is reused instead of walking every JSON blob on every tap.
actor AnalysisExportWorker {
    private let source: AnalysisExportAssembler.Source
    private var fetched: AnalysisExportAssembler.Snapshot?
    private var prepared: AnalysisExport.Input?

    /// The fetch and the blob copies happen HERE, the first time a document is asked
    /// for, never on the tap that opened the sheet.
    init(source: AnalysisExportAssembler.Source) { self.source = source }

    func document(scope: AnalysisExport.CSVScope, detail: AnalysisExport.CSVDetail) throws -> AnalysisExport.CSVDocument {
        try Task.checkCancellation()
        if prepared == nil {
            // Kept once fetched, so a selection change that cancels the decode below
            // does not pay for the fetch a second time.
            let snapshot = try fetched ?? AnalysisExportAssembler.snapshot(from: source)
            fetched = snapshot
            try Task.checkCancellation()
            prepared = try snapshot.input()
        }
        try Task.checkCancellation()
        let result = AnalysisExport.csv(prepared!, scope: scope, detail: detail)
        try Task.checkCancellation()
        return result
    }
}
