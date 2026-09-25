// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.engine.MaxAttemptLog
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.ui.maxes.LiveMaxSession
import run.nuri.getagrip.ui.maxes.maxToBeat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The max visit's state holder: what it publishes, and when.
///
/// Pure — it owns a `MaxMeasurementDraft` and a few snapshot values, so nothing here needs a
/// Canvas, a gauge or Robolectric. The screen's per-sample cost is exactly what this class
/// publishes, which is why the rule lives here rather than in the composable.
class MaxMeasurementTests {

    private fun point(kg: Double, t: Double) = DeviceStore.TracePoint(kg = kg, t = t)

    /// **The rule this class exists for.** Samples arrive ~80 times a second; a mirror that
    /// wrote unconditionally would invalidate the hero on every one of them. A steady load
    /// publishes twice — the pull beginning, and its peak — however many samples arrive.
    @Test
    fun aSteadyLoadPublishesOnceHoweverManySamplesArrive() {
        val session = LiveMaxSession(bothTogether = false, side = Side.left)
        var t = 0.0
        repeat(80) {
            session.receive(point(18.0, t))
            t += 1.0 / 80.0
        }
        assertEquals(2, session.publishes)
        assertTrue(session.isPulling)
        assertEquals(18.0, session.pullPeakKg)
    }

    /// A CLIMBING pull publishes each real step: each one is a different number on screen.
    @Test
    fun eachGenuineClimbPublishes() {
        val session = LiveMaxSession(bothTogether = false, side = Side.left)
        listOf(5.0, 10.0, 15.0, 20.0).forEachIndexed { index, kg -> session.receive(point(kg, index * 0.1)) }
        assertEquals(1 + 4, session.publishes)
        assertEquals(20.0, session.pullPeakKg)
    }

    /// Easing off publishes nothing: the pull's peak is a running maximum.
    @Test
    fun easingOffDoesNotRepublishThePeak() {
        val session = LiveMaxSession(bothTogether = false, side = Side.left)
        session.receive(point(24.0, 0.0))
        val before = session.publishes
        session.receive(point(20.0, 0.1))
        session.receive(point(12.0, 0.2))
        session.receive(point(6.0, 0.3))
        assertEquals(before, session.publishes)
        assertEquals(24.0, session.pullPeakKg)
    }

    /// Drift under the threshold is not a pull, and costs the screen nothing at all.
    @Test
    fun driftPublishesNothing() {
        val session = LiveMaxSession(bothTogether = false, side = Side.left)
        repeat(200) { session.receive(point(0.4, it / 80.0)) }
        assertEquals(0, session.publishes)
        assertFalse(session.isPulling)
        assertNull(session.pullPeakKg)
    }

    /// Letting go logs the pull with no tap: the hero shows it as the last pull, the pull's
    /// own values clear, and a new best is announced once.
    @Test
    fun lettingGoLogsThePullAndAnnouncesANewBest() {
        val session = LiveMaxSession(bothTogether = false, side = Side.left)
        session.receive(point(26.0, 0.0))
        session.receive(point(0.2, 0.5))
        assertTrue(session.isPulling, "A quick re-grip is still the same pull")
        session.receive(point(0.2, 0.5 + MaxAttemptLog.releaseSeconds))
        assertFalse(session.isPulling)
        assertNull(session.pullPeakKg)
        assertEquals(26.0, session.lastAttempt?.peakKg)
        assertEquals(1, session.newBestTick)
        // A weaker second pull logs, but is no new best.
        session.receive(point(20.0, 3.0))
        session.receive(point(0.2, 3.1))
        session.receive(point(0.2, 3.2 + MaxAttemptLog.releaseSeconds))
        assertEquals(20.0, session.lastAttempt?.peakKg)
        assertEquals(1, session.newBestTick)
        assertEquals(listOf(26.0, 20.0), session.snapshot.log.attempts.map { it.peakKg })
    }

    /// Switching hands forgets the hero's last pull (it belonged to the other hand) and logs a
    /// pull in progress against the hand that pulled it.
    @Test
    fun switchingHandsLogsThePullInProgressAndClearsTheHero() {
        val session = LiveMaxSession(bothTogether = false, side = Side.left)
        session.receive(point(30.0, 0.0))
        session.select(Side.right)
        assertEquals(Side.right, session.side)
        assertNull(session.lastAttempt)
        assertFalse(session.isPulling)
        assertEquals(listOf(Side.left), session.snapshot.log.attempts.map { it.side })
    }

    /// The dashed rule is the number to beat: this visit's best on the selected hand, else
    /// that hand's saved max — never the other hand's.
    @Test
    fun theNumberToBeatIsTheVisitsBestElseTheSavedMax() {
        val session = LiveMaxSession(bothTogether = false, side = Side.left)
        val saved = { side: Side -> if (side == Side.right) 40.0 else 30.0 }
        assertEquals(30.0, maxToBeat(session.snapshot, saved))
        session.receive(point(26.0, 0.0))
        session.close()
        assertEquals(26.0, maxToBeat(session.snapshot, saved), "This visit's best beats the saved max as the rule")
        session.select(Side.right)
        assertEquals(40.0, maxToBeat(session.snapshot, saved))
    }

    /// Deleting the last pull from the review takes it off the hero too.
    @Test
    fun removingTheLastPullClearsTheHero() {
        val session = LiveMaxSession(bothTogether = false, side = Side.left)
        session.receive(point(30.0, 0.0))
        session.close()
        val id = session.lastAttempt!!.id
        session.remove(id)
        assertNull(session.lastAttempt)
        assertTrue(session.snapshot.log.attempts.isEmpty())
    }
}
