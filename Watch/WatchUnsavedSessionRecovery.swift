// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftData
import SwiftUI

extension View {
    /// The phone's `unsavedSessionRecovery()`, on the wrist: every session that finished
    /// here but was never saved or discarded is offered back once, at launch.
    func watchUnsavedSessionRecovery(store: UnsavedSessionDraftStore = .standard) -> some View {
        modifier(WatchUnsavedSessionRecovery(store: store))
    }
}

/// Save writes through `SessionLedger`, the watch summary's own path, with no grade —
/// grading lives on the phone, where History offers it after the fact.
private struct WatchUnsavedSessionRecovery: ViewModifier {
    let store: UnsavedSessionDraftStore

    @Environment(SessionLedger.self) private var ledger
    @Environment(\.modelContext) private var context
    @State private var queue: [UnsavedSessionDraft] = []
    @State private var isPresented = false
    @State private var didLoad = false

    func body(content: Content) -> some View {
        content
            .task {
                // Once, at launch. A draft written later by this process belongs to a
                // summary that is still on screen.
                guard !didLoad else { return }
                didLoad = true
                guard Self.recoveryAllowed else { return }
                queue = store.all().filter { !LiveSessionDrafts.ids.contains($0.id) }
                isPresented = !queue.isEmpty
            }
            .alert(queue.first.map(Self.title) ?? "",
                   isPresented: $isPresented,
                   presenting: queue.first) { draft in
                Button("Save") { save(draft) }
                Button("Discard", role: .destructive) { discard(draft) }
            } message: { draft in
                Text("\(draft.templateName) · \(draft.completedCount) of \(draft.reps.count) pulls")
            }
    }

    private func save(_ draft: UnsavedSessionDraft) {
        if store.load(id: draft.id) != nil {
            let log = ledger.recordSession(plan: draft.plan, template: template(draft.templateID),
                                           reps: draft.reps,
                                           startedAt: draft.startedAt,
                                           finishedAt: draft.finishedAt,
                                           rpe: nil)
            // A failed save keeps the draft for the next launch rather than losing it.
            if log != nil { store.delete(id: draft.id) }
        }
        advance()
    }

    private func discard(_ draft: UnsavedSessionDraft) {
        store.delete(id: draft.id)
        advance()
    }

    private func template(_ id: UUID?) -> SessionTemplate? {
        guard let id else { return nil }
        let descriptor = FetchDescriptor<SessionTemplate>(predicate: #Predicate { $0.id == id })
        return (try? context.fetch(descriptor))?.first
    }

    private func advance() {
        guard !queue.isEmpty else { return }
        queue.removeFirst()
        guard !queue.isEmpty else { return }
        // Wait for the dismissing alert to leave; a second one on the same beat is dropped.
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(450))
            isPresented = true
        }
    }

    private static func title(_ draft: UnsavedSessionDraft) -> String {
        let when = Calendar.current.isDateInToday(draft.finishedAt)
            ? draft.finishedAt.formatted(date: .omitted, time: .shortened)
            : draft.finishedAt.formatted(date: .abbreviated, time: .shortened)
        return String(localized: "Unsaved session from \(when)")
    }

    /// Never over a seeded or previewed world — see the phone's twin.
    private static var recoveryAllowed: Bool {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        return !arguments.contains { $0.hasPrefix("-seed") || $0.hasPrefix("-preview") }
        #else
        return true
        #endif
    }
}
