// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import run.nuri.getagrip.ble.HostClock
import run.nuri.getagrip.engine.CriticalForceOutcome
import run.nuri.getagrip.engine.CriticalForceTest
import run.nuri.getagrip.engine.CriticalForceTrace
import run.nuri.getagrip.engine.RunnerCue
import run.nuri.getagrip.runner.CueSink
import run.nuri.getagrip.ui.criticalforce.CriticalForceSession
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// The screen's driver: the cue mapping onto the runner's vocabulary, and the rule that a
/// value is published only when it CHANGES — readings arrive 80 times a second, and a
/// mirror written on every one would recompose the test screen at the sample rate.
class CriticalForceSessionTests {

    private class Clock(var now: Double = 1_000.0) : HostClock {
        override fun wallSeconds(): Double = now
        override fun uptimeSeconds(): Double = now
    }

    private class Cues : CueSink {
        val played = mutableListOf<RunnerCue>()
        var began = 0
        var ended = 0
        override fun play(cue: RunnerCue) { played += cue }
        override fun begin() { began += 1 }
        override fun end() { ended += 1 }
    }

    /// A dispatcher nobody advances unless a test says so: the ticker never runs by itself,
    /// and the test ticks.
    private val scheduler = TestCoroutineScheduler()
    private fun session(clock: Clock, cues: Cues) =
        CriticalForceSession(CoroutineScope(StandardTestDispatcher(scheduler)), cues, clock)

    @Test
    fun theTestSpeaksTheRunnersCueVocabulary() {
        val clock = Clock()
        val cues = Cues()
        val s = session(clock, cues)
        s.arm()
        assertEquals(0, cues.began, "the cue player starts a frame after Start, not on the tap")
        scheduler.runCurrent()
        assertEquals(1, cues.began)

        s.receive(30.0, clock.now)                     // the first pull starts rep 1
        clock.now += 7.0; s.tick()                     // the bell
        clock.now += 1.0; s.tick()                     // 2 s to go
        clock.now += 1.0; s.tick()                     // 1 s to go
        clock.now += 1.0; s.tick()                     // pull 2

        assertEquals(listOf(
            RunnerCue.RepStarted,
            RunnerCue.RepEnded(completed = true),
            RunnerCue.RestTick(2),
            RunnerCue.RestTick(1),
            RunnerCue.RepStarted,
        ), cues.played)
        assertEquals(2, s.pullNumber)
        assertEquals(CriticalForceTest.Phase.Pulling(1), s.phase)
    }

    @Test
    fun stoppingBeforeSixteenVoidsWithTheConnectionLostCue() {
        val clock = Clock()
        val cues = Cues()
        val s = session(clock, cues)
        s.arm()
        s.receive(30.0, clock.now)
        clock.now += 3.0
        s.stop()
        assertEquals(RunnerCue.ConnectionLost, cues.played.last())
        assertEquals(CriticalForceTest.Phase.Voided(CriticalForceTest.VoidReason.tooFewReps), s.phase)
        s.end()
        s.end()
        assertEquals(1, cues.ended, "end is idempotent")
    }

    /// A reading republishes only the phase; the bars and the clock wait for the 20 Hz
    /// tick, so 80 readings a second cannot recompose the screen 80 times a second.
    @Test
    fun readingsMoveThePhaseAndTheTickMovesTheBars() {
        val clock = Clock()
        val s = session(clock, Cues())
        s.arm()
        s.receive(30.0, clock.now)
        assertEquals(CriticalForceTest.Phase.Pulling(0), s.phase, "the first pull arms at once")
        val before = s.publishes
        repeat(40) { s.receive(30.0, clock.now + (it + 1) / 80.0) }
        assertEquals(before, s.publishes, "readings alone publish nothing while the phase holds")
        assertTrue(s.repMeans.isEmpty())
        clock.now += 0.5
        s.tick()
        assertEquals(1, s.repMeans.size, "the tick publishes the live bar")
    }

    @Test
    fun identicalReadingsPublishOnce() {
        val clock = Clock()
        val s = session(clock, Cues())
        s.arm()
        val before = s.publishes
        // Armed, below the start threshold: nothing the screen draws moves.
        repeat(80) { s.receive(0.4, clock.now + it / 80.0) }
        assertEquals(before, s.publishes)
    }

    @Test
    fun aWholeTestFinishesWithAStorableResult() {
        val clock = Clock()
        val cues = Cues()
        val s = session(clock, cues)
        s.arm()
        val start = clock.now
        var rel = 0.0
        var nextTick = 0.0
        while (rel <= 238.5) {
            val rep = (rel / 10).toInt()
            val into = rel - rep * 10
            val kg = if (rep < 24 && into < 7) 20 + 20 * exp(-rep / 5.0) else 0.0
            s.receive(if (rel == 0.0) 30.0 else kg, start + rel)
            if (rel >= nextTick) {
                clock.now = start + rel
                s.tick()
                nextTick += 0.05
            }
            rel += 1.0 / 80
        }
        assertEquals(CriticalForceTest.Phase.Finished, s.phase)
        assertEquals(RunnerCue.SessionCompleted, cues.played.last { it !is RunnerCue.RestTick })
        assertEquals(24, s.repMeans.size)
        val (outcome, trace) = assertNotNull(s.outcome())
        val result = assertIs<CriticalForceOutcome.Success>(outcome).result
        assertTrue(result.criticalForceKg in 20.0..22.0)
        assertTrue(CriticalForceTrace.decode(trace).isNotEmpty())
    }
}
