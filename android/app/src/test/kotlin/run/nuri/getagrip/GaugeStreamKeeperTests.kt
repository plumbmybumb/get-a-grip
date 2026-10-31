// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip

import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.ProgressorCommand
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.DiagnosticBreadcrumb
import run.nuri.getagrip.store.GaugeTareMode
import run.nuri.getagrip.store.TarePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// The live gauge and the max test re-kick a silent stream themselves, so the Tare control
/// stays Tare and Wake is only the fallback (iOS `GaugeStreamKeeperTests`; a Progressor
/// user, 2026-10-31: "why do I have to press anything to start measuring?").
class GaugeStreamKeeperTests {
    private class World(kind: GaugeKind = GaugeKind.progressor, holding: Boolean = true) {
        val clock = FakeClock()
        val client = RecordingProgressorClient(kind)
        val device = DeviceStore(client = client, scope = inertScope(), clock = clock)
        val t0: Double
        init {
            client.connect()
            if (holding) device.keepStreamAlive("gauge", true)
            t0 = clock.wall
            device.startStreaming(StreamStartCause.manualMeasurement)
        }
        val starts: Int get() = client.commands.count { it == ProgressorCommand.startWeightMeasurement }
        fun check(at: Double) = device.checkStreamKeepAlive(t0 + at)
        val mode: GaugeTareMode get() = TarePolicy.gaugeMode(
            isStreaming = device.isStreaming,
            isReadingLive = device.isReadingLive,
            keepsStreamAlive = device.keepsStreamAlive,
            rekickExhausted = device.streamRekickExhausted,
        )
    }

    @Test fun aSilentStreamIsReKickedWithoutAnyTap() {
        val w = World()
        assertEquals(1, w.starts)
        w.check(1.0)
        assertEquals(1, w.starts, "inside the spacing after the screen's own start")
        w.check(2.1)
        assertEquals(2, w.starts)
        assertTrue(w.device.diagnosticEntries.any {
            it.event == DiagnosticBreadcrumb.StreamStartRequested(StreamStartCause.watchdog)
        })
        w.check(2.6)
        assertEquals(2, w.starts, "re-kicks are spaced, never one per tick")
        assertEquals(GaugeTareMode.waitingForReadings, w.mode)
    }

    @Test fun theKeeperOnlyEverSendsStart() {
        val w = World()
        var t = 0.5
        while (t <= 12.0) { w.check(t); t += 0.5 }
        assertTrue(w.client.commands.all { it == ProgressorCommand.startWeightMeasurement }, "${w.client.commands}")
    }

    @Test fun afterThreeUnansweredReKicksWakeIsOffered() {
        val w = World()
        listOf(2.1, 4.2, 6.3).forEach(w::check)
        assertEquals(1 + DeviceStore.maximumRekicksWithoutSample, w.starts)
        assertFalse(w.device.streamRekickExhausted)

        w.check(8.4)
        assertTrue(w.device.streamRekickExhausted)
        assertEquals(4, w.starts, "giving up sends nothing")
        assertTrue(w.device.diagnosticEntries.any { it.event == DiagnosticBreadcrumb.StreamRekickGaveUp })
        assertEquals(GaugeTareMode.wakeAfterRestarts, w.mode)

        w.check(20.0)
        assertEquals(4, w.starts, "exhausted means quiet")

        // Wake hands the stream back to the keeper.
        w.device.startStreaming(StreamStartCause.manualWake)
        assertFalse(w.device.streamRekickExhausted)
    }

    @Test fun aSampleClearsTheGiveUp() {
        val w = World()
        listOf(2.1, 4.2, 6.3, 8.4).forEach(w::check)
        assertTrue(w.device.streamRekickExhausted)
        w.client.emit(ProgressorEvent.Sample(ForceSample(0.2, 0u)))
        assertFalse(w.device.streamRekickExhausted)
        assertEquals(GaugeTareMode.tare, w.mode)
    }

    @Test fun aFlowingStreamIsNeverReKicked() {
        val w = World()
        w.clock.wall = w.t0 + 3.0
        w.client.emit(ProgressorEvent.Sample(ForceSample(1.0, 0u)))
        w.check(3.5)
        assertEquals(1, w.starts)
    }

    /// The runner has its own watchdog (which also breaks the engine's timeline first), and
    /// a stream stopped on purpose stays stopped.
    @Test fun noHolderOrAStoppedStreamMeansNoReKick() {
        val w = World(holding = false)
        w.check(10.0)
        assertEquals(1, w.starts)
        w.device.keepStreamAlive("gauge", true)
        w.device.stopStreaming(StreamStopCause.userStopped)
        w.check(20.0)
        assertEquals(1, w.starts)
        assertEquals(GaugeTareMode.notMeasuring, w.mode)
        w.device.keepStreamAlive("gauge", false)
        assertFalse(w.device.keepsStreamAlive)
    }

    @Test fun holdersAreNamed() {
        val w = World(holding = false)
        w.device.keepStreamAlive("gauge", true)
        w.device.keepStreamAlive("gauge", true)
        w.device.keepStreamAlive("gauge", false)
        assertFalse(w.device.keepsStreamAlive)
    }

    @Test fun aBroadcastScaleGetsTheThreeSecondFloorAndIsNeverBouncedWhileScanning() {
        val w = World(GaugeKind.whc06)
        assertEquals(3.0, w.device.streamSilenceRekickSeconds)
        w.check(2.9)
        assertEquals(1, w.starts)
        w.check(3.1)
        assertEquals(2, w.starts)
        w.client.setState(ProgressorConnectionState.Scanning)
        w.check(30.0)
        assertEquals(2, w.starts, "a scale still scanning is not connected")
    }

    @Test fun theControlStaysTareWhileTheKeeperWorks() {
        assertEquals(GaugeTareMode.tare, TarePolicy.gaugeMode(true, true, true, false))
        assertEquals(GaugeTareMode.waitingForReadings, TarePolicy.gaugeMode(true, false, true, false))
        assertEquals(GaugeTareMode.notMeasuring, TarePolicy.gaugeMode(false, false, true, false))
        assertEquals(GaugeTareMode.wakeAfterRestarts, TarePolicy.gaugeMode(true, false, true, true))
        assertEquals(GaugeTareMode.wake, TarePolicy.gaugeMode(true, false, false, false))
        assertTrue(GaugeTareMode.wakeAfterRestarts.isEnabled)
        assertFalse(GaugeTareMode.waitingForReadings.isEnabled)
        assertFalse(GaugeTareMode.notMeasuring.isEnabled)
        assertNotNull(GaugeTareMode.wakeAfterRestarts.reason)
    }

    @Test fun resetZeroesThePeakAndTouchesNothingElse() {
        val w = World()
        w.client.emit(ProgressorEvent.Sample(ForceSample(12.0, 0u)))
        w.client.emit(ProgressorEvent.Sample(ForceSample(4.0, 12_500u)))
        assertEquals(12.0, w.device.peakKg)
        assertTrue(w.device.hasPeak)
        val commands = w.client.commands.toList()
        val trace = w.device.trace.toList()

        w.device.resetDisplayStatistics()

        assertEquals(0.0, w.device.peakKg)
        assertFalse(w.device.hasPeak)
        assertEquals(4.0, w.device.currentKg, "the reading is untouched — no tare")
        assertEquals(trace, w.device.trace)
        assertEquals(commands, w.client.commands, "no tare, stop or start written")
        assertTrue(w.device.isStreaming)
    }
}
