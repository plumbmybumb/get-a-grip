// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The odometer at the top of Settings. Folded from denormalized columns, so what these
/// pin is the ARITHMETIC — which kinds count as sessions, that a skipped pull is not a
/// pull, that a benchmark day is a day trained and nothing else.
final class LifetimeStatsTests: XCTestCase {

    private let plan = RoutineDraft.starter.normalized.plan.executable
    private let day = DayStamp(year: 2026, month: 9, day: 1)

    private func rep(held: Double, avg: Double, peak: Double,
                     outcome: RepOutcome = .completed) -> RepSummary {
        let slot = PlanMath.sequence(for: plan)[0]
        return RepSummary(setIndex: slot.setIndex, repIndex: slot.repIndex,
                          side: slot.side, grip: slot.grip,
                          targetSeconds: slot.holdSeconds, heldSeconds: held,
                          peakKg: peak, avgKg: avg, outcome: outcome)
    }

    private func hang(_ reps: [RepSummary], on day: DayStamp) -> WorkoutLog {
        WorkoutLog(plan: plan, templateID: nil, templateName: "Daily",
                   sessionsPerDayTarget: 2, reps: reps,
                   startedAt: day.date().addingTimeInterval(10 * 3600),
                   finishedAt: day.date().addingTimeInterval(10 * 3600 + 600), day: day)
    }

    func testEveryKindCountsOnceAndOnlyWhereItBelongs() {
        let logs = [
            hang([rep(held: 7, avg: 18, peak: 20)], on: day),
            // A second session with one completed pull and one skipped: the skip is not a
            // pull, adds no time, and its peak of 0 cannot lower the heaviest.
            hang([rep(held: 10, avg: 12, peak: 15), rep(held: 0, avg: 0, peak: 0, outcome: .skipped)],
                 on: day + 1),
            WorkoutLog(logged: .hangManual, day: day + 1, at: (day + 1).date(), sessionsPerDayTarget: 2, minutes: 15),
            WorkoutLog(logged: .climbLimit, day: day + 3, at: (day + 3).date(), sessionsPerDayTarget: 2, minutes: 90),
            WorkoutLog(logged: .benchmark, day: day + 5, at: (day + 5).date(), sessionsPerDayTarget: 2),
        ]

        let stats = logs.lifetime

        XCTAssertEqual(stats.sessions, 3, "two runner sessions and the hand-logged hang")
        XCTAssertEqual(stats.pulls, 2, "completed pulls only")
        XCTAssertEqual(stats.heldSeconds, 17)
        XCTAssertEqual(stats.volumeKg, 18 + 12, "load × pulls, per session, added up")
        XCTAssertEqual(stats.climbs, 1)
        XCTAssertEqual(stats.daysTrained, 4, "the benchmark day counts as a day, twice-trained days once")
        XCTAssertEqual(stats.heaviestPullKg, 20)
        XCTAssertEqual(stats.since, day)
        XCTAssertFalse(stats.isEmpty)
    }

    func testNothingTrainedIsEmptyNotZeros() {
        let stats = [WorkoutLog]().lifetime
        XCTAssertTrue(stats.isEmpty)
        XCTAssertNil(stats.since)
    }

    /// A benchmark alone is a day trained, so the card shows it rather than the empty line.
    func testABenchmarkDayAloneIsADayTrained() {
        let stats = [WorkoutLog(logged: .benchmark, day: day, at: day.date(), sessionsPerDayTarget: 1)].lifetime
        XCTAssertEqual(stats.sessions, 0)
        XCTAssertEqual(stats.daysTrained, 1)
        XCTAssertFalse(stats.isEmpty)
    }
}
