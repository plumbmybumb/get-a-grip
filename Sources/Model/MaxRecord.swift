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
/// There is deliberately NO denormalized `gripKey` column: the key is always computed
/// from the three components beside it, because a stored key that can drift out of step
/// with them is a second source of truth for the one string the whole trend join hangs on.
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
    /// Which hand this was pulled with. **Defaulted to "both"**, which is what every
    /// record written before this column existed means and what an untouched picker
    /// still means — so the additive migration needs no backfill and the app behaves
    /// exactly as it did for anyone who never opens the control.
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

    /// An unknown hand from a newer build reads as `.both`, which is the widest and
    /// least surprising reading — the record still counts for every rep of that grip
    /// rather than vanishing from a screen the user put it on.
    var side: Side {
        get { Side(rawValue: sideRaw) ?? .both }
        set { sideRaw = newValue.rawValue }
    }

    /// **Identity for "the current max" is the grip AND the hand.** Folding on `gripKey`
    /// alone would make a right-hand max supersede a left-hand one recorded a minute
    /// earlier — the newest wins, and the other hand silently loses its number. That is
    /// the whole reason this exists as its own property rather than being spelled out at
    /// each fold site.
    var maxKey: String { MaxTable.key(grip: gripKey, side: side) }

    /// An unknown source from a newer build reads as `.manual`, which understates the
    /// provenance rather than claiming a measurement that may not have happened.
    var source: MaxSource {
        get { MaxSource(rawValue: sourceRaw) ?? .manual }
        set { sourceRaw = newValue.rawValue }
    }
}
