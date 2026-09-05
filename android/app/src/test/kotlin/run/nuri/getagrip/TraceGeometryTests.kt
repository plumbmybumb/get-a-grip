// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.ui.components.TraceGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/// The force trace's arithmetic, without a screen.
///
/// Every rule in here was learned on real hardware — the disappearing broadcast graph, the
/// bridge drawn across a backgrounded gap, the rubber-banding axis, the head that breathed
/// at the firmware's delivery cadence — and every one of them is pure enough to be pinned
/// by a JVM test. The Canvas above `TraceGeometry` draws; it decides nothing.
class TraceGeometryTests {

    private fun points(vararg pairs: Pair<Double, Double>): List<DeviceStore.TracePoint> =
        pairs.map { (t, kg) -> DeviceStore.TracePoint(kg = kg, t = t) }

    // MARK: - Smoothing

    /// Three-point mean of the DRAWING only. The interior point is averaged with both
    /// neighbours; nothing here ever reaches the values the engine times reps with.
    @Test
    fun smoothingAveragesAPointWithBothNeighbours() {
        val samples = points(0.0 to 0.0, 0.1 to 30.0, 0.2 to 0.0)
        assertEquals(10.0, TraceGeometry.smoothed(samples, 1), 1e-9)
    }

    /// **The ends are left alone.** There is no neighbour to average the newest point
    /// with, and inventing one would move the number being read at the exact tip of the
    /// curve.
    @Test
    fun smoothingLeavesTheEndsUntouched() {
        val samples = points(0.0 to 5.0, 0.1 to 30.0, 0.2 to 7.0)
        assertEquals(5.0, TraceGeometry.smoothed(samples, 0), 1e-9)
        assertEquals(7.0, TraceGeometry.smoothed(samples, 2), 1e-9)
    }

    // MARK: - Gap detection

    /// The Progressor's floor. Three periods of 80 Hz is 37.5 ms, far under 0.35 s, so a
    /// Tindeq keeps the number that was tuned for its ~10 notifications a second.
    @Test
    fun theGapThresholdNeverFallsBelowTheProgressorFloor() {
        val gap = TraceGeometry.streamGapSeconds(
            GaugeKind.progressor.capabilities.nominalSampleRate,
            bridgesSparseDelivery = false,
        )
        assertEquals(TraceGeometry.PROGRESSOR_GAP_SECONDS, gap, 1e-9)
    }

    /// A 10 Hz board's own three periods are 0.3 s — still under the floor — while a 4 Hz
    /// device would get 0.75 s. Keyed to the rate, an ordinary miss stays ordinary.
    @Test
    fun aSlowGaugeGetsThreeOfItsOwnSamplePeriods() {
        assertEquals(0.75, TraceGeometry.streamGapSeconds(4.0, bridgesSparseDelivery = false), 1e-9)
        assertEquals(0.35, TraceGeometry.streamGapSeconds(10.0, bridgesSparseDelivery = false), 1e-9)
    }

    /// **A broadcast gauge has no gap threshold at all.** Delivery is bursty by nature —
    /// clumps of advertisements with multi-second holes — so every hole "started a new run"
    /// and the whole drawn history vanished at each one. Points older than the window still
    /// fall off the left edge on their own.
    @Test
    fun aBroadcastGaugeNeverBreaksARun() {
        val gap = TraceGeometry.streamGapSeconds(8.0, bridgesSparseDelivery = true)
        val sparse = points(0.0 to 1.0, 4.0 to 2.0, 9.0 to 3.0)
        assertEquals(0, TraceGeometry.runStart(sparse, gap))
    }

    /// A gap is a BOUNDARY, not data: the points either side of it are minutes apart in the
    /// hand even though they are adjacent in the array.
    @Test
    fun theRunStartsAfterTheNewestGap() {
        val samples = points(
            0.0 to 1.0,
            0.1 to 1.0,
            // Backgrounded here.
            30.0 to 2.0,
            30.1 to 2.0,
        )
        assertEquals(2, TraceGeometry.runStart(samples, gapSeconds = 0.35))
    }

    /// Two gaps: only the NEWEST one matters, because everything before it is history the
    /// window is about to drop anyway.
    @Test
    fun onlyTheNewestGapStartsTheRun() {
        val samples = points(0.0 to 1.0, 10.0 to 1.0, 10.1 to 1.0, 40.0 to 2.0)
        assertEquals(3, TraceGeometry.runStart(samples, gapSeconds = 0.35))
    }

    /// An unbroken buffer draws from its own beginning.
    @Test
    fun anUnbrokenBufferStartsAtZero() {
        val samples = points(0.0 to 1.0, 0.1 to 1.0, 0.2 to 1.0)
        assertEquals(0, TraceGeometry.runStart(samples, gapSeconds = 0.35))
    }

    // MARK: - Placement

    /// Points are placed by their own playback TIME against a right edge that has already
    /// slid `drift` past the newest of them — never by index, which is what made an 80 Hz
    /// stream delivered in batches of eight stutter ten times a second.
    @Test
    fun aPointIsPlacedByItsOwnAgeInTheWindow() {
        val width = 600f
        // Exactly one window old: hard on the left edge.
        assertEquals(0f, TraceGeometry.x(0.0, newestT = 6.0, drift = 0.0, width = width), 1e-3f)
        // Newest, with no drift: hard on the right edge.
        assertEquals(width, TraceGeometry.x(6.0, newestT = 6.0, drift = 0.0, width = width), 1e-3f)
        // Half a window old: the middle.
        assertEquals(300f, TraceGeometry.x(3.0, newestT = 6.0, drift = 0.0, width = width), 1e-3f)
    }

    /// **ONE off-screen anchor per run**, so the segment crossing x = 0 is drawn every
    /// frame and the trace slides continuously off the left edge instead of the visible
    /// start snapping forward when a point ages out.
    @Test
    fun theAnchorIsTheNewestPointStillOffTheLeftEdge() {
        // 0…12 s of history in a 6 s window: everything before t = 6 is off-screen.
        val samples = (0..12).map { DeviceStore.TracePoint(kg = 1.0, t = it.toDouble()) }
        val anchor = TraceGeometry.anchorIndex(
            samples = samples,
            runStart = 0,
            newestT = 12.0,
            drift = 0.0,
            width = 600f,
        )
        // t = 6 lands exactly on x = 0, so t = 5 is the last point still strictly off the
        // left edge — ONE anchor, and the segment from it to t = 6 is what crosses the edge.
        assertEquals(5, anchor)
        assertTrue(TraceGeometry.x(samples[anchor + 1].t, 12.0, 0.0, 600f) >= 0f)
    }

    /// The anchor never walks back past its own run: a gap's far side is not this run's
    /// history.
    @Test
    fun theAnchorNeverPrecedesTheRunItBelongsTo() {
        val samples = (0..12).map { DeviceStore.TracePoint(kg = 1.0, t = it.toDouble()) }
        val anchor = TraceGeometry.anchorIndex(samples, runStart = 11, newestT = 12.0, drift = 0.0, width = 600f)
        assertEquals(11, anchor)
    }

    // MARK: - The head

    /// The newest sample is always one BLE batch old, so the head reads a 120 ms running
    /// average: long enough to damp batch noise, short enough that a fast pull's onset does
    /// not drag a laggy hook at the tip.
    @Test
    fun theHeadAveragesTheLastTwelveHundredthsOfASecond() {
        val samples = points(
            // Well outside the 0.12 s window — must not count.
            0.0 to 100.0,
            0.90 to 10.0,
            0.95 to 12.0,
            1.00 to 14.0,
        )
        assertEquals(12.0, TraceGeometry.headAverageKg(samples), 1e-9)
    }

    /// One lonely sample inside the window is its own average — the fallback exists so a
    /// sparse gauge still gets a head rather than a zero.
    @Test
    fun theHeadFallsBackToTheNewestSampleWhenItStandsAlone() {
        val samples = points(0.0 to 3.0, 5.0 to 9.0)
        assertEquals(9.0, TraceGeometry.headAverageKg(samples), 1e-9)
    }

    // MARK: - The axis

    /// The floor is 10 kg, so an idle gauge does not draw its own noise as a mountain.
    @Test
    fun theAxisNeverFallsBelowTenKilograms() {
        assertEquals(10.0, TraceGeometry.axisTarget(maxSeen = 0.5, thresholdKg = null, bandHiKg = null), 1e-9)
    }

    /// A threshold gets 1.6× and a band's ceiling 1.25×, so neither sits jammed against the
    /// top edge of the card.
    @Test
    fun theAxisLeavesHeadroomAboveAThresholdAndABand() {
        assertEquals(32.0, TraceGeometry.axisTarget(0.0, thresholdKg = 20.0, bandHiKg = null), 1e-9)
        assertEquals(50.0, TraceGeometry.axisTarget(0.0, thresholdKg = null, bandHiKg = 40.0), 1e-9)
        assertEquals(50.0, TraceGeometry.axisTarget(maxSeen = 40.0, thresholdKg = null, bandHiKg = null), 1e-9)
    }

    /// **The max is LATCHED**, so a peak sliding out of the six-second buffer cannot pull
    /// the whole graph back down — the rubber-banding this replaced.
    @Test
    fun theCeilingLatchesThePeakEvenAfterItLeavesTheBuffer() {
        val axis = TraceGeometry.AxisMemory()
        axis.ceiling(points(0.0 to 40.0), null, null, now = 0.0, reduceMotion = true)
        // A later, emptier buffer: the ceiling must not shrink back to the 10 kg floor.
        val after = axis.ceiling(points(10.0 to 1.0), null, null, now = 10.0, reduceMotion = true)
        assertEquals(50.0, after, 1e-9)
    }

    /// The displayed ceiling EASES toward its target at dt × 10 per frame, so the one
    /// legitimate rescale — a new personal peak mid-session — is a glide, not a snap. The
    /// first frame is the exception: there is nothing to glide from.
    @Test
    fun theCeilingEasesTowardANewTargetRatherThanSnapping() {
        val axis = TraceGeometry.AxisMemory()
        val first = axis.ceiling(points(0.0 to 1.0), null, null, now = 0.0, reduceMotion = false)
        assertEquals(10.0, first, 1e-9)
        // A 100 kg peak wants a 125 kg ceiling; one 50 ms frame moves half way there
        // (dt × 10 = 0.5), not all the way.
        val stepped = axis.ceiling(points(0.05 to 100.0), null, null, now = 0.05, reduceMotion = false)
        assertEquals(10.0 + (125.0 - 10.0) * 0.5, stepped, 1e-9)
        assertTrue(stepped < 125.0)
    }

    /// Reduce Motion takes the target outright: someone who asked for less movement gets a
    /// scale that changes, not one that travels.
    @Test
    fun reduceMotionTakesTheCeilingImmediately() {
        val axis = TraceGeometry.AxisMemory()
        axis.ceiling(points(0.0 to 1.0), null, null, now = 0.0, reduceMotion = true)
        val stepped = axis.ceiling(points(0.05 to 100.0), null, null, now = 0.05, reduceMotion = true)
        assertEquals(125.0, stepped, 1e-9)
    }

    /// The per-frame step is clamped at 0.1 s, so a stalled main thread cannot make the
    /// axis jump the whole way in one frame the moment it recovers.
    @Test
    fun aLongFrameCannotJumpTheAxisAllTheWay() {
        val axis = TraceGeometry.AxisMemory()
        axis.ceiling(points(0.0 to 1.0), null, null, now = 0.0, reduceMotion = false)
        val afterStall = axis.ceiling(points(5.0 to 100.0), null, null, now = 5.0, reduceMotion = false)
        // dt clamps to 0.1 → dt × 10 = 1.0, i.e. the largest single step allowed, which
        // lands exactly on the target and never past it.
        assertEquals(125.0, afterStall, 1e-9)
    }
}
