// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// The small, in-memory vocabulary used to explain a post-session force-line dropout.
/// It deliberately records no device identifier, user data or network payload.
enum DiagnosticBreadcrumb: Equatable, Sendable {
    case broadcastScan(String)
    case connection(ProgressorConnectionState)
    case retiringPeripheral
    case quarantineReleased
    case scenePhase(String)
    case traceFlush(count: Int)
    case signalFreshness(Bool)
    case streamStartRequested(StreamStartCause)
    case streamStartDeferred(StreamStartCause)
    case streamStartWritten(StreamStartCause)
    /// **Without this, "Signal became stale" is ambiguous** — it reads identically
    /// whether the gauge went quiet on its own or the app deliberately stopped it at the
    /// end of a session. Reading the first hardware logs, that ambiguity was the one
    /// question the ring could not answer, and it is the difference between a real stall
    /// and normal behaviour.
    case streamStopped(StreamStopCause)
    case backgroundDisconnectScheduled
    case backgroundDisconnectCancelled

    var text: String {
        switch self {
        case .broadcastScan(let event):
            "Bluetooth scan: " + event
        case .connection(let state):
            "Connection: " + state.label
        case .retiringPeripheral:
            "Peripheral retired and quarantined"
        case .quarantineReleased:
            "Peripheral quarantine released"
        case .scenePhase(let phase):
            "Scene: " + phase
        case .traceFlush(let count):
            "Trace flushed " + String(count) + "x"
        case .signalFreshness(let fresh):
            fresh ? "Signal became fresh" : "Signal became stale"
        case .streamStartRequested(let cause):
            "Stream start requested (" + cause.label + ")"
        case .streamStartDeferred(let cause):
            "Stream start deferred by tare (" + cause.label + ")"
        case .streamStartWritten(let cause):
            "Stream start written (" + cause.label + ")"
        case .streamStopped(let cause):
            "Stream stopped (" + cause.label + ")"
        case .backgroundDisconnectScheduled:
            "Backgrounded — holding the link, disconnect scheduled"
        case .backgroundDisconnectCancelled:
            "Back in time — link kept, disconnect cancelled"
        }
    }
}

struct DiagnosticBreadcrumbEntry: Equatable, Identifiable, Sendable {
    let id: UUID
    let date: Date
    let event: DiagnosticBreadcrumb

    var text: String { event.text }
}

/// A bounded, newest-first-at-the-call-site ring for the evidence needed after a session.
/// Trace flushes are coalesced only while consecutive: a long backlog must not evict the
/// connection transition that tells us whether the link itself actually changed.
struct DiagnosticBreadcrumbRing: Sendable {
    static let capacity = 64

    private(set) var entries: [DiagnosticBreadcrumbEntry] = []

    mutating func append(_ event: DiagnosticBreadcrumb, at date: Date = .now) {
        // Keep the first time a repeated no-op was observed. A different event
        // still gets its own row, so loss and recovery boundaries remain visible.
        if case .broadcastScan = event, entries.last?.event == event { return }
        if case .traceFlush(let added) = event,
           let lastIndex = entries.indices.last,
           case .traceFlush(let existing) = entries[lastIndex].event {
            entries[lastIndex] = DiagnosticBreadcrumbEntry(
                id: entries[lastIndex].id,
                date: date,
                event: .traceFlush(count: existing + added)
            )
            return
        }

        entries.append(DiagnosticBreadcrumbEntry(id: UUID(), date: date, event: event))
        if entries.count > Self.capacity {
            entries.removeFirst(entries.count - Self.capacity)
        }
    }
}
