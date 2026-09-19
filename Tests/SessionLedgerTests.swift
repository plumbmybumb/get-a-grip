// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import SwiftData
import XCTest
@testable import Doigt

/// The write path the phone and the watch share. Everything here runs against an
/// in-memory container built from the real CloudKit-shaped schema, so the blob
/// accessors and the frozen columns are exercised exactly as they are on either device.
@MainActor
final class SessionLedgerTests: XCTestCase {

    private static let schema = TestFixtures.schema

    private func makeWorld(today: DayStamp = DayStamp(raw: 20_000))
        throws -> (container: ModelContainer, ledger: SessionLedger, clock: DayClock) {
        let config = ModelConfiguration("Doigt", schema: Self.schema,
                                        isStoredInMemoryOnly: true, allowsSave: true,
                                        cloudKitDatabase: .none)
        let container = try ModelContainer(for: Self.schema, configurations: [config])
        let clock = DayClock(today: today)
        return (container, SessionLedger(context: container.mainContext, clock: clock), clock)
    }

    private func oneRep(of plan: SessionPlan, heldSeconds: Double = 7,
                        outcome: RepOutcome = .completed) -> RepSummary {
        let slot = PlanMath.sequence(for: plan)[0]
        return RepSummary(setIndex: slot.setIndex, repIndex: slot.repIndex,
                          side: slot.side, grip: slot.grip,
                          targetSeconds: slot.holdSeconds, heldSeconds: heldSeconds,
                          peakKg: 20, avgKg: 18, outcome: outcome)
    }

    func testRecordSessionFreezesTheRoutineAndStampsTheClocksDay() throws {
        let world = try makeWorld()
        let template = SessionTemplate(draft: RoutineDraft.starter.normalized, sortIndex: 0)
        world.container.mainContext.insert(template)
        let plan = template.plan.executable

        let log = try XCTUnwrap(world.ledger.recordSession(
            plan: plan, template: template, reps: [oneRep(of: plan)],
            startedAt: .now, finishedAt: .now, rpe: nil))

        XCTAssertEqual(log.dayKey, world.clock.today.raw, "the day is the clock's, not the wall's")
        XCTAssertEqual(log.templateID, template.id)
        XCTAssertEqual(log.templateName, template.name, "frozen at save")
        XCTAssertEqual(log.sessionsPerDayTarget, template.sessionsPerDay)
        XCTAssertEqual(log.completedReps, 1)
        XCTAssertEqual(log.plannedReps, PlanMath.totalReps(plan))
        XCTAssertNil(world.ledger.saveError)
        XCTAssertEqual(try world.container.mainContext.fetch(FetchDescriptor<WorkoutLog>()).count, 1)
    }

    func testAMaxThatIsNotANumberRefusesTheWholeWrite() throws {
        let world = try makeWorld()
        let plan = RoutineDraft.starter.normalized.plan.executable
        let bad = MaxRecord(grip: plan.sets[0].grip, kg: .nan, source: .measured)

        let log = world.ledger.recordSession(plan: plan, template: nil, reps: [oneRep(of: plan)],
                                             startedAt: .now, finishedAt: .now, rpe: nil,
                                             newMaxes: [bad])

        XCTAssertNil(log)
        XCTAssertNotNil(world.ledger.saveError)
        XCTAssertEqual(try world.container.mainContext.fetch(FetchDescriptor<WorkoutLog>()).count, 0,
                       "nothing lands when the batch is refused")
        XCTAssertEqual(try world.container.mainContext.fetch(FetchDescriptor<MaxRecord>()).count, 0)
    }

    func testARoutinelessSessionKeepsThePlansOwnName() throws {
        let world = try makeWorld()
        var plan = RoutineDraft.starter.normalized.plan.executable
        plan.name = "Borrowed"

        let log = try XCTUnwrap(world.ledger.recordSession(
            plan: plan, template: nil, reps: [oneRep(of: plan)],
            startedAt: .now, finishedAt: .now, rpe: nil))

        XCTAssertNil(log.templateID)
        XCTAssertEqual(log.templateName, "Borrowed")
        XCTAssertEqual(log.sessionsPerDayTarget, 1)
    }

    // MARK: - The folds the watch shares with the store

    func testHangCompletionsCountHangsPerRoutineAndIgnoreClimbs() throws {
        let day = DayStamp(raw: 20_000)
        let plan = RoutineDraft.starter.normalized.plan.executable
        let a = UUID(), b = UUID()
        func hang(_ id: UUID?, on stamp: DayStamp) -> WorkoutLog {
            WorkoutLog(plan: plan, templateID: id, templateName: "x", sessionsPerDayTarget: 2,
                       reps: [], startedAt: .now, finishedAt: .now, day: stamp)
        }
        let logs = [
            hang(a, on: day), hang(a, on: day), hang(b, on: day),
            hang(a, on: day - 1),                      // yesterday: not today's count
            hang(nil, on: day),                        // routine deleted: attributed to nobody
            WorkoutLog(logged: .climbLimit, day: day, at: .now, sessionsPerDayTarget: 2),
            WorkoutLog(logged: .hangManual, day: day, at: .now, sessionsPerDayTarget: 2),
        ]

        XCTAssertEqual(logs.hangCompletions(on: day), [a: 2, b: 1])
        XCTAssertEqual(logs.unattributedHangs(on: day), 1, ".hangManual only, never every nil id")
        XCTAssertEqual(logs.climb(on: day), .climbLimit)
    }

    func testNewestPerGripAndHandKeepsBothHands() throws {
        let grip = TestFixtures.halfCrimp20
        let earlier = Date(timeIntervalSinceReferenceDate: 1_000)
        let later = Date(timeIntervalSinceReferenceDate: 2_000)
        let records = [
            MaxRecord(grip: grip, kg: 30, source: .measured, side: .left, recordedAt: earlier),
            MaxRecord(grip: grip, kg: 28, source: .measured, side: .right, recordedAt: later),
            MaxRecord(grip: grip, kg: 31, source: .manual, side: .left, recordedAt: later),
        ]

        let newest = records.newestPerGripAndHand()
        XCTAssertEqual(newest.count, 2, "one per hand, not one per grip")
        XCTAssertEqual(newest[MaxTable.key(grip: grip.key, side: .left)]?.kg, 31)
        XCTAssertEqual(newest[MaxTable.key(grip: grip.key, side: .right)]?.kg, 28)

        let table = records.maxTable()
        XCTAssertEqual(table.max(grip: grip.key, side: .left), 31)
        XCTAssertEqual(table.max(grip: grip.key, side: .right), 28)
        XCTAssertNil(table.max(grip: grip.key, side: .both),
                     "a both-hands rep never borrows a one-handed number")
    }
}
