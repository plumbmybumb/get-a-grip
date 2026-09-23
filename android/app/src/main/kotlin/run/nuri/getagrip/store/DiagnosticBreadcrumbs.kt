// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import run.nuri.getagrip.ble.ProgressorConnectionState
import run.nuri.getagrip.ble.StreamStartCause
import run.nuri.getagrip.ble.StreamStopCause
import java.util.UUID
import java.util.Locale

/// The small, in-memory vocabulary used to explain a post-session force-line dropout.
/// It deliberately records no device identifier, user data or network payload.
sealed interface DiagnosticBreadcrumb {
    data class BroadcastScan(val event: String) : DiagnosticBreadcrumb
    data class Connection(val state: ProgressorConnectionState) : DiagnosticBreadcrumb
    data object RetiringPeripheral : DiagnosticBreadcrumb
    data object QuarantineReleased : DiagnosticBreadcrumb
    data class ScenePhase(val phase: String) : DiagnosticBreadcrumb
    data class TraceFlush(val count: Int) : DiagnosticBreadcrumb
    data class SignalFreshness(val fresh: Boolean) : DiagnosticBreadcrumb
    data class StreamStartRequested(val cause: StreamStartCause) : DiagnosticBreadcrumb
    data class StreamStartDeferred(val cause: StreamStartCause) : DiagnosticBreadcrumb
    data class StreamStartWritten(val cause: StreamStartCause) : DiagnosticBreadcrumb

    /// **Without this, "Signal became stale" is ambiguous** between the gauge going quiet
    /// and the app stopping it at session end — the one question the first hardware logs
    /// could not answer.
    data class StreamStopped(val cause: StreamStopCause) : DiagnosticBreadcrumb

    data object BackgroundDisconnectScheduled : DiagnosticBreadcrumb
    data object BackgroundDisconnectCancelled : DiagnosticBreadcrumb

    /// A remotely calibrated gauge's progress from "connected" to "produces force". The
    /// string is a fixed phase description — never the serial, per the rule above.
    data class Calibration(val phase: String) : DiagnosticBreadcrumb

    /// The cue player's output, and whether OTHER media was playing either side of it:
    /// evidence, read from the phone, that a session never stops the user's podcast or
    /// video.
    data class Audio(val event: String) : DiagnosticBreadcrumb

    val text: String
        get() = when (this) {
            is BroadcastScan -> "Bluetooth scan: $event"
            is Connection -> "Connection: " + state.label
            RetiringPeripheral -> "Peripheral retired and quarantined"
            QuarantineReleased -> "Peripheral quarantine released"
            is ScenePhase -> "Scene: $phase"
            is TraceFlush -> "Trace flushed ${count}x"
            is SignalFreshness -> if (fresh) "Signal became fresh" else "Signal became stale"
            is StreamStartRequested -> "Stream start requested (" + cause.label + ")"
            is StreamStartDeferred -> "Stream start deferred by tare (" + cause.label + ")"
            is StreamStartWritten -> "Stream start written (" + cause.label + ")"
            is StreamStopped -> "Stream stopped (" + cause.label + ")"
            BackgroundDisconnectScheduled ->
                "Backgrounded — holding the link, disconnect scheduled"
            BackgroundDisconnectCancelled ->
                "Back in time — link kept, disconnect cancelled"
            is Calibration -> "Calibration: " + phase
            is Audio -> "Audio: $event"
        }
}

data class DiagnosticBreadcrumbEntry(
    val id: UUID,
    /// Seconds on `HostClock.wallSeconds`, the trace's own timeline, since the ring is read
    /// beside it.
    val at: Double,
    val event: DiagnosticBreadcrumb,
) {
    val text: String get() = event.text
}

/// A bounded, newest-last ring of post-session evidence. Trace flushes coalesce only while
/// consecutive, so a long backlog cannot evict the connection transition that shows whether
/// the link changed.
///
/// TRANSLATION NOTE: Swift's `struct` becomes a class; one store owns one ring, so there
/// was never a copy to preserve.
class DiagnosticBreadcrumbRing {
    companion object {
        const val capacity = 64
    }

    private val storage = ArrayList<DiagnosticBreadcrumbEntry>()

    val entries: List<DiagnosticBreadcrumbEntry> get() = storage.toList()

    /// True when the ring gained an entry; false when dropped as a repeat or merged.
    /// `DeviceStore` republishes only on true.
    fun append(event: DiagnosticBreadcrumb, at: Double = 0.0): Boolean {
        // Repeated watchdog no-ops must not evict the transition that explains a stall.
        // Keep the first timestamp; a different event starts a new entry as usual.
        if (event is DiagnosticBreadcrumb.BroadcastScan && storage.lastOrNull()?.event == event) return false
        if (event is DiagnosticBreadcrumb.TraceFlush && storage.isNotEmpty()) {
            val lastIndex = storage.lastIndex
            val existing = storage[lastIndex].event
            if (existing is DiagnosticBreadcrumb.TraceFlush) {
                storage[lastIndex] = DiagnosticBreadcrumbEntry(
                    id = storage[lastIndex].id,
                    at = at,
                    event = DiagnosticBreadcrumb.TraceFlush(existing.count + event.count),
                )
                return false
            }
        }

        storage.add(DiagnosticBreadcrumbEntry(id = UUID.randomUUID(), at = at, event = event))
        while (storage.size > capacity) storage.removeAt(0)
        return true
    }
}

/** Relative times explain long-lived scans without including a person's wall-clock date. */
fun List<DiagnosticBreadcrumbEntry>.diagnosticTimeline(): String {
    val start = firstOrNull()?.at ?: return ""
    return "Elapsed from first retained event\n" + joinToString("\n") {
        val elapsed = (it.at - start).coerceAtLeast(0.0)
        "[+${String.format(Locale.ROOT, "%.1f", elapsed)}s] ${it.text}"
    }
}
