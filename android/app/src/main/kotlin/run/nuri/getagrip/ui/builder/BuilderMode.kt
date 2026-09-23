// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import java.util.UUID

/// Which door the routine document was opened through.
///
/// There is only ONE builder screen, because the wizard IS the editor. The mode changes
/// exactly three things and nothing else: which draft seeds the document, whether the
/// step-by-step guide starts at step 1 or retired, and whether the last block is the finish
/// button or the delete row. Everything in between — name, rhythm, sets, every day, fine
/// tuning — is byte-for-byte the same screen, which is what makes "you can change any of it
/// later, this same screen is the editor" a fact about the code rather than a promise in a
/// coach mark.
sealed interface BuilderMode {
    /// No routine exists yet: blank document, guide on.
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

/// Scroll targets for the guide's Next.
///
/// Each one is attached to an ALWAYS-BUILT block wrapper, never to a lazily-created set
/// row: a scroll-to that misses must degrade to "no auto-scroll", never to "broken". That
/// is also why the document is an eager `Column` in a `verticalScroll` rather than a
/// `LazyColumn` — a lazy list has not built the anchor a screenful below the fold.
enum class BuilderAnchor { Name, Rhythm, Sets, Totals, EveryDay, Finish }
