// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// A max visit: every pull is an attempt against the hand selected when it began.
final class MaxAttemptLogTests: XCTestCase {
    /// Feeds `kg` at 80 Hz for `seconds`, returning the time after the last sample.
    @discardableResult
    private func hold(_ log: inout MaxAttemptLog, kg: Double, seconds: Double,
                      from t: TimeInterval) -> TimeInterval {
        var time = t
        let step = 1.0 / 80
        while time < t + seconds {
            log.add(kg, at: time)
            time += step
        }
        return time
    }

    /// A pull at `kg` followed by long enough off the edge to log it.
    @discardableResult
    private func pull(_ log: inout MaxAttemptLog, kg: Double, from t: TimeInterval) -> TimeInterval {
        let t = hold(&log, kg: kg, seconds: 1, from: t)
        return hold(&log, kg: 0.2, seconds: MaxAttemptLog.releaseSeconds + 0.1, from: t)
    }

    func testEveryPullIsItsOwnAttemptWithNoStartBetween() {
        var log = MaxAttemptLog(side: .left)
        var t = hold(&log, kg: 0.3, seconds: 3, from: 0)
        XCTAssertTrue(log.attempts.isEmpty, "Drift under the threshold is not a pull")
        XCTAssertFalse(log.isPulling)
        for kg in [30.0, 34, 32] { t = pull(&log, kg: kg, from: t) }
        XCTAssertEqual(log.attempts.map(\.peakKg), [30, 34, 32])
        XCTAssertEqual(log.best(for: .left)?.peakKg, 34)
        XCTAssertFalse(log.isPulling)
    }

    func testAPullStaysOpenUntilTheReleaseWindowHasPassed() {
        var log = MaxAttemptLog(side: .left)
        var t = hold(&log, kg: 30, seconds: 1, from: 0)
        XCTAssertTrue(log.isPulling)
        XCTAssertEqual(log.pullPeakKg, 30)
        t = hold(&log, kg: 0.2, seconds: MaxAttemptLog.releaseSeconds - 0.3, from: t)
        XCTAssertTrue(log.isPulling, "A quick re-grip is still the same pull")
        t = hold(&log, kg: 33, seconds: 0.5, from: t)
        hold(&log, kg: 0.2, seconds: MaxAttemptLog.releaseSeconds + 0.1, from: t)
        XCTAssertEqual(log.attempts.map(\.peakKg), [33])
    }

    func testSwitchingHandsMidPullLogsItAgainstTheHandThatPulled() {
        var log = MaxAttemptLog(side: .left)
        let t = hold(&log, kg: 28, seconds: 1, from: 0)
        let closed = log.select(.right)
        XCTAssertEqual(closed?.side, .left)
        XCTAssertEqual(closed?.peakKg, 28)
        pull(&log, kg: 31, from: t)
        XCTAssertEqual(log.attempts(for: .left).map(\.peakKg), [28])
        XCTAssertEqual(log.attempts(for: .right).map(\.peakKg), [31])
    }

    func testTiesKeepTheEarlierAttempt() {
        var log = MaxAttemptLog(side: .left)
        var t = pull(&log, kg: 30, from: 0)
        t = pull(&log, kg: 30, from: t)
        XCTAssertEqual(log.best(for: .left)?.id, log.attempts.first?.id)
    }

    func testMoveAndRemoveEditTheLogOnly() {
        var log = MaxAttemptLog(side: .left)
        var t = pull(&log, kg: 30, from: 0)
        t = pull(&log, kg: 35, from: t)
        let wrongHand = log.attempts[1].id
        log.move(wrongHand, to: .right)
        XCTAssertEqual(log.best(for: .left)?.peakKg, 30)
        XCTAssertEqual(log.best(for: .right)?.peakKg, 35)
        log.remove(log.attempts[0].id)
        XCTAssertNil(log.best(for: .left))
        XCTAssertEqual(log.side, .left, "Editing the log never changes the selected hand")
    }

    func testClosingWithNoPullOrBelowThresholdLogsNothing() {
        var log = MaxAttemptLog(side: .left)
        XCTAssertNil(log.close())
        hold(&log, kg: 1.5, seconds: 2, from: 0)
        XCTAssertNil(log.close())
        for bad in [Double.nan, .infinity, -.infinity] { log.add(bad, at: 3) }
        XCTAssertTrue(log.attempts.isEmpty)
    }
}
