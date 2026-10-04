// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import XCTest
@testable import Doigt

/// The receipt arithmetic, with no store: two max tables and the routines' plans in, the
/// moves and offers out. The store-level tests (`TemplateStoreTests`) cover the save
/// around it; these pin the pure rules.
final class MaxReceiptMathTests: XCTestCase {

    private let grip = GripSpec()

    private func routine(_ name: String, mode: HandMode, sets: [SetPlan]) -> MaxReceiptMath.Routine {
        var plan = SessionPlan()
        plan.name = name
        plan.handMode = mode
        plan.sets = sets
        return .init(id: UUID(), name: name, plan: plan)
    }

    func testAPercentTargetMovesWithTheNewMaxForEachHand() {
        let r = routine("Alternating", mode: .alternateEachRep,
                        sets: [SetPlan(grip: grip, targetLoPercent: 0.25, targetHiPercent: 0.30)])
        var previous = MaxTable()
        previous.record(60, grip: grip.key, side: .both)
        var current = previous
        current.record(30, grip: grip.key, side: .left)

        let receipt = MaxReceiptMath.receipt(
            for: [MaxSave(grip: grip, side: .left, kg: 30, source: .manual)],
            previous: previous, current: current, routines: [r])

        XCTAssertEqual(receipt.percentMoves.count, 1, "the right hand still reads the shared max")
        let move = receipt.percentMoves[0].move
        XCTAssertEqual(move.side, .left)
        XCTAssertEqual(move.oldBand, 15...18)
        XCTAssertEqual(move.newBand, 7.5...9)
        XCTAssertTrue(receipt.rescaleOffers.isEmpty)
    }

    func testATypedBandIsOfferedARoundedRescaleOnlyForASharedPeakChange() {
        let r = routine("Together", mode: .bothHands,
                        sets: [SetPlan(grip: grip, targetLoKg: 20, targetHiKg: 24)])
        var previous = MaxTable()
        previous.record(60, grip: grip.key, side: .both)
        var current = previous
        current.record(66, grip: grip.key, side: .both)

        let receipt = MaxReceiptMath.receipt(
            for: [MaxSave(grip: grip, side: .both, kg: 66, source: .measured)],
            previous: previous, current: current, routines: [r])

        let offer = try? XCTUnwrap(receipt.rescaleOffers.first)
        XCTAssertEqual(offer?.ratio ?? 0, 1.1, accuracy: 0.0001)
        XCTAssertEqual(offer?.routines.first?.moves.first?.newBand, 22...26.5,
                       "scaled to the half kilogram, like a number someone could type")
        XCTAssertEqual(offer?.expectedPlans[r.id], r.plan, "the offer pins the plan it was shown for")
    }

    /// CloudKit does not guarantee unique routine ids: an ambiguous one is skipped, and an
    /// unrelated routine beside it still reports.
    func testAnAmbiguousRoutineIDIsSkippedWithoutHidingTheOthers() {
        let twin = routine("Twin", mode: .bothHands,
                           sets: [SetPlan(grip: grip, targetLoPercent: 0.25, targetHiPercent: 0.30)])
        let other = routine("Other", mode: .bothHands,
                            sets: [SetPlan(grip: grip, targetLoPercent: 0.25, targetHiPercent: 0.30)])
        var current = MaxTable()
        current.record(40, grip: grip.key, side: .both)

        let receipt = MaxReceiptMath.receipt(
            for: [MaxSave(grip: grip, side: .both, kg: 40, source: .manual)],
            previous: MaxTable(), current: current, routines: [twin, twin, other])

        XCTAssertEqual(receipt.percentMoves.map { $0.move.routineID }, [other.id])
    }
}
