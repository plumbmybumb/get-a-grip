// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.engine.ForceSample
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.engine.ProgressorEvent
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.DiagnosticBreadcrumb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceStreamBoundaryTests {
    @Test fun trailingNotificationsCannotReviveAStoppedMeasurement() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.connect()
        var delivered = 0
        device.onSample = { delivered += 1 }
        device.startStreaming(StreamStartCause.manualMeasurement)
        client.emit(ProgressorEvent.Sample(ForceSample(5.0, 0u)))
        val trace = device.trace.toList()
        device.stopStreaming(StreamStopCause.userStopped)
        client.emit(ProgressorEvent.Sample(ForceSample(50.0, 12_500u)))
        assertEquals(0.0, device.currentKg)
        assertEquals(5.0, device.peakKg)
        assertEquals(trace, device.trace)
        assertEquals(1, delivered)
        device.startStreaming(StreamStartCause.manualMeasurement)
        client.emit(ProgressorEvent.Sample(ForceSample(3.0, 0u)))
        assertEquals(2, delivered)
        assertEquals(3.0, device.currentKg)
        device.stopStreaming(StreamStopCause.userStopped)
    }
    /// **THE ONE DOOR.** Every codec has its own plausibility ceiling, and the one that did
    /// not let a 1e33 kg reading through to the peak, the runner and history. The store is
    /// where a reading becomes app state, so it holds the rule for every client there is or
    /// will be — asserted here for every kind, with a client that decodes nothing itself.
    @Test fun anImplausibleSampleFromAnyClientNeverBecomesAppState() {
        assertEquals(1500.0, DeviceStore.maxAcceptedKilograms)
        val nonsense = listOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            1e33, -1e33, 1500.0001, -1500.0001,
        )
        for (kind in GaugeKind.entries) {
            val client = RecordingProgressorClient(kind)
            val clock = FakeClock()
            val device = DeviceStore(client = client, scope = inertScope(), clock = clock)
            client.connect()
            val delivered = mutableListOf<Double>()
            val traced = mutableListOf<Double>()
            device.onSample = { delivered += it.kg }
            device.onTracePoint = { traced += it.kg }
            device.startStreaming(StreamStartCause.manualMeasurement)
            client.emit(ProgressorEvent.Sample(ForceSample(12.0, 0u)))

            // Five seconds on, the gauge sends only nonsense.
            clock.wall += 5.0
            var micros = 0u
            for (kg in nonsense) {
                micros += 12_500u
                client.emit(ProgressorEvent.Sample(ForceSample(kg, micros)))
                assertEquals(12.0, device.currentKg, "$kind: $kg must not be the reading")
                assertEquals(12.0, device.peakKg, "$kind: $kg must not be the peak")
            }
            assertEquals(listOf(12.0), delivered, "$kind: the runner never sees one")
            assertEquals(listOf(12.0), traced, "$kind: nor does anything timed off the trace")
            assertEquals(listOf(12.0), device.trace.map { it.kg }, "$kind")
            assertEquals(12.0, device.lastSample?.kg, "$kind")
            // Nonsense is not a signal either: the stream has been quiet for five seconds.
            assertEquals(5.0, device.secondsSinceLastSample(), "$kind")

            // The bound itself is a reading, in both directions.
            client.emit(ProgressorEvent.Sample(ForceSample(1500.0, micros + 12_500u)))
            assertEquals(1500.0, device.peakKg, "$kind")
            client.emit(ProgressorEvent.Sample(ForceSample(-1500.0, micros + 25_000u)))
            assertEquals(-1500.0, device.currentKg, "$kind")
            assertEquals(3, delivered.size, "$kind")
            device.stopStreaming(StreamStopCause.userStopped)
        }
        for (kg in nonsense) assertFalse(DeviceStore.isPlausible(kg), "$kg")
        for (kg in listOf(0.0, -0.0, 62.5, -3.0, 1500.0, -1500.0)) assertTrue(DeviceStore.isPlausible(kg), "$kg")
    }
    @Test fun disconnectedTareDoesNotWrite() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        device.tare()
        assertTrue(client.commands.isEmpty())
    }
    @Test fun finishingStreamWhileBackgroundedBeginsIdleGrace() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.connect()
        device.startStreaming(StreamStartCause.manualMeasurement)
        device.beginBackgroundGrace()
        assertFalse(device.diagnosticEntries.any { it.event == DiagnosticBreadcrumb.BackgroundDisconnectScheduled })
        device.stopStreaming(StreamStopCause.userStopped)
        assertEquals(1, device.diagnosticEntries.count { it.event == DiagnosticBreadcrumb.BackgroundDisconnectScheduled })
        assertTrue(device.state.isConnected)
        device.cancelBackgroundGrace()
    }
    /// **A backlog flush does not republish the breadcrumb ring per sample.** Every dropped
    /// sample records a `TraceFlush`, and the ring merges consecutive ones into a single
    /// entry — so the published revision must not move while the count climbs, or Settings
    /// is invalidated at sample rate for a number nobody is watching. A read still sees the
    /// merged count, so a report shared mid-flush is current.
    @Test fun mergedTraceFlushesDoNotRepublishTheRing() {
        val client = RecordingProgressorClient()
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.connect()
        device.startStreaming(StreamStartCause.manualMeasurement)
        // 0.9 s device deltas with the wall clock held still: every second sample runs the
        // playback clock more than half a second ahead of wall time and is flushed.
        var micros = 0u
        fun sample() { client.emit(ProgressorEvent.Sample(ForceSample(5.0, micros))); micros += 900_000u }
        repeat(3) { sample() }
        val flushes = { (device.diagnosticEntries.last().event as? DiagnosticBreadcrumb.TraceFlush)?.count ?: 0 }
        assertEquals(1, flushes())
        val revision = device.diagnosticRevision
        repeat(40) { sample() }
        assertEquals(revision, device.diagnosticRevision, "a merge is not a new entry")
        assertEquals(21, flushes(), "and the merged count is still what a read returns")
    }
}
