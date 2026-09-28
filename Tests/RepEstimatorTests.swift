// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The live gauge's estimated reps: up off the baseline, back down to it, one rep.
final class RepEstimatorTests: XCTestCase {
    /// Feeds `kg` at 80 Hz for `seconds`, returning the time after the last sample.
    @discardableResult
    private func hold(_ reps: inout RepEstimator, kg: Double, seconds: Double,
                      from t: TimeInterval) -> TimeInterval {
        var time = t
        let step = 1.0 / 80
        while time < t + seconds {
            reps.add(kg, at: time)
            time += step
        }
        return time
    }

    /// One pull at `kg`, then long enough at rest to end it.
    @discardableResult
    private func pull(_ reps: inout RepEstimator, kg: Double, seconds: Double = 3,
                      from t: TimeInterval) -> TimeInterval {
        let t = hold(&reps, kg: kg, seconds: seconds, from: t)
        return hold(&reps, kg: 0.2, seconds: RepEstimator.releaseSeconds + 0.1, from: t)
    }

    func testEachPullAndReleaseIsOneRep() {
        var reps = RepEstimator()
        var t = hold(&reps, kg: 0.4, seconds: 2, from: 0)
        XCTAssertEqual(reps.count, 0, "Drift at rest is not a rep")
        for kg in [12.0, 13, 11.5, 12.5] { t = pull(&reps, kg: kg, from: t) }
        XCTAssertEqual(reps.count, 4)
        XCTAssertFalse(reps.isPulling)
    }

    func testTheRepCountsOnTheWayDownNotOnTheWayUp() {
        var reps = RepEstimator()
        let t = hold(&reps, kg: 20, seconds: 5, from: 0)
        XCTAssertTrue(reps.isPulling)
        XCTAssertEqual(reps.count, 0, "Still on the edge: nothing is known to have ended")
        hold(&reps, kg: 0, seconds: RepEstimator.releaseSeconds + 0.1, from: t)
        XCTAssertEqual(reps.count, 1)
    }

    func testAKnockOnTheGaugeIsNotARep() {
        var reps = RepEstimator()
        var t = hold(&reps, kg: 15, seconds: RepEstimator.minimumPullSeconds / 2, from: 0)
        t = hold(&reps, kg: 0, seconds: 1, from: t)
        XCTAssertEqual(reps.count, 0)
        XCTAssertFalse(reps.isPulling)
    }

    func testEasingOffMidPullIsTheSamePull() {
        var reps = RepEstimator()
        var t = hold(&reps, kg: 40, seconds: 2, from: 0)
        t = hold(&reps, kg: 25, seconds: 2, from: t)
        t = hold(&reps, kg: 38, seconds: 2, from: t)
        hold(&reps, kg: 0.3, seconds: 1, from: t)
        XCTAssertEqual(reps.count, 1, "A pull ends at the baseline, not at a dip")
    }

    func testAQuickRegripDoesNotSplitOnePullInTwo() {
        var reps = RepEstimator()
        var t = hold(&reps, kg: 20, seconds: 2, from: 0)
        t = hold(&reps, kg: 0.2, seconds: RepEstimator.releaseSeconds / 2, from: t)
        t = hold(&reps, kg: 20, seconds: 2, from: t)
        hold(&reps, kg: 0.2, seconds: 1, from: t)
        XCTAssertEqual(reps.count, 1)
    }

    func testTheBaselineScalesWithAHeavyPull() {
        var reps = RepEstimator()
        let t = hold(&reps, kg: 60, seconds: 2, from: 0)
        XCTAssertEqual(reps.releaseKg, 9, accuracy: 0.001)
        // 5 kg is under 15 % of 60: off the edge.
        hold(&reps, kg: 5, seconds: 1, from: t)
        XCTAssertEqual(reps.count, 1)
    }

    func testAnUntaredGaugeRestingAboveTheLineCountsNothing() {
        var reps = RepEstimator()
        var t: TimeInterval = 0
        for _ in 0..<4 {
            t = hold(&reps, kg: 20, seconds: 2, from: t)
            t = hold(&reps, kg: 4, seconds: 2, from: t)
        }
        XCTAssertEqual(reps.count, 0, "Never back to the baseline, so never a finished rep")
    }

    func testCancelDropsThePullInProgressAndResetZeroes() {
        var reps = RepEstimator()
        var t = pull(&reps, kg: 12, from: 0)
        t = hold(&reps, kg: 12, seconds: 2, from: t)
        reps.cancelPull()
        hold(&reps, kg: 0, seconds: 1, from: t)
        XCTAssertEqual(reps.count, 1, "A cancelled pull is not counted when the load falls")
        reps.reset()
        XCTAssertEqual(reps, RepEstimator())
    }

    func testNonFiniteReadingsAreIgnored() {
        var reps = RepEstimator()
        let t = hold(&reps, kg: 20, seconds: 2, from: 0)
        reps.add(.nan, at: t)
        reps.add(0, at: .infinity)
        XCTAssertTrue(reps.isPulling)
    }
}

final class StopwatchTests: XCTestCase {
    func testStartPauseResumeBanksOnlyRunningTime() {
        var watch = Stopwatch()
        XCTAssertEqual(watch.elapsed(at: 100), 0)
        watch.start(at: 10)
        XCTAssertEqual(watch.elapsed(at: 15), 5)
        watch.pause(at: 15)
        XCTAssertFalse(watch.isRunning)
        XCTAssertEqual(watch.elapsed(at: 60), 5, "Paused time is not counted")
        watch.start(at: 60)
        XCTAssertEqual(watch.elapsed(at: 62.5), 7.5)
    }

    func testRepeatedStartOrPauseChangesNothing() {
        var watch = Stopwatch()
        watch.start(at: 0)
        watch.start(at: 5)
        XCTAssertEqual(watch.elapsed(at: 10), 10)
        watch.pause(at: 10)
        watch.pause(at: 20)
        XCTAssertEqual(watch.elapsed(at: 30), 10)
    }

    func testAClockSteppingBackCannotEraseBankedTime() {
        var watch = Stopwatch()
        watch.start(at: 0)
        watch.pause(at: 8)
        watch.start(at: 20)
        XCTAssertEqual(watch.elapsed(at: 19), 8)
    }

    func testResetZeroesAndStops() {
        var watch = Stopwatch()
        watch.start(at: 0)
        watch.reset()
        XCTAssertFalse(watch.isRunning)
        XCTAssertEqual(watch.elapsed(at: 50), 0)
    }

    func testLabel() {
        XCTAssertEqual(Stopwatch.label(0), "0:00.0")
        XCTAssertEqual(Stopwatch.label(42.37), "0:42.3")
        XCTAssertEqual(Stopwatch.label(725), "12:05.0")
        XCTAssertEqual(Stopwatch.label(3723.4), "1:02:03.4")
        XCTAssertEqual(Stopwatch.label(-3), "0:00.0")
    }
}
