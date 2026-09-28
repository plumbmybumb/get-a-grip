// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// The live gauge's estimated reps: up off the baseline, back down to it, one rep.
/// Twin of Tests/RepEstimatorTests.swift.
class RepEstimatorTests {
    /// Feeds `kg` at 80 Hz for `seconds`, returning the time after the last sample.
    private fun hold(reps: RepEstimator, kg: Double, seconds: Double, from: Double): Double {
        var time = from
        val step = 1.0 / 80
        while (time < from + seconds) {
            reps.add(kg, time)
            time += step
        }
        return time
    }

    /// One pull at `kg`, then long enough at rest to end it.
    private fun pull(reps: RepEstimator, kg: Double, from: Double, seconds: Double = 3.0): Double {
        val t = hold(reps, kg, seconds, from)
        return hold(reps, 0.2, RepEstimator.releaseSeconds + 0.1, t)
    }

    @Test fun eachPullAndReleaseIsOneRep() {
        val reps = RepEstimator()
        var t = hold(reps, 0.4, 2.0, 0.0)
        assertEquals(0, reps.count, "Drift at rest is not a rep")
        for (kg in listOf(12.0, 13.0, 11.5, 12.5)) t = pull(reps, kg, t)
        assertEquals(4, reps.count)
        assertFalse(reps.isPulling)
    }

    @Test fun theRepCountsOnTheWayDownNotOnTheWayUp() {
        val reps = RepEstimator()
        val t = hold(reps, 20.0, 5.0, 0.0)
        assertTrue(reps.isPulling)
        assertEquals(0, reps.count, "Still on the edge: nothing is known to have ended")
        hold(reps, 0.0, RepEstimator.releaseSeconds + 0.1, t)
        assertEquals(1, reps.count)
    }

    @Test fun aKnockOnTheGaugeIsNotARep() {
        val reps = RepEstimator()
        val t = hold(reps, 15.0, RepEstimator.minimumPullSeconds / 2, 0.0)
        hold(reps, 0.0, 1.0, t)
        assertEquals(0, reps.count)
        assertFalse(reps.isPulling)
    }

    @Test fun easingOffMidPullIsTheSamePull() {
        val reps = RepEstimator()
        var t = hold(reps, 40.0, 2.0, 0.0)
        t = hold(reps, 25.0, 2.0, t)
        t = hold(reps, 38.0, 2.0, t)
        hold(reps, 0.3, 1.0, t)
        assertEquals(1, reps.count, "A pull ends at the baseline, not at a dip")
    }

    @Test fun aQuickRegripDoesNotSplitOnePullInTwo() {
        val reps = RepEstimator()
        var t = hold(reps, 20.0, 2.0, 0.0)
        t = hold(reps, 0.2, RepEstimator.releaseSeconds / 2, t)
        t = hold(reps, 20.0, 2.0, t)
        hold(reps, 0.2, 1.0, t)
        assertEquals(1, reps.count)
    }

    @Test fun theBaselineScalesWithAHeavyPull() {
        val reps = RepEstimator()
        val t = hold(reps, 60.0, 2.0, 0.0)
        assertEquals(9.0, reps.releaseKg, 0.001)
        hold(reps, 5.0, 1.0, t)
        assertEquals(1, reps.count)
    }

    @Test fun anUntaredGaugeRestingAboveTheLineCountsNothing() {
        val reps = RepEstimator()
        var t = 0.0
        repeat(4) {
            t = hold(reps, 20.0, 2.0, t)
            t = hold(reps, 4.0, 2.0, t)
        }
        assertEquals(0, reps.count, "Never back to the baseline, so never a finished rep")
    }

    @Test fun cancelDropsThePullInProgressAndResetZeroes() {
        val reps = RepEstimator()
        var t = pull(reps, 12.0, 0.0)
        t = hold(reps, 12.0, 2.0, t)
        reps.cancelPull()
        hold(reps, 0.0, 1.0, t)
        assertEquals(1, reps.count, "A cancelled pull is not counted when the load falls")
        reps.reset()
        assertEquals(0, reps.count)
        assertFalse(reps.isPulling)
    }

    @Test fun nonFiniteReadingsAreIgnored() {
        val reps = RepEstimator()
        val t = hold(reps, 20.0, 2.0, 0.0)
        reps.add(Double.NaN, t)
        reps.add(0.0, Double.POSITIVE_INFINITY)
        assertTrue(reps.isPulling)
    }
}

class StopwatchTests {
    @Test fun startPauseResumeBanksOnlyRunningTime() {
        var watch = Stopwatch()
        assertEquals(0.0, watch.elapsed(100.0))
        watch = watch.started(10.0)
        assertEquals(5.0, watch.elapsed(15.0))
        watch = watch.paused(15.0)
        assertFalse(watch.isRunning)
        assertEquals(5.0, watch.elapsed(60.0), "Paused time is not counted")
        watch = watch.started(60.0)
        assertEquals(7.5, watch.elapsed(62.5))
    }

    @Test fun repeatedStartOrPauseChangesNothing() {
        var watch = Stopwatch().started(0.0).started(5.0)
        assertEquals(10.0, watch.elapsed(10.0))
        watch = watch.paused(10.0).paused(20.0)
        assertEquals(10.0, watch.elapsed(30.0))
    }

    @Test fun aClockSteppingBackCannotEraseBankedTime() {
        val watch = Stopwatch().started(0.0).paused(8.0).started(20.0)
        assertEquals(8.0, watch.elapsed(19.0))
    }

    @Test fun label() {
        assertEquals("0:00.0", Stopwatch.label(0.0))
        assertEquals("0:42.3", Stopwatch.label(42.37))
        assertEquals("12:05.0", Stopwatch.label(725.0))
        assertEquals("1:02:03.4", Stopwatch.label(3723.4))
        assertEquals("0:00.0", Stopwatch.label(-3.0))
    }
}
