// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import run.nuri.getagrip.ble.HostClock
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.RunnerEvent
import run.nuri.getagrip.engine.RunnerPhase
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.runner.RunnerSession
import run.nuri.getagrip.store.DeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/// What the RUNNER SCREEN actually shows, read through the same published snapshot the view
/// binds to.
///
/// This file exists because the engine-level paused test could not prove the numeral.
/// `SessionRunnerTests` has no access to `RunnerSession.secondsShown`, so it re-derived the
/// display arithmetic itself — and a test that recomputes what it is checking stays green
/// when the thing it is checking is deleted. The paused-display branch is exactly that kind
/// of bug: `secondsRemaining` rejects a wrapped `Paused` phase, and the fallback used to
/// reset the numeral to the rep's FULL hold length. Tolerable when the number was one figure
/// among many; not tolerable now it is the hero of the screen.
///
/// TRANSLATION NOTE (from Tests/RunnerSessionDisplayTests.swift): the Swift session reads
/// `ProcessInfo.systemUptime` directly; here the clock is injected, because
/// `SystemClock.elapsedRealtimeNanos()` returns a stubbed zero in a JVM unit test and the
/// countdown would never move. `System.nanoTime()` is the same monotonic guarantee, so these
/// two still run against REAL time and the session's own 100 ms ticker — which is the point:
/// they are about what the ticker publishes, not about the engine's arithmetic.
class RunnerSessionDisplayTests {

    private object NanoClock : HostClock {
        override fun wallSeconds(): Double = System.currentTimeMillis() / 1_000.0
        override fun uptimeSeconds(): Double = System.nanoTime() / 1_000_000_000.0
    }

    private class Harness(hold: Int, rest: Int, leadIn: Int) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val plan = SessionPlan(
            name = "Timer",
            sets = listOf(SetPlan(grip = GripSpec(), repsPerSide = 2)),
            holdSeconds = hold,
            restSeconds = rest,
            leadInSeconds = leadIn,
        )
        private val device = DeviceStore(RecordingProgressorClient(), scope = scope)
        val session = RunnerSession(
            plan = plan,
            routineName = "Timer",
            device = device,
            timerOnly = true,
            scope = scope,
            clock = NanoClock,
        )

        fun close() {
            session.end()
            scope.cancel()
        }
    }

    /// Bounded poll rather than a fixed sleep — the session is driven by its own 100 ms
    /// wall-clock ticker, so how long a phase takes to arrive is not something a test can
    /// assume.
    private suspend fun waitForResting(session: RunnerSession, timeoutMillis: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            if (session.snapshot.phase is RunnerPhase.Resting) return
            delay(50)
        }
        fail(
            "never reached the rest phase — the assertion below would have been checking a " +
                "state this test never got to",
        )
    }

    /// Pausing mid-hold must FREEZE the numeral, not reset it to the full hold length.
    @Test
    fun pausingAHoldFreezesTheDisplayedNumeral() = runBlocking {
        val harness = Harness(hold = 10, rest = 20, leadIn = 0)
        try {
            harness.session.begin()
            harness.session.startIfReady(run.nuri.getagrip.ble.StreamStartCause.initial)

            // The session's own 100 ms ticker drives `secondsShown`; let it bank real time.
            delay(1_200)
            val running = harness.session.snapshot.secondsShown
            assertTrue(running < 10, "the hold has started counting down (was $running)")

            harness.session.send(RunnerEvent.Pause)
            val atPause = harness.session.snapshot.secondsShown
            delay(900)

            assertEquals(
                atPause,
                harness.session.snapshot.secondsShown,
                "a paused hold neither counts down nor snaps back to the full hold",
            )
            assertTrue(
                harness.session.snapshot.secondsShown < 10,
                "and specifically it does not reset to the rep's full length — that is the " +
                    "bug this whole file exists for",
            )
        } finally {
            harness.close()
        }
    }

    /// The dial and the numeral are one value in two channels; a paused rest must not leave
    /// the ring draining behind the pause button.
    @Test
    fun pausingARestFreezesTheRingFraction() = runBlocking {
        val harness = Harness(hold = 1, rest = 20, leadIn = 0)
        try {
            harness.session.begin()
            harness.session.startIfReady(run.nuri.getagrip.ble.StreamStartCause.initial)

            // **Poll for the phase; do not assume a sleep is long enough.** Asserting nothing
            // here would let a slow ticker leave the session still WORKING, and the test would
            // then pass on the working fraction while paused-rest handling was broken — it
            // would be checking a state it never reached.
            waitForResting(harness.session)

            harness.session.send(RunnerEvent.Pause)
            val frozen = assertNotNull(harness.session.snapshot.phaseRemainingFraction)
            delay(900)

            assertEquals(
                frozen,
                assertNotNull(harness.session.snapshot.phaseRemainingFraction),
                0.001,
            )
        } finally {
            harness.close()
        }
    }

    @Test fun finishTimestampFreezesBeforeOutcomeIsRequested() = runBlocking {
        val harness = Harness(hold = 10, rest = 20, leadIn = 0)
        try {
            harness.session.begin()
            harness.session.send(RunnerEvent.Abort)
            val beforeWaiting = java.time.Instant.now()
            delay(100)
            val finished = harness.session.outcome().finishedAt
            assertTrue(!finished.isAfter(beforeWaiting))
            delay(100)
            assertEquals(finished, harness.session.outcome().finishedAt)
        } finally { harness.close() }
    }
}
