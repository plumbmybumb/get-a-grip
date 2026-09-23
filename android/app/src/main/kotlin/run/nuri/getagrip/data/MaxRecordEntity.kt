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

/// The most you have ever pulled on one grip — what every "about 25 % of your max" caption
/// is a percentage OF.
///
/// APPEND-ONLY: a new max inserts a row. That gives history for free and avoids two
/// devices' merge conflict on one mutable row (last-writer-wins is the wrong resolution for
/// a *strength* number).
///
/// NO denormalized `gripKey` column: the key is always computed from the three components,
/// since a stored key could drift from them on the one string the trend join hangs on.
///
/// Same schema rules as the other two: every column defaulted, nothing unique beyond the
/// primary key, no relationships.
@Entity(tableName = "MaxRecord")
data class MaxRecordEntity(
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val edgeMM: Int = 20,
    /// `FingerSet.token` — the wire format, never localized.
    val fingersRaw: String = "IMRL",
    /// `GripPosition.rawValue`, raw so an unknown position from a newer build survives.
    val positionRaw: String = "halfCrimp",
    val kg: Double = 0.0,
    val recordedAt: Instant = storedNow(),
    val sourceRaw: String = "manual",
    /// Which hand. **Defaulted to "both"** — what every older record and an untouched
    /// picker mean — so no backfill, and nothing changes for anyone who never uses the
    /// control.
    val sideRaw: String = "both",
    val note: String = "",
) {

    /// Total both ways: `FingerSet.fromToken` and `GripPosition(_)` never fail, so a record
    /// round-trips to its grip.
    val grip: GripSpec
        get() = GripSpec(
            edgeMM = edgeMM,
            fingers = FingerSet.fromToken(fingersRaw),
            position = GripPosition(positionRaw),
        )

    /// Always computed — see the type's note. What a `SetPlan`'s grip is matched against.
    val gripKey: String get() = grip.key

    /// An unknown hand from a newer build reads as `both`, the widest reading: the record
    /// still counts rather than vanishing.
    val side: Side get() = Side.fromRaw(sideRaw) ?: Side.both

    /// **Identity for "the current max" is grip AND hand.** Folded on `gripKey` alone, a
    /// right-hand max would supersede the left one and that hand would silently lose its
    /// number. One property, so no fold site can spell it wrong.
    val maxKey: String get() = MaxTable.key(gripKey, side)

    /// An unknown source reads as `manual`: understate provenance rather than claim a
    /// measurement.
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
