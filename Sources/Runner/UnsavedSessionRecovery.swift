// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import SwiftUI

extension View {
    /// Offers back, once per launch, every session that finished but was never saved or
    /// discarded — see `UnsavedSessionDraft`. Hung on the app's root, phone and watch.
    ///
    /// `record` is the platform's own Save path — `TemplateStore.recordSession` on the
    /// phone, `SessionLedger.recordSession` on the watch — and answers whether the write
    /// landed. Everything else (the queue, the copy, when to ask) is one implementation,
    /// so the two apps cannot drift apart in what they say or when they say it.
    func unsavedSessionRecovery(store: UnsavedSessionDraftStore = .standard,
                                record: @escaping @MainActor (UnsavedSessionDraft) -> Bool) -> some View {
        modifier(UnsavedSessionRecovery(store: store, record: record))
    }
}

/// "Unsaved session from 07:42" — Save or Discard, one draft at a time, oldest first.
///
/// An ALERT, not a sheet or a cover, on purpose: it asks one yes-or-no question, it
/// presents nothing that could fight the runner's own cover for the root's presenting
/// controller, and there is nothing to edit. Save goes through the summary's own path
/// with no grade — History offers the grade afterwards — and without the new-max review,
/// which asked a question the draft cannot answer.
private struct UnsavedSessionRecovery: ViewModifier {
    let store: UnsavedSessionDraftStore
    let record: @MainActor (UnsavedSessionDraft) -> Bool

    @State private var queue: [UnsavedSessionDraft] = []
    @State private var isPresented = false
    @State private var didLoad = false

    func body(content: Content) -> some View {
        content
            .task {
                // Once per view life: read the disk at LAUNCH, never again. A draft written
                // later by this process belongs to a summary that is still open.
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
                Text(Self.message(draft))
            }
    }

    private func save(_ draft: UnsavedSessionDraft) {
        // Re-read: another window may have answered this draft while ours was up, and a
        // second Save would log the session twice.
        if store.load(id: draft.id) != nil {
            // A failed save keeps the draft: the store's own error says why, and the
            // next launch asks again rather than the session silently going.
            if record(draft) { store.delete(id: draft.id) }
        }
        advance()
    }

    private func discard(_ draft: UnsavedSessionDraft) {
        store.delete(id: draft.id)
        advance()
    }

    private func advance() {
        guard !queue.isEmpty else { return }
        queue.removeFirst()
        guard !queue.isEmpty else { return }
        // The alert is still dismissing; a second one presented on the same beat is
        // dropped by SwiftUI, so the next draft waits for the first to leave.
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

    private static func message(_ draft: UnsavedSessionDraft) -> String {
        String(localized: "\(draft.templateName) · \(draft.completedCount) of \(draft.reps.count) pulls. The app closed before it was saved.")
    }

    /// Never over a seeded or previewed world: those are fixtures, and a draft left by an
    /// earlier run would put an alert in front of every screenshot and UI test after it.
    private static var recoveryAllowed: Bool {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        return !arguments.contains { $0.hasPrefix("-seed") || $0.hasPrefix("-preview") }
        #else
        return true
        #endif
    }
}
