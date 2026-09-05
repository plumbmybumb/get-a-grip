// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.engine.MaxAttempt
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.ui.maxes.MaxMeasureTiming
import run.nuri.getagrip.ui.maxes.MaxMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// The measure screen's state holder: what it publishes, when, and when the attempt ends.
///
/// Pure — it owns a `MaxAttempt` and two snapshot values, so nothing here needs a Canvas, a
/// gauge or Robolectric. That is the whole reason the rule lives in a class rather than in
/// the composable.
class MaxMeasurementTests {

    private fun point(kg: Double, t: Double) = DeviceStore.TracePoint(kg = kg, t = t)

    // MARK: - Publish on CHANGE, never on every sample

    /// **The rule this class exists for.** Samples arrive ~80 times a second and the peak
    /// climbs during the ramp and then holds still; a mirror that wrote unconditionally would
    /// invalidate the screen on every one of them — the exact pattern that made the routine
    /// deck feel laggy. Eighty identical samples must publish exactly once.
    @Test
    fun aSteadyLoadPublishesOnceHoweverManySamplesArrive() {
        val m = MaxMeasurement()
        var t = 0.0
        repeat(80) {
            m.receive(point(18.0, t))
            t += 1.0 / 80.0
        }
        assertEquals(1, m.publishes)
        assertEquals(18.0, m.peakKg)
    }

    /// A CLIMBING pull publishes on each real step, because each one is a different number on
    /// screen. The guard is about equality, not about rate limiting.
    @Test
    fun eachGenuineClimbPublishes() {
        val m = MaxMeasurement()
        listOf(5.0, 10.0, 15.0, 20.0).forEachIndexed { index, kg ->
            m.receive(point(kg, index * 0.1))
        }
        assertEquals(4, m.publishes)
        assertEquals(20.0, m.peakKg)
    }

    /// Coming back DOWN publishes nothing: `peakKg` is a running maximum, so the number on
    /// screen has not changed. This is what stops the hero flickering back toward zero while
    /// you ease off — the reason the live reading is demoted to its own line.
    @Test
    fun easingOffDoesNotRepublishThePeak() {
        val m = MaxMeasurement()
        m.receive(point(24.0, 0.0))
        assertEquals(1, m.publishes)
        m.receive(point(20.0, 0.1))
        m.receive(point(12.0, 0.2))
        m.receive(point(6.0, 0.3))
        assertEquals(1, m.publishes)
        assertEquals(24.0, m.peakKg)
    }

    /// Completion is a publish of its own, because `isComplete` is what the screen watches to
    /// leave the measuring phase.
    @Test
    fun completingPublishesOnceMore() {
        val m = MaxMeasurement()
        m.receive(point(24.0, 0.0))
        val before = m.publishes
        m.finish()
        assertTrue(m.isComplete)
        assertEquals(before + 1, m.publishes)
        // Idempotent: a Done tap after a timeout must not publish a second time.
        m.finish()
        assertEquals(before + 1, m.publishes)
    }

    // MARK: - hasResult

    /// **A result means you PULLED, not merely that a number arrived.** Every load cell drifts
    /// a few hundred grams with nothing on it, so the screen must not offer to save a 0.4 kg
    /// max for somebody who never touched the edge. Mirrored off `MaxAttempt.releaseKg` so the
    /// screen and the engine cannot disagree about what counts.
    @Test
    fun driftIsNotAResult() {
        val m = MaxMeasurement()
        m.receive(point(0.4, 0.0))
        assertFalse(m.hasResult)
        m.receive(point(MaxAttempt.releaseKg, 0.1))
        assertTrue(m.hasResult)
    }

    /// Pull, hold, let go — no tap. The attempt ends itself once the load has been off the
    /// edge for `releaseSeconds`, which is what the screen's completion effect watches.
    @Test
    fun lettingGoEndsTheAttemptWithoutATap() {
        val m = MaxMeasurement()
        m.receive(point(26.0, 0.0))
        m.receive(point(0.2, 0.5))
        assertFalse(m.isComplete)
        m.receive(point(0.2, 0.5 + MaxAttempt.releaseSeconds))
        assertTrue(m.isComplete)
        assertEquals(26.0, m.peakKg)
    }

    @Test
    fun resetClearsEverythingIncludingTheCounter() {
        val m = MaxMeasurement()
        m.receive(point(26.0, 0.0))
        m.finish()
        m.reset()
        assertEquals(0.0, m.peakKg)
        assertFalse(m.isComplete)
        assertFalse(m.hasResult)
        assertEquals(0, m.publishes)
    }

    // MARK: - The timeout

    /// Long enough for a full attempt including a slow set-up on the edge; short enough that a
    /// screen left open cannot flatten the gauge's battery.
    @Test
    fun theAttemptTimesOutAtFortyFiveSeconds() {
        assertEquals(45.0, MaxMeasureTiming.TIMEOUT_SECONDS)
        assertFalse(MaxMeasureTiming.hasTimedOut(0.0))
        assertFalse(MaxMeasureTiming.hasTimedOut(44.9))
        assertTrue(MaxMeasureTiming.hasTimedOut(45.0))
        assertTrue(MaxMeasureTiming.hasTimedOut(120.0))
    }

    /// **A timeout KEEPS the result.** It is the same `finish()` the Done button calls, so a
    /// pull that happened is still offered — only the reason for stopping differs, and that
    /// travels with the stream-stop cause rather than with the number.
    @Test
    fun timingOutKeepsWhateverWasPulled() {
        val m = MaxMeasurement()
        m.receive(point(29.5, 1.0))
        m.finish()
        assertTrue(m.isComplete)
        assertTrue(m.hasResult)
        assertEquals(29.5, m.peakKg)
    }

    /// A screen opened and forgotten times out with NOTHING, and must not offer to save the
    /// gauge's drift as a max.
    @Test
    fun timingOutWithNoPullOffersNothing() {
        val m = MaxMeasurement()
        m.receive(point(0.3, 1.0))
        m.finish()
        assertTrue(m.isComplete)
        assertFalse(m.hasResult)
    }
}
