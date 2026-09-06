// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class ActivityAndGripInteractionTests: XCTestCase {
    func testSelectingDigitsNeverLeavesOnlyTheThumbOrAnEmptyGrip() {
        for rawValue in 1...31 {
            let fingers = FingerSet(rawValue: rawValue)
            guard !fingers.intersection(.four).isEmpty else { continue }
            for finger in FingerSet.allDigits {
                let result = FingerSelection.toggling(finger, in: fingers)
                XCTAssertFalse(result.intersection(.four).isEmpty)
                let candidate = fingers.symmetricDifference(finger)
                XCTAssertEqual(result, candidate.intersection(.four).isEmpty ? fingers : candidate)
            }
        }
    }

    func testThumbCanBeRemovedWithoutChangingTheOtherFingers() {
        let pinch: FingerSet = [.index, .thumb]
        XCTAssertEqual(FingerSelection.toggling(.index, in: pinch), pinch)
        XCTAssertEqual(FingerSelection.toggling(.thumb, in: pinch), .index)
        XCTAssertEqual(FingerSelection.toggling(.middle, in: pinch), [.index, .middle, .thumb])
    }

    @MainActor
    func testBackFromHistoryRestoresTheTodaySpotlight() {
        let tour = TourController()
        tour.begin(.intro, hasRoutine: true)
        let historyIndex = TourStep.today.firstIndex { $0.target == .historyMonth }!
        for _ in 0..<historyIndex { tour.advance() }
        XCTAssertEqual(tour.current?.tab, 1)
        tour.back()
        XCTAssertEqual(tour.current?.target, .consistency)
        XCTAssertEqual(tour.current?.tab, 0)
        tour.advance()
        XCTAssertEqual(tour.current?.tab, 1)
    }

    @MainActor
    func testPresentedToursDoNotRequestATab() {
        for act in [TourAct.builder, .session] {
            let tour = TourController()
            tour.begin(act)
            XCTAssertNil(tour.current?.tab)
            tour.advance()
            XCTAssertNil(tour.current?.tab)
        }
    }

    func testReleaseWaitsWithoutAClockAndRestIsADistinctCountdownPhase() {
        XCTAssertFalse(SessionActivity.Phase.releasing.runsCountdown)
        XCTAssertFalse(SessionActivity.Phase.armed.runsCountdown)
        XCTAssertFalse(SessionActivity.Phase.paused.runsCountdown)
        XCTAssertTrue(SessionActivity.Phase.leadIn.runsCountdown)
        XCTAssertTrue(SessionActivity.Phase.pulling.runsCountdown)
        XCTAssertTrue(SessionActivity.Phase.resting.runsCountdown)
        XCTAssertNotEqual(SessionActivity.Phase.releasing, .resting)
    }
}
