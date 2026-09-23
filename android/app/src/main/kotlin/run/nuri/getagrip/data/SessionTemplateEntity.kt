// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import run.nuri.getagrip.engine.BlobCodec
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.L10n
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.ReminderTime
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.Side
import java.time.Instant
import java.util.UUID

/// A routine, as stored. One row per named routine ("Daily no-hangs", "Rest day").
///
/// TRANSLATION NOTE (Sources/Model/SessionTemplate.swift): the SwiftData `@Model` becomes a
/// Room `@Entity` data class — a VALUE. The Swift extension's computed members are
/// properties or a pure `applying(_:)` returning a new row, so `TemplateStore` addresses
/// routines by `id` and answers "deleted under me" with a re-fetch.
///
/// The CloudKit rules are KEPT without CloudKit, so a future sync stays possible: every
/// column defaulted, no uniqueness beyond the primary key, ZERO relationships, additive
/// changes only. The blob columns are why set ORDER survives: a CloudKit to-many syncs as
/// an unordered set, and set order IS the routine.
///
/// `id` is Room's primary key, a LOCAL index — not the sync-hostile `@Attribute(.unique)`.
/// Nothing relies on it to deduplicate.
@Entity(tableName = "SessionTemplate")
data class SessionTemplateEntity(
    /// Identity for reminder ids and undo; not a DB constraint.
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val name: String = L10n.tr("Daily no-hangs"),
    /// RAW, so a hand mode written by a NEWER build survives a round-trip untouched — see
    /// the rule in `applying(_:)`.
    val handModeRaw: String = "alternateEachRep",
    /// See `SessionPlan.startingHand`. Raw like `handModeRaw`, defaulted `left` (every
    /// routine before the column) and declared to Room, so the 1 → 2 auto-migration needs
    /// no backfill.
    @ColumnInfo(defaultValue = "left") val startingHandRaw: String = "left",
    val holdSeconds: Int = 10,          // the RHYTHM block: routine-level defaults every
    val restSeconds: Int = 20,          // set inherits unless it overrides
    val setBreakSeconds: Int = 60,
    val leadInSeconds: Int = 5,
    /// Engagement DETECTOR, not intensity. Intensity lives in the target band below.
    val thresholdKg: Double = 2.0,
    /// See `SessionPlan.waitForReleaseBeforeRest`. Defaulted `true`, so older routines gain
    /// the behaviour.
    val waitForReleaseBeforeRest: Boolean = true,
    /// See `SessionPlan.pausesOutsideTargetBand`. The column arrived LATE on iOS
    /// (2026-08-19): until then every save silently reset the switch to true (found by the
    /// QR share review). Defaulted `true`, the plan's own default.
    val pausesOutsideTargetBand: Boolean = true,
    /// TARGET LOAD as a fraction of each grip's own max, the routine-level default.
    /// Nullable: "no target" is a real answer, and 0 would be indistinguishable from a
    /// deliberate zero.
    val targetLoPercent: Double? = null,
    val targetHiPercent: Double? = null,
    /// `[SetPlan]` as canonical JSON TEXT — byte-identical to the iOS blob.
    val setsData: String = "",
    val sessionsPerDay: Int = 2,
    val remindersData: String = "",         // [ReminderTime]
    val parkedRemindersData: String = "",   // [ReminderTime], restored when sessionsPerDay goes back up
    /// Conservative default for a record missing the field: unasked-for notifications are
    /// the worse failure.
    val remindersEnabled: Boolean = false,
    /// A WHENEVER routine: no daily target, no reminders, never owed. **Defaulted to
    /// false** (every older routine was a ritual), so no backfill.
    val isOnDemand: Boolean = false,
    /// 0 is the primary routine — the one Today opens on.
    val sortIndex: Int = 0,
    @ColumnInfo(name = "createdAt") val createdAt: Instant = storedNow(),
    @ColumnInfo(name = "updatedAt") val updatedAt: Instant = storedNow(),
) {

    /// The set list. Order IS the routine, which is why it is a blob and not a relation.
    val sets: List<SetPlan> get() = BlobCodec.decodeArray(setsData) { SetPlan.fromJson(it) }

    /// Reminder slots. Sorted and deduped on the way IN — see `tidied`.
    val reminders: List<ReminderTime>
        get() = BlobCodec.decodeArray(remindersData) { ReminderTime.fromJson(it) }

    val parkedReminders: List<ReminderTime>
        get() = BlobCodec.decodeArray(parkedRemindersData) { ReminderTime.fromJson(it) }

    /// null ONLY for a mode written by a future version. Running the routine reads
    /// `plan.handMode`, which falls back; this is for the caller that must know it did.
    val handMode: HandMode? get() = HandMode.fromRaw(handModeRaw)

    /// null ONLY for a raw this build cannot read as a starting hand — see `handMode`.
    val startingHand: Side? get() = Side.startingHandOrNull(startingHandRaw)

    /// The engine-facing value type: every number quoted about this routine is a fold over
    /// this, never the columns.
    val plan: SessionPlan
        get() = SessionPlan(
            name = name,
            sets = sets,
            handMode = HandMode.fallback(handModeRaw),
            startingHand = Side.startingHandFallback(startingHandRaw),
            holdSeconds = holdSeconds,
            restSeconds = restSeconds,
            setBreakSeconds = setBreakSeconds,
            leadInSeconds = leadInSeconds,
            thresholdKg = thresholdKg,
            waitForReleaseBeforeRest = waitForReleaseBeforeRest,
            pausesOutsideTargetBand = pausesOutsideTargetBand,
            targetLoPercent = targetLoPercent,
            targetHiPercent = targetHiPercent,
        )

    /// The editor's working copy — the wizard IS the editor, so "new" and "prefill" produce
    /// this type too.
    val draft: RoutineDraft
        get() = RoutineDraft(
            templateID = id,
            plan = plan,
            sessionsPerDay = sessionsPerDay,
            reminders = reminders,
            parkedReminders = parkedReminders,
            remindersEnabled = remindersEnabled,
            isOnDemand = isOnDemand,
        )

    val estimatedSeconds: Int get() = PlanMath.totalSeconds(plan)
    val summaryLine: String get() = PlanMath.summaryLine(plan)

    /// The ONE write path from a draft, so no caller can forget `updatedAt`. Normalized
    /// here as the last gate before disk (`normalized` is idempotent).
    ///
    /// A failed encode leaves the previous blob ALONE: writing "" would turn "one set could
    /// not be encoded" into "no sets".
    fun applying(draft: RoutineDraft, now: Instant = storedNow()): SessionTemplateEntity {
        val clean = draft.normalized
        val source = clean.plan
        return copy(
            name = source.name,
            handModeRaw = appliedHandModeRaw(source.handMode),
            startingHandRaw = appliedStartingHandRaw(source.startingHand),
            holdSeconds = source.holdSeconds,
            restSeconds = source.restSeconds,
            setBreakSeconds = source.setBreakSeconds,
            leadInSeconds = source.leadInSeconds,
            thresholdKg = source.thresholdKg,
            waitForReleaseBeforeRest = source.waitForReleaseBeforeRest,
            pausesOutsideTargetBand = source.pausesOutsideTargetBand,
            targetLoPercent = source.targetLoPercent,
            targetHiPercent = source.targetHiPercent,
            setsData = BlobCodec.encodeAll(source.sets) ?: setsData,
            sessionsPerDay = clean.sessionsPerDay,
            remindersData = BlobCodec.encodeAll(tidied(clean.reminders)) ?: remindersData,
            parkedRemindersData =
                BlobCodec.encodeAll(tidied(clean.parkedReminders)) ?: parkedRemindersData,
            remindersEnabled = clean.remindersEnabled,
            isOnDemand = clean.isOnDemand,
            updatedAt = now,
        )
    }

    /// Written verbatim, with ONE exception: for a stored raw this build cannot read, the
    /// draft carries the FALLBACK, not a user choice, so an unrelated edit would quietly
    /// downgrade a newer build's routine. Any OTHER mode in the draft is a deliberate pick
    /// and wins.
    private fun appliedHandModeRaw(picked: HandMode): String {
        val rawIsUnreadable = handMode == null
        if (rawIsUnreadable && picked == HandMode.fallback(handModeRaw)) return handModeRaw
        return picked.rawValue
    }

    /// The same rule for the starting hand, whose raw a newer build may also widen.
    private fun appliedStartingHandRaw(picked: Side): String {
        val rawIsUnreadable = startingHand == null
        if (rawIsUnreadable && picked == Side.startingHandFallback(startingHandRaw)) return startingHandRaw
        return picked.rawValue
    }

    companion object {
        /// A `ReminderTime`'s identity IS its time, and notification identifiers are
        /// content-keyed, so duplicates would collapse at schedule time and disagree with
        /// the editor.
        private fun tidied(times: List<ReminderTime>): List<ReminderTime> = times.toSet().sorted()

        /// The twin of `SessionTemplate.init(draft:sortIndex:)`. Adopts the draft's id when
        /// it has one, so a routine re-created from a stashed or undone draft keeps the
        /// UUID its reminder identifiers are built from.
        fun from(
            draft: RoutineDraft,
            sortIndex: Int,
            now: Instant = storedNow(),
        ): SessionTemplateEntity = SessionTemplateEntity(
            id = draft.templateID ?: UUID.randomUUID(),
            sortIndex = sortIndex,
            createdAt = now,
        ).applying(draft, now)
    }
}
