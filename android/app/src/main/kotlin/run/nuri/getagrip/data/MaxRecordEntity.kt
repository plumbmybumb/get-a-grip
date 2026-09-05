// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.MaxTable
import run.nuri.getagrip.engine.Side
import java.time.Instant
import java.util.UUID

/// The most you have ever pulled on one grip — the number every "about 25 % of your max"
/// caption is a percentage OF.
///
/// APPEND-ONLY: recording a new max inserts a row rather than mutating one. That gives a
/// max history for free, and it sidesteps the merge conflict two devices would otherwise
/// have on a single mutable row (last-writer-wins on a *strength* number is exactly the
/// wrong resolution).
///
/// There is deliberately NO denormalized `gripKey` column: the key is always computed
/// from the three components beside it, because a stored key that can drift out of step
/// with them is a second source of truth for the one string the whole trend join hangs on.
///
/// Same schema rules as the other two: every column defaulted, nothing unique beyond the
/// primary key, no relationships.
@Entity(tableName = "MaxRecord")
data class MaxRecordEntity(
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val edgeMM: Int = 20,
    /// `FingerSet.token` — the wire format, never localized.
    val fingersRaw: String = "IMRL",
    /// `GripPosition.rawValue`, kept raw so an unknown position from a newer build
    /// survives.
    val positionRaw: String = "halfCrimp",
    val kg: Double = 0.0,
    val recordedAt: Instant = storedNow(),
    val sourceRaw: String = "manual",
    /// Which hand this was pulled with. **Defaulted to "both"**, which is what every
    /// record written before this column existed means and what an untouched picker
    /// still means — so the additive migration needs no backfill and the app behaves
    /// exactly as it did for anyone who never opens the control.
    val sideRaw: String = "both",
    val note: String = "",
) {

    /// Total in both directions: `FingerSet.fromToken` and `GripPosition(_)` never fail,
    /// so a record always round-trips to the grip it was filed under.
    val grip: GripSpec
        get() = GripSpec(
            edgeMM = edgeMM,
            fingers = FingerSet.fromToken(fingersRaw),
            position = GripPosition(positionRaw),
        )

    /// Always computed — see the type's note. This is what a `SetPlan`'s grip is matched
    /// against to find "your max on this grip".
    val gripKey: String get() = grip.key

    /// An unknown hand from a newer build reads as `both`, which is the widest and least
    /// surprising reading — the record still counts for every rep of that grip rather
    /// than vanishing from a screen the user put it on.
    val side: Side get() = Side.fromRaw(sideRaw) ?: Side.both

    /// **Identity for "the current max" is the grip AND the hand.** Folding on `gripKey`
    /// alone would make a right-hand max supersede a left-hand one recorded a minute
    /// earlier — the newest wins, and the other hand silently loses its number. That is
    /// the whole reason this exists as its own property rather than being spelled out at
    /// each fold site.
    val maxKey: String get() = MaxTable.key(gripKey, side)

    /// An unknown source from a newer build reads as `manual`, which understates the
    /// provenance rather than claiming a measurement that may not have happened.
    val source: MaxSource get() = MaxSource.fromRaw(sourceRaw) ?: MaxSource.manual

    companion object {
        fun from(
            grip: GripSpec,
            kg: Double,
            source: MaxSource,
            side: Side = Side.both,
            recordedAt: Instant = storedNow(),
        ): MaxRecordEntity = MaxRecordEntity(
            id = UUID.randomUUID(),
            edgeMM = grip.edgeMM,
            fingersRaw = grip.fingers.token,
            positionRaw = grip.position.rawValue,
            kg = kg,
            recordedAt = recordedAt,
            sourceRaw = source.rawValue,
            sideRaw = side.rawValue,
            note = "",
        )
    }
}
