// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class FaceFlipPolicyTests: XCTestCase {

    func testFlipsForTheWatchHandWhileTheHandIsOnTheBlock() {
        for phase: RunnerPhase in [.leadIn(slot: 0), .armed(slot: 0), .working(slot: 0), .releasing(slot: 0)] {
            XCTAssertTrue(FaceFlipPolicy.shouldFlip(phase: phase, side: .left, wrist: .left, enabled: true),
                          "\(phase): the watch hand is on the block")
            XCTAssertFalse(FaceFlipPolicy.shouldFlip(phase: phase, side: .right, wrist: .left, enabled: true),
                           "\(phase): the other hand pulls, the watch wrist is free")
            XCTAssertTrue(FaceFlipPolicy.shouldFlip(phase: phase, side: .both, wrist: .right, enabled: true),
                          "\(phase): both hands on the block includes the watch hand")
        }
    }

    func testTurnsTowardTheHandOnEitherWrist() {
        XCTAssertEqual(FaceFlipPolicy.rotationDegrees(wrist: .left), 90, "up goes to 3 o'clock")
        XCTAssertEqual(FaceFlipPolicy.rotationDegrees(wrist: .right), -90, "up goes to 9 o'clock")
    }

    func testNeverFlipsBetweenPullsOrWhenSwitchedOff() {
        XCTAssertFalse(FaceFlipPolicy.shouldFlip(phase: .resting(slot: 0), side: .left, wrist: .left, enabled: true),
                       "a rest looks forward to the next hand while the wrist is at your side")
        XCTAssertFalse(FaceFlipPolicy.shouldFlip(phase: .paused(before: .working(slot: 0)),
                                                 side: .left, wrist: .left, enabled: true),
                       "a pause is a tap, made the normal way up")
        XCTAssertFalse(FaceFlipPolicy.shouldFlip(phase: .idle, side: .left, wrist: .left, enabled: true))
        XCTAssertFalse(FaceFlipPolicy.shouldFlip(phase: .finished, side: .left, wrist: .left, enabled: true))
        XCTAssertFalse(FaceFlipPolicy.shouldFlip(phase: .working(slot: 0), side: nil, wrist: .left, enabled: true))
        XCTAssertFalse(FaceFlipPolicy.shouldFlip(phase: .working(slot: 0), side: .left, wrist: .left, enabled: false),
                       "the switch wins for anyone whose block posture is different")
    }
}
