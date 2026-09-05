// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Which door the routine document was opened through.
///
/// There is only ONE builder view, because the wizard IS the editor. The mode changes
/// exactly three things and nothing else: which draft seeds the document, whether the
/// step-by-step guide starts at step 1 or retired, and whether the last block is the
/// finish button or the delete row. Everything in between — name, rhythm, sets, every
/// day, fine tuning — is byte-for-byte the same screen, which is what makes "you can
/// change any of it later, this same screen is the editor" a fact about the code rather
/// than a promise in a coach mark. It was four until the prefill chooser left the
/// creating document (2026-08-19): both doors now open on the same first screenful.
enum BuilderMode: Identifiable, Hashable {
    /// No routine exists yet: blank document, guide on.
    case firstRun
    /// A second (rest day, max day) routine — blank as well, and nothing is presumed
    /// from the first one.
    case addAnother
    /// An existing routine, by id.
    case edit(UUID)

    /// `.sheet(item:)` identity. Distinct per edited routine so opening a different
    /// routine rebuilds the document rather than reusing the previous one's state.
    var id: String {
        switch self {
        case .firstRun:       "builder.firstRun"
        case .addAnother:     "builder.addAnother"
        case .edit(let uuid): uuid.uuidString
        }
    }

    /// The `matchedTransitionSource` id this sheet zooms out of on Today — the empty
    /// card, the "New routine…" menu item, or the routine card itself.
    var zoomID: String {
        switch self {
        case .firstRun:       "build-routine"
        case .addAnother:     "new-routine"
        case .edit(let uuid): uuid.uuidString
        }
    }

    /// Create modes. The draft-rescue stash is scoped to these two: restoring a stale
    /// draft into an EDIT could overwrite a CloudKit merge the user never saw.
    var isCreating: Bool {
        if case .edit = self { return false }
        return true
    }

    var editingID: UUID? {
        if case .edit(let uuid) = self { return uuid }
        return nil
    }
}

/// Scroll targets for the guide's `Next`.
///
/// Each one is attached to an ALWAYS-BUILT block wrapper, never to a lazily-created set
/// row: a `scrollTo` that misses must degrade to "no auto-scroll", never to "broken".
enum BuilderAnchor: Hashable {
    case name, rhythm, sets, totals, everyDay, finish
}
