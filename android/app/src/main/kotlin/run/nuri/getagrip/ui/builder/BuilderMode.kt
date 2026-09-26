// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import java.util.UUID

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

    /// Create modes. The draft-rescue stash is scoped to these two: restoring a stale draft
    /// into an EDIT could overwrite a merge the user never saw.
    val isCreating: Boolean get() = this !is Edit

    val editingID: UUID? get() = (this as? Edit)?.id
}
