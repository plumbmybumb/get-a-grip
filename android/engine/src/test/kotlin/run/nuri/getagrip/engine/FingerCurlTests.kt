// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FingerCurlTests {
    private val curl = GripSpec(position = GripPosition.fingerCurl)

    @Test
    fun curlWireIdentityAndHandShape() {
        val wire = """{"edgeMM":20,"fingers":"IMRL","position":"fingerCurl"}"""
        val decoded = assertNotNull(BlobCodec.decode(wire) { GripSpec.fromJson(it) })
        assertEquals(curl, decoded)
        assertEquals(wire, BlobCodec.encode(decoded))
        assertEquals("20|IMRL|fingerCurl", curl.key)
        assertEquals("20|IMRL|halfCrimp", GripSpec().key)
        assertEquals(GripPosition.halfCrimp.closure, curl.position.closure)
        assertNotEquals(GripPosition.fullCrimp.shortName, curl.position.shortName)
    }

    @Test
    fun curlTargetsNeverBorrowHalfCrimpMaxes() {
        val plan = SessionPlan(targetLoPercent = 0.25, targetHiPercent = 0.25)
        val set = SetPlan(grip = curl)
        val maxes = MaxTable()
        maxes.record(80.0, GripSpec().key, Side.left)
        maxes.record(100.0, GripSpec().key, Side.both)
        assertNull(PlanMath.targetBand(set, plan, Side.left, maxes))
        maxes.record(40.0, curl.key, Side.left)
        assertEquals(10.0..10.0, PlanMath.targetBand(set, plan, Side.left, maxes))
        assertNull(PlanMath.targetBand(set, plan, Side.right, maxes))
        assertEquals(80.0, maxes.exact(GripSpec().key, Side.left))
    }

    @Test
    fun curlAndHalfCrimpStaySeparateInPlanTotalsAndExport() {
        val plan = SessionPlan(sets = listOf(SetPlan(grip = GripSpec()), SetPlan(grip = curl), SetPlan(grip = curl)))
        val totals = PlanMath.gripTotals(plan)
        assertEquals(listOf(GripSpec().key, curl.key), totals.map { it.grip.key })
        assertEquals(2 * totals[0].totalReps, totals[1].totalReps)
        assertEquals("CURL", AnalysisExport.positionCode(GripPosition.fingerCurl))
        assertEquals("finger curl (isometric, starting in half crimp)", AnalysisExport.positionEnglish(GripPosition.fingerCurl))
    }
}
