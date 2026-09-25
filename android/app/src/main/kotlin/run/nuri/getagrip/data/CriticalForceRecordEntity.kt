// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import run.nuri.getagrip.engine.CriticalForcePoint
import run.nuri.getagrip.engine.CriticalForceProtocol
import run.nuri.getagrip.engine.CriticalForceRep
import run.nuri.getagrip.engine.CriticalForceRepsCodec
import run.nuri.getagrip.engine.CriticalForceResult
import run.nuri.getagrip.engine.CriticalForceTrace
import run.nuri.getagrip.engine.CriticalForceHands
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.MaxTable
import run.nuri.getagrip.engine.Side
import java.time.Duration
import java.time.Instant
import java.util.UUID

/// One critical force test, frozen: the result, every pull, and the force trace it came
/// from. See `CriticalForceTest` and `docs/CRITICAL_FORCE.md`.
///
/// Shaped like `MaxRecordEntity` on purpose. It is APPEND-ONLY, identified by grip value
/// AND hand, and the grip is stored as its components with the key always computed. It is
/// a table of its own rather than a `WorkoutLog` kind, because an older build reads an
/// unknown kind as `hang` and would count a maximal test as a routine session. The testing
/// DAY is still a `benchmark` log, which every build already understands. See
/// `TemplateStore.recordCriticalForce`.
///
/// Same schema rules as the other tables: every column defaulted or nullable, nothing
/// unique beyond the primary key, no relationships. Added in database version 3.
///
/// TRANSLATION NOTE (from Sources/Model/CriticalForceRecord.swift): `repsData` is the
/// canonical JSON TEXT every blob column on this side uses; `traceData` stays BYTES, the
/// `CriticalForceTrace` layout, identical to iOS. A `ByteArray` in a data class compares by
/// reference, so `equals`/`hashCode` are written out to compare its contents.
@Entity(tableName = "CriticalForceRecord")
data class CriticalForceRecordEntity(
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val edgeMM: Int = 20,
    val fingersRaw: String = "IMRL",
    val positionRaw: String = "halfCrimp",
    val sideRaw: String = "both",
    val recordedAt: Instant = storedNow(),
    /// `CriticalForceProtocol.key`, e.g. `7:3x24`. A CF is only comparable with one from
    /// the same protocol.
    val protocolKey: String = CriticalForceProtocol.standard.key,
    val criticalForceKg: Double = 0.0,
    val wPrimeKgS: Double = 0.0,
    val peakKg: Double = 0.0,
    /// null when the final pulls had too little data for the end window.
    val endForceKg: Double? = null,
    val repsRun: Int = 0,
    val restsKept: Int = 0,
    val restsTotal: Int = 0,
    /// Body weight at the time of the test, FROZEN. The setting can change later; a test's
    /// share of body weight must not.
    val bodyMassKg: Double? = null,
    /// The grip's max for this hand when the test ran, FROZEN, for the same reason.
    val maxAtTestKg: Double? = null,
    val repsData: String = "",              // [CriticalForceRep], write-once
    val traceData: ByteArray = ByteArray(0), // CriticalForceTrace, write-once
    val note: String = "",
) {

    val grip: GripSpec
        get() = GripSpec(
            edgeMM = edgeMM,
            fingers = FingerSet.fromToken(fingersRaw),
            position = GripPosition(positionRaw),
        )

    val gripKey: String get() = grip.key

    val side: Side get() = Side.fromRaw(sideRaw) ?: Side.both

    /// Grip AND hand, like `MaxRecordEntity.maxKey`: a right-hand test must never supersede
    /// the left-hand one.
    val testKey: String get() = MaxTable.key(gripKey, side)

    val protocolUsed: CriticalForceProtocol get() = CriticalForceProtocol.fromKey(protocolKey)

    val reps: List<CriticalForceRep> get() = CriticalForceRepsCodec.decode(repsData)

    val trace: List<CriticalForcePoint> get() = CriticalForceTrace.decode(traceData)

    val percentOfBodyMass: Double?
        get() = bodyMassKg?.takeIf { it > 0 }?.let { criticalForceKg / it * 100 }

    val percentOfMax: Double?
        get() = maxAtTestKg?.takeIf { it > 0 }?.let { criticalForceKg / it * 100 }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CriticalForceRecordEntity) return false
        return id == other.id && edgeMM == other.edgeMM && fingersRaw == other.fingersRaw &&
            positionRaw == other.positionRaw && sideRaw == other.sideRaw &&
            recordedAt == other.recordedAt && protocolKey == other.protocolKey &&
            criticalForceKg == other.criticalForceKg && wPrimeKgS == other.wPrimeKgS &&
            peakKg == other.peakKg && endForceKg == other.endForceKg && repsRun == other.repsRun &&
            restsKept == other.restsKept && restsTotal == other.restsTotal &&
            bodyMassKg == other.bodyMassKg && maxAtTestKg == other.maxAtTestKg &&
            repsData == other.repsData && traceData.contentEquals(other.traceData) &&
            note == other.note
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + recordedAt.hashCode()
        result = 31 * result + criticalForceKg.hashCode()
        result = 31 * result + traceData.contentHashCode()
        return result
    }

    companion object {
        fun from(
            grip: GripSpec,
            side: Side,
            result: CriticalForceResult,
            trace: ByteArray,
            bodyMassKg: Double?,
            maxAtTestKg: Double?,
            recordedAt: Instant = storedNow(),
        ): CriticalForceRecordEntity = CriticalForceRecordEntity(
            id = UUID.randomUUID(),
            edgeMM = grip.edgeMM,
            fingersRaw = grip.fingers.token,
            positionRaw = grip.position.rawValue,
            sideRaw = side.rawValue,
            recordedAt = recordedAt,
            protocolKey = result.protocolUsed.key,
            criticalForceKg = result.criticalForceKg,
            wPrimeKgS = result.wPrimeKgS,
            peakKg = result.peakKg,
            endForceKg = result.endForceKg,
            repsRun = result.repsRun,
            restsKept = result.restsKept,
            restsTotal = result.restsTotal,
            bodyMassKg = bodyMassKg,
            maxAtTestKg = maxAtTestKg,
            repsData = CriticalForceRepsCodec.encode(result.reps),
            traceData = trace,
            note = "",
        )
    }
}

/// The newest test overall.
val List<CriticalForceRecordEntity>.newest: CriticalForceRecordEntity?
    get() = maxByOrNull { it.recordedAt }

/// The newest VISIT: the newest test plus the other hand's, when both were tested one at a
/// time (same grip, saved together). Left before right. What Today reports.
val List<CriticalForceRecordEntity>.latestVisit: List<CriticalForceRecordEntity>
    get() {
        val newest = newest ?: return emptyList()
        val byHand = LinkedHashMap<Side, CriticalForceRecordEntity>()
        for (record in this) {
            if (record.gripKey != newest.gripKey) continue
            if (Duration.between(record.recordedAt, newest.recordedAt).abs() >= Duration.ofMinutes(15)) continue
            val held = byHand[record.side]
            if (held != null && !held.recordedAt.isBefore(record.recordedAt)) continue
            byHand[record.side] = record
        }
        return byHand.values.sortedBy { it.side.sortRank }
    }

/// How that visit's hands were tested, to open the next test the same way.
val List<CriticalForceRecordEntity>.latestHands: CriticalForceHands?
    get() {
        val visit = latestVisit
        val first = visit.firstOrNull() ?: return null
        if (visit.size > 1) return CriticalForceHands.OneAtATime(Side.left)
        return if (first.side == Side.both) CriticalForceHands.BothHands else CriticalForceHands.Single(first.side)
    }

/// "L", "R", "B": a hand on a line with no room for its name.
val Side.initial: String
    get() = when (this) {
        Side.left -> L10n.tr("L")
        Side.right -> L10n.tr("R")
        Side.both -> L10n.tr("B")
    }

/// Left, right, both: the order hands are listed in.
val Side.sortRank: Int
    get() = when (this) {
        Side.left -> 0
        Side.right -> 1
        Side.both -> 2
    }
