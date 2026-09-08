// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip

import kotlin.test.*
import run.nuri.getagrip.ble.ProgressorClientDiagnostic
import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.engine.GaugeKind
import run.nuri.getagrip.store.DeviceStore
import run.nuri.getagrip.store.DiagnosticBreadcrumb
import run.nuri.getagrip.store.DiagnosticBreadcrumbRing
import run.nuri.getagrip.store.diagnosticTimeline

class BroadcastDiagnosticsTests {
    @Test fun repeatedNoOpsRetainTheOriginalFailureAndElapsedTime() {
        val ring = DiagnosticBreadcrumbRing()
        ring.append(DiagnosticBreadcrumb.BroadcastScan("started (initial)"), at = 1_000.0)
        ring.append(DiagnosticBreadcrumb.BroadcastScan("failed (code 6)"), at = 1_600.0)
        repeat(100) {
            ring.append(DiagnosticBreadcrumb.BroadcastScan("retry pending"), at = 1_601.5 + it)
        }
        assertEquals(3, ring.entries.size)
        assertEquals(1_601.5, ring.entries.last().at)
        val report = ring.entries.diagnosticTimeline()
        assertContains(report, "[+600.0s] Bluetooth scan: failed (code 6)")
        assertContains(report, "[+601.5s] Bluetooth scan: retry pending")
        assertFalse(report.contains("1600"))
    }

    @Test fun broadcastLifecycleFactsReachTheOptionalReportWithoutClaimingAWireWrite() {
        val client = RecordingProgressorClient(GaugeKind.whc06)
        val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
        client.onDiagnostic?.invoke(ProgressorClientDiagnostic.BroadcastScan("failed (code 6)"))
        val text = device.diagnosticEntries.diagnosticTimeline()
        assertContains(text, "Bluetooth scan: failed (code 6)")
        assertFalse(text.contains("written"))
    }

    @Test fun onlyBroadcastWatchdogRequestNoiseIsSuppressed() {
        for (kind in listOf(GaugeKind.whc06, GaugeKind.progressor, GaugeKind.entralpi)) {
            val client = RecordingProgressorClient(kind)
            val device = DeviceStore(client = client, scope = inertScope(), clock = FakeClock())
            client.setState(ProgressorConnectionState.Connected)
            device.startStreaming(StreamStartCause.watchdog)
            device.startStreaming(StreamStartCause.manualWake)
            val requests = device.diagnosticEntries.map { it.event }
                .filterIsInstance<DiagnosticBreadcrumb.StreamStartRequested>()
            assertEquals(if (kind == GaugeKind.whc06) listOf(StreamStartCause.manualWake)
                else listOf(StreamStartCause.watchdog, StreamStartCause.manualWake),
                requests.map { it.cause })
            assertEquals(2, client.commands.size, "Reporting must not change stream requests")
        }
    }

    @Test fun emptyTimelineHasNoMisleadingElapsedTime() {
        assertEquals("", emptyList<run.nuri.getagrip.store.DiagnosticBreadcrumbEntry>().diagnosticTimeline())
    }
}
