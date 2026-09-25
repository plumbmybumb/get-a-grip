// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The known protocols, pinned to the numbers Nuri captured from their sources. Each is
/// checked AFTER `normalized`, because that is what `importRoutine` writes — a band the
/// normalizer demoted or a set it dropped would be a protocol that lands different from its
/// preview. Twin of Tests/RoutineProtocolTests.swift.
class RoutineProtocolTests {
    private fun landed(item: RoutineProtocol): RoutineDraft = item.draft.normalized
    private fun bands(plan: SessionPlan) = plan.sets.map { it.targetPercentBand }
    private fun timing(plan: SessionPlan) = listOf(plan.holdSeconds, plan.restSeconds, plan.setBreakSeconds)

    @Test fun everyProtocolIsSaveableAndStableUnderNormalizing() {
        for (item in RoutineProtocol.allCases) {
            val draft = landed(item)
            assertNull(draft.validationIssue, "$item")
            assertEquals(draft, draft.normalized, "$item must be normalized already")
            assertEquals(item.title, draft.plan.name, "$item")
            assertEquals(item.draft.plan.sets.size, draft.plan.sets.size, "$item: normalizing dropped a set")
        }
        assertEquals(listOf(RoutineProtocol.dailyNoHangs, RoutineProtocol.c4WarmUp, RoutineProtocol.c4Max,
            RoutineProtocol.fingerRehab), RoutineProtocol.allCases)
    }

    @Test fun eachCallMintsFreshRowIdentity() {
        for (item in RoutineProtocol.allCases) {
            val a = item.draft.plan.sets.map { it.id }.toSet()
            val b = item.draft.plan.sets.map { it.id }.toSet()
            assertTrue(a.intersect(b).isEmpty(), "$item")
        }
    }

    @Test fun dailyNoHangs() {
        val plan = landed(RoutineProtocol.dailyNoHangs).plan
        assertEquals(HandMode.alternateEachRep, plan.handMode)
        assertEquals(listOf(10, 10, 10), timing(plan),
            "alternating: a 10 s gap + the other hand's 10 s pull = 20 s a hand")
        assertEquals(listOf(
            GripSpec(20, FingerSet.four, GripPosition.halfCrimp),
            GripSpec(20, FingerSet.frontThree, GripPosition.drag),
            GripSpec(20, FingerSet.frontTwo, GripPosition.drag),
            GripSpec(20, FingerSet.middleTwo, GripPosition.drag),
            GripSpec(20, FingerSet.frontTwo, GripPosition.halfCrimp),
            GripSpec(20, FingerSet.middleTwo, GripPosition.halfCrimp),
        ).map { it.key }, plan.sets.map { it.grip.key })
        assertEquals(listOf(6, 6, 2, 2, 2, 2), plan.sets.map { it.repsPerSide })
        assertEquals(List(6) { 0.35..0.45 }, bands(plan))
        assertEquals(40, PlanMath.totalReps(plan))
        assertEquals(2, landed(RoutineProtocol.dailyNoHangs).sessionsPerDay)
    }

    @Test fun c4WarmUp() {
        val draft = landed(RoutineProtocol.c4WarmUp)
        val plan = draft.plan
        assertEquals(HandMode.alternateEachSet, plan.handMode)
        assertEquals(listOf(5, 10, 30), timing(plan))
        assertEquals(List(6) { 2 }, plan.sets.map { it.repsPerSide })
        assertEquals(listOf(0.45..0.55, 0.65..0.75, 0.75..0.85, 0.85..0.95, 0.55..0.65, 0.75..0.85), bands(plan))
        assertTrue(draft.isOnDemand)
    }

    /// A band that gated would pause the clock on a pull above the max on file.
    @Test fun c4MaxDrawsItsBandButNeverGatesOnIt() {
        val draft = landed(RoutineProtocol.c4Max)
        val plan = draft.plan
        assertEquals(listOf(4, 10, 60), timing(plan))
        assertEquals(listOf(3, 3, 3), plan.sets.map { it.repsPerSide })
        assertEquals(List(3) { 0.80..1.0 }, bands(plan))
        assertFalse(plan.pausesOutsideTargetBand)
        assertTrue(draft.isOnDemand)
    }

    /// The ceiling is the prescription, so it gates; hands alternate every pull.
    @Test fun fingerRehabGatesAtItsCeilingAndAlternates() {
        val plan = landed(RoutineProtocol.fingerRehab).plan
        assertEquals(HandMode.alternateEachRep, plan.handMode)
        assertEquals(listOf(10, 50, 50), timing(plan),
            "alternating: a 50 s gap + the other hand's 10 s pull = a minute a hand")
        assertEquals(List(5) { 5 }, plan.sets.map { it.repsPerSide })
        assertEquals(List(5) { 0.15..0.25 }, bands(plan))
        assertTrue(plan.pausesOutsideTargetBand)
        assertNotNull(RoutineProtocol.fingerRehab.caution)
    }
}
