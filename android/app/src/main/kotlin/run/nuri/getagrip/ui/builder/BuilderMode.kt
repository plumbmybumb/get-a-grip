// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import java.util.UUID
import run.nuri.getagrip.engine.RoutineDraft

/// Which door the routine document was opened through.
///
/// ONE builder screen for creating and editing. The mode changes exactly two things: which
/// draft seeds the document, and whether the last block is the finish button or the delete
/// row. Everything in between — name, rhythm, sets, every day, fine tuning — is the same
/// screen.
sealed interface BuilderMode {
    /// No routine exists yet: blank document.
    data object FirstRun : BuilderMode

    /// A second (rest day, max day) routine — blank as well, and nothing is presumed from
    /// the first one.
    data object AddAnother : BuilderMode

    /// An existing routine, by id.
    data class Edit(val id: UUID) : BuilderMode

    /// A routine from a scanned code or an AI reply, opened from the import preview's "Edit
    /// before adding" (Nuri, 2026-10-02): a NEW routine, seeded with the import (already under
    /// the name it would land with — see `TodayScreen`).
    data class Importing(val draft: RoutineDraft) : BuilderMode

    /// Create modes. The draft-rescue stash is scoped to these two: restoring a stale draft
    /// into an EDIT could overwrite a merge the user never saw.
    val isCreating: Boolean get() = this !is Edit

    /// The step-by-step create flow (pages walked with Next, a fresh draft). An import is created
    /// too, but arrives whole, so it opens like an edit: every page one tap away.
    val walksPages: Boolean get() = this == FirstRun || this == AddAnother

    val editingID: UUID? get() = (this as? Edit)?.id
}
