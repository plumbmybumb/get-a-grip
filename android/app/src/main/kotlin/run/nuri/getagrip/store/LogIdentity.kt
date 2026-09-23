// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.SessionPlan
import java.util.UUID

/// **Who a finished session is filed under** — its row's id and the routine it ran as, as
/// they were WHEN it ran.
///
/// One value rather than a routine plus overrides, because the two doors that write a
/// session — the summary's Save and launch recovery (`FinishedSessionDraft`) — have to
/// agree on every field: the same `id` is what makes a Save and a recovery one row, and the
/// name and target are frozen so renaming or deleting the routine later cannot rewrite
/// history.
data class LogIdentity(
    val id: UUID,
    /// Best-effort grouping: the routine may be gone by the time the session is saved.
    val templateID: UUID?,
    val templateName: String,
    val sessionsPerDayTarget: Int,
) {
    companion object {
        /// The ONE place the fallbacks for a session with no routine live: the plan's own
        /// name, and a daily target of one.
        fun of(template: SessionTemplateEntity?, plan: SessionPlan, id: UUID): LogIdentity =
            LogIdentity(
                id = id,
                templateID = template?.id,
                templateName = template?.name ?: plan.name,
                sessionsPerDayTarget = template?.sessionsPerDay ?: 1,
            )
    }
}
