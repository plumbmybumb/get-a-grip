// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// **A finished session nobody has saved yet**, on disk.
///
/// The `WorkoutLog` is written only when Save is tapped, and the summary it waits behind
/// can sit open for minutes — long enough for iOS to reclaim a backgrounded app, for a
/// watch to come off the wrist, for a battery to die. Every one of those used to throw a
/// twenty-minute session away without a word. Now the moment a session FINISHES its
/// result is written here, Save and Discard both delete it, and anything still here at
/// the next launch is a session that ended with neither — which is offered back.
///
/// Everything the Save path needs and nothing it does not: the plan, the reps, the two
/// times and the routine they belong to. The engine value types ARE the format, with
/// their own lenient decoders, so a field added to `RepSummary` later decodes here exactly
/// as it decodes in history. New fields on the draft itself must be optional.
struct UnsavedSessionDraft: Codable, Equatable, Identifiable, Sendable {
    /// One file per session, so a second session finishing can never overwrite a first
    /// one that is still waiting to be offered back.
    var id: UUID
    var plan: SessionPlan
    var reps: [RepSummary]
    var startedAt: Date
    var finishedAt: Date
    /// The routine it ran, looked up again at save time. A routine deleted since is not
    /// a reason to lose the session: the ledger freezes the name from the plan instead.
    var templateID: UUID?
    var templateName: String

    var completedCount: Int { reps.filter { $0.outcome == .completed }.count }
}

/// The directory of drafts. A value with a URL, so tests point it at a temporary
/// directory and the apps at their own Application Support — a draft is only ever
/// offered back on the device that ran the session.
struct UnsavedSessionDraftStore: Sendable {
    let directory: URL

    init(directory: URL) {
        self.directory = directory
    }

    /// Plain Application Support, beside the legal-acceptance record — NOT the App Group:
    /// nothing else reads these, and the widget has no business seeing a workout.
    static var standard: UnsavedSessionDraftStore {
        UnsavedSessionDraftStore(directory: URL.applicationSupportDirectory
            .appending(path: "unsaved-sessions", directoryHint: .isDirectory))
    }

    private func url(for id: UUID) -> URL {
        directory.appending(path: "\(id.uuidString).json")
    }

    /// NOT `BlobCodec`'s: its ISO 8601 dates drop the fraction of a second, and these two
    /// dates are the ones the summary's own Save would have written exactly. The default
    /// strategy is a `Double` and loses nothing.
    static let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.outputFormatting = [.sortedKeys]
        return e
    }()
    static let decoder = JSONDecoder()

    /// ATOMIC and SYNCHRONOUS. Synchronous because Save deletes this file, and a write
    /// still in flight when that delete lands would resurrect a session already logged —
    /// offered back at the next launch and saved twice. The payload is a few kilobytes;
    /// atomic is what keeps a half-written file from ever being read as a draft.
    func write(_ draft: UnsavedSessionDraft) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try Self.encoder.encode(draft)
        try data.write(to: url(for: draft.id), options: .atomic)
    }

    func load(id: UUID) -> UnsavedSessionDraft? {
        guard let data = try? Data(contentsOf: url(for: id)) else { return nil }
        return try? Self.decoder.decode(UnsavedSessionDraft.self, from: data)
    }

    /// Every readable draft, oldest first. A file this build cannot decode is skipped
    /// and LEFT where it is: a newer build may have written it, and an update may read it.
    func all() -> [UnsavedSessionDraft] {
        let files = (try? FileManager.default.contentsOfDirectory(
            at: directory, includingPropertiesForKeys: nil)) ?? []
        return files
            .filter { $0.pathExtension == "json" }
            .compactMap { file in
                (try? Data(contentsOf: file)).flatMap {
                    try? Self.decoder.decode(UnsavedSessionDraft.self, from: $0)
                }
            }
            .sorted { $0.finishedAt < $1.finishedAt }
    }

    func delete(id: UUID) {
        try? FileManager.default.removeItem(at: url(for: id))
    }
}

/// Drafts written by THIS process — sessions whose summaries may still be on screen.
///
/// Recovery skips them. A draft is only "unsaved" once the process that owned its
/// summary is gone; offering one back while its summary is still open (an iPad's second
/// window appearing, say) would put two Save buttons on one session, and both would work.
@MainActor
enum LiveSessionDrafts {
    private(set) static var ids: Set<UUID> = []
    static func insert(_ id: UUID) { ids.insert(id) }
    static func remove(_ id: UUID) { ids.remove(id) }
}
