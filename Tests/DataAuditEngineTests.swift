// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class DataAuditEngineTests: XCTestCase {
    func testUnknownMissingAndMalformedOutcomesNeverBecomeCompletedPulls() throws {
        for json in [#"{"outcome":"futureOutcome"}"#, #"{"outcome":4}"#, "{}"] {
            let rep = try JSONDecoder().decode(RepSummary.self, from: Data(json.utf8))
            XCTAssertEqual(rep.outcome, .aborted)
        }
        for outcome in [RepOutcome.completed, .earlyRelease, .skipped, .aborted] {
            let data = try JSONEncoder().encode(RepSummary(outcome: outcome))
            XCTAssertEqual(try JSONDecoder().decode(RepSummary.self, from: data).outcome, outcome)
        }
    }

    func testReminderNormalizationPreservesGoalAndRestoresDistinctTimes() {
        var draft = RoutineDraft.blank()
        draft.plan.sets = [SetPlan()]
        let morning = ReminderTime(hour: 8, minute: 0)
        draft.sessionsPerDay = 3
        draft.reminders = [morning, morning]
        draft.parkedReminders = [morning, ReminderTime(hour: 19, minute: 0)]
        XCTAssertNotNil(draft.validationIssue)
        let normalized = draft.normalized
        XCTAssertEqual(normalized.sessionsPerDay, 3)
        XCTAssertEqual(normalized.reminders.count, 3)
        XCTAssertEqual(Set(normalized.reminders).count, 3)
        XCTAssertNil(normalized.validationIssue)
        draft.reminders = [morning]
        draft.setSessionsPerDay(3)
        XCTAssertEqual(draft.reminders.count, 3, "a parked duplicate must not consume a slot")
    }

    func testMissingMidnightRoundTripsOnActualSantiagoTransitions() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Santiago"))
        for day in [DayStamp(year: 2026, month: 9, day: 6), DayStamp(year: 2027, month: 9, day: 5)] {
            let date = day.date(calendar: calendar)
            XCTAssertEqual(DayStamp(date: date, calendar: calendar), day)
            XCTAssertEqual(calendar.component(.hour, from: date), 1)
        }
    }

    func testTargetLinesEngageWithoutExactEqualityButOrdinaryBandsStayStrict() {
        for high: Double? in [nil, 10] {
            var runner = makeRunner(low: 10, high: high)
            feed(&runner, kg: 10.1)
            XCTAssertEqual(runner.phase, .working(slot: 0))
        }
        var outsideLine = makeRunner(low: 10, high: 10)
        feed(&outsideLine, kg: 10.4)
        XCTAssertEqual(outsideLine.phase, .armed(slot: 0))
        var ordinaryBand = makeRunner(low: 10, high: 11)
        feed(&ordinaryBand, kg: 9.9)
        XCTAssertEqual(ordinaryBand.phase, .armed(slot: 0))
        var zeroTarget = makeRunner(low: 0, high: 0)
        feed(&zeroTarget, kg: 0)
        XCTAssertEqual(zeroTarget.phase, .armed(slot: 0), "an unloaded zero target must not start a pull")
    }

    private func makeRunner(low: Double, high: Double?) -> SessionRunner {
        var plan = SessionPlan(sets: [SetPlan(repsPerSide: 1, targetLoKg: low, targetHiKg: high)],
                               handMode: .bothHands, holdSeconds: 2, leadInSeconds: 0)
        plan.pausesOutsideTargetBand = true
        var runner = SessionRunner(plan: plan)
        _ = runner.handle(.start, at: 0)
        return runner
    }

    private func feed(_ runner: inout SessionRunner, kg: Double) {
        for sample in 1...32 {
            _ = runner.handle(.sample(ForceSample(kg: kg, deviceMicros: UInt32(sample * 12_500))),
                              at: Double(sample) * 0.0125)
        }
    }
}
