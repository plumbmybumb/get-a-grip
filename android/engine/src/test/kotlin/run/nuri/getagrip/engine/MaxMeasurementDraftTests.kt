// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// Twin of Tests/MaxMeasurementDraftTests.swift: one visit's pulls, picks and corrections.
class MaxMeasurementDraftTests {
    /// One logged pull at `kg` on the draft's selected hand, starting at `t`.
    private fun pull(draft: MaxMeasurementDraft, kg: Double, at: Double): Double {
        draft.add(kg, at)
        draft.add(kg, at + 0.5)
        draft.add(0.1, at + 0.6)
        draft.add(0.1, at + 0.6 + MaxAttemptLog.releaseSeconds)
        return at + 1 + MaxAttemptLog.releaseSeconds
    }

    private fun r(side: Side, kg: Double, source: MaxSource = MaxSource.measured) = MaxMeasurementResult(side, kg, source)

    @Test fun eachHandSavesItsHardestPullByDefault() {
        val draft = MaxMeasurementDraft()
        var t = pull(draft, 37.0, 0.0)
        t = pull(draft, 39.0, t)
        t = pull(draft, 38.0, t)
        draft.select(Side.right)
        pull(draft, 42.0, t)
        assertEquals(listOf(r(Side.left, 39.0), r(Side.right, 42.0)), draft.results)
    }

    @Test fun aPickSurvivesLaterPullsAndFollowsItsAttempt() {
        val draft = MaxMeasurementDraft()
        var t = pull(draft, 37.0, 0.0)
        t = pull(draft, 40.0, t)
        draft.pick(draft.log.attempts[0].id)
        t = pull(draft, 44.0, t)
        assertEquals(listOf(r(Side.left, 37.0)), draft.results, "A deliberate pick is kept even after a harder pull")
        draft.move(draft.log.attempts[0].id, Side.right)
        assertEquals(listOf(r(Side.left, 44.0), r(Side.right, 37.0)), draft.results,
            "Moving the picked pull clears the pick on both hands")
    }

    @Test fun correctionBelongsToThePullItCorrects() {
        val draft = MaxMeasurementDraft()
        var t = pull(draft, 37.0, 0.0)
        assertTrue(draft.correct(listOf(r(Side.left, 36.5))))
        assertEquals(listOf(r(Side.left, 36.5, MaxSource.manual)), draft.results)
        assertEquals(37.0, draft.measuredPeak(Side.left))
        assertTrue(draft.correct(listOf(r(Side.left, 37.0))))
        assertEquals(MaxSource.measured, draft.results.first().source, "The exact peak restores measured provenance")
        draft.correct(listOf(r(Side.left, 36.5)))
        t = pull(draft, 35.0, t)
        assertEquals(listOf(r(Side.left, 37.0)), draft.results, "A new pull on the hand retires the correction")
    }

    @Test fun correctionRejectsInvalidValuesAtomicallyAndCannotInventAHand() {
        val draft = MaxMeasurementDraft()
        val t = pull(draft, 37.0, 0.0)
        draft.select(Side.right)
        pull(draft, 42.0, t)
        val original = draft.results
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFalse(draft.correct(listOf(r(Side.left, 36.0), r(Side.right, bad))))
            assertEquals(original, draft.results)
        }
        assertFalse(draft.correct(listOf(r(Side.both, 80.0))))
        assertFalse(draft.correct(listOf(r(Side.left, 36.0), r(Side.left, 35.0))))
        assertEquals(original, draft.results)
    }

    @Test fun correctionIsRefusedMidPull() {
        val draft = MaxMeasurementDraft()
        val t = pull(draft, 37.0, 0.0)
        draft.add(30.0, t)
        assertFalse(draft.correct(listOf(r(Side.left, 36.0))))
    }

    /// Provenance comes from the VALUE, not an input label (kept from the Android suite).
    @Test fun correctionProvenanceComesFromItsValueNotAnInputLabel() {
        val draft = MaxMeasurementDraft()
        pull(draft, 30.0, 0.0)
        draft.correct(listOf(r(Side.left, 29.0, MaxSource.measured)))
        assertEquals(MaxSource.manual, draft.results.single().source)
        draft.correct(listOf(r(Side.left, 30.0, MaxSource.manual)))
        assertEquals(MaxSource.measured, draft.results.single().source)
    }

    @Test fun aHandWithNoPullSavesNothing() {
        val draft = MaxMeasurementDraft()
        draft.select(Side.right)
        pull(draft, 41.0, 0.0)
        assertEquals(listOf(r(Side.right, 41.0)), draft.results)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -3.0, 0.0,
            MaxAttempt.releaseKg - 0.01)) {
            val empty = MaxMeasurementDraft()
            pull(empty, value, 0.0)
            assertTrue(empty.results.isEmpty())
        }
    }

    @Test fun deletingThePickedPullFallsBackToTheBest() {
        val draft = MaxMeasurementDraft()
        var t = pull(draft, 37.0, 0.0)
        t = pull(draft, 40.0, t)
        draft.pick(draft.log.attempts[0].id)
        draft.remove(draft.log.attempts[0].id)
        assertEquals(listOf(r(Side.left, 40.0)), draft.results)
    }

    @Test fun separateHandsNeverProduceASharedBenchmark() {
        val draft = MaxMeasurementDraft()
        draft.select(Side.both)
        assertEquals(Side.left, draft.log.side)
        pull(draft, 37.0, 0.0)
        assertEquals(listOf(Side.left), draft.results.map { it.side })
    }

    @Test fun combinedModeSavesOneSharedValueAndCannotSplit() {
        val draft = MaxMeasurementDraft(bothTogether = true)
        assertEquals(Side.both, draft.log.side)
        draft.select(Side.left)
        val t = pull(draft, 76.0, 0.0)
        pull(draft, 80.0, t)
        draft.move(draft.log.attempts[0].id, Side.left)
        assertEquals(listOf(r(Side.both, 80.0)), draft.results)
        assertTrue(draft.correct(listOf(r(Side.both, 78.0))))
        assertEquals(listOf(r(Side.both, 78.0, MaxSource.manual)), draft.results)
    }
}
