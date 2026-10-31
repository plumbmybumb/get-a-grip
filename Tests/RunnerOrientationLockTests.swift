// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import UIKit
import XCTest
@testable import Doigt

/// The nightstand orientation rule as arithmetic: iPhone is portrait unless a live
/// runner holds landscape; iPad turns everywhere; a late release cannot clear a newer
/// runner's hold. See `RunnerOrientation`.
final class RunnerOrientationLockTests: XCTestCase {

    func testIPhoneIsPortraitUntilARunnerHoldsTheLock() {
        var lock = RunnerOrientationLock()
        XCTAssertEqual(lock.mask(isPad: false), .portrait)
        XCTAssertTrue(lock.set(true, by: UUID()))
        XCTAssertEqual(lock.mask(isPad: false), [.portrait, .landscapeLeft, .landscapeRight])
        XCTAssertFalse(lock.mask(isPad: false).contains(.portraitUpsideDown),
                       "a tented iPhone is never upside down")
    }

    func testIPadIsAlwaysEveryOrientation() {
        var lock = RunnerOrientationLock()
        XCTAssertEqual(lock.mask(isPad: true), .all)
        lock.set(true, by: UUID())
        XCTAssertEqual(lock.mask(isPad: true), .all)
    }

    func testALateReleaseDoesNotClearANewerRunnersHold() {
        var lock = RunnerOrientationLock()
        let old = UUID(), new = UUID()
        lock.set(true, by: old)
        // The next runner appears before the previous one's onDisappear has run.
        XCTAssertFalse(lock.set(true, by: new), "already landscape: nothing to re-apply")
        XCTAssertFalse(lock.set(false, by: old), "the newer runner still holds it")
        XCTAssertTrue(lock.allowsLandscape)
        XCTAssertTrue(lock.set(false, by: new))
        XCTAssertEqual(lock.mask(isPad: false), .portrait)
    }

    func testReleasingTwiceIsHarmless() {
        var lock = RunnerOrientationLock()
        let token = UUID()
        lock.set(true, by: token)
        XCTAssertTrue(lock.set(false, by: token))
        // A finished session releases, then its view disappears and releases again.
        XCTAssertFalse(lock.set(false, by: token))
        XCTAssertFalse(lock.allowsLandscape)
    }

    /// A device turned LEFT shows its interface turned RIGHT.
    func testTheRequestedInterfaceMirrorsTheDevice() {
        XCTAssertEqual(RunnerOrientationLock.interfaceMask(matching: .landscapeLeft), .landscapeRight)
        XCTAssertEqual(RunnerOrientationLock.interfaceMask(matching: .landscapeRight), .landscapeLeft)
        XCTAssertNil(RunnerOrientationLock.interfaceMask(matching: .portrait))
        XCTAssertNil(RunnerOrientationLock.interfaceMask(matching: .faceUp))
        XCTAssertNil(RunnerOrientationLock.interfaceMask(matching: .unknown))
    }
}
