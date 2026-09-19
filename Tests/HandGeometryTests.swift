// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

/// **One hand, four marks** — and the numbers that make them one mark.
///
/// `HandMark` (the widget), `IslandHand` (under the cutout), `FingerGlyph` (the app) and
/// `EdgeMark` (the routine card) are the same drawing at four sizes, which is what lets
/// the app icon and the UI stay the same picture. They used to hold four copies of this
/// array, and four copies is how that promise breaks quietly: one gets tuned, the hand on
/// the card stops being the hand on the island, and nothing fails.
///
/// So the shape is pinned here rather than left to eyes on a screenshot.
final class HandGeometryTests: XCTestCase {

    func testTheFourLengthFactorsAreTheHouseHand() {
        XCTAssertEqual(HandGeometry.lengthFactor, [0.86, 1.0, 0.94, 0.80])
    }

    /// Index, MIDDLE, ring, little — anatomical order, so the mirror that flips a left
    /// hand to a right one keeps the middle finger longest either way round.
    func testTheMiddleFingerIsTheLongest() {
        let factors = HandGeometry.lengthFactor
        XCTAssertEqual(factors.count, 4, "Four fingers; the thumb is drawn separately")
        let middle = factors[1]
        XCTAssertEqual(middle, factors.max())
        for (index, factor) in factors.enumerated() where index != 1 {
            XCTAssertLessThan(factor, middle, "Only the middle finger may be the longest")
        }
        XCTAssertEqual(HandGeometry.longestFactor, middle)
    }

    /// The little finger is the shortest, and the index sits between it and the ring —
    /// the order is what stops the row reading as a chart instead of a hand.
    func testTheLadderRunsLittleIndexRingMiddle() {
        let (index, middle, ring, little) = (HandGeometry.lengthFactor[0], HandGeometry.lengthFactor[1],
                                             HandGeometry.lengthFactor[2], HandGeometry.lengthFactor[3])
        XCTAssertEqual(little, HandGeometry.lengthFactor.min())
        XCTAssertLessThan(little, index)
        XCTAssertLessThan(index, ring)
        XCTAssertLessThan(ring, middle)
    }

    /// A finger is TALLER than it is wide, and by enough that no bar can round into a
    /// dot — the collision that made session dots and finger pips indistinguishable.
    func testABarIsTallerThanItIsWide() {
        XCTAssertGreaterThan(HandGeometry.barAspect, 1.5)
        XCTAssertEqual(HandGeometry.barAspect, 1.75)
    }
}
