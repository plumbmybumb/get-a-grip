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
        return (container, SessionLedger(context: container.mainContext), clock)
    }

    private func oneRep(of plan: SessionPlan, heldSeconds: Double = 7,
                        outcome: RepOutcome = .completed) -> RepSummary {
        let slot = PlanMath.sequence(for: plan)[0]
        return RepSummary(setIndex: slot.setIndex, repIndex: slot.repIndex,
                          side: slot.side, grip: slot.grip,
                          targetSeconds: slot.holdSeconds, heldSeconds: heldSeconds,
                          peakKg: 20, avgKg: 18, outcome: outcome)
    }

    func testRecordSessionFreezesTheRoutineAndStampsTheDayItStarted() throws {
        let world = try makeWorld()
        let template = SessionTemplate(draft: RoutineDraft.starter.normalized, sortIndex: 0)
        world.container.mainContext.insert(template)
        let plan = template.plan.executable

        let log = try XCTUnwrap(world.ledger.recordSession(
            plan: plan, template: template, reps: [oneRep(of: plan)],
            startedAt: .now, finishedAt: .now, rpe: nil))

        XCTAssertEqual(log.dayKey, DayStamp(trainingDayOf: log.startedAt).raw,
                       "the training day the session started in")
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

    // MARK: - The launch repair

    /// Nuri's 2026-09-19 hang: started 23:47, saved 44 seconds into the 20th by a clock
    /// that turned at midnight. The repair re-files it under the evening it belonged to,
    /// leaves the hand-logged climb on the day the person chose, and has nothing to do
    /// the second time.
    func testRepairRefilesASessionThatCrossedMidnightUnderItsEvening() throws {
        let world = try makeWorld()
        var paris = Calendar(identifier: .gregorian)
        paris.timeZone = TimeZone(identifier: "Europe/Paris")!
        let started = paris.date(from: DateComponents(year: 2026, month: 9, day: 19, hour: 23, minute: 47))!
        let sept19 = DayStamp(year: 2026, month: 9, day: 19)
        let sept20 = DayStamp(year: 2026, month: 9, day: 20)
        let plan = RoutineDraft.starter.normalized.plan.executable

        let hang = WorkoutLog(plan: plan, templateID: nil, templateName: "Daily burn",
                              sessionsPerDayTarget: 2, reps: [oneRep(of: plan)],
                              startedAt: started, finishedAt: started.addingTimeInterval(13 * 60),
                              day: sept20)
        let climb = WorkoutLog(logged: .climbLimit, day: sept20,
                               at: started.addingTimeInterval(20 * 60), sessionsPerDayTarget: 2)
        let morning = WorkoutLog(plan: plan, templateID: nil, templateName: "Daily burn",
                                 sessionsPerDayTarget: 2, reps: [oneRep(of: plan)],
                                 startedAt: started.addingTimeInterval(10 * 3600),
                                 finishedAt: started.addingTimeInterval(10 * 3600 + 13 * 60),
                                 day: sept20)
        let context = world.container.mainContext
        context.insert(hang)
        context.insert(climb)
        context.insert(morning)
        try context.save()

        XCTAssertEqual(world.ledger.repairTrainingDays(calendar: paris), 1)
        XCTAssertEqual(hang.day, sept19, "the evening it was part of")
        XCTAssertEqual(climb.day, sept20, "a hand log keeps the day the person chose")
        XCTAssertEqual(morning.day, sept20, "a row already right is not touched")
        XCTAssertEqual(world.ledger.repairTrainingDays(calendar: paris), 0)
        XCTAssertEqual(try context.fetch(FetchDescriptor<WorkoutLog>()).count, 3)
    }

    private var paris: Calendar {
        var paris = Calendar(identifier: .gregorian)
        paris.timeZone = TimeZone(identifier: "Europe/Paris")!
        return paris
    }

    private func parisDate(_ day: Int, _ hour: Int, _ minute: Int) -> Date {
        paris.date(from: DateComponents(year: 2026, month: 9, day: day, hour: hour, minute: minute))!
    }

    /// A runner session as the midnight clock filed it: started late on the 19th,
    /// stamped the calendar day it was SAVED on.
    private func midnightStampedHang(startedAt: Date, day: DayStamp) -> WorkoutLog {
        let plan = RoutineDraft.starter.normalized.plan.executable
        return WorkoutLog(plan: plan, templateID: nil, templateName: "Daily burn",
                          sessionsPerDayTarget: 2, reps: [oneRep(of: plan)],
                          startedAt: startedAt, finishedAt: startedAt.addingTimeInterval(13 * 60),
                          day: day)
    }

    /// The repair touches only rows the midnight clock itself wrote. A kind from a newer
    /// build reads as `.hang` through `SessionKind(fallback:)` and used to be rewritten;
    /// a row outside the midnight window was stamped by some other rule (another time
    /// zone, most likely) and re-deriving it here is how travel moved history; a row
    /// started after the cutoff came from the writer that already stamps the training
    /// day. The benchmark marker, stamped by the app like a hang, is repaired.
    func testRepairLeavesUnknownKindsForeignStampsAndLaterRowsAlone() throws {
        let world = try makeWorld()
        let context = world.container.mainContext
        let sept17 = DayStamp(year: 2026, month: 9, day: 17)
        let sept19 = DayStamp(year: 2026, month: 9, day: 19)
        let sept20 = DayStamp(year: 2026, month: 9, day: 20)
        let lateOn19 = parisDate(19, 23, 47)

        let future = midnightStampedHang(startedAt: lateOn19, day: sept20)
        future.kindRaw = "fingerYoga"
        let foreign = midnightStampedHang(startedAt: lateOn19, day: sept17)
        let later = midnightStampedHang(startedAt: parisDate(20, 2, 30), day: sept20)
        let benchmark = WorkoutLog(logged: .benchmark, day: sept20, at: parisDate(20, 1, 30),
                                   sessionsPerDayTarget: 2)
        let stale = midnightStampedHang(startedAt: lateOn19, day: sept20)
        for log in [future, foreign, later, benchmark, stale] { context.insert(log) }
        try context.save()

        XCTAssertEqual(world.ledger.repairTrainingDays(calendar: paris,
                                                       before: parisDate(20, 2, 0)), 2)
        XCTAssertEqual(stale.day, sept19, "the midnight-stamped hang moves")
        XCTAssertEqual(benchmark.day, sept19, "01:30 belongs to the evening before")
        XCTAssertEqual(future.dayKey, sept20.raw, "a newer build's kind is left as it wrote it")
        XCTAssertEqual(future.kindRaw, "fingerYoga")
        XCTAssertEqual(foreign.day, sept17, "not a midnight stamp in this zone: not ours to move")
        XCTAssertEqual(later.day, sept20, "started after the cutoff: the new writer's row")
    }

    /// Once per install. A row that is wrong after the flag is set stays as it is — the
    /// repair is not a launch-time rewrite of history any more.
    func testRepairRunsOncePerInstallAndMarksItself() throws {
        let world = try makeWorld()
        let context = world.container.mainContext
        let suite = "SessionLedgerTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let sept19 = DayStamp(year: 2026, month: 9, day: 19)
        let sept20 = DayStamp(year: 2026, month: 9, day: 20)

        let first = midnightStampedHang(startedAt: parisDate(19, 23, 47), day: sept20)
        context.insert(first)
        try context.save()
        XCTAssertEqual(world.ledger.repairTrainingDaysIfNeeded(defaults: defaults, calendar: paris), 1)
        XCTAssertEqual(first.day, sept19)
        XCTAssertEqual(defaults.integer(forKey: SessionLedger.trainingDayRepairKey),
                       SessionLedger.trainingDayRepairVersion)

        let second = midnightStampedHang(startedAt: parisDate(19, 23, 48), day: sept20)
        context.insert(second)
        try context.save()
        XCTAssertEqual(world.ledger.repairTrainingDaysIfNeeded(defaults: defaults, calendar: paris), 0)
        XCTAssertEqual(second.day, sept20, "the one-shot has already run on this install")
    }

    /// The writer and the repair reach the same answer from the same column: a session
    /// that started before 04:00 and finished after it is filed under the evening it
    /// began in, and a repair afterwards has nothing to move.
    func testTheWriterStampsWhatTheRepairWouldAndNeverTheSaveTimeDay() throws {
        let world = try makeWorld()
        let plan = RoutineDraft.starter.normalized.plan.executable
        let calendar = Calendar.current
        let started = try XCTUnwrap(calendar.date(from: DateComponents(
            year: 2026, month: 9, day: 20, hour: 3, minute: 50)))
        let finished = try XCTUnwrap(calendar.date(from: DateComponents(
            year: 2026, month: 9, day: 20, hour: 4, minute: 10)))

        let log = try XCTUnwrap(world.ledger.recordSession(
            plan: plan, template: nil, reps: [oneRep(of: plan)],
            startedAt: started, finishedAt: finished, rpe: nil))

        XCTAssertEqual(log.day, DayStamp(year: 2026, month: 9, day: 19))
        XCTAssertEqual(world.ledger.repairTrainingDays(), 0)
    }
}
