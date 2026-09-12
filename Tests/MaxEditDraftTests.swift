// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import XCTest
@testable import Doigt

final class MaxEditDraftTests: XCTestCase {
    func testOpeningAnEditorDoesNotCreateAnotherBenchmark() {
        let draft = MaxEditDraft(leftKg: 31.23456789, rightKg: 34.87654321)
        XCTAssertEqual(draft.leftKg, 31.23456789)
        XCTAssertEqual(draft.rightKg, 34.87654321)
        XCTAssertTrue(draft.changes.isEmpty)
        XCTAssertFalse(draft.canSave)
    }

    func testOnlyTheEditedHandIsSavedIncludingALowerWorkingMax() {
        var draft = MaxEditDraft(leftKg: 40, rightKg: 45)
        draft.leftKg = 35
        XCTAssertEqual(draft.changes, [.init(side: .left, kg: 35)])
        XCTAssertTrue(draft.canSave)
    }

    func testRevertingAnEditDoesNotSaveAnotherRecord() {
        var draft = MaxEditDraft(leftKg: 40, rightKg: 45)
        draft.leftKg = 35
        draft.leftKg = 40
        XCTAssertTrue(draft.changes.isEmpty)
        XCTAssertFalse(draft.canSave)
    }

    func testTwoHandEditsAreIndependentAndNeverCreateASharedMax() {
        var draft = MaxEditDraft()
        draft.leftKg = 24
        draft.rightKg = 28
        XCTAssertEqual(draft.changes, [.init(side: .left, kg: 24), .init(side: .right, kg: 28)])
        XCTAssertFalse(draft.changes.contains { $0.side == .both })
        XCTAssertNil(draft.originalKg(for: .both))
    }

    func testMissingHandIsNotInferredFromTheOtherHand() {
        var draft = MaxEditDraft(leftKg: 40)
        XCTAssertEqual(draft.rightKg, 0)
        XCTAssertNil(draft.originalKg(for: .right))
        draft.leftKg = 42
        XCTAssertEqual(draft.changes, [.init(side: .left, kg: 42)])
        XCTAssertTrue(draft.canSave, "An untouched missing hand must not block the edited hand.")
    }

    func testZeroCannotSilentlyDeleteOneHandWhileSavingTheOther() {
        var draft = MaxEditDraft(leftKg: 40, rightKg: 45)
        draft.leftKg = 0
        draft.rightKg = 47
        XCTAssertTrue(draft.hasInvalidChanges)
        XCTAssertFalse(draft.canSave)
        XCTAssertEqual(draft.changes, [.init(side: .right, kg: 47)])
    }

    func testNonFiniteAndNegativeChangesCannotBeSaved() {
        for invalid in [Double.nan, .infinity, -.infinity, -1] {
            var draft = MaxEditDraft(leftKg: 40)
            draft.leftKg = invalid
            XCTAssertTrue(draft.hasInvalidChanges)
            XCTAssertFalse(draft.canSave)
            XCTAssertTrue(draft.changes.isEmpty)
        }
    }

    func testPoundsDisplayDoesNotChangeStoredPrecision() {
        let kilograms = 31.23456789
        var draft = MaxEditDraft(leftKg: kilograms)
        _ = WeightUnit.lb.fromKg(draft.leftKg)
        XCTAssertTrue(draft.changes.isEmpty)
        XCTAssertEqual(draft.leftKg, kilograms)

        let enteredPounds = 75.5
        draft.rightKg = WeightUnit.lb.toKg(enteredPounds)
        XCTAssertEqual(draft.changes.count, 1)
        XCTAssertEqual(draft.changes.first?.side, .right)
        XCTAssertEqual(draft.changes.first?.kg ?? 0, 34.246223935, accuracy: 1e-12)
        XCTAssertEqual(draft.leftKg, kilograms)
    }

    func testDeletingTheCurrentMaxRefreshesAnUntouchedFieldToItsPredecessor() {
        var draft = MaxEditDraft(leftKg: 40, rightKg: 45)
        draft.rebase(leftKg: 30, rightKg: 45)
        XCTAssertEqual(draft.leftKg, 30)
        XCTAssertEqual(draft.originalKg(for: .left), 30)
        XCTAssertEqual(draft.rightKg, 45)
        XCTAssertTrue(draft.changes.isEmpty)
        XCTAssertFalse(draft.canSave)
    }

    func testDeletingTheCurrentMaxPreservesATypedEditAgainstTheNewPredecessor() {
        var draft = MaxEditDraft(leftKg: 40, rightKg: 45)
        draft.leftKg = 33
        draft.rebase(leftKg: 30, rightKg: 45)
        XCTAssertEqual(draft.leftKg, 33)
        XCTAssertEqual(draft.originalKg(for: .left), 30)
        XCTAssertEqual(draft.rightKg, 45)
        XCTAssertEqual(draft.changes, [.init(side: .left, kg: 33)])
        XCTAssertTrue(draft.canSave)
    }

    func testRebasingAfterTheLastHandRecordIsDeletedDoesNotCopyTheSharedFallback() {
        var current = MaxTable()
        let grip = GripSpec().key
        current.record(50, grip: grip, side: .both)
        current.record(45, grip: grip, side: .right)
        var draft = MaxEditDraft(leftKg: 40, rightKg: 45)
        draft.rebase(leftKg: current.exact(grip: grip, side: .left),
                     rightKg: current.exact(grip: grip, side: .right))
        XCTAssertEqual(current.max(grip: grip, side: .left), 50)
        XCTAssertEqual(draft.leftKg, 0)
        XCTAssertNil(draft.originalKg(for: .left))
        XCTAssertEqual(draft.rightKg, 45)
        XCTAssertTrue(draft.changes.isEmpty)
    }

    func testAConcurrentRecordMatchingTheTypedValueNoLongerNeedsAnotherSave() {
        var draft = MaxEditDraft(leftKg: 40, rightKg: 45)
        draft.leftKg = 33
        draft.rebase(leftKg: 33, rightKg: 45)
        XCTAssertEqual(draft.leftKg, 33)
        XCTAssertEqual(draft.originalKg(for: .left), 33)
        XCTAssertTrue(draft.changes.isEmpty)
        XCTAssertFalse(draft.canSave)
    }
}
