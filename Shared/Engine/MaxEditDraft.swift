// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// A manual edit starts from exact hand records, never their shared fallback.
/// Opening the editor, converting display units, or leaving one hand alone must
/// not create another benchmark. Explicit changes or selected retests become
/// append-only saves; an unchanged retest never quietly includes the other hand.
struct MaxEditDraft: Equatable, Sendable {
    struct Change: Equatable, Sendable {
        let side: Side
        let kg: Double
    }

    private var originals: [Side: Double]
    private(set) var repeatedTests: Set<Side> = []
    var leftKg: Double
    var rightKg: Double

    init(leftKg: Double? = nil, rightKg: Double? = nil) {
        var originals: [Side: Double] = [:]
        if let leftKg, leftKg.isFinite, leftKg > 0 { originals[.left] = leftKg }
        if let rightKg, rightKg.isFinite, rightKg > 0 { originals[.right] = rightKg }
        self.originals = originals
        self.leftKg = originals[.left] ?? 0
        self.rightKg = originals[.right] ?? 0
    }

    func originalKg(for side: Side) -> Double? { originals[side] }

    func recordsAnotherTest(for side: Side) -> Bool { repeatedTests.contains(side) }

    mutating func setRecordsAnotherTest(_ enabled: Bool, for side: Side) {
        guard side != .both else { return }
        if enabled {
            guard originals[side] != nil else { return }
            repeatedTests.insert(side)
        } else {
            repeatedTests.remove(side)
        }
    }

    /// History can change while this editor remains on its navigation stack.
    /// Follow those changes only for untouched fields; a typed draft belongs to the
    /// person editing it. Compare any retained edits against the latest exact max.
    mutating func rebase(leftKg: Double?, rightKg: Double?) {
        let leftWasEdited = self.leftKg != (originals[.left] ?? 0) || repeatedTests.contains(.left)
        let rightWasEdited = self.rightKg != (originals[.right] ?? 0) || repeatedTests.contains(.right)
        let current = MaxEditDraft(leftKg: leftKg, rightKg: rightKg)
        originals = current.originals
        if !leftWasEdited { self.leftKg = current.leftKg }
        if !rightWasEdited { self.rightKg = current.rightKg }
    }

    private func value(for side: Side) -> Double {
        side == .left ? leftKg : rightKg
    }

    /// Zero cannot mean delete: removing a current record exposes its predecessor.
    /// Reject the entire edit if a changed field is invalid so Save cannot silently
    /// ignore that hand while committing the other one.
    var hasInvalidChanges: Bool {
        [Side.left, .right].contains { side in
            let kg = value(for: side)
            return (kg != (originals[side] ?? 0) || repeatedTests.contains(side))
                && (!kg.isFinite || kg <= 0)
        }
    }

    var changes: [Change] {
        [Side.left, .right].compactMap { side in
            let kg = value(for: side)
            guard kg.isFinite, kg > 0,
                  kg != (originals[side] ?? 0) || repeatedTests.contains(side) else { return nil }
            return Change(side: side, kg: kg)
        }
    }

    var canSave: Bool { !hasInvalidChanges && !changes.isEmpty }
}
