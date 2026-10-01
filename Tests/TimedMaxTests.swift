// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// Timed maxes (2026-10-01): "the most you can hold for 10 s", measured as the AVERAGE
/// over the window, stored beside the peak, and named by a routine's percentage.
final class TimedMaxTests: XCTestCase {
    private let grip = GripSpec()

    /// Feeds `kg(t)` at 80 Hz from `start` for `seconds`; returns the time after.
    @discardableResult
    private func feed(_ attempt: inout MaxAttempt, seconds: Double, from start: Double = 0,
                      kg: (Double) -> Double) -> Double {
        var t = start
        while t < start + seconds {
            attempt.add(kg(t), at: t)
            t += 1.0 / 80
        }
        return t
    }

    // MARK: - The measurement

    /// The result is the mean over the window, not the peak and not the floor: a pull
    /// that fades linearly from 40 to 30 kg over 10 s holds an average of 35.
    func testATimedResultIsTheAverageOverTheWindow() {
        var attempt = MaxAttempt(window: 10)
        let t = feed(&attempt, seconds: 10.5) { 40 - $0 }
        feed(&attempt, seconds: 1.5, from: t) { _ in 0 }
        XCTAssertTrue(attempt.hasResult)
        XCTAssertEqual(attempt.resultKg ?? 0, 35, accuracy: 0.1)
        XCTAssertEqual(attempt.peakKg, 40, accuracy: 0.01, "The peak is still known — just not the result")
    }

    /// Letting go before the window closes is no result: you did not hold for 10 s.
    func testLettingGoEarlyIsNoResult() {
        var attempt = MaxAttempt(window: 10)
        let t = feed(&attempt, seconds: 6) { _ in 40 }
        feed(&attempt, seconds: 3, from: t) { _ in 0 }
        XCTAssertTrue(attempt.isComplete, "Release still ends the attempt")
        XCTAssertFalse(attempt.hasResult)
        XCTAssertNil(attempt.resultKg)
        XCTAssertEqual(attempt.heldSeconds, 6, accuracy: 0.05)
    }

    /// Coming off the edge ends the window: a 9 s hang plus the release wait must not
    /// pass as a 10 s max with zeros averaged in.
    func testLettingGoJustShortIsNoResultEvenAfterTheReleaseWait() {
        var attempt = MaxAttempt(endsAfter: 1, window: 10)
        let t = feed(&attempt, seconds: 9.2) { _ in 40 }
        feed(&attempt, seconds: 2, from: t) { _ in 0 }
        XCTAssertTrue(attempt.isComplete)
        XCTAssertNil(attempt.resultKg)
        XCTAssertLessThan(attempt.heldSeconds, 10)
    }

    /// Load after the window closes is not averaged in — holding on past ten seconds while
    /// fading must not drag a 10 s max down.
    func testLoadAfterTheWindowIsNotCounted() {
        var attempt = MaxAttempt(window: 5)
        var t = feed(&attempt, seconds: 5.2) { _ in 30 }
        t = feed(&attempt, seconds: 4, from: t) { _ in 10 }
        XCTAssertEqual(attempt.resultKg ?? 0, 30, accuracy: 0.05)
        XCTAssertEqual(attempt.remainingSeconds, 0)
    }

    /// Sample-and-hold: a reading that stood twice as long weighs twice as much, so
    /// bursty delivery cannot skew the average toward whichever readings arrived densely.
    func testReadingsAreWeightedByHowLongTheyStood() {
        var attempt = MaxAttempt(window: 3)
        attempt.add(20, at: 0)
        attempt.add(40, at: 1)   // 20 kg stood for 1 s
        attempt.add(40, at: 3)   // 40 kg stood for 2 s
        XCTAssertEqual(attempt.resultKg ?? 0, (20 * 1 + 40 * 2) / 3.0, accuracy: 0.001)
    }

    /// A peak attempt is unchanged by any of this.
    func testAPeakAttemptStillRecordsThePeak() {
        var attempt = MaxAttempt()
        let t = feed(&attempt, seconds: 2) { 40 - $0 * 5 }
        feed(&attempt, seconds: 3, from: t) { _ in 0 }
        XCTAssertEqual(attempt.resultKg ?? 0, 40, accuracy: 0.01)
    }

    // MARK: - The visit

    func testATimedVisitLogsAveragesAndRemembersAShortPull() {
        var log = MaxAttemptLog(side: .left, windowSeconds: 5)
        var t = 0.0
        func hold(_ kg: Double, _ seconds: Double) {
            let end = t + seconds
            while t < end { log.add(kg, at: t); t += 1.0 / 80 }
        }
        hold(30, 3); hold(0, 2)
        XCTAssertTrue(log.attempts.isEmpty, "A 3 s pull is not a 5 s max")
        XCTAssertEqual(log.lastShortSeconds ?? 0, 3, accuracy: 0.05)
        hold(32, 5.5); hold(0, 2)
        XCTAssertEqual(log.attempts.count, 1)
        XCTAssertEqual(log.attempts.first?.kg ?? 0, 32, accuracy: 0.05)
        XCTAssertNil(log.lastShortSeconds, "A new pull clears the short-pull notice")
    }

    // MARK: - The table

    /// Peak keys are byte-identical to the format every stored max already uses.
    func testPeakKeysAreUnchangedAndTimedKeysAreDistinct() {
        XCTAssertEqual(MaxTable.key(grip: grip.key, side: .left), "\(grip.key)|left")
        XCTAssertEqual(MaxTable.key(grip: grip.key, side: .left, seconds: 0), "\(grip.key)|left")
        XCTAssertEqual(MaxTable.key(grip: grip.key, side: .left, seconds: 10), "\(grip.key)|left|10s")
    }

    /// A timed lookup never falls back to the peak — but the hand still falls back to the
    /// shared max OF THE SAME LENGTH.
    func testATimedLookupNeverFallsBackToThePeak() {
        var table = MaxTable()
        table.record(40, grip: grip.key, side: .both)
        XCTAssertNil(table.max(grip: grip.key, side: .left, seconds: 10))
        table.record(33, grip: grip.key, side: .both, seconds: 10)
        XCTAssertEqual(table.max(grip: grip.key, side: .left, seconds: 10), 33)
        XCTAssertEqual(table.max(grip: grip.key, side: .left), 40, "The peak is untouched")
        table.record(7, grip: grip.key, side: .right, seconds: 7)
        XCTAssertEqual(table.timedLengths(grip: grip.key), [7, 10])
        XCTAssertEqual(table.timedLengths, [7, 10])
        XCTAssertEqual(table.timedLengths(grip: GripSpec(edgeMM: 15).key), [])
    }

    // MARK: - The plan

    private func plan(setPercent: ClosedRange<Double>? = nil, setSeconds: Int? = nil,
                      routinePercent: ClosedRange<Double>? = nil, routineSeconds: Int? = nil) -> SessionPlan {
        var set = SetPlan(grip: grip, repsPerSide: 2)
        set.targetLoPercent = setPercent?.lowerBound
        set.targetHiPercent = setPercent?.upperBound
        set.targetMaxSeconds = setSeconds
        var plan = SessionPlan()
        plan.handMode = .bothHands
        plan.sets = [set]
        plan.targetLoPercent = routinePercent?.lowerBound
        plan.targetHiPercent = routinePercent?.upperBound
        plan.targetMaxSeconds = routineSeconds
        return plan
    }

    private var table: MaxTable {
        var table = MaxTable()
        table.record(40, grip: grip.key, side: .both)
        table.record(30, grip: grip.key, side: .both, seconds: 10)
        return table
    }

    func testAPercentageResolvesAgainstTheMaxItNames() {
        let peak = plan(setPercent: 0.9...0.9)
        XCTAssertEqual(PlanMath.targetBand(peak.sets[0], in: peak, side: .both, maxes: table), 36...36)
        let timed = plan(setPercent: 0.9...0.9, setSeconds: 10)
        XCTAssertEqual(PlanMath.targetBand(timed.sets[0], in: timed, side: .both, maxes: table), 27...27)
    }

    func testATimedBasisWithNoTimedMaxIsNoTargetNeverThePeak() {
        let p = plan(setPercent: 0.9...0.9, setSeconds: 7)
        XCTAssertNil(PlanMath.targetBand(p.sets[0], in: p, side: .both, maxes: table))
        XCTAssertEqual(PlanMath.untargetedGripCount(p, maxes: table), 1)
    }

    /// The basis travels with the band: an inheriting set uses the ROUTINE's basis, a set
    /// with its own band uses its own.
    func testTheBasisComesFromTheLevelTheBandComesFrom() {
        let inherits = plan(routinePercent: 0.9...0.9, routineSeconds: 10)
        XCTAssertEqual(PlanMath.maxSeconds(inherits.sets[0], in: inherits), 10)
        let own = plan(setPercent: 0.5...0.5, routinePercent: 0.9...0.9, routineSeconds: 10)
        XCTAssertNil(PlanMath.maxSeconds(own.sets[0], in: own), "Its own band, its own (peak) basis")
    }

    /// The card's intensity colour reads a timed percentage against the PEAK where both are
    /// on file — 90 % of a 30 kg 10 s max is 67.5 % of a 40 kg peak, not near-max.
    func testIntensityRestatesATimedPercentageAgainstThePeak() {
        let p = plan(setPercent: 0.8...0.9, setSeconds: 10)
        XCTAssertEqual(PlanMath.peakIntensity(of: p, maxes: table) ?? 0, 0.675, accuracy: 0.0001)
        var noPeak = MaxTable()
        noPeak.record(30, grip: grip.key, side: .both, seconds: 10)
        XCTAssertNil(PlanMath.peakIntensity(of: p, maxes: noPeak), "No peak to compare: no colour, not a guess")
    }

    // MARK: - Storage

    func testTheBasisRoundTripsAndOldBlobsReadAsPeak() throws {
        let p = plan(setPercent: 0.9...0.9, setSeconds: 10, routineSeconds: 7)
        let decoded = try JSONDecoder().decode(SessionPlan.self, from: JSONEncoder().encode(p))
        XCTAssertEqual(decoded.sets[0].targetMaxSeconds, 10)
        XCTAssertEqual(decoded.targetMaxSeconds, 7)

        let legacy = Data(#"{"repsPerSide":3,"targetLoPercent":0.2,"targetHiPercent":0.3}"#.utf8)
        XCTAssertNil(try JSONDecoder().decode(SetPlan.self, from: legacy).targetMaxSeconds)
        let zero = Data(#"{"repsPerSide":3,"targetMaxSeconds":0}"#.utf8)
        XCTAssertNil(try JSONDecoder().decode(SetPlan.self, from: zero).targetMaxSeconds)
    }

    /// Save demotes the routine band onto the sets; its basis must go with it, or every
    /// set would silently fall back to the peak.
    func testNormalizingCarriesTheRoutineBasisOntoTheSets() {
        var draft = RoutineDraft.blank()
        draft.plan = plan(routinePercent: 0.9...0.9, routineSeconds: 10)
        let saved = draft.normalized.plan
        XCTAssertNil(saved.targetPercentBand)
        XCTAssertNil(saved.targetMaxSeconds)
        XCTAssertEqual(saved.sets[0].targetMaxSeconds, 10)
        XCTAssertEqual(PlanMath.targetBand(saved.sets[0], in: saved, side: .both, maxes: table), 27...27)
    }

    /// A length left behind on a set with no percentage of its own says nothing.
    func testNormalizingDropsAnOrphanedBasis() {
        var draft = RoutineDraft.blank()
        draft.plan = plan(setSeconds: 10)
        XCTAssertNil(draft.normalized.plan.sets[0].targetMaxSeconds)
    }
}
