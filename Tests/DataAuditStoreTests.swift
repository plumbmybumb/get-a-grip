// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
import SwiftData
import XCTest
@testable import Doigt

@MainActor
final class DataAuditStoreTests: XCTestCase {
    private func world() throws -> (ModelContainer, ModelContext, DayClock, TemplateStore) {
        let schema = Schema([SessionTemplate.self, WorkoutLog.self, MaxRecord.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
        let container = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(container)
        let clock = DayClock()
        let settings = SettingsStore()
        settings.didAskNotificationPermission = true
        return (container, context, clock, TemplateStore(context: context, clock: clock,
                                                        settings: settings, storageMode: .localOnly))
    }

    func testClockChangeRefreshesStoreWithoutAnObserverOrderingDependency() throws {
        let (_, _, clock, store) = try world()
        XCTAssertNotNil(store.recordLoggedSession(.hangManual))
        XCTAssertEqual(store.unattributedHangsToday, 1)
        clock.advance(to: clock.today + 1)
        XCTAssertEqual(store.unattributedHangsToday, 0, "clock update must drive recomputation directly")
    }

    func testSameNamedRoutinesHaveDistinctPercentReceiptIdentity() throws {
        let (_, _, _, store) = try world()
        var draft = RoutineDraft.blank(named: "Same name")
        draft.plan.handMode = .bothHands
        draft.plan.sets = [SetPlan(targetLoPercent: 0.25, targetHiPercent: 0.30)]
        let first = try XCTUnwrap(store.create(draft))
        let second = try XCTUnwrap(store.create(draft))
        let impact = store.maxImpact(grip: GripSpec(), previousMaxes: MaxTable(), newKg: 40)
        XCTAssertEqual(impact.percentMoves.count, 2)
        XCTAssertEqual(Set(impact.percentMoves.map(\.id)).count, 2)
        XCTAssertEqual(Set(impact.percentMoves.map(\.routineID)), [first.id, second.id])
    }

    func testHistoryTracksFromRoutineCreationEvenBeforeFirstLog() {
        let today = DayStamp(year: 2026, month: 9, day: 6)
        let log = WorkoutLog(logged: .hangManual, day: today, at: today.date(), sessionsPerDayTarget: 1)
        let ledger = HistoryView.DayLedger(logs: [log], today: today, trackingSince: today - 7)
        XCTAssertEqual(ledger.trackingSince, today - 7)
        XCTAssertEqual(ledger.fraction(on: today - 1), 0)
    }

    func testMalformedZeroGoalMatchesStoreMinimumOneSessionRule() {
        let summary = RoutineSummary(id: UUID(), name: "Zero goal", ladder: [], setCount: 0,
            totalReps: 0, sharedEdgeMM: nil, estimatedSeconds: 0, sessionsPerDay: 0,
            completedToday: 1, nextReminder: nil)
        XCTAssertTrue(summary.targetMet)
    }
}
