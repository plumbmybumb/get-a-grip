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
import java.time.Instant
import java.util.UUID

/// A routine, as stored. One row per named routine ("Daily no-hangs", "Rest day").
///
/// TRANSLATION NOTE (Sources/Model/SessionTemplate.swift): a SwiftData `@Model` class
/// becomes a Room `@Entity` data class — a VALUE, not a live object. Everything the Swift
/// extension computed (`sets`, `reminders`, `plan`, `draft`, `apply`) is here as a
/// property or a pure `applying(_:)` that returns a new row, because there is no managed
/// object to mutate in place. Two consequences worth stating: `TemplateStore` addresses a
/// routine by `id` rather than by object identity, and "this object was deleted under me"
/// is answered by a re-fetch instead of by `modelContext != nil`.
///
/// The CloudKit rules the iOS schema was shaped by are KEPT even though Android has no
/// CloudKit: every column has a default, there is no uniqueness constraint beyond the
/// primary key, there are ZERO relationships, and every future change must be additive.
/// The Android schema is the same shape so a future sync could ever be possible — and the
/// blob columns are the reason set ORDER survives at all: a CloudKit to-many relationship
/// syncs as an unordered reference set, and set order IS the routine.
///
/// `id` is Room's primary key, which is a LOCAL index rather than the sync constraint
/// `@Attribute(.unique)` would have been — the thing SwiftData refuses to build a
/// CloudKit container around. Room needs a key; nothing here relies on it to deduplicate.
@Entity(tableName = "SessionTemplate")
data class SessionTemplateEntity(
    /// Identity for reminder ids and undo; not a DB constraint.
    @PrimaryKey val id: UUID = UUID.randomUUID(),
    val name: String = L10n.tr("Daily no-hangs"),
    /// RAW, like Schengen's `Trip.countryCode`: a hand mode written by a NEWER build
    /// survives a round-trip through this build untouched. `applying(_:)` is what keeps
    /// that promise — see the rule there.
    val handModeRaw: String = "alternateEachRep",
    val holdSeconds: Int = 10,          // the RHYTHM block: routine-level defaults every
    val restSeconds: Int = 20,          // set inherits unless it overrides
    val setBreakSeconds: Int = 60,
    val leadInSeconds: Int = 5,
    /// Engagement DETECTOR, not intensity. Intensity lives in the target band below.
    val thresholdKg: Double = 2.0,
    /// See `SessionPlan.waitForReleaseBeforeRest`. Defaulted `true`, so a routine that
    /// predates the column gains the behaviour rather than silently keeping the old one.
    val waitForReleaseBeforeRest: Boolean = true,
    /// See `SessionPlan.pausesOutsideTargetBand`. The column arrived LATE on iOS
    /// (2026-08-19): the plan field, its Fine-tuning toggle and the runner's gate all
    /// shipped first, and every save silently dropped the switch back to true on the way
    /// to disk — found by the QR share review, because the payload carried a field the
    /// store then lost at both ends. Defaulted `true`, the plan's own default.
    val pausesOutsideTargetBand: Boolean = true,
    /// TARGET LOAD as a fraction of each grip's own max — the routine-level default
    /// every set inherits. Nullable because "no target" is a real, common answer, and a
    /// sentinel like 0 would be indistinguishable from a deliberate zero.
    val targetLoPercent: Double? = null,
    val targetHiPercent: Double? = null,
    /// `[SetPlan]` as canonical JSON TEXT — byte-identical to the iOS blob.
    val setsData: String = "",
    val sessionsPerDay: Int = 2,
    val remindersData: String = "",         // [ReminderTime]
    val parkedRemindersData: String = "",   // [ReminderTime], restored when sessionsPerDay goes back up
    /// Conservative default for a record that reaches this build without the field:
    /// scheduling notifications nobody asked for is the worse of the two failures.
    val remindersEnabled: Boolean = false,
    /// A WHENEVER routine: no daily target, no reminders, never owed. **Defaulted to
    /// false**, which is what every routine written before this column existed was — a
    /// ritual — so the additive migration needs no backfill.
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

    /// null ONLY for a mode written by a future version (sync skew). Everything that has
    /// to *run* the routine reads `plan.handMode`, which falls back; this is for the one
    /// caller that needs to know the fallback happened.
    val handMode: HandMode? get() = HandMode.fromRaw(handModeRaw)

    /// The engine-facing value type. Every number the app quotes about this routine is a
    /// fold over this plan, never over the columns directly.
    val plan: SessionPlan
        get() = SessionPlan(
            name = name,
            sets = sets,
            handMode = HandMode.fallback(handModeRaw),
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

    /// The editor's working copy. The wizard IS the editor, so this is the same type
    /// "new routine" and "prefill" produce.
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

    /// The ONE write path from a draft — no caller can update six fields and forget
    /// `updatedAt`. Normalized here rather than trusting the caller: this is the last
    /// gate before disk and `normalized` is idempotent, so a second pass costs nothing.
    ///
    /// A failed encode must leave the previous blob ALONE. Writing "" would turn "one set
    /// could not be encoded" into "this routine has no sets".
    fun applying(draft: RoutineDraft, now: Instant = storedNow()): SessionTemplateEntity {
        val clean = draft.normalized
        val source = clean.plan
        return copy(
            name = source.name,
            handModeRaw = appliedHandModeRaw(source.handMode),
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

    /// The raw is written verbatim, with ONE exception: when the stored raw is a mode
    /// this build cannot read, the draft carries the FALLBACK rather than a choice the
    /// user made — so an unconditional write would quietly downgrade a routine a newer
    /// build wrote, on nothing more than an unrelated edit to the rep count. Any OTHER
    /// mode in the draft IS a deliberate pick and wins.
    private fun appliedHandModeRaw(picked: HandMode): String {
        val rawIsUnreadable = handMode == null
        if (rawIsUnreadable && picked == HandMode.fallback(handModeRaw)) return handModeRaw
        return picked.rawValue
    }

    companion object {
        /// A `ReminderTime`'s identity IS its time, so two 08:00 slots are one slot, and
        /// the notification identifier is content-keyed — duplicates would collapse at
        /// schedule time anyway, silently disagreeing with what the editor shows.
        private fun tidied(times: List<ReminderTime>): List<ReminderTime> = times.toSet().sorted()

        /// The twin of `SessionTemplate.init(draft:sortIndex:)`.
        ///
        /// The draft's id is adopted when it has one, so a routine re-created from a
        /// stashed or undone draft keeps the UUID its `doigt.routine.<uuid>.r0480`
        /// reminder identifiers are built from.
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
