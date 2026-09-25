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
import run.nuri.getagrip.ui.maxes.criticalForceHandsFor
import run.nuri.getagrip.ui.maxes.newCriticalForceTest
import run.nuri.getagrip.data.CriticalForceRecordEntity
import java.time.Instant
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.ProgressorEvent

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
        assertEquals(1, request.handIndex)
        assertEquals(CriticalForceStage.Testing, request.stage)
        assertTrue(request.awaitingNextHand, "the second hand waits for its own Start")
        assertEquals(null, w.device.onTracePoint, "nothing is measuring between hands")
        assertEquals(Side.left, request.side)
        val firstSession = request.session
        // Moving the gauge to the other hand loads it: that must not start the test.
        w.clock.wall += 5
        w.device.onTracePoint?.invoke(DeviceStore.TracePoint(30.0, w.clock.wall))
        assertEquals(firstSession, request.session)
        assertEquals(1, w.client.commands.count { it == ProgressorCommand.startWeightMeasurement })

        request.startNextHand(w.device)
        assertTrue(!request.awaitingNextHand)
        assertEquals(CriticalForceTest.Phase.Armed, request.session.phase, "Start arms a fresh test")
        assertTrue(firstSession !== request.session)
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
        assertTrue(request.awaitingNextHand)
        request.finishEarlyBetweenHands()
        assertTrue(!request.awaitingNextHand)
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
        request.startNextHand(w.device)
        w.pull(request, floor = 17.0, upTo = 40.0)
        request.session.interrupt(CriticalForceTest.VoidReason.lostGauge)
        request.phaseChanged(request.session.phase)
        assertEquals(CriticalForceStage.Result, request.stage)
        assertEquals(listOf(Side.left), request.results.map { it.side })
        assertEquals(1, request.notes.size)
        assertTrue(request.notes[0].startsWith("Right: "), request.notes[0])
    }

    /// Between hands nothing is measuring: leaving the screen voids nothing, and the first
    /// hand's result is not turned into a note.
    @Test
    fun leavingBetweenHandsVoidsNothing() {
        val w = World()
        val request = w.request(CriticalForceHands.OneAtATime(Side.left))
        request.start(w.device)
        w.pull(request, floor = 20.0)
        assertTrue(request.awaitingNextHand)
        request.teardown()
        assertEquals(CriticalForceTest.Phase.Finished, request.session.phase, "the finished hand stays finished")
        assertEquals(listOf(Side.left), request.results.map { it.side })
        assertTrue(request.notes.isEmpty())
        assertEquals(1, w.service.ended)
    }

    /// Start between hands needs a gauge; without one it does nothing.
    @Test
    fun theNextHandCannotStartWithoutTheGauge() {
        val w = World()
        val request = w.request(CriticalForceHands.OneAtATime(Side.left))
        request.start(w.device)
        w.pull(request, floor = 20.0)
        w.client.setState(ProgressorConnectionState.Disconnected(reason = null))
        request.startNextHand(w.device)
        assertTrue(request.awaitingNextHand)
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

    /// Where a new test opens: from "+", the last test's grip and hands, else the routines'
    /// first grip one at a time; from a grip's Measure, that grip's own last hands.
    @Test
    fun aNewTestOpensWhereTheLastOneWas() {
        val g20 = GripSpec()
        val g15 = GripSpec(edgeMM = 15)
        assertEquals(g15 to CriticalForceHands.OneAtATime(Side.left),
            newCriticalForceTest(emptyList(), listOf(g15, g20)))
        val both = CriticalForceRecordEntity(edgeMM = 20, sideRaw = "both", recordedAt = Instant.parse("2026-09-01T10:00:00Z"))
        val left = CriticalForceRecordEntity(edgeMM = 15, sideRaw = "left", recordedAt = Instant.parse("2026-09-20T10:00:00Z"))
        val right = left.copy(id = java.util.UUID.randomUUID(), sideRaw = "right")
        val tests = listOf(both, left, right)
        assertEquals(g15 to CriticalForceHands.OneAtATime(Side.left), newCriticalForceTest(tests, listOf(g20)))
        assertEquals(CriticalForceHands.BothHands, criticalForceHandsFor(g20, tests))
        assertEquals(CriticalForceHands.OneAtATime(Side.left), criticalForceHandsFor(GripSpec(edgeMM = 6), tests))
    }

    // MARK: - Zeroed before each hand (iOS f2378af)

    /// A live reading on the gauge, through the client, as the radio delivers it.
    private var micros = 0u
    private fun World.onGauge(kg: Double) {
        if (!device.isStreaming) device.startStreaming(StreamStartCause.manualWake)
        micros += 12_500u
        client.emit(ProgressorEvent.Sample(ForceSample(kg, micros)))
    }

    private fun World.indexOf(command: ProgressorCommand, after: Int = -1): Int =
        client.commands.withIndex().first { it.index > after && it.value == command }.index

    /// Every test starts on a zeroed gauge, as a routine does: tare FIRST, then the stream.
    @Test
    fun startZeroesTheGaugeBeforeTheStream() {
        val w = World()
        val request = w.request(CriticalForceHands.Single(Side.left))
        request.start(w.device)
        assertEquals(CriticalForceStage.Testing, request.stage)
        assertTrue(w.indexOf(ProgressorCommand.tare) < w.indexOf(ProgressorCommand.startWeightMeasurement),
            "tare must precede the stream: ${w.client.commands}")
        assertNull(request.loadOnGaugeKg)
    }

    /// A hand still on the gauge: Start zeroes nothing, arms nothing, and says why.
    @Test
    fun startIsRefusedUnderLoad() {
        val w = World()
        val request = w.request(CriticalForceHands.OneAtATime(Side.left))
        w.onGauge(5.0)
        assertTrue(w.device.isReadingLive)
        request.start(w.device)
        assertEquals(CriticalForceStage.Setup, request.stage)
        assertEquals(5.0, request.loadOnGaugeKg)
        assertTrue(ProgressorCommand.tare !in w.client.commands, "no tare under load")
        assertEquals(null, w.device.onTracePoint, "no test armed")
        assertEquals(0, w.service.begun)

        // Let go, and the next Start goes through and clears the warning.
        w.onGauge(0.3)
        request.start(w.device)
        assertEquals(CriticalForceStage.Testing, request.stage)
        assertNull(request.loadOnGaugeKg)
        assertTrue(ProgressorCommand.tare in w.client.commands)
    }

    /// Between hands the check runs on the live reading BEFORE the stream stops: afterwards
    /// the load is unknown, and a tare must not be guessed at.
    @Test
    fun theNextHandIsRefusedUnderLoadAndZeroedWhenLetGo() {
        val w = World()
        val request = w.request(CriticalForceHands.OneAtATime(Side.left))
        request.start(w.device)
        w.pull(request, floor = 20.0)
        assertTrue(request.awaitingNextHand)
        val before = w.client.commands.size
        w.onGauge(6.0)
        request.startNextHand(w.device)
        assertTrue(request.awaitingNextHand, "still waiting")
        assertEquals(6.0, request.loadOnGaugeKg)
        assertEquals(null, w.device.onTracePoint)
        assertTrue(w.client.commands.drop(before).none {
            it == ProgressorCommand.tare || it == ProgressorCommand.stopWeightMeasurement
        }, "nothing sent under load: ${w.client.commands.drop(before)}")

        w.onGauge(0.2)
        request.startNextHand(w.device)
        assertTrue(!request.awaitingNextHand)
        assertNull(request.loadOnGaugeKg)
        val stop = w.indexOf(ProgressorCommand.stopWeightMeasurement, before - 1)
        val tare = w.indexOf(ProgressorCommand.tare, stop)
        val start = w.indexOf(ProgressorCommand.startWeightMeasurement, stop)
        assertTrue(tare < start, "the next hand is zeroed before its stream: ${w.client.commands}")
        assertEquals(CriticalForceTest.Phase.Armed, request.session.phase)
    }
}
