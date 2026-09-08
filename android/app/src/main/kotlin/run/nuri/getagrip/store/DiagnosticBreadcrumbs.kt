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

    /// **Without this, "Signal became stale" is ambiguous** — it reads identically whether
    /// the gauge went quiet on its own or the app deliberately stopped it at the end of a
    /// session. Reading the first hardware logs, that ambiguity was the one question the
    /// ring could not answer, and it is the difference between a real stall and normal
    /// behaviour.
    data class StreamStopped(val cause: StreamStopCause) : DiagnosticBreadcrumb

    data object BackgroundDisconnectScheduled : DiagnosticBreadcrumb
    data object BackgroundDisconnectCancelled : DiagnosticBreadcrumb

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
        }
}

data class DiagnosticBreadcrumbEntry(
    val id: UUID,
    /// Seconds on `HostClock.wallSeconds`, not a `java.time.Instant`: the ring is
    /// in-memory evidence read beside the trace, and the trace's own timeline is that
    /// same clock.
    val at: Double,
    val event: DiagnosticBreadcrumb,
) {
    val text: String get() = event.text
}

/// A bounded, newest-last ring for the evidence needed after a session. Trace flushes are
/// coalesced only while consecutive: a long backlog must not evict the connection
/// transition that tells us whether the link itself actually changed.
///
/// TRANSLATION NOTE: Swift's `struct` becomes a class, per the house type mapping — one
/// store owns exactly one ring and appends to it, so there was never a copy to preserve.
class DiagnosticBreadcrumbRing {
    companion object {
        const val capacity = 64
    }

    private val storage = ArrayList<DiagnosticBreadcrumbEntry>()

    val entries: List<DiagnosticBreadcrumbEntry> get() = storage.toList()

    fun append(event: DiagnosticBreadcrumb, at: Double = 0.0) {
        // Repeated watchdog no-ops must not evict the transition that explains a stall.
        // Keep the first timestamp; a different event starts a new entry as usual.
        if (event is DiagnosticBreadcrumb.BroadcastScan && storage.lastOrNull()?.event == event) return
        if (event is DiagnosticBreadcrumb.TraceFlush && storage.isNotEmpty()) {
            val lastIndex = storage.lastIndex
            val existing = storage[lastIndex].event
            if (existing is DiagnosticBreadcrumb.TraceFlush) {
                storage[lastIndex] = DiagnosticBreadcrumbEntry(
                    id = storage[lastIndex].id,
                    at = at,
                    event = DiagnosticBreadcrumb.TraceFlush(existing.count + event.count),
                )
                return
            }
        }

        storage.add(DiagnosticBreadcrumbEntry(id = UUID.randomUUID(), at = at, event = event))
        while (storage.size > capacity) storage.removeAt(0)
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
