// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// A max visit: every pull is an attempt against the hand selected when it began.
/// Twin of Tests/MaxAttemptLogTests.swift.
class MaxAttemptLogTests {
    /// Feeds `kg` at 80 Hz for `seconds`, returning the time after the last sample.
    private fun hold(log: MaxAttemptLog, kg: Double, seconds: Double, from: Double): Double {
        var time = from
        val step = 1.0 / 80
        while (time < from + seconds) {
            log.add(kg, time)
            time += step
        }
        return time
    }

    /// A pull at `kg` followed by long enough off the edge to log it.
    private fun pull(log: MaxAttemptLog, kg: Double, from: Double): Double {
        val t = hold(log, kg, 1.0, from)
        return hold(log, 0.2, MaxAttemptLog.releaseSeconds + 0.1, t)
    }

    @Test fun everyPullIsItsOwnAttemptWithNoStartBetween() {
        val log = MaxAttemptLog(Side.left)
        var t = hold(log, 0.3, 3.0, 0.0)
        assertTrue(log.attempts.isEmpty(), "Drift under the threshold is not a pull")
        assertFalse(log.isPulling)
        for (kg in listOf(30.0, 34.0, 32.0)) t = pull(log, kg, t)
        assertEquals(listOf(30.0, 34.0, 32.0), log.attempts.map { it.peakKg })
        assertEquals(34.0, log.best(Side.left)?.peakKg)
        assertFalse(log.isPulling)
    }

    @Test fun aPullStaysOpenUntilTheReleaseWindowHasPassed() {
        val log = MaxAttemptLog(Side.left)
        var t = hold(log, 30.0, 1.0, 0.0)
        assertTrue(log.isPulling)
        assertEquals(30.0, log.pullPeakKg)
        t = hold(log, 0.2, MaxAttemptLog.releaseSeconds - 0.3, t)
        assertTrue(log.isPulling, "A quick re-grip is still the same pull")
        t = hold(log, 33.0, 0.5, t)
        hold(log, 0.2, MaxAttemptLog.releaseSeconds + 0.1, t)
        assertEquals(listOf(33.0), log.attempts.map { it.peakKg })
    }

    @Test fun switchingHandsMidPullLogsItAgainstTheHandThatPulled() {
        val log = MaxAttemptLog(Side.left)
        val t = hold(log, 28.0, 1.0, 0.0)
        val closed = log.select(Side.right)
        assertEquals(Side.left, closed?.side)
        assertEquals(28.0, closed?.peakKg)
        pull(log, 31.0, t)
        assertEquals(listOf(28.0), log.attempts(Side.left).map { it.peakKg })
        assertEquals(listOf(31.0), log.attempts(Side.right).map { it.peakKg })
    }

    @Test fun tiesKeepTheEarlierAttempt() {
        val log = MaxAttemptLog(Side.left)
        var t = pull(log, 30.0, 0.0)
        t = pull(log, 30.0, t)
        assertEquals(log.attempts.first().id, log.best(Side.left)?.id)
    }

    @Test fun moveAndRemoveEditTheLogOnly() {
        val log = MaxAttemptLog(Side.left)
        var t = pull(log, 30.0, 0.0)
        t = pull(log, 35.0, t)
        val wrongHand = log.attempts[1].id
        log.move(wrongHand, Side.right)
        assertEquals(30.0, log.best(Side.left)?.peakKg)
        assertEquals(35.0, log.best(Side.right)?.peakKg)
        log.remove(log.attempts[0].id)
        assertNull(log.best(Side.left))
        assertEquals(Side.left, log.side, "Editing the log never changes the selected hand")
    }

    @Test fun closingWithNoPullOrBelowThresholdLogsNothing() {
        val log = MaxAttemptLog(Side.left)
        assertNull(log.close())
        hold(log, 1.5, 2.0, 0.0)
        assertNull(log.close())
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) log.add(bad, 3.0)
        assertTrue(log.attempts.isEmpty())
    }

    /// The visit's release window is its own; a single test keeps two seconds.
    @Test fun aVisitEndsEachPullSoonerThanASingleTest() {
        assertEquals(1.0, MaxAttemptLog.releaseSeconds)
        assertEquals(MaxAttempt.releaseSeconds, MaxAttempt().endsAfter)
        val attempt = MaxAttempt(endsAfter = MaxAttemptLog.releaseSeconds)
        attempt.add(25.0, 0.0)
        attempt.add(0.2, 0.5)
        attempt.add(0.2, 0.5 + MaxAttemptLog.releaseSeconds)
        assertTrue(attempt.isComplete)
    }
}
