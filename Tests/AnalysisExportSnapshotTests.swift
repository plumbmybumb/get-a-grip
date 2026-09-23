// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import SwiftData
import XCTest
@testable import Doigt

@MainActor
final class AnalysisExportSnapshotTests: XCTestCase {
    func testSnapshotDecodesOffActorAndSurvivesSourceChanges() async throws {
        let day = DayStamp(raw: 20_000)
        var rep = RepSummary()
        rep.peakKg = 20
        rep.avgKg = 15
        rep.heldSeconds = 10
        let log = WorkoutLog(plan: SessionPlan(), templateID: nil, templateName: "Frozen",
            sessionsPerDayTarget: 2, reps: [rep], startedAt: day.date(),
            finishedAt: day.date().addingTimeInterval(30), day: day)
        let max = MaxRecord(grip: rep.grip, kg: 30, source: .manual)
        let originalPlan = log.plan
        let snapshot = AnalysisExportAssembler.snapshot(logs: [log], maxRecords: [max],
            displayName: { $0.templateName }, today: day)
        log.resultsData = Data()
        log.planData = Data()
        log.templateName = "Changed"
        let result = try await Task.detached { try snapshot.input() }.value
        XCTAssertEqual(result.sessions.first?.routineName, "Frozen")
        XCTAssertEqual(result.sessions.first?.reps, [rep])
        XCTAssertEqual(result.sessions.first?.plan, originalPlan)
        XCTAssertEqual(result.sessions.first?.timing, .gauge)
        XCTAssertEqual(result.maxes.first?.kg, 30)
        XCTAssertEqual(result.sessionsPerDayTarget, 2)
    }

    func testTimingInferenceAndMalformedBlobFallbackArePreserved() async throws {
        let day = DayStamp(raw: 20_000)
        let timer = WorkoutLog(plan: SessionPlan(), templateID: nil, templateName: "Timer",
            sessionsPerDayTarget: 1, reps: [RepSummary()], startedAt: day.date(),
            finishedAt: day.date().addingTimeInterval(30), day: day)
        let logged = WorkoutLog(logged: .climbVolume, day: day, at: day.date(), sessionsPerDayTarget: 1)
        let corrupt = WorkoutLog(plan: SessionPlan(), templateID: nil, templateName: "Old",
            sessionsPerDayTarget: 1, reps: [], startedAt: day.date(), finishedAt: day.date(), day: day)
        corrupt.resultsData = Data("bad".utf8)
        corrupt.planData = Data("bad".utf8)
        let snapshot = AnalysisExportAssembler.snapshot(logs: [timer, logged, corrupt], maxRecords: [],
            displayName: { $0.templateName }, today: day)
        let result = try await Task.detached { try snapshot.input() }.value
        XCTAssertEqual(result.sessions.map(\.timing), [.timerOnly, .logged, .logged])
        XCTAssertNil(result.sessions.last?.plan)
        XCTAssertEqual(result.sessions.last?.reps, [])
    }

    func testExportWorkerPreservesEachRequestedScopeAndDetailAfterCancellation() async throws {
        let day = DayStamp(raw: 20_000)
        let recent = WorkoutLog(logged: .climbVolume, day: day, at: day.date(), sessionsPerDayTarget: 1)
        let olderDay = DayStamp(raw: day.raw - 100)
        let older = WorkoutLog(logged: .climbVolume, day: olderDay, at: olderDay.date(), sessionsPerDayTarget: 1)
        let snapshot = AnalysisExportAssembler.snapshot(logs: [recent, older], maxRecords: [],
            displayName: { $0.templateName }, today: day)
        let worker = AnalysisExportWorker(snapshot: snapshot)
        let cancelled = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await worker.document(scope: .all, detail: .pulls)
        }
        do {
            _ = try await cancelled.value
            XCTFail("Cancelled export must not publish a document")
        } catch is CancellationError { }
        let input = try snapshot.input()
        for scope in [AnalysisExport.CSVScope.recent, .all, .recent] {
            for detail in [AnalysisExport.CSVDetail.summary, .pulls] {
                let result = try await worker.document(scope: scope, detail: detail)
                XCTAssertEqual(result.text, AnalysisExport.csv(input, scope: scope, detail: detail).text)
                XCTAssertEqual(result.sessionCount, scope == .all ? 2 : 1)
            }
        }
    }

    // MARK: - The store-backed path the History screen uses

    private func makeContainer() throws -> ModelContainer {
        let config = ModelConfiguration("Doigt", schema: TestFixtures.schema,
                                        isStoredInMemoryOnly: true, allowsSave: true,
                                        cloudKitDatabase: .none)
        return try ModelContainer(for: TestFixtures.schema, configurations: [config])
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

    /// The tap now freezes an address, not the rows — so the worker's own fetch has to
    /// produce byte-for-byte the document the old main-actor snapshot did.
    func testStoreBackedWorkerMatchesTheMainActorSnapshot() async throws {
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
        let displayName: (WorkoutLog) -> String = { log in
            log.templateID.flatMap { names[$0] } ?? log.templateName
        }
        let sorted = logs.sorted { $0.startedAt > $1.startedAt }
        let maxes = try context.fetch(FetchDescriptor<MaxRecord>())
        let expected = try AnalysisExportAssembler.snapshot(
            logs: sorted, maxRecords: maxes, displayName: displayName, today: today).input()

        let worker = AnalysisExportWorker(source: .init(
            container: container, workoutID: nil, routineNames: names, today: today))
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

        let worker = AnalysisExportWorker(source: .init(
            container: container, workoutID: wanted.id, routineNames: [:], today: today))
        let result = try await worker.document(scope: .workout, detail: .pulls)
        XCTAssertEqual(result.sessionCount, 1)
        XCTAssertTrue(result.text.contains("Wanted"))
        XCTAssertFalse(result.text.contains("Other"))
    }
}
