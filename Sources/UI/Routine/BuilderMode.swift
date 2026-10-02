// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// Which door the routine document was opened through.
///
/// ONE builder view for creating and editing. The mode changes exactly two things:
/// which draft seeds the document, and whether the last block is the finish button or
/// the delete row. Everything between is the same screen.
enum BuilderMode: Identifiable, Hashable {
    /// No routine exists yet: blank document.
    case firstRun
    /// A second (rest day, max day) routine — blank too, presuming nothing from the first.
    case addAnother
    /// An existing routine, by id.
    case edit(UUID)
    /// A routine from a scanned code or an AI reply, opened from the import preview's
    /// "Edit before adding" (Nuri, 2026-10-02): a NEW routine, seeded with the import.
    case importing(RoutineDraft)

    /// `.sheet(item:)` identity, distinct per routine so a different one rebuilds the
    /// document rather than reusing state.
    var id: String {
        switch self {
        case .firstRun:       "builder.firstRun"
        case .addAnother:     "builder.addAnother"
        case .edit(let uuid): uuid.uuidString
        case .importing:      "builder.importing"
        }
    }

    /// The `matchedTransitionSource` id this sheet zooms out of on Today — the empty
    /// card, the "New routine…" menu item, or the routine card itself.
    var zoomID: String {
        switch self {
        case .firstRun:       "build-routine"
        case .addAnother:     "new-routine"
        case .edit(let uuid): uuid.uuidString
        // No card on Today is its source: the standard presentation.
        case .importing:      "builder.importing"
        }
    }

    /// Create modes. The draft-rescue stash is scoped to these: restoring a stale draft into
    /// an EDIT could overwrite a CloudKit merge the user never saw.
    var isCreating: Bool {
        if case .edit = self { return false }
        return true
    }

    /// The step-by-step create flow (pages walked with Next, a fresh draft). An import is
    /// created too, but arrives whole, so it opens like an edit: every page one tap away.
    var walksPages: Bool {
        switch self {
        case .firstRun, .addAnother: true
        case .edit, .importing: false
        }
    }

    var isImporting: Bool {
        if case .importing = self { return true }
        return false
    }

    var editingID: UUID? {
        if case .edit(let uuid) = self { return uuid }
        return nil
    }
}
