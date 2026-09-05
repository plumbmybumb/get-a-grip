// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// The max rule and, mostly, the rule for when an attempt is OVER.
///
/// The measurement itself is now a one-liner — the peak sample — so the tests that
/// matter are the ones around it: a stray reading must not end an attempt early, a
/// re-grip must not either, and a quiet set-up must not end one before it has begun.
/// Those are the failures that would cost someone a real effort.
final class MaxAttemptTests: XCTestCase {

    /// Feed a constant load for `seconds` at 80 Hz, the real sample rate. Returns the
    /// timestamp one tick past the end, so callers can chain.
    @discardableResult
    private func hold(_ attempt: inout MaxAttempt, kg: Double,
                      seconds: Double, from start: Double = 0) -> Double {
        let period = 1.0 / 80
        var t = start
        let end = start + seconds
        while t < end {
            attempt.add(kg, at: t)
            t += period
        }
        return t
    }

    // MARK: - The result

    func testTheResultIsTheHardestReading() {
        var attempt = MaxAttempt()
        var t = hold(&attempt, kg: 30, seconds: 2)
        t = hold(&attempt, kg: 58, seconds: 1, from: t)
        hold(&attempt, kg: 41, seconds: 2, from: t)
        XCTAssertEqual(attempt.peakKg, 58, accuracy: 0.001)
    }

    /// The point of the change from a sustained hold: a real max decays the moment it is
    /// reached, and the number has to be what was actually pulled.
    func testABriefPeakCounts() {
        var attempt = MaxAttempt()
        var t = hold(&attempt, kg: 40, seconds: 2)
        t = hold(&attempt, kg: 66, seconds: 0.15, from: t)
        hold(&attempt, kg: 38, seconds: 2, from: t)
        XCTAssertEqual(attempt.peakKg, 66, accuracy: 0.001,
                       "A peak counts however briefly it lasted — see MaxAttempt")
    }

    /// The live display promises "this is what will be saved", so it must never retreat.
    func testTheResultNeverDecreases() {
        var attempt = MaxAttempt()
        var seen = 0.0
        let period = 1.0 / 80
        var t = 0.0
        while t < 10 {
            attempt.add(max(0, 30 + 20 * sin(t)), at: t)
            XCTAssertGreaterThanOrEqual(attempt.peakKg, seen)
            seen = attempt.peakKg
            t += period
        }
    }

    func testAnEmptyAttemptHasNoResult() {
        let attempt = MaxAttempt()
        XCTAssertFalse(attempt.hasResult)
        XCTAssertFalse(attempt.isComplete)
        XCTAssertEqual(attempt.peakKg, 0)
    }

    /// Idle drift is not a max. Without this, a screen opened and left alone would offer
    /// to record the load cell's own noise.
    func testNoiseBelowTheThresholdIsNotAResult() {
        var attempt = MaxAttempt()
        hold(&attempt, kg: 0.4, seconds: 10)
        XCTAssertFalse(attempt.hasResult)
        XCTAssertGreaterThan(attempt.peakKg, 0, "The reading is still tracked")
    }

    // MARK: - Ending

    func testPullThenLetGoFinishesOnItsOwn() {
        var attempt = MaxAttempt()
        var t = hold(&attempt, kg: 45, seconds: 3)
        XCTAssertFalse(attempt.isComplete, "Still on the edge")

        t = hold(&attempt, kg: 0.3, seconds: MaxAttempt.releaseSeconds + 0.2, from: t)
        XCTAssertTrue(attempt.isComplete)
        XCTAssertEqual(attempt.peakKg, 45, accuracy: 0.001,
                       "Letting go must not disturb the result")

        // Anything after completion is ignored, so a stray sample — or the gauge being
        // knocked while the result is on screen — cannot rewrite a finished attempt.
        hold(&attempt, kg: 90, seconds: 3, from: t)
        XCTAssertEqual(attempt.peakKg, 45, accuracy: 0.001)
    }

    /// Re-gripping between efforts must not end the attempt.
    func testABriefReleaseDoesNotFinishTheAttempt() {
        var attempt = MaxAttempt()
        var t = hold(&attempt, kg: 45, seconds: 2)
        t = hold(&attempt, kg: 0.5, seconds: MaxAttempt.releaseSeconds - 0.5, from: t)
        XCTAssertFalse(attempt.isComplete)

        // …and the second effort still counts.
        hold(&attempt, kg: 58, seconds: 2, from: t)
        XCTAssertEqual(attempt.peakKg, 58, accuracy: 0.001)
    }

    /// THE regression this guard exists for: settling onto the edge is entirely below
    /// the release threshold, and an attempt that ended during it would be over before
    /// the first pull.
    func testAQuietSetUpDoesNotFinishTheAttempt() {
        var attempt = MaxAttempt()
        let t = hold(&attempt, kg: 0.4, seconds: 20)
        XCTAssertFalse(attempt.isComplete)
        XCTAssertFalse(attempt.hasResult)

        hold(&attempt, kg: 40, seconds: 3, from: t)
        XCTAssertEqual(attempt.peakKg, 40, accuracy: 0.001)
    }

    /// A brush against the edge that never reaches `releaseKg` is not a pull, so it must
    /// not arm the auto-finish either.
    func testBrushingTheEdgeBelowTheThresholdDoesNotArmTheFinish() {
        var attempt = MaxAttempt()
        var t = hold(&attempt, kg: 1.5, seconds: 1)
        t = hold(&attempt, kg: 0, seconds: 5, from: t)
        XCTAssertFalse(attempt.isComplete)
    }

    func testFinishingByHandKeepsThePeak() {
        var attempt = MaxAttempt()
        hold(&attempt, kg: 33, seconds: 2)
        attempt.finish()
        XCTAssertTrue(attempt.isComplete)
        XCTAssertEqual(attempt.peakKg, 33, accuracy: 0.001)
    }

    /// Dropped BLE packets leave a GAP in the timeline rather than a low reading, and a
    /// gap that spans the release window while you are still pulling must not end the
    /// attempt — the samples either side are both above the threshold.
    func testAGapWhileStillPullingDoesNotFinishTheAttempt() {
        var attempt = MaxAttempt()
        var t = hold(&attempt, kg: 50, seconds: 1)
        t += 4                                   // four seconds of silence
        hold(&attempt, kg: 50, seconds: 1, from: t)
        XCTAssertFalse(attempt.isComplete)
        XCTAssertEqual(attempt.peakKg, 50, accuracy: 0.001)
    }

    // MARK: - Robustness

    func testNonFiniteSamplesAreIgnored() {
        var attempt = MaxAttempt()
        hold(&attempt, kg: 40, seconds: 2)
        attempt.add(.nan, at: 99)
        attempt.add(200, at: .infinity)
        XCTAssertEqual(attempt.peakKg, 40, accuracy: 0.001)
    }

    /// The window is gone, so the sample rate cannot influence the peak — pinned because
    /// a lossy link must not report a different max from a clean one.
    func testTheRateOfSamplingDoesNotChangeTheAnswer() {
        func run(period: Double) -> Double {
            var attempt = MaxAttempt()
            var t = 0.0
            while t < 4 {
                attempt.add(t < 1 ? 10 : 47, at: t)
                t += period
            }
            return attempt.peakKg
        }
        XCTAssertEqual(run(period: 1.0 / 80), 47, accuracy: 0.001)
        XCTAssertEqual(run(period: 1.0 / 10), 47, accuracy: 0.001)
        XCTAssertEqual(run(period: 1.0 / 4), 47, accuracy: 0.001)
    }
}
