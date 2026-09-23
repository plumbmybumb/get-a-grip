// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import SwiftData

/// The most you have ever pulled on one grip — the number every "about 25 % of your
/// max" caption is a percentage OF.
///
/// APPEND-ONLY: recording a new max inserts a row rather than mutating one. That gives a
/// max history for free, and it sidesteps the CloudKit merge conflict two devices would
/// otherwise have on a single mutable row (last-writer-wins on a *strength* number is
/// exactly the wrong resolution).
///
/// NO denormalized `gripKey` column: the key is always computed from its components, so
/// the string the whole trend join hangs on has one source of truth.
///
/// Same CloudKit rules as the other models: every attribute defaulted, nothing unique,
/// no relationships.
@Model
final class MaxRecord {
    var id: UUID = UUID()
    var edgeMM: Int = 20
    var fingersRaw: String = "IMRL"          // FingerSet.token — the wire format, never localized
    var positionRaw: String = "halfCrimp"    // GripPosition.rawValue, kept raw so an
                                             // unknown position from a newer build survives
    var kg: Double = 0
    var recordedAt: Date = Date.now
    var sourceRaw: String = "manual"
    /// Which hand this was pulled with. **Defaulted to "both"** — what every older record
    /// and an untouched picker mean. Additive, no backfill.
    var sideRaw: String = "both"
    var note: String = ""

    init(grip: GripSpec, kg: Double, source: MaxSource, side: Side = .both,
         recordedAt: Date = .now) {
        self.id = UUID()
        self.edgeMM = grip.edgeMM
        self.fingersRaw = grip.fingers.token
        self.positionRaw = grip.position.rawValue
        self.kg = kg
        self.recordedAt = recordedAt
        self.sourceRaw = source.rawValue
        self.sideRaw = side.rawValue
        self.note = ""
    }
}

extension MaxRecord {
    /// Total in both directions: `FingerSet(token:)` and `GripPosition(_:)` never fail,
    /// so a record always round-trips to the grip it was filed under.
    var grip: GripSpec {
        get {
            GripSpec(edgeMM: edgeMM,
                     fingers: FingerSet(token: fingersRaw),
                     position: GripPosition(positionRaw))
        }
        set {
            edgeMM = newValue.edgeMM
            fingersRaw = newValue.fingers.token
            positionRaw = newValue.position.rawValue
        }
    }

    /// Always computed — see the type's note. This is what a `SetPlan`'s grip is matched
    /// against to find "your max on this grip".
    var gripKey: String { grip.key }

    /// An unknown hand from a newer build reads as `.both`, so the record still counts
    /// rather than vanishing.
    var side: Side {
        get { Side(rawValue: sideRaw) ?? .both }
        set { sideRaw = newValue.rawValue }
    }

    /// **Identity for "the current max" is the grip AND the hand.** Folding on `gripKey`
    /// alone would let a right-hand max supersede the left-hand one, and one hand would
    /// silently lose its number.
    var maxKey: String { MaxTable.key(grip: gripKey, side: side) }

    /// An unknown source from a newer build reads as `.manual`, which understates the
    /// provenance rather than claiming a measurement that may not have happened.
    var source: MaxSource {
        get { MaxSource(rawValue: sourceRaw) ?? .manual }
        set { sourceRaw = newValue.rawValue }
    }
}

extension Collection where Element == MaxRecord {
    /// Newest per GRIP **AND HAND** — see `maxKey`.
    ///
    /// Here rather than in the store so the watch's runner resolves loads against
    /// exactly the fold the phone's does.
    func newestPerGripAndHand() -> [String: MaxRecord] {
        var newest: [String: MaxRecord] = [:]
        for record in self {
            let key = record.maxKey
            if let held = newest[key], held.recordedAt >= record.recordedAt { continue }
            newest[key] = record
        }
        return newest
    }

    /// The table a session resolves its percentage targets against.
    func maxTable() -> MaxTable { MaxTable.folding(newestPerGripAndHand()) }
}

extension MaxTable {
    /// Built from the newest-per-hand fold in one breath, so a table can never disagree
    /// with the records it was folded from.
    static func folding(_ newest: [String: MaxRecord]) -> MaxTable {
        var table = MaxTable()
        for record in newest.values {
            table.record(record.kg, grip: record.gripKey, side: record.side)
        }
        return table
    }
}
