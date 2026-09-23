// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import androidx.core.util.AtomicFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.engine.BlobCodec
import run.nuri.getagrip.engine.JsonRead
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.store.LogIdentity
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID

/// **A finished session, written down the moment it finishes — before anyone taps Save.**
///
/// With nothing persisted until Save, a finished workout lived only in memory while the
/// climber chalked up, and Android reclaiming the process took a twenty-minute session with
/// it. The draft is everything Save needs, frozen at the finish; Save and Discard delete
/// it, and a launch that finds one asks what to do (`UnsavedSessionRecovery`). iOS has the
/// same draft and prompt.
///
/// `id` is the session's `WorkoutLog` row id from EITHER door, so a Save just before death
/// plus the next launch's recovery are one row.
data class FinishedSessionDraft(
    val id: UUID,
    /// Best-effort grouping as on the log: the routine may be gone; name and target are as
    /// it ran.
    val templateID: UUID?,
    val templateName: String,
    val sessionsPerDayTarget: Int,
    val plan: SessionPlan,
    val reps: List<RepSummary>,
    val startedAt: Instant,
    val finishedAt: Instant,
) {
    /// Who the session is filed under when it is saved — see `LogIdentity`.
    val identity: LogIdentity get() = LogIdentity(id, templateID, templateName, sessionsPerDayTarget)

    fun toJson(): JsonElement = JsonObject(
        mapOf(
            "version" to JsonPrimitive(FORMAT_VERSION),
            "id" to JsonPrimitive(id.toString()),
            "templateID" to (templateID?.let { JsonPrimitive(it.toString()) } ?: JsonNull),
            "templateName" to JsonPrimitive(templateName),
            "sessionsPerDayTarget" to JsonPrimitive(sessionsPerDayTarget),
            "plan" to plan.toJson(),
            "reps" to JsonArray(reps.map { it.toJson() }),
            "startedAt" to JsonPrimitive(startedAt.toEpochMilli()),
            "finishedAt" to JsonPrimitive(finishedAt.toEpochMilli()),
        ),
    )

    companion object {
        /// Bumped only if an old draft could no longer be read: a one-file format that
        /// lives for one session, never migrated.
        const val FORMAT_VERSION = 1

        fun of(outcome: SessionOutcome, template: SessionTemplateEntity?): FinishedSessionDraft {
            // The same identity the summary's Save files the session under.
            val identity = LogIdentity.of(template, outcome.plan, outcome.id)
            return FinishedSessionDraft(
                id = identity.id,
                templateID = identity.templateID,
                templateName = identity.templateName,
                sessionsPerDayTarget = identity.sessionsPerDayTarget,
                plan = outcome.plan,
                reps = outcome.results,
                startedAt = outcome.startedAt,
                finishedAt = outcome.finishedAt,
            )
        }

        /// Null for anything but a whole draft of a readable version: a prompt promising a
        /// session it cannot save is worse than none.
        fun fromJson(element: JsonElement?): FinishedSessionDraft? {
            val o = JsonRead.obj(element) ?: return null
            if (JsonRead.int(o["version"]) != FORMAT_VERSION) return null
            val id = JsonRead.string(o["id"])?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            val plan = SessionPlan.fromJson(o["plan"]) ?: return null
            val reps = (o["reps"] as? JsonArray)?.map { RepSummary.fromJson(it) ?: return null } ?: return null
            return FinishedSessionDraft(
                id = id,
                templateID = JsonRead.string(o["templateID"])?.let { runCatching { UUID.fromString(it) }.getOrNull() },
                templateName = JsonRead.string(o["templateName"]) ?: return null,
                sessionsPerDayTarget = JsonRead.int(o["sessionsPerDayTarget"]) ?: 1,
                plan = plan,
                reps = reps,
                startedAt = JsonRead.long(o["startedAt"])?.let(Instant::ofEpochMilli) ?: return null,
                finishedAt = JsonRead.long(o["finishedAt"])?.let(Instant::ofEpochMilli) ?: return null,
            )
        }
    }
}

/// One draft in one file, written ATOMICALLY (`AtomicFile` renames a synced sibling), so a
/// kill mid-write leaves the previous state, not a truncated draft.
///
/// Synchronous on purpose: a few kilobytes once per session, and a background write could
/// land AFTER the Discard meant to delete it, resurrecting a thrown-away session.
class FinishedSessionDraftStore(file: File) {
    private val atomic = AtomicFile(file)

    fun save(draft: FinishedSessionDraft): Boolean {
        val text = runCatching { BlobCodec.write(draft.toJson()) }.getOrNull() ?: return false
        val stream = try {
            atomic.startWrite()
        } catch (_: IOException) {
            return false
        }
        return try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
            true
        } catch (_: IOException) {
            atomic.failWrite(stream)
            false
        }
    }

    /// Null when there is no draft, or one this build cannot read.
    fun load(): FinishedSessionDraft? {
        val bytes = try {
            atomic.readFully()
        } catch (_: IOException) {
            return null
        }
        return FinishedSessionDraft.fromJson(BlobCodec.parse(bytes.toString(Charsets.UTF_8)))
    }

    fun clear() {
        atomic.delete()
    }

    companion object {
        /// `filesDir`, not the cache, which the system may clear: this is a workout nobody
        /// has saved.
        const val FILE_NAME = "finished-session-draft.json"
    }
}
