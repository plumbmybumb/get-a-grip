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
