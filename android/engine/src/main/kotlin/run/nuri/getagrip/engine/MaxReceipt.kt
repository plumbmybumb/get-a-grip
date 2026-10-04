// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.util.UUID

// What a new max changes, as pure values (iOS `MaxReceipt.swift`).
//
// Lifted out of `TemplateStore` (2026-10-04): the store records the max and writes any accepted
// rescale; everything in between — which percentage targets now resolve to different kilograms,
// which typed-kilogram targets may be offered a rescale — is arithmetic over two max tables and
// the routines' plans, and lives here where a test reaches it without a database.

/// Unsaved values from one editing or measurement flow. `seconds`: 0 for a peak max; otherwise
/// the timed window — see `MaxRecordEntity.durationSeconds`.
data class MaxSave(
    val grip: GripSpec,
    val side: Side,
    val kg: Double,
    val source: MaxSource,
    val seconds: Int = 0,
)

/// The pieces a max-save receipt is made of: the percentage targets that followed a new max,
/// and the typed-kilogram targets offered a rescale.
object MaxImpact {
    /// A percentage band that now resolves to different kilograms. INFORMATIONAL: percent
    /// targets follow the newest max by design.
    data class PercentMove(
        val routineID: UUID,
        val routineName: String,
        val side: Side,
        val loPercent: Double,
        val hiPercent: Double,
        /// null when the grip had no max before — the band never resolved until now.
        val oldBand: ClosedFloatingPointRange<Double>?,
        val newBand: ClosedFloatingPointRange<Double>,
    )

    /// Explicit-kilogram sets on this grip, offered a proportional rescale. An OFFER, never
    /// automatic: a typed number is never moved by arithmetic without a yes (`PlanMath`'s
    /// precedence rule).
    data class KgOffer(
        val routineID: UUID,
        val routineName: String,
        val moves: List<Move>,
    ) {
        data class Move(
            val oldBand: ClosedFloatingPointRange<Double>,
            val newBand: ClosedFloatingPointRange<Double>,
        )
    }
}

/// A receipt describes one committed save, not an intermediate left/right state: a batch with
/// shared and individual values resolves its targets from the final table.
data class MaxSaveReceipt(
    val values: List<MaxSave>,
    val percentMoves: List<PercentMove>,
    val rescaleOffers: List<RescaleOffer>,
    val id: UUID = UUID.randomUUID(),
) {
    data class PercentMove(val grip: GripSpec, val move: MaxImpact.PercentMove) {
        val id: String get() = "${grip.key}|${move.routineID}|${move.side.rawValue}|${move.loPercent}|${move.hiPercent}"
    }
    data class RescaleOffer(
        val grip: GripSpec,
        val ratio: Double,
        val newMaxKg: Double,
        val routines: List<MaxImpact.KgOffer>,
        /// Offers are reviewable snapshots: a routine edited while the receipt is open must not
        /// be silently scaled using a now-outdated preview.
        val expectedPlans: Map<UUID, SessionPlan>,
    ) {
        val id: String get() = grip.key
    }
    val hasDetails: Boolean get() = percentMoves.isNotEmpty() || rescaleOffers.isNotEmpty()
}

object MaxReceiptMath {
    /// A routine as the receipt reads it, in the order the store shows routines.
    data class Routine(val id: UUID, val name: String, val plan: SessionPlan)

    /// What `values`, already saved, changed: `previous` is the max table before the save and
    /// `current` the one after it.
    fun receipt(
        values: List<MaxSave>,
        previous: MaxTable,
        current: MaxTable,
        candidates: List<Routine>,
    ): MaxSaveReceipt {
        // A duplicate routine ID must not identify two proposals or two Compose rows; it is
        // skipped without hiding the routines beside it.
        val byID = candidates.groupBy { it.id }
        val routines = candidates.filter { byID[it.id]?.size == 1 }
        val percentMoves = mutableListOf<MaxSaveReceipt.PercentMove>()
        val rescaleOffers = mutableListOf<MaxSaveReceipt.RescaleOffer>()
        for ((gripKey, changes) in values.groupBy { it.grip.key }.toSortedMap()) {
            val grip = changes.first().grip
            // Rescaling typed kilograms is a PEAK-max story: a timed max is a different number
            // and no ratio against the old peak.
            val sharedChange = changes.firstOrNull { it.side == Side.both && it.seconds == 0 }
            val ratio = sharedChange?.let { shared ->
                previous.exact(gripKey, Side.both)?.let { shared.kg / it }
            }
            val kgOffers = mutableListOf<MaxImpact.KgOffer>()
            val expectedPlans = mutableMapOf<UUID, SessionPlan>()
            for (routine in routines) {
                val plan = routine.plan.executable
                val sides = if (plan.handMode == HandMode.bothHands) listOf(Side.both)
                    else listOf(Side.left, Side.right)
                // A shared typed band can be rescaled only if both alternating hands used, and
                // still use, that same shared benchmark.
                val canScale = sharedChange != null && (plan.handMode == HandMode.bothHands ||
                    listOf(Side.left, Side.right).all {
                        previous.exact(gripKey, it) == null && current.exact(gripKey, it) == null
                    })
                val seenPercents = mutableSetOf<String>()
                val kgMoves = mutableListOf<MaxImpact.KgOffer.Move>()
                for (set in plan.sets.filter { it.grip.key == gripKey }) {
                    val explicit = set.targetBand
                    if (explicit != null) {
                        if (!canScale || ratio == null || !ratio.isFinite() || ratio <= 0) continue
                        val newBand = scaled(explicit, ratio)
                        if (explicit == newBand) continue
                        val move = MaxImpact.KgOffer.Move(explicit, newBand)
                        if (move !in kgMoves) kgMoves.add(move)
                    } else {
                        val percent = PlanMath.targetPercent(set, plan) ?: continue
                        // Keyed with the basis too: 90 % of the peak and 90 % of a 10 s max
                        // are different targets on the same grip.
                        val basis = PlanMath.maxSeconds(set, plan)?.let { "${it}s" } ?: "peak"
                        if (!seenPercents.add("${percent.start}–${percent.endInclusive}|$basis")) continue
                        for (side in sides) {
                            // Through the tables, so each set reads the max it is a
                            // percentage OF — a timed save moves the sets measured against it.
                            val oldBand = PlanMath.targetBand(set, plan, side, previous)
                            val newBand = PlanMath.targetBand(set, plan, side, current) ?: continue
                            if (oldBand == newBand) continue
                            percentMoves.add(MaxSaveReceipt.PercentMove(grip, MaxImpact.PercentMove(
                                routine.id, routine.name, side, percent.start, percent.endInclusive, oldBand, newBand,
                            )))
                        }
                    }
                }
                if (kgMoves.isNotEmpty()) {
                    kgOffers.add(MaxImpact.KgOffer(routine.id, routine.name, kgMoves))
                    expectedPlans[routine.id] = routine.plan
                }
            }
            if (kgOffers.isNotEmpty() && ratio != null && sharedChange != null) {
                rescaleOffers.add(MaxSaveReceipt.RescaleOffer(grip, ratio, sharedChange.kg, kgOffers, expectedPlans))
            }
        }
        return MaxSaveReceipt(values.toList(), percentMoves, rescaleOffers)
    }

    /// Half-kilogram rounding like the percent path, so a scaled typed number looks typeable.
    fun scaledKg(kg: Double, ratio: Double): Double = PlanMath.roundedToHalfKg(kg * ratio)

    fun scaled(band: ClosedFloatingPointRange<Double>, ratio: Double): ClosedFloatingPointRange<Double> {
        val lo = scaledKg(band.start, ratio)
        val hi = scaledKg(band.endInclusive, ratio)
        return minOf(lo, hi)..maxOf(lo, hi)
    }
}
