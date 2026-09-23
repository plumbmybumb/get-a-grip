// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Which door the routine document was opened through.
///
/// ONE builder view, because the wizard IS the editor. The mode changes exactly three
/// things: which draft seeds the document, whether the guide starts at step 1 or
/// retired, and whether the last block is the finish button or the delete row.
/// Everything between is the same screen, so "this same screen is the editor" is a fact
/// about the code, not a promise in a coach mark.
enum BuilderMode: Identifiable, Hashable {
    /// No routine exists yet: blank document, guide on.
    case firstRun
    /// A second (rest day, max day) routine — blank too, presuming nothing from the first.
    case addAnother
    /// An existing routine, by id.
    case edit(UUID)

    /// `.sheet(item:)` identity, distinct per routine so a different one rebuilds the
    /// document rather than reusing state.
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

    /// Create modes. The draft-rescue stash is scoped to these: restoring a stale draft into
    /// an EDIT could overwrite a CloudKit merge the user never saw.
    var isCreating: Bool {
        if case .edit = self { return false }
        return true
    }

    var editingID: UUID? {
        if case .edit(let uuid) = self { return uuid }
        return nil
    }
}

/// Scroll targets for the guide's `Next`, each on an ALWAYS-BUILT block wrapper, never a
/// lazily-created row: a missed `scrollTo` must degrade to "no auto-scroll".
enum BuilderAnchor: Hashable {
    case name, rhythm, sets, totals, everyDay, finish
}
