// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MaxMeasurementDraftTests {
    @Test fun activeHandCannotSwitchOrSaveMidAttempt() {
        val draft = MaxMeasurementDraft()
        assertTrue(draft.begin(Side.left))
        assertFalse(draft.begin(Side.right))
        assertEquals(Side.left, draft.activeSide)
        assertTrue(draft.results.isEmpty())
        assertEquals(Side.left, draft.finish(30.0))
        assertNull(draft.activeSide)
        assertEquals(listOf(MaxMeasurementResult(Side.left, 30.0)), draft.results)
    }

    @Test fun handsRemainIndependentAndAlwaysHaveStableOrdering() {
        val draft = MaxMeasurementDraft()
        draft.begin(Side.right)
        draft.finish(40.0)
        draft.begin(Side.left)
        draft.finish(30.0)
        assertEquals(listOf(MaxMeasurementResult(Side.left, 30.0), MaxMeasurementResult(Side.right, 40.0)), draft.results)
    }

    @Test fun aCombinedMeasurementRequiresExplicitCombinedMode() {
        assertFalse(MaxMeasurementDraft().begin(Side.both))
        val draft = MaxMeasurementDraft(bothTogether = true)
        assertFalse(draft.begin(Side.left))
        assertFalse(draft.begin(Side.right))
        assertTrue(draft.begin(Side.both))
        draft.finish(70.0)
        assertEquals(listOf(MaxMeasurementResult(Side.both, 70.0)), draft.results)
    }

    @Test fun anEmptyOrInvalidRetryPreservesThePreviousCorrectedResult() {
        for (invalid in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, MaxAttempt.releaseKg - 0.1)) {
            val draft = MaxMeasurementDraft()
            draft.begin(Side.left)
            draft.finish(30.0)
            assertTrue(draft.correct(listOf(MaxMeasurementResult(Side.left, 29.0))))
            draft.begin(Side.left)
            draft.finish(invalid)
            assertEquals(30.0, draft.measuredPeak(Side.left))
            assertEquals(listOf(MaxMeasurementResult(Side.left, 29.0, MaxSource.manual)), draft.results)
        }
    }

    @Test fun validRetryReplacesOnlyItsOwnHandAndRestoresMeasuredSource() {
        val draft = MaxMeasurementDraft()
        draft.begin(Side.left); draft.finish(30.0)
        draft.begin(Side.right); draft.finish(40.0)
        draft.correct(listOf(MaxMeasurementResult(Side.left, 29.0)))
        draft.begin(Side.left); draft.finish(28.0)
        assertEquals(listOf(MaxMeasurementResult(Side.left, 28.0), MaxMeasurementResult(Side.right, 40.0)), draft.results)
    }

    @Test fun correctionProvenanceComesFromItsValueNotAnInputLabel() {
        val draft = MaxMeasurementDraft()
        draft.begin(Side.left); draft.finish(30.0)
        draft.correct(listOf(MaxMeasurementResult(Side.left, 29.0, MaxSource.measured)))
        assertEquals(MaxSource.manual, draft.results.single().source)
        draft.correct(listOf(MaxMeasurementResult(Side.left, 30.0, MaxSource.manual)))
        assertEquals(MaxSource.measured, draft.results.single().source)
    }

    @Test fun correctionRejectsEveryHandWhenAnyValueIsInvalidOrUnmeasured() {
        val draft = MaxMeasurementDraft()
        draft.begin(Side.left); draft.finish(30.0)
        assertFalse(draft.correct(listOf(MaxMeasurementResult(Side.left, 29.0), MaxMeasurementResult(Side.right, 40.0))))
        assertEquals(30.0, draft.peak(Side.left))
        assertFalse(draft.correct(listOf(MaxMeasurementResult(Side.left, 29.0), MaxMeasurementResult(Side.left, 28.0))))
        assertFalse(draft.correct(listOf(MaxMeasurementResult(Side.left, Double.NaN))))
        draft.begin(Side.right)
        assertFalse(draft.correct(listOf(MaxMeasurementResult(Side.left, 29.0))))
        assertEquals(30.0, draft.peak(Side.left))
    }

    @Test fun copiedDraftHasIndependentCapturedValuesAndCorrections() {
        val original = MaxMeasurementDraft()
        original.begin(Side.left); original.finish(30.0)
        val copy = original.copy()
        copy.correct(listOf(MaxMeasurementResult(Side.left, 29.0)))
        copy.begin(Side.right); copy.finish(40.0)
        assertEquals(listOf(MaxMeasurementResult(Side.left, 30.0)), original.results)
        assertEquals(2, copy.results.size)
    }
}
