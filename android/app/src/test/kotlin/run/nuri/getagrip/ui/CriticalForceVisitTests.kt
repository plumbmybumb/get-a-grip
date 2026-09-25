// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.engine.CriticalForceHands
import run.nuri.getagrip.engine.CriticalForceTest
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.RunnerCue
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.inertScope
import run.nuri.getagrip.runner.CueSink
import run.nuri.getagrip.runner.SessionServiceController
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.ui.criticalforce.CriticalForceSession
import run.nuri.getagrip.ui.criticalforce.CriticalForceStage
import run.nuri.getagrip.ui.criticalforce.CriticalForceTestRequest
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/// One visit, one hand after the other: the flow iOS keeps in `CriticalForceTestView`
/// (`start`, `arm`, `finishIfDone`, `nextHandOrFinish`), which on this side lives in
/// `CriticalForceTestRequest` so it can be driven with no screen.
class CriticalForceVisitTests {

    private class Service : SessionServiceController {
        var begun = 0
        var ended = 0
        override fun begin() { begun += 1 }
        override fun end() { ended += 1 }
    }

    private class World {
        val clock = FakeClock()
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = clock)
        val service = Service()
        val cues = CueSink { }

        init { client.setState(ProgressorConnectionState.Connected) }

        fun request(hands: CriticalForceHands) = CriticalForceTestRequest(
            grip = GripSpec(), hands = hands,
            newSession = { CriticalForceSession(CoroutineScope(StandardTestDispatcher()), cues, clock) },
            service = service,
        )
    }

    /// One hand's full test through the device's own callback, the screen's phase watch
    /// replayed after every step. `upTo` stops early (seconds from the first pull).
    private fun World.pull(request: CriticalForceTestRequest, floor: Double, upTo: Double = 238.0) {
        val start = clock.wall
        var rel = 0.0
        var nextTick = 0.0
        while (rel <= upTo) {
            val rep = (rel / 10).toInt()
            val into = rel - rep * 10
            val kg = if (rel == 0.0) 30.0 else if (rep < 24 && into < 7) floor + 20 * exp(-rep / 5.0) else 0.0
            clock.wall = start + rel
            device.onTracePoint?.invoke(DeviceStore.TracePoint(kg, clock.wall))
            if (rel >= nextTick) {
                request.session.tick()
                request.phaseChanged(request.session.phase)
                nextTick += 0.1
            }
            rel += 1.0 / 40
        }
        clock.wall += 1.0
    }

    @Test
    fun oneAtATimeRunsBothHandsAndRestartsTheStreamBetween() {
        val w = World()
        val request = w.request(CriticalForceHands.OneAtATime(Side.right))
        request.start(w.device)
        assertEquals(1, w.service.begun, "a connected gauge keeps the test alive")
        assertEquals(Side.right, request.side)

        w.pull(request, floor = 20.0)
        assertEquals(1, request.handIndex, "the second hand is armed")
        assertEquals(CriticalForceStage.Testing, request.stage)
        assertEquals(CriticalForceTest.Phase.Armed, request.session.phase)
        assertEquals(Side.left, request.side)
        val starts = w.client.commands.count { it == ProgressorCommand.startWeightMeasurement }
        assertEquals(2, starts, "a fresh stream for the fresh hand")

        w.pull(request, floor = 17.0)
        assertEquals(CriticalForceStage.Result, request.stage)
        assertEquals(listOf(Side.right, Side.left), request.results.map { it.side })
        assertTrue(request.results[0].result.criticalForceKg > request.results[1].result.criticalForceKg)
        assertEquals(Side.right, request.shownSide, "the result opens on the first hand")
        assertEquals(1, w.service.ended)
        assertEquals(null, w.device.onTracePoint)
    }

    @Test
    fun finishingWithTheFirstHandOnlyKeepsIt() {
        val w = World()
        val request = w.request(CriticalForceHands.OneAtATime(Side.left))
        request.start(w.device)
        w.pull(request, floor = 20.0)
        request.finishEarlyBetweenHands()
        assertEquals(CriticalForceStage.Result, request.stage)
        assertEquals(listOf(Side.left), request.results.map { it.side })
    }

    @Test
    fun aVoidedFirstHandEndsTheVisitWithNoResult() {
        val w = World()
        val request = w.request(CriticalForceHands.OneAtATime(Side.left))
        request.start(w.device)
        w.pull(request, floor = 20.0, upTo = 50.0)
        request.session.stop()
        request.phaseChanged(request.session.phase)
        val ended = assertIs<CriticalForceStage.Ended>(request.stage)
        assertTrue(ended.message.startsWith("Stopped before pull 16"))
        assertTrue(request.results.isEmpty())
    }

    /// A later hand voiding keeps the first hand's result and says why on the result screen.
    @Test
    fun aVoidedSecondHandIsANoteOnTheResult() {
        val w = World()
        val request = w.request(CriticalForceHands.OneAtATime(Side.left))
        request.start(w.device)
        w.pull(request, floor = 20.0)
        w.pull(request, floor = 17.0, upTo = 40.0)
        request.session.interrupt(CriticalForceTest.VoidReason.lostGauge)
        request.phaseChanged(request.session.phase)
        assertEquals(CriticalForceStage.Result, request.stage)
        assertEquals(listOf(Side.left), request.results.map { it.side })
        assertEquals(1, request.notes.size)
        assertTrue(request.notes[0].startsWith("Right: "), request.notes[0])
    }

    @Test
    fun bothHandsIsOneTestOnOneSide() {
        val w = World()
        val request = w.request(CriticalForceHands.BothHands)
        request.start(w.device)
        w.pull(request, floor = 30.0)
        assertEquals(CriticalForceStage.Result, request.stage)
        assertEquals(listOf(Side.both), request.results.map { it.side })
    }

    @Test
    fun theVisitSoundsLikeTheRunner() {
        val played = mutableListOf<RunnerCue>()
        val w = World()
        val request = CriticalForceTestRequest(
            grip = GripSpec(), hands = CriticalForceHands.Single(Side.right),
            newSession = { CriticalForceSession(CoroutineScope(StandardTestDispatcher()), CueSink { played += it }, w.clock) },
        )
        request.start(w.device)
        w.pull(request, floor = 20.0)
        assertEquals(24, played.count { it == RunnerCue.RepStarted })
        assertEquals(RunnerCue.SessionCompleted, played.last { it !is RunnerCue.RestTick })
    }
}
