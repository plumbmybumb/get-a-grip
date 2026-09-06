// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
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
}
