// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import SwiftData
import XCTest
@testable import Doigt

/// The export's one path: an address (`AnalysisExportAssembler.Source`) resolved on the
/// worker's own context, then decoded off the main actor.
@MainActor
final class AnalysisExportSnapshotTests: XCTestCase {
    private func makeContainer() throws -> ModelContainer {
        let config = ModelConfiguration("Doigt", schema: TestFixtures.schema,
                                        isStoredInMemoryOnly: true, allowsSave: true,
                                        cloudKitDatabase: .none)
        return try ModelContainer(for: TestFixtures.schema, configurations: [config])
    }

    private func source(_ container: ModelContainer, workoutID: UUID? = nil,
                        routineNames: [UUID: String] = [:],
                        today: DayStamp) -> AnalysisExportAssembler.Source {
        .init(container: container, workoutID: workoutID, routineNames: routineNames, today: today)
    }

    private func hangLog(named name: String, templateID: UUID?, day: DayStamp,
                         avgKg: Double) -> WorkoutLog {
        var rep = RepSummary()
        rep.peakKg = avgKg + 4
        rep.avgKg = avgKg
        rep.heldSeconds = 10
        return WorkoutLog(plan: SessionPlan(), templateID: templateID, templateName: name,
            sessionsPerDayTarget: 2, reps: [rep], startedAt: day.date(),
            finishedAt: day.date().addingTimeInterval(30), day: day)
    }

    func testSnapshotDecodesOffActorAndSurvivesSourceChanges() async throws {
        let container = try makeContainer()
        let context = container.mainContext
        let day = DayStamp(raw: 20_000)
        var rep = RepSummary()
        rep.peakKg = 20
        rep.avgKg = 15
        rep.heldSeconds = 10
        let log = WorkoutLog(plan: SessionPlan(), templateID: nil, templateName: "Frozen",
            sessionsPerDayTarget: 2, reps: [rep], startedAt: day.date(),
            finishedAt: day.date().addingTimeInterval(30), day: day)
        context.insert(log)
        context.insert(MaxRecord(grip: rep.grip, kg: 30, source: .manual))
        try context.save()
        let originalPlan = log.plan

        let snapshot = try AnalysisExportAssembler.snapshot(from: source(container, today: day))
        log.resultsData = Data()
        log.planData = Data()
        log.templateName = "Changed"
        try context.save()

        let result = try await Task.detached { try snapshot.input() }.value
        XCTAssertEqual(result.sessions.first?.routineName, "Frozen")
        XCTAssertEqual(result.sessions.first?.reps, [rep])
        XCTAssertEqual(result.sessions.first?.plan, originalPlan)
        XCTAssertEqual(result.sessions.first?.timing, .gauge)
        XCTAssertEqual(result.maxes.first?.kg, 30)
        XCTAssertEqual(result.sessionsPerDayTarget, 2)
    }

    func testTimingInferenceAndMalformedBlobFallbackArePreserved() async throws {
        let container = try makeContainer()
        let context = container.mainContext
        let day = DayStamp(raw: 20_000)
        // Distinct start times, newest first, so the fetch's order is the asserted one.
        let timer = WorkoutLog(plan: SessionPlan(), templateID: nil, templateName: "Timer",
            sessionsPerDayTarget: 1, reps: [RepSummary()],
            startedAt: day.date().addingTimeInterval(200),
            finishedAt: day.date().addingTimeInterval(230), day: day)
        let logged = WorkoutLog(logged: .climbVolume, day: day,
                                at: day.date().addingTimeInterval(100), sessionsPerDayTarget: 1)
        let corrupt = WorkoutLog(plan: SessionPlan(), templateID: nil, templateName: "Old",
            sessionsPerDayTarget: 1, reps: [], startedAt: day.date(), finishedAt: day.date(), day: day)
        corrupt.resultsData = Data("bad".utf8)
        corrupt.planData = Data("bad".utf8)
        [timer, logged, corrupt].forEach(context.insert)
        try context.save()

        let snapshot = try AnalysisExportAssembler.snapshot(from: source(container, today: day))
        let result = try await Task.detached { try snapshot.input() }.value
        XCTAssertEqual(result.sessions.map(\.timing), [.timerOnly, .logged, .logged])
        XCTAssertNil(result.sessions.last?.plan)
        XCTAssertEqual(result.sessions.last?.reps, [])
    }

    func testExportWorkerPreservesEachRequestedScopeAndDetailAfterCancellation() async throws {
        let container = try makeContainer()
        let context = container.mainContext
        let day = DayStamp(raw: 20_000)
        let olderDay = DayStamp(raw: day.raw - 100)
        context.insert(WorkoutLog(logged: .climbVolume, day: day, at: day.date(), sessionsPerDayTarget: 1))
        context.insert(WorkoutLog(logged: .climbVolume, day: olderDay, at: olderDay.date(),
                                  sessionsPerDayTarget: 1))
        try context.save()

        let exportSource = source(container, today: day)
        let worker = AnalysisExportWorker(source: exportSource)
        let cancelled = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await worker.document(scope: .all, detail: .pulls)
        }
        do {
            _ = try await cancelled.value
            XCTFail("Cancelled export must not publish a document")
        } catch is CancellationError { }
        let input = try AnalysisExportAssembler.snapshot(from: exportSource).input()
        for scope in [AnalysisExport.CSVScope.recent, .all, .recent] {
            for detail in [AnalysisExport.CSVDetail.summary, .pulls] {
                let result = try await worker.document(scope: scope, detail: detail)
                XCTAssertEqual(result.text, AnalysisExport.csv(input, scope: scope, detail: detail).text)
                XCTAssertEqual(result.sessionCount, scope == .all ? 2 : 1)
            }
        }
    }

    /// The oracle for the worker's fetch: the same rows read on the MAIN context, copied
    /// field by field. Written out here rather than borrowed from the assembler, so a
    /// fetch that drops, reorders or misnames a session cannot agree with itself.
    private func mainContextInput(logs: [WorkoutLog], maxes: [MaxRecord],
                                  routineNames: [UUID: String],
                                  today: DayStamp) throws -> AnalysisExport.Input {
        let sorted = logs.sorted { $0.startedAt > $1.startedAt }
        let sessions = sorted.map { log in
            AnalysisExportAssembler.SessionSnapshot(metadata: AnalysisExport.Session(
                id: log.id, day: log.day, startedAt: log.startedAt,
                routineName: log.displayName(in: routineNames), kind: log.kind,
                minutes: log.sessionMinutes,
                rpe: log.grade, fingerStrain: log.fingerStrain, peakKg: log.peakKg,
                avgKg: log.avgKg, totalHeldSeconds: log.totalHeldSeconds,
                plannedReps: log.plannedReps, completedReps: log.completedReps,
                notes: log.notes, sessionsPerDayTarget: log.sessionsPerDayTarget,
                finishedAt: log.finishedAt),
                planData: log.planData, resultsData: log.resultsData)
        }
        let entries = maxes.sorted { $0.recordedAt < $1.recordedAt }.map { record in
            AnalysisExport.MaxEntry(grip: record.grip, side: record.side, kg: record.kg,
                day: DayStamp(date: record.recordedAt, calendar: .current),
                recordedAt: record.recordedAt, source: record.source)
        }
        let target = sorted.first?.sessionsPerDayTarget ?? 1
        return try AnalysisExportAssembler.Snapshot(sessions: sessions, maxes: entries,
            today: today, sessionsPerDayTarget: max(1, target)).input()
    }

    /// The tap freezes an address, not the rows — so the worker's own fetch has to
    /// produce byte-for-byte the document the rows on the main context describe.
    func testStoreBackedWorkerMatchesTheMainContextRows() async throws {
        let container = try makeContainer()
        let context = container.mainContext
        let today = DayStamp(raw: 20_000)
        let routine = UUID()
        let logs = [
            hangLog(named: "Old name", templateID: routine, day: today, avgKg: 15),
            hangLog(named: "Old name", templateID: routine, day: today - 3, avgKg: 14),
            hangLog(named: "Deleted routine", templateID: UUID(), day: today - 9, avgKg: 12),
            WorkoutLog(logged: .climbVolume, day: today - 1, at: (today - 1).date(),
                       sessionsPerDayTarget: 2),
        ]
        logs.forEach(context.insert)
        context.insert(MaxRecord(grip: RepSummary().grip, kg: 30, source: .manual,
                                 recordedAt: (today - 5).date()))
        try context.save()

        let names = [routine: "Renamed"]
        let expected = try mainContextInput(
            logs: logs, maxes: try context.fetch(FetchDescriptor<MaxRecord>()),
            routineNames: names, today: today)

        let worker = AnalysisExportWorker(source: source(container, routineNames: names, today: today))
        for scope in [AnalysisExport.CSVScope.recent, .all] {
            for detail in [AnalysisExport.CSVDetail.summary, .pulls] {
                let result = try await worker.document(scope: scope, detail: detail)
                XCTAssertEqual(result.text, AnalysisExport.csv(expected, scope: scope, detail: detail).text)
            }
        }
        let all = try await worker.document(scope: .all, detail: .summary)
        XCTAssertEqual(all.sessionCount, 4)
        XCTAssertEqual(all.maxCount, 1)
        XCTAssertTrue(all.text.contains("Renamed"), "a live routine exports under its live name")
        XCTAssertTrue(all.text.contains("Deleted routine"), "a gone routine keeps its frozen name")
        XCTAssertFalse(all.text.contains("Old name"))
    }

    func testStoreBackedWorkoutExportFetchesOnlyThatSession() async throws {
        let container = try makeContainer()
        let context = container.mainContext
        let today = DayStamp(raw: 20_000)
        let wanted = hangLog(named: "Wanted", templateID: nil, day: today, avgKg: 15)
        context.insert(wanted)
        context.insert(hangLog(named: "Other", templateID: nil, day: today - 1, avgKg: 14))
        try context.save()

        let worker = AnalysisExportWorker(source: source(container, workoutID: wanted.id, today: today))
        let result = try await worker.document(scope: .workout, detail: .pulls)
        XCTAssertEqual(result.sessionCount, 1)
        XCTAssertTrue(result.text.contains("Wanted"))
        XCTAssertFalse(result.text.contains("Other"))
    }
}
