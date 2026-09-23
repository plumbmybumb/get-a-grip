// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import run.nuri.getagrip.store.TemplateStore

/// **The launch half of the finished-session draft** — what the next launch does with a
/// session that finished but was never saved. The prompt is a view (`UnsavedSessionPrompt`);
/// every decision is here, so it is testable against a real store.
class UnsavedSessionRecovery(
    private val drafts: FinishedSessionDraftStore,
    private val templates: TemplateStore,
) {
    /// The draft to offer, or null. **A draft whose row already exists is quietly finished,
    /// not offered**: a Save landed just before the process died, and saving again would
    /// overwrite the RPE given with none.
    suspend fun pending(): FinishedSessionDraft? {
        val draft = withContext(Dispatchers.IO) { drafts.load() } ?: return null
        if (templates.session(draft.id) != null) {
            drafts.clear()
            return null
        }
        return draft
    }

    /// Through the ordinary recording path, under the draft's id, with no effort reading (a
    /// guess is not an answer). Deleted only once the row lands, so a failed save can
    /// retry.
    suspend fun save(draft: FinishedSessionDraft): Boolean {
        val saved = templates.recordSession(
            plan = draft.plan,
            identity = draft.identity,
            reps = draft.reps,
            startedAt = draft.startedAt,
            finishedAt = draft.finishedAt,
            rpe = null,
        ) != null
        if (saved) drafts.clear()
        return saved
    }

    fun discard() {
        drafts.clear()
    }
}
