// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class SessionMaxCandidateTests: XCTestCase {
    private let grip = GripSpec(edgeMM: 20, fingers: .four, position: .halfCrimp)

    private func rep(_ side: Side, _ kg: Double, grip: GripSpec? = nil,
                     outcome: RepOutcome = .completed) -> RepSummary {
        RepSummary(side: side, grip: grip ?? self.grip, heldSeconds: 10,
                   peakKg: kg, avgKg: kg, outcome: outcome)
    }

    func testStrongestCompletedPeakIsKeptSeparatelyForEveryGripAndHand() {
        let other = GripSpec(edgeMM: 10, fingers: .frontTwo, position: .openHand)
        let candidates = SessionMaxCandidate.from(reps: [
            rep(.left, 35), rep(.right, 30), rep(.left, 40), rep(.right, 28),
            rep(.both, 60), rep(.left, 20, grip: other)
        ], maxes: MaxTable())

        XCTAssertEqual(candidates.map(\.kg), [60, 40, 30, 20])
        XCTAssertEqual(Set(candidates.map(\.id)), [
            MaxTable.key(grip: grip.key, side: .left),
            MaxTable.key(grip: grip.key, side: .right),
            MaxTable.key(grip: grip.key, side: .both),
            MaxTable.key(grip: other.key, side: .left)
        ])
        XCTAssertEqual(candidates.filter { $0.grip == grip }.map(\.side), [.both, .left, .right])
        XCTAssertTrue(candidates.allSatisfy { $0.previous == nil })
    }

    func testSharedTargetFallbackDoesNotHideTheFirstMaxForEitherHand() {
        var maxes = MaxTable()
        maxes.record(60, grip: grip.key, side: .both)
        let candidates = SessionMaxCandidate.from(
            reps: [rep(.left, 35), rep(.right, 30), rep(.both, 59)], maxes: maxes)

        XCTAssertEqual(candidates.map(\.side), [.left, .right])
        XCTAssertEqual(candidates.map(\.kg), [35, 30])
        XCTAssertTrue(candidates.allSatisfy { $0.previous == nil },
                      "A shared fallback is not an earlier record for either hand")
        XCTAssertEqual(maxes.max(grip: grip.key, side: .left), 60,
                       "Candidate review must not change target fallback behavior")
    }

    func testExistingMaxComparisonUsesOnlyTheSameGripAndHand() {
        var maxes = MaxTable()
        maxes.record(60, grip: grip.key, side: .both)
        maxes.record(40, grip: grip.key, side: .left)
        maxes.record(30, grip: grip.key, side: .right)
        let candidates = SessionMaxCandidate.from(reps: [
            rep(.left, 39), rep(.left, 40), rep(.right, 31), rep(.both, 59)
        ], maxes: maxes)

        XCTAssertEqual(candidates.count, 1)
        XCTAssertEqual(candidates.first?.side, .right)
        XCTAssertEqual(candidates.first?.kg, 31)
        XCTAssertEqual(candidates.first?.previous, 30)
    }

    func testTwoHandedPeakReplacesOnlyATwoHandedMax() {
        var maxes = MaxTable()
        maxes.record(50, grip: grip.key, side: .left)
        maxes.record(45, grip: grip.key, side: .right)
        let first = SessionMaxCandidate.from(reps: [rep(.both, 40)], maxes: maxes)
        XCTAssertEqual(first.first?.side, .both)
        XCTAssertNil(first.first?.previous)

        maxes.record(38, grip: grip.key, side: .both)
        let next = SessionMaxCandidate.from(reps: [rep(.both, 40)], maxes: maxes)
        XCTAssertEqual(next.first?.side, .both)
        XCTAssertEqual(next.first?.previous, 38)
    }

    func testUnfinishedTimerOnlyAndInvalidPeaksAreNotOffered() {
        var reps = [RepOutcome.aborted, .skipped, .earlyRelease].map {
            rep(.left, 50, outcome: $0)
        }
        reps += [Double.nan, .infinity, -.infinity, -5, 0, 1].map { rep(.right, $0) }
        XCTAssertTrue(SessionMaxCandidate.from(reps: reps, maxes: MaxTable()).isEmpty)
    }
}
