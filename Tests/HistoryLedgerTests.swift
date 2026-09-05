// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import XCTest
@testable import Doigt

/// The month grid's day-fill rule, pinned.
///
/// It used to live in `HistoryView.fraction(on:)` as a filter over every log, run once
/// per cell. Folding it into `DayLedger` made the screen O(logs) instead of
/// O(days × logs) — but it also meant rewriting the rule, and a grid that silently
/// changed what a filled square means would be a worse bug than the slowness it fixed.
/// Every case below is one the old implementation answered the same way.
final class HistoryLedgerTests: XCTestCase {

    private let today = DayStamp(raw: 20_000)

    private func hang(_ day: DayStamp, target: Int = 2) -> WorkoutLog {
        WorkoutLog(plan: SessionPlan(), templateID: nil, templateName: "R",
                   sessionsPerDayTarget: target, reps: [],
                   startedAt: day.date(), finishedAt: day.date(), day: day)
    }

    private func ledger(_ logs: [WorkoutLog]) -> HistoryView.DayLedger {
        HistoryView.DayLedger(logs: logs, today: today)
    }

    // MARK: - Filling a day

    func testADayWithNothingOnItIsEmpty() {
        let l = ledger([])
        XCTAssertEqual(l.fraction(on: today), 0)
        XCTAssertFalse(l.climbed(on: today))
        XCTAssertFalse(l.benchmarked(on: today))
    }

    func testHangsFillTheirFractionOfTheDaysOwnTarget() {
        XCTAssertEqual(ledger([hang(today, target: 2)]).fraction(on: today), 0.5)
        XCTAssertEqual(ledger([hang(today, target: 2), hang(today, target: 2)])
                        .fraction(on: today), 1)
        XCTAssertEqual(ledger([hang(today, target: 1)]).fraction(on: today), 1)
    }

    func testAHandLoggedHangFillsOnlyItsShareOfTheDaysTarget() {
        let log = WorkoutLog(logged: .hangManual, day: today, at: today.date(),
                             sessionsPerDayTarget: 2)
        XCTAssertEqual(ledger([log]).fraction(on: today), 0.5)
    }

    /// A third session on a two-a-day routine is still one full day, not 150 % of one.
    func testMoreSessionsThanTheTargetStillFillsExactlyOnce() {
        let l = ledger([hang(today), hang(today), hang(today)])
        XCTAssertEqual(l.fraction(on: today), 1)
    }

    /// The target is the one FROZEN into the logs, so a day trained under a once-a-day
    /// routine stays a full day after the routine is changed to ask for two.
    func testTheDayUsesTheLargestTargetFrozenIntoItsOwnLogs() {
        let l = ledger([hang(today, target: 1), hang(today, target: 4)])
        XCTAssertEqual(l.fraction(on: today), 0.5, "two hangs against the larger target")
    }

    /// Defends the `max(1, …)` the old fraction applied: a zero target would divide by
    /// zero and paint every cell as either blank or infinite.
    func testAZeroTargetIsTreatedAsOne() {
        XCTAssertEqual(ledger([hang(today, target: 0)]).fraction(on: today), 1)
    }

    // MARK: - Days that settle themselves

    func testAClimbFillsTheDayOutrightAndMarksIt() {
        let l = ledger([WorkoutLog(logged: .climbVolume, day: today, at: today.date(),
                                   sessionsPerDayTarget: 2)])
        XCTAssertEqual(l.fraction(on: today), 1, "a climb settles the day whatever the target")
        XCTAssertTrue(l.climbed(on: today))
        XCTAssertFalse(l.benchmarked(on: today))
    }

    func testABenchmarkFillsTheDayAndMarksItSeparatelyFromAClimb() {
        let l = ledger([WorkoutLog(logged: .benchmark, day: today, at: today.date(),
                                   sessionsPerDayTarget: 2)])
        XCTAssertEqual(l.fraction(on: today), 1)
        XCTAssertTrue(l.benchmarked(on: today))
        XCTAssertFalse(l.climbed(on: today), "a benchmark is a full day, not a climbing day")
    }

    func testAClimbAndABenchmarkOnOneDayBothRegister() {
        let l = ledger([WorkoutLog(logged: .climbLimit, day: today, at: today.date(),
                                   sessionsPerDayTarget: 2),
                        WorkoutLog(logged: .benchmark, day: today, at: today.date(),
                                   sessionsPerDayTarget: 2)])
        XCTAssertTrue(l.climbed(on: today))
        XCTAssertTrue(l.benchmarked(on: today))
    }

    // MARK: - Days are kept apart

    func testEachDayAnswersOnlyForItsOwnLogs() {
        let yesterday = today - 1
        let l = ledger([hang(today, target: 2), hang(yesterday, target: 2),
                        hang(yesterday, target: 2)])
        XCTAssertEqual(l.fraction(on: today), 0.5)
        XCTAssertEqual(l.fraction(on: yesterday), 1)
        XCTAssertEqual(l.fraction(on: today - 2), 0)
    }

    // MARK: - Where the record starts

    func testTrackingStartsAtTheOldestLogHoweverTheyAreOrdered() {
        // Newest-first, the order History's `@Query` actually delivers.
        let l = ledger([hang(today), hang(today - 40), hang(today - 9)])
        XCTAssertEqual(l.trackingSince, today - 40)
    }

    /// With no history at all, "since" is today — days before it are hairlines, and a
    /// sentinel leaking through here would draw 35 tracked days for a brand-new install.
    func testWithNoLogsTrackingStartsToday() {
        XCTAssertEqual(ledger([]).trackingSince, today)
    }
}
