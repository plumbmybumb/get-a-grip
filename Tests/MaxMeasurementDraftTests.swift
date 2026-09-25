// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class MaxMeasurementDraftTests: XCTestCase {
    /// One logged pull at `kg` on the draft's selected hand, starting at `t`.
    @discardableResult
    private func pull(_ draft: inout MaxMeasurementDraft, kg: Double, at t: TimeInterval) -> TimeInterval {
        draft.add(kg, at: t)
        draft.add(kg, at: t + 0.5)
        draft.add(0.1, at: t + 0.6)
        draft.add(0.1, at: t + 0.6 + MaxAttemptLog.releaseSeconds)
        return t + 1 + MaxAttemptLog.releaseSeconds
    }

    func testEachHandSavesItsHardestPullByDefault() {
        var draft = MaxMeasurementDraft()
        var t = pull(&draft, kg: 37, at: 0)
        t = pull(&draft, kg: 39, at: t)
        t = pull(&draft, kg: 38, at: t)
        draft.select(.right)
        pull(&draft, kg: 42, at: t)
        XCTAssertEqual(draft.results, [.init(side: .left, kg: 39), .init(side: .right, kg: 42)])
    }

    func testAPickSurvivesLaterPullsAndFollowsItsAttempt() {
        var draft = MaxMeasurementDraft()
        var t = pull(&draft, kg: 37, at: 0)
        t = pull(&draft, kg: 40, at: t)
        draft.pick(draft.log.attempts[0].id)
        t = pull(&draft, kg: 44, at: t)
        XCTAssertEqual(draft.results, [.init(side: .left, kg: 37)],
                       "A deliberate pick is kept even after a harder pull")
        draft.move(draft.log.attempts[0].id, to: .right)
        XCTAssertEqual(draft.results, [.init(side: .left, kg: 44), .init(side: .right, kg: 37)],
                       "Moving the picked pull clears the pick on both hands")
    }

    func testCorrectionBelongsToThePullItCorrects() {
        var draft = MaxMeasurementDraft()
        var t = pull(&draft, kg: 37, at: 0)
        XCTAssertTrue(draft.correct([.init(side: .left, kg: 36.5)]))
        XCTAssertEqual(draft.results, [.init(side: .left, kg: 36.5, source: .manual)])
        XCTAssertEqual(draft.measuredPeak(for: .left), 37)
        XCTAssertTrue(draft.correct([.init(side: .left, kg: 37)]))
        XCTAssertEqual(draft.results.first?.source, .measured, "The exact peak restores measured provenance")
        draft.correct([.init(side: .left, kg: 36.5)])
        t = pull(&draft, kg: 35, at: t)
        XCTAssertEqual(draft.results, [.init(side: .left, kg: 37)],
                       "A new pull on the hand retires the correction")
    }

    func testCorrectionRejectsInvalidValuesAtomicallyAndCannotInventAHand() {
        var draft = MaxMeasurementDraft()
        let t = pull(&draft, kg: 37, at: 0)
        draft.select(.right)
        pull(&draft, kg: 42, at: t)
        let original = draft.results
        for bad in [0, -1, Double.nan, .infinity] {
            XCTAssertFalse(draft.correct([.init(side: .left, kg: 36), .init(side: .right, kg: bad)]))
            XCTAssertEqual(draft.results, original)
        }
        XCTAssertFalse(draft.correct([.init(side: .both, kg: 80)]))
        XCTAssertFalse(draft.correct([.init(side: .left, kg: 36), .init(side: .left, kg: 35)]))
        XCTAssertEqual(draft.results, original)
    }

    func testCorrectionIsRefusedMidPull() {
        var draft = MaxMeasurementDraft()
        let t = pull(&draft, kg: 37, at: 0)
        draft.add(30, at: t)
        XCTAssertFalse(draft.correct([.init(side: .left, kg: 36)]))
    }

    func testAHandWithNoPullSavesNothing() {
        var draft = MaxMeasurementDraft()
        draft.select(.right)
        pull(&draft, kg: 41, at: 0)
        XCTAssertEqual(draft.results, [.init(side: .right, kg: 41)])
        for value in [Double.nan, .infinity, -.infinity, -3, 0, MaxAttempt.releaseKg - 0.01] {
            var empty = MaxMeasurementDraft()
            pull(&empty, kg: value, at: 0)
            XCTAssertTrue(empty.results.isEmpty)
        }
    }

    func testDeletingThePickedPullFallsBackToTheBest() {
        var draft = MaxMeasurementDraft()
        var t = pull(&draft, kg: 37, at: 0)
        t = pull(&draft, kg: 40, at: t)
        draft.pick(draft.log.attempts[0].id)
        draft.remove(draft.log.attempts[0].id)
        XCTAssertEqual(draft.results, [.init(side: .left, kg: 40)])
    }

    func testSeparateHandsNeverProduceASharedBenchmark() {
        var draft = MaxMeasurementDraft()
        draft.select(.both)
        XCTAssertEqual(draft.log.side, .left)
        pull(&draft, kg: 37, at: 0)
        XCTAssertEqual(draft.results.map(\.side), [.left])
    }

    func testCombinedModeSavesOneSharedValueAndCannotSplit() {
        var draft = MaxMeasurementDraft(bothTogether: true)
        XCTAssertEqual(draft.log.side, .both)
        draft.select(.left)
        let t = pull(&draft, kg: 76, at: 0)
        pull(&draft, kg: 80, at: t)
        draft.move(draft.log.attempts[0].id, to: .left)
        XCTAssertEqual(draft.results, [.init(side: .both, kg: 80)])
        XCTAssertTrue(draft.correct([.init(side: .both, kg: 78)]))
        XCTAssertEqual(draft.results, [.init(side: .both, kg: 78, source: .manual)])
    }
}
