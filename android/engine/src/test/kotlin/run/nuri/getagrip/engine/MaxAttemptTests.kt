// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// The max rule and, mostly, the rule for when an attempt is OVER.
///
/// The measurement itself is now a one-liner — the peak sample — so the tests that
/// matter are the ones around it: a stray reading must not end an attempt early, a
/// re-grip must not either, and a quiet set-up must not end one before it has begun.
/// Those are the failures that would cost someone a real effort.
/// Translated from Tests/MaxAttemptTests.swift.
class MaxAttemptTests {

    /// Feed a constant load for `seconds` at 80 Hz, the real sample rate. Returns the
    /// timestamp one tick past the end, so callers can chain.
    private fun hold(attempt: MaxAttempt, kg: Double, seconds: Double, from: Double = 0.0): Double {
        val period = 1.0 / 80
        var t = from
        val end = from + seconds
        while (t < end) {
            attempt.add(kg, t)
            t += period
        }
        return t
    }

    // MARK: - The result

    @Test
    fun theResultIsTheHardestReading() {
        val attempt = MaxAttempt()
        var t = hold(attempt, kg = 30.0, seconds = 2.0)
        t = hold(attempt, kg = 58.0, seconds = 1.0, from = t)
        hold(attempt, kg = 41.0, seconds = 2.0, from = t)
        assertEquals(58.0, attempt.peakKg, 0.001)
    }

    /// The point of the change from a sustained hold: a real max decays the moment it is
    /// reached, and the number has to be what was actually pulled.
    @Test
    fun aBriefPeakCounts() {
        val attempt = MaxAttempt()
        var t = hold(attempt, kg = 40.0, seconds = 2.0)
        t = hold(attempt, kg = 66.0, seconds = 0.15, from = t)
        hold(attempt, kg = 38.0, seconds = 2.0, from = t)
        assertEquals(
            66.0, attempt.peakKg, 0.001,
            "A peak counts however briefly it lasted — see MaxAttempt",
        )
    }

    /// The live display promises "this is what will be saved", so it must never retreat.
    @Test
    fun theResultNeverDecreases() {
        val attempt = MaxAttempt()
        var seen = 0.0
        val period = 1.0 / 80
        var t = 0.0
        while (t < 10) {
            attempt.add(maxOf(0.0, 30 + 20 * sin(t)), t)
            assertTrue(attempt.peakKg >= seen)
            seen = attempt.peakKg
            t += period
        }
    }

    @Test
    fun anEmptyAttemptHasNoResult() {
        val attempt = MaxAttempt()
        assertFalse(attempt.hasResult)
        assertFalse(attempt.isComplete)
        assertEquals(0.0, attempt.peakKg)
    }

    /// Idle drift is not a max. Without this, a screen opened and left alone would offer
    /// to record the load cell's own noise.
    @Test
    fun noiseBelowTheThresholdIsNotAResult() {
        val attempt = MaxAttempt()
        hold(attempt, kg = 0.4, seconds = 10.0)
        assertFalse(attempt.hasResult)
        assertTrue(attempt.peakKg > 0, "The reading is still tracked")
    }

    // MARK: - Ending

    @Test
    fun pullThenLetGoFinishesOnItsOwn() {
        val attempt = MaxAttempt()
        var t = hold(attempt, kg = 45.0, seconds = 3.0)
        assertFalse(attempt.isComplete, "Still on the edge")

        t = hold(attempt, kg = 0.3, seconds = MaxAttempt.releaseSeconds + 0.2, from = t)
        assertTrue(attempt.isComplete)
        assertEquals(45.0, attempt.peakKg, 0.001, "Letting go must not disturb the result")

        // Anything after completion is ignored, so a stray sample — or the gauge being
        // knocked while the result is on screen — cannot rewrite a finished attempt.
        hold(attempt, kg = 90.0, seconds = 3.0, from = t)
        assertEquals(45.0, attempt.peakKg, 0.001)
    }

    /// Re-gripping between efforts must not end the attempt.
    @Test
    fun aBriefReleaseDoesNotFinishTheAttempt() {
        val attempt = MaxAttempt()
        var t = hold(attempt, kg = 45.0, seconds = 2.0)
        t = hold(attempt, kg = 0.5, seconds = MaxAttempt.releaseSeconds - 0.5, from = t)
        assertFalse(attempt.isComplete)

        // …and the second effort still counts.
        hold(attempt, kg = 58.0, seconds = 2.0, from = t)
        assertEquals(58.0, attempt.peakKg, 0.001)
    }

    /// THE regression this guard exists for: settling onto the edge is entirely below
    /// the release threshold, and an attempt that ended during it would be over before
    /// the first pull.
    @Test
    fun aQuietSetUpDoesNotFinishTheAttempt() {
        val attempt = MaxAttempt()
        val t = hold(attempt, kg = 0.4, seconds = 20.0)
        assertFalse(attempt.isComplete)
        assertFalse(attempt.hasResult)

        hold(attempt, kg = 40.0, seconds = 3.0, from = t)
        assertEquals(40.0, attempt.peakKg, 0.001)
    }

    /// A brush against the edge that never reaches `releaseKg` is not a pull, so it must
    /// not arm the auto-finish either.
    @Test
    fun brushingTheEdgeBelowTheThresholdDoesNotArmTheFinish() {
        val attempt = MaxAttempt()
        var t = hold(attempt, kg = 1.5, seconds = 1.0)
        t = hold(attempt, kg = 0.0, seconds = 5.0, from = t)
        assertFalse(attempt.isComplete)
    }

    @Test
    fun finishingByHandKeepsThePeak() {
        val attempt = MaxAttempt()
        hold(attempt, kg = 33.0, seconds = 2.0)
        attempt.finish()
        assertTrue(attempt.isComplete)
        assertEquals(33.0, attempt.peakKg, 0.001)
    }

    /// Dropped BLE packets leave a GAP in the timeline rather than a low reading, and a
    /// gap that spans the release window while you are still pulling must not end the
    /// attempt — the samples either side are both above the threshold.
    @Test
    fun aGapWhileStillPullingDoesNotFinishTheAttempt() {
        val attempt = MaxAttempt()
        var t = hold(attempt, kg = 50.0, seconds = 1.0)
        t += 4 // four seconds of silence
        hold(attempt, kg = 50.0, seconds = 1.0, from = t)
        assertFalse(attempt.isComplete)
        assertEquals(50.0, attempt.peakKg, 0.001)
    }

    // MARK: - Robustness

    @Test
    fun nonFiniteSamplesAreIgnored() {
        val attempt = MaxAttempt()
        hold(attempt, kg = 40.0, seconds = 2.0)
        attempt.add(Double.NaN, 99.0)
        attempt.add(200.0, Double.POSITIVE_INFINITY)
        assertEquals(40.0, attempt.peakKg, 0.001)
    }

    /// The window is gone, so the sample rate cannot influence the peak — pinned because
    /// a lossy link must not report a different max from a clean one.
    @Test
    fun theRateOfSamplingDoesNotChangeTheAnswer() {
        fun run(period: Double): Double {
            val attempt = MaxAttempt()
            var t = 0.0
            while (t < 4) {
                attempt.add(if (t < 1) 10.0 else 47.0, t)
                t += period
            }
            return attempt.peakKg
        }
        assertEquals(47.0, run(1.0 / 80), 0.001)
        assertEquals(47.0, run(1.0 / 10), 0.001)
        assertEquals(47.0, run(1.0 / 4), 0.001)
    }
}
