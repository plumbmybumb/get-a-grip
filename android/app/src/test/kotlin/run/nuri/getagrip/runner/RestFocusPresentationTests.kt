// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import kotlinx.coroutines.cancel
import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.engine.RunnerEvent
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.inertScope
import run.nuri.getagrip.store.DeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Use the real engine → RunnerSession publication path. The rest that just
 * started belongs to the completed slot, even while its labels show the next one. */
class RestFocusPresentationTests {
    @Test fun nineSecondsStaysCompactAndTenSecondsUsesRestFocus() {
        for ((rest, expected) in listOf(9 to false, 10 to true)) {
            Harness(rest = rest).use { h ->
                h.session.send(RunnerEvent.SkipRep)
                assertEquals(RunnerPhase.Resting(0), h.session.snapshot.phase)
                assertEquals(rest, h.session.snapshot.scheduledRestSeconds)
                assertEquals(expected, h.session.snapshot.showsRestFocus)
            }
        }
    }

    @Test fun aSetOverrideDeterminesTheLayoutInsteadOfTheRoutineDefault() {
        for ((routine, override, expected) in listOf(Triple(30, 9, false), Triple(3, 10, true))) {
            Harness(rest = routine, firstRest = override).use { h ->
                h.session.send(RunnerEvent.SkipRep)
                assertEquals(override, h.session.snapshot.scheduledRestSeconds)
                assertEquals(expected, h.session.snapshot.showsRestFocus)
            }
        }
    }

    @Test fun setBreakUsesTheCompletedSlotWhileIdentityAndCountsLookAhead() {
        Harness(rest = 3, setBreak = 10, firstRest = 3, nextRest = 3, reps = 1).use { h ->
            h.session.send(RunnerEvent.SkipRep)
            val snapshot = h.session.snapshot
            assertTrue(snapshot.isSetBreak)
            assertEquals(10, snapshot.scheduledRestSeconds)
            assertTrue(snapshot.showsRestFocus)
            assertEquals(15, snapshot.grip?.edgeMM)
            assertEquals(2, snapshot.setNumber)
            assertEquals(1, snapshot.completedRepCount)
            assertEquals(2, snapshot.plannedRepCount)
        }
        Harness(rest = 30, setBreak = 9, nextRest = 45, reps = 1).use { h ->
            h.session.send(RunnerEvent.SkipRep)
            assertEquals(15, h.session.snapshot.grip?.edgeMM)
            assertEquals(9, h.session.snapshot.scheduledRestSeconds)
            assertFalse(h.session.snapshot.showsRestFocus,
                "The upcoming set's long rest cannot enlarge a short set break")
        }
    }

    @Test fun longRestKeepsItsLayoutThroughTheFinalSecondsAndClearsAtTheNextPull() {
        Harness(rest = 10).use { h ->
            h.session.send(RunnerEvent.SkipRep)
            val started = h.clock.uptime
            for ((elapsed, remaining) in listOf(0.0 to 10, 8.0 to 2, 9.0 to 1, 9.999 to 1)) {
                h.clock.uptime = started + elapsed
                h.session.tickNow()
                assertEquals(remaining, h.session.snapshot.secondsShown)
                assertTrue(h.session.snapshot.showsRestFocus, "Keep the layout stable at $remaining seconds")
                assertEquals(10, h.session.snapshot.scheduledRestSeconds)
            }
            assertTrue(h.session.snapshot.copy(secondsShown = 0).showsRestFocus,
                "Only a phase transition can dismiss the rest layout")
            h.clock.uptime = started + 10
            h.session.tickNow()
            assertTrue(h.session.snapshot.phase is RunnerPhase.Working)
            assertFalse(h.session.snapshot.showsRestFocus)
            assertNull(h.session.snapshot.scheduledRestSeconds)
        }
    }

    @Test fun releaseKeepsTheCurrentHandAndPauseRetainsTheUpcomingRest() {
        Harness(rest = 10, mode = HandMode.alternateEachRep, timerOnly = false).use { h ->
            repeat(15) { h.sample(12.0) }
            assertEquals(RunnerPhase.Releasing(0), h.session.snapshot.phase)
            assertEquals(Side.left, h.session.snapshot.side)
            assertNull(h.session.snapshot.scheduledRestSeconds)
            assertFalse(h.session.snapshot.showsRestFocus)
            h.sample(0.0)
            assertEquals(RunnerPhase.Resting(0), h.session.snapshot.phase)
            assertEquals(Side.right, h.session.snapshot.side)
            assertTrue(h.session.snapshot.showsRestFocus)
            h.session.send(RunnerEvent.Pause)
            val frozen = h.session.snapshot
            h.clock.uptime += 30
            h.session.tickNow()
            assertEquals(frozen.secondsShown, h.session.snapshot.secondsShown)
            assertEquals(RunnerPhase.Paused(RunnerPhase.Resting(0)), h.session.snapshot.phase)
            assertTrue(h.session.snapshot.showsRestFocus)
            h.session.send(RunnerEvent.Resume)
            assertTrue(h.session.snapshot.showsRestFocus)
            h.session.send(RunnerEvent.SkipSet)
            assertFalse(h.session.snapshot.showsRestFocus)
            assertNull(h.session.snapshot.scheduledRestSeconds)
        }
    }

    @Test fun aLingeringDurationCannotShowRestFocusOutsideRest() {
        for (phase in listOf(RunnerPhase.Idle, RunnerPhase.LeadIn(0), RunnerPhase.Armed(0),
            RunnerPhase.Working(0), RunnerPhase.Releasing(0),
            RunnerPhase.Paused(RunnerPhase.Working(0)),
            RunnerPhase.Paused(RunnerPhase.Releasing(0)), RunnerPhase.Finished)) {
            assertFalse(RunnerSnapshot(phase = phase, scheduledRestSeconds = 30).showsRestFocus, "$phase")
        }
        assertFalse(RunnerSnapshot(phase = RunnerPhase.Resting(0)).showsRestFocus)
    }

    private class Harness(rest: Int = 10, setBreak: Int = 30, firstRest: Int? = null,
        nextRest: Int? = null, reps: Int = 2, mode: HandMode = HandMode.bothHands,
        timerOnly: Boolean = true) : AutoCloseable {
        val clock = FakeClock()
        private val scope = inertScope()
        private val client = RecordingProgressorClient()
        private val device = DeviceStore(client, scope = scope, clock = clock).also { client.connect() }
        val session = RunnerSession(
            plan = SessionPlan(name = "Rest presentation", sets = listOf(
                SetPlan(grip = GripSpec(edgeMM = 20), repsPerSide = reps, restSeconds = firstRest),
                SetPlan(grip = GripSpec(edgeMM = 15), repsPerSide = reps, restSeconds = nextRest),
            ), handMode = mode, holdSeconds = 1, restSeconds = rest, setBreakSeconds = setBreak,
                leadInSeconds = 0, waitForReleaseBeforeRest = true),
            routineName = "Rest presentation", device = device, scope = scope, clock = clock,
            timerOnly = timerOnly,
        ).also { it.begin() }
        private var micros = 0u
        fun sample(kg: Double) {
            clock.uptime += 0.1
            micros += 100_000u
            client.emit(ProgressorEvent.Sample(ForceSample(kg, micros)))
            session.tickNow()
        }
        override fun close() { session.end(); scope.cancel() }
    }
}
