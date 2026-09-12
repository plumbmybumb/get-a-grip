// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class MaxMeasurementDraftTests: XCTestCase {
    func testCorrectionBeforeSaveKeepsHandOwnershipAndChangesOnlyItsProvenance() {
        var draft = MaxMeasurementDraft()
        draft.begin(side: .left)
        draft.finish(peakKg: 37)
        draft.begin(side: .right)
        draft.finish(peakKg: 42)
        XCTAssertTrue(draft.correct([.init(side: .left, kg: 36.5)]))
        XCTAssertEqual(draft.results, [.init(side: .left, kg: 36.5, source: .manual),
                                      .init(side: .right, kg: 42)])
        XCTAssertEqual(draft.measuredPeak(for: .left), 37)
        XCTAssertTrue(draft.correct([.init(side: .left, kg: 37)]))
        XCTAssertEqual(draft.results.first?.source, .measured)
    }

    func testCorrectionRejectsInvalidValuesAtomicallyAndCannotInventAHand() {
        var draft = MaxMeasurementDraft()
        draft.begin(side: .left)
        draft.finish(peakKg: 37)
        draft.begin(side: .right)
        draft.finish(peakKg: 42)
        let original = draft.results
        for bad in [0, -1, Double.nan, .infinity] {
            XCTAssertFalse(draft.correct([.init(side: .left, kg: 36), .init(side: .right, kg: bad)]))
            XCTAssertEqual(draft.results, original)
        }
        XCTAssertFalse(draft.correct([.init(side: .both, kg: 80)]))
        XCTAssertFalse(draft.correct([.init(side: .left, kg: 36), .init(side: .left, kg: 35)]))
        XCTAssertEqual(draft.results, original)
        draft.begin(side: .left)
        XCTAssertFalse(draft.correct([.init(side: .right, kg: 40)]))
    }

    func testFailedRetryPreservesCorrectionButValidRetryRestoresMeasuredSource() {
        var draft = MaxMeasurementDraft()
        draft.begin(side: .left)
        draft.finish(peakKg: 37)
        draft.correct([.init(side: .left, kg: 36.5)])
        draft.begin(side: .left)
        draft.finish(peakKg: 0)
        XCTAssertEqual(draft.results, [.init(side: .left, kg: 36.5, source: .manual)])
        draft.begin(side: .left)
        draft.finish(peakKg: 38)
        XCTAssertEqual(draft.results, [.init(side: .left, kg: 38)])
    }

    func testCombinedCorrectionRemainsOneSharedManualResult() {
        var draft = MaxMeasurementDraft(bothTogether: true)
        draft.begin(side: .both)
        draft.finish(peakKg: 80)
        XCTAssertTrue(draft.correct([.init(side: .both, kg: 78)]))
        XCTAssertEqual(draft.results, [.init(side: .both, kg: 78, source: .manual)])
    }

    func testSeparateAttemptsStayAttachedToTheirOriginalHands() {
        var draft = MaxMeasurementDraft()
        XCTAssertTrue(draft.begin(side: .left))
        XCTAssertFalse(draft.begin(side: .right))
        XCTAssertEqual(draft.finish(peakKg: 37), .left)
        XCTAssertTrue(draft.begin(side: .right))
        XCTAssertTrue(draft.results.isEmpty, "An unfinished second attempt must not expose a partial save")
        XCTAssertEqual(draft.finish(peakKg: 42), .right)
        XCTAssertEqual(draft.results, [.init(side: .left, kg: 37), .init(side: .right, kg: 42)])
    }

    func testRetryOnlyReplacesTheSelectedHandAndCanRecordALowerPeak() {
        var draft = MaxMeasurementDraft()
        XCTAssertTrue(draft.begin(side: .left))
        draft.finish(peakKg: 45)
        XCTAssertTrue(draft.begin(side: .right))
        draft.finish(peakKg: 50)
        XCTAssertTrue(draft.begin(side: .left))
        XCTAssertEqual(draft.peak(for: .left), 45, "A retry keeps the prior peak until it has a valid replacement")
        XCTAssertEqual(draft.peak(for: .right), 50)
        draft.finish(peakKg: 40)
        XCTAssertEqual(draft.results, [.init(side: .left, kg: 40), .init(side: .right, kg: 50)])
    }

    func testFailedRetryPreservesBothCapturedHands() {
        for value in [Double.nan, .infinity, -.infinity, -3, 0, MaxAttempt.releaseKg - 0.01] {
            var draft = MaxMeasurementDraft()
            XCTAssertTrue(draft.begin(side: .left))
            draft.finish(peakKg: 37)
            XCTAssertTrue(draft.begin(side: .right))
            draft.finish(peakKg: 42)
            XCTAssertTrue(draft.begin(side: .left))
            XCTAssertFalse(draft.begin(side: .right), "Retry ownership remains locked to the left hand")
            XCTAssertTrue(draft.results.isEmpty)
            draft.finish(peakKg: value)
            XCTAssertNil(draft.activeSide)
            XCTAssertEqual(draft.results, [.init(side: .left, kg: 37), .init(side: .right, kg: 42)])
        }
    }

    func testNoPullOnSecondHandKeepsTheFirstResultSavable() {
        var draft = MaxMeasurementDraft()
        XCTAssertTrue(draft.begin(side: .right))
        draft.finish(peakKg: 41)
        XCTAssertTrue(draft.begin(side: .left))
        draft.finish(peakKg: 0.4)
        XCTAssertEqual(draft.results, [.init(side: .right, kg: 41)])
    }

    func testInvalidPeaksNeverBecomeBenchmarks() {
        for value in [Double.nan, .infinity, -.infinity, -3, 0, MaxAttempt.releaseKg - 0.01] {
            var draft = MaxMeasurementDraft()
            XCTAssertTrue(draft.begin(side: .left))
            draft.finish(peakKg: value)
            XCTAssertTrue(draft.results.isEmpty)
            XCTAssertNil(draft.activeSide)
        }
    }

    func testCompletionWithoutAnAttemptCannotInventAHand() {
        var draft = MaxMeasurementDraft()
        XCTAssertNil(draft.finish(peakKg: 40))
        XCTAssertTrue(draft.results.isEmpty)
        XCTAssertTrue(draft.begin(side: .left))
        draft.finish(peakKg: 30)
        XCTAssertNil(draft.finish(peakKg: 60))
        XCTAssertEqual(draft.results, [.init(side: .left, kg: 30)])
    }

    func testSeparateHandsNeverProduceASharedBenchmark() {
        var draft = MaxMeasurementDraft()
        XCTAssertFalse(draft.begin(side: .both))
        XCTAssertNil(draft.activeSide)
        XCTAssertTrue(draft.results.isEmpty)
    }

    func testCombinedMeasurementRequiresItsExplicitMode() {
        var draft = MaxMeasurementDraft(bothTogether: true)
        XCTAssertFalse(draft.begin(side: .left))
        XCTAssertFalse(draft.begin(side: .right))
        XCTAssertTrue(draft.begin(side: .both))
        draft.finish(peakKg: 76)
        XCTAssertEqual(draft.results, [.init(side: .both, kg: 76)])
    }
}
