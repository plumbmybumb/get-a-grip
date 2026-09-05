// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import SwiftData

/// A routine, as stored. One row per named routine ("Daily no-hangs", "Rest day").
///
/// CloudKit rules are load-bearing: every attribute has a default, there is NO
/// `#Unique` / `@Attribute(.unique)` (CloudKit cannot enforce uniqueness, and SwiftData
/// then refuses to build the container with a CloudKit configuration at all — which
/// fails SOFTLY, straight past the cloud rung into local-only, so the app looks fine and
/// simply never syncs), and future schema changes must be additive-only.
///
/// ZERO relationships, which also sidesteps CloudKit's delete-rule limitations. The set
/// list is a Codable blob instead: a CloudKit to-many relationship syncs as an UNORDERED
/// reference set, and set order IS the routine.
@Model
final class SessionTemplate {
    var id: UUID = UUID()               // identity for reminder ids and undo; not a DB constraint
    var name: String = "Daily no-hangs"
    /// RAW, like Schengen's `Trip.countryCode`: a hand mode written by a NEWER build
    /// survives a round-trip through this build untouched. `apply(_:)` is what keeps
    /// that promise — see the rule there.
    var handModeRaw: String = "alternateEachRep"
    var holdSeconds: Int = 10           // the RHYTHM block: routine-level defaults every
    var restSeconds: Int = 20           // set inherits unless it overrides
    var setBreakSeconds: Int = 60
    var leadInSeconds: Int = 5
    /// Engagement DETECTOR, not intensity. Intensity lives in the target band below.
    var thresholdKg: Double = 2.0
    /// See `SessionPlan.waitForReleaseBeforeRest`. Defaulted `true`, so a routine that
    /// predates the column gains the behaviour rather than silently keeping the old one.
    var waitForReleaseBeforeRest: Bool = true
    /// See `SessionPlan.pausesOutsideTargetBand`. The column arrived LATE (2026-08-19):
    /// the plan field, its Fine-tuning toggle and the runner's gate all shipped first,
    /// and every save silently dropped the switch back to true on the way to disk —
    /// found by the QR share review, because the payload carried a field the store then
    /// lost at both ends. Defaulted `true`, the plan's own default, so existing rows
    /// keep the behaviour they were authored under. Additive migration, no backfill.
    var pausesOutsideTargetBand: Bool = true
    /// TARGET LOAD as a fraction of each grip's own max — the routine-level default
    /// every set inherits. Optional because "no target" is a real, common answer, and a
    /// sentinel like 0 would be indistinguishable from a deliberate zero.
    var targetLoPercent: Double? = nil
    var targetHiPercent: Double? = nil
    /// `[SetPlan]` JSON.
    var setsData: Data = Data()
    var sessionsPerDay: Int = 2
    var remindersData: Data = Data()        // [ReminderTime]
    var parkedRemindersData: Data = Data()  // [ReminderTime], restored when sessionsPerDay goes back up
    /// Conservative default for a record that reaches this build without the field:
    /// scheduling notifications nobody asked for is the worse of the two failures.
    var remindersEnabled: Bool = false
    /// A WHENEVER routine: no daily target, no reminders, never owed. **Defaulted to
    /// false**, which is what every routine written before this column existed was — a
    /// ritual — so the additive migration needs no backfill.
    var isOnDemand: Bool = false
    /// 0 is the primary routine — the one Today opens on.
    var sortIndex: Int = 0
    var createdAt: Date = Date.now
    var updatedAt: Date = Date.now

    init(draft: RoutineDraft, sortIndex: Int) {
        // The draft's id is adopted when it has one, so a routine re-created from a
        // stashed or undone draft keeps the UUID its `doigt.routine.<uuid>.r0480`
        // reminder identifiers are built from.
        self.id = draft.templateID ?? UUID()
        self.sortIndex = sortIndex
        self.createdAt = .now
        apply(draft)      // which is also what stamps updatedAt
    }
}

extension SessionTemplate {
    /// The set list. Order IS the routine, which is why it is a blob and not a relationship.
    var sets: [SetPlan] {
        get { BlobCodec.decodeArray(SetPlan.self, from: setsData) }
        set {
            // A failed encode must leave the previous blob ALONE. Writing `Data()` here
            // would turn "one set could not be encoded" into "this routine has no sets",
            // and then sync that loss to every other device.
            guard let data = BlobCodec.encode(newValue) else { return }
            setsData = data
        }
    }

    /// Reminder slots. Sorted and deduped on the way IN: a `ReminderTime`'s identity IS
    /// its time, so two 08:00 slots are one slot, and the notification identifier is
    /// content-keyed — duplicates would collapse at schedule time anyway, silently
    /// disagreeing with what the editor shows.
    var reminders: [ReminderTime] {
        get { BlobCodec.decodeArray(ReminderTime.self, from: remindersData) }
        set {
            guard let data = BlobCodec.encode(Self.tidied(newValue)) else { return }
            remindersData = data
        }
    }

    var parkedReminders: [ReminderTime] {
        get { BlobCodec.decodeArray(ReminderTime.self, from: parkedRemindersData) }
        set {
            guard let data = BlobCodec.encode(Self.tidied(newValue)) else { return }
            parkedRemindersData = data
        }
    }

    private static func tidied(_ times: [ReminderTime]) -> [ReminderTime] {
        Set(times).sorted()
    }

    /// nil ONLY for a mode written by a future version (CloudKit sync skew). Everything
    /// that has to *run* the routine reads `plan.handMode`, which falls back; this is for
    /// the one caller that needs to know the fallback happened.
    var handMode: HandMode? { HandMode(rawValue: handModeRaw) }

    /// The engine-facing value type. Every number the app quotes about this routine is a
    /// fold over this plan, never over the columns directly.
    var plan: SessionPlan {
        SessionPlan(name: name,
                    sets: sets,
                    handMode: HandMode(fallback: handModeRaw),
                    holdSeconds: holdSeconds,
                    restSeconds: restSeconds,
                    setBreakSeconds: setBreakSeconds,
                    leadInSeconds: leadInSeconds,
                    thresholdKg: thresholdKg,
                    waitForReleaseBeforeRest: waitForReleaseBeforeRest,
                    pausesOutsideTargetBand: pausesOutsideTargetBand,
                    targetLoPercent: targetLoPercent,
                    targetHiPercent: targetHiPercent)
    }

    /// The editor's working copy. The wizard IS the editor, so this is the same type
    /// "new routine" and "prefill" produce.
    var draft: RoutineDraft {
        RoutineDraft(templateID: id,
                     plan: plan,
                     sessionsPerDay: sessionsPerDay,
                     reminders: reminders,
                     parkedReminders: parkedReminders,
                     remindersEnabled: remindersEnabled,
                     isOnDemand: isOnDemand)
    }

    /// The ONE write path from a draft — no caller can update six fields and forget
    /// `updatedAt`. Normalized here rather than trusting the caller: this is the last
    /// gate before disk and `normalized` is idempotent, so a second pass costs nothing.
    func apply(_ draft: RoutineDraft) {
        let clean = draft.normalized
        let source = clean.plan

        name = source.name
        applyHandMode(source.handMode)
        holdSeconds = source.holdSeconds
        restSeconds = source.restSeconds
        setBreakSeconds = source.setBreakSeconds
        leadInSeconds = source.leadInSeconds
        thresholdKg = source.thresholdKg
        waitForReleaseBeforeRest = source.waitForReleaseBeforeRest
        pausesOutsideTargetBand = source.pausesOutsideTargetBand
        targetLoPercent = source.targetLoPercent
        targetHiPercent = source.targetHiPercent
        sets = source.sets
        sessionsPerDay = clean.sessionsPerDay
        reminders = clean.reminders
        parkedReminders = clean.parkedReminders
        remindersEnabled = clean.remindersEnabled
        isOnDemand = clean.isOnDemand
        updatedAt = .now
    }

    /// The raw is written verbatim, with ONE exception: when the stored raw is a mode
    /// this build cannot read, the draft carries the FALLBACK rather than a choice the
    /// user made — so an unconditional write would quietly downgrade a routine a newer
    /// build wrote, on nothing more than an unrelated edit to the rep count. Any OTHER
    /// mode in the draft IS a deliberate pick and wins.
    private func applyHandMode(_ picked: HandMode) {
        let rawIsUnreadable = handMode == nil
        if rawIsUnreadable && picked == HandMode(fallback: handModeRaw) { return }
        handModeRaw = picked.rawValue
    }

    var estimatedSeconds: Int { PlanMath.totalSeconds(plan) }
    var summaryLine: String { PlanMath.summaryLine(plan) }
}
