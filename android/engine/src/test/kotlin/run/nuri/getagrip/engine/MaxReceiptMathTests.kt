// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// The receipt arithmetic, with no store (iOS `MaxReceiptMathTests`): two max tables and the
/// routines' plans in, the moves and offers out.
class MaxReceiptMathTests {
    private val grip = GripSpec()

    private fun routine(name: String, mode: HandMode, sets: List<SetPlan>) =
        MaxReceiptMath.Routine(UUID.randomUUID(), name, SessionPlan(name = name, handMode = mode, sets = sets))

    @Test fun aPercentTargetMovesWithTheNewMaxForEachHand() {
        val r = routine("Alternating", HandMode.alternateEachRep,
            listOf(SetPlan(grip = grip, targetLoPercent = 0.25, targetHiPercent = 0.30)))
        val previous = MaxTable().apply { record(60.0, grip.key, Side.both) }
        val current = MaxTable().apply { record(60.0, grip.key, Side.both); record(30.0, grip.key, Side.left) }

        val receipt = MaxReceiptMath.receipt(
            listOf(MaxSave(grip, Side.left, 30.0, MaxSource.manual)), previous, current, listOf(r))

        assertEquals(1, receipt.percentMoves.size, "the right hand still reads the shared max")
        val move = receipt.percentMoves[0].move
        assertEquals(Side.left, move.side)
        assertEquals(15.0..18.0, move.oldBand)
        assertEquals(7.5..9.0, move.newBand)
        assertTrue(receipt.rescaleOffers.isEmpty())
    }

    @Test fun aTypedBandIsOfferedARoundedRescaleOnlyForASharedPeakChange() {
        val r = routine("Together", HandMode.bothHands,
            listOf(SetPlan(grip = grip, targetLoKg = 20.0, targetHiKg = 24.0)))
        val previous = MaxTable().apply { record(60.0, grip.key, Side.both) }
        val current = MaxTable().apply { record(66.0, grip.key, Side.both) }

        val receipt = MaxReceiptMath.receipt(
            listOf(MaxSave(grip, Side.both, 66.0, MaxSource.measured)), previous, current, listOf(r))

        val offer = assertNotNull(receipt.rescaleOffers.firstOrNull())
        assertEquals(1.1, offer.ratio, 0.0001)
        assertEquals(22.0..26.5, offer.routines.first().moves.first().newBand,
            "scaled to the half kilogram, like a number someone could type")
        assertEquals(r.plan, offer.expectedPlans[r.id], "the offer pins the plan it was shown for")
    }

    /// A duplicate routine id is skipped, and an unrelated routine beside it still reports.
    @Test fun anAmbiguousRoutineIDIsSkippedWithoutHidingTheOthers() {
        val twin = routine("Twin", HandMode.bothHands,
            listOf(SetPlan(grip = grip, targetLoPercent = 0.25, targetHiPercent = 0.30)))
        val other = routine("Other", HandMode.bothHands,
            listOf(SetPlan(grip = grip, targetLoPercent = 0.25, targetHiPercent = 0.30)))
        val current = MaxTable().apply { record(40.0, grip.key, Side.both) }

        val receipt = MaxReceiptMath.receipt(
            listOf(MaxSave(grip, Side.both, 40.0, MaxSource.manual)), MaxTable(), current, listOf(twin, twin, other))

        assertEquals(listOf(other.id), receipt.percentMoves.map { it.move.routineID })
    }
}
