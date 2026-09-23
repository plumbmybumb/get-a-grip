// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import run.nuri.getagrip.engine.ForceSample
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
