// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation
import SwiftData

/// The ONE way a finished session becomes a `WorkoutLog` — shared by the phone's
/// `TemplateStore` and by the watch, which has no store of its own.
///
/// Extracted from `TemplateStore.recordSession` (2026-09-19) so a session the watch ran
/// is written by exactly the code the phone uses: the same frozen routine name, the same
/// training day derived from the session's own start, the same refusal of a max that is
/// not a number. Two hand-written copies of "how a session is saved" would eventually save two
/// different sessions.
///
/// Foundation, Observation and SwiftData only — no UIKit, no reminders, no derived state.
/// The store composes it and recomputes its own world afterwards; the watch has no
/// derived world to recompute, so the ledger is all it needs.
@Observable @MainActor
final class SessionLedger {
    private let context: ModelContext

    /// The last write that failed, in words; nil after a successful one. The store
    /// mirrors it into its own `saveError`, which is what every screen already reads.
    private(set) var saveError: String?

    /// No `DayClock`: the day a session is filed under is a fact about when it STARTED,
    /// not about what the clock reads when the summary is dismissed.
    init(context: ModelContext) {
        self.context = context
    }

    /// Write a finished session. Rolls the context back and returns nil when the save
    /// fails or a max in `newMaxes` is not a positive, finite number — a zero or NaN max
    /// would make every percentage-of-max caption in the app lie.
    ///
    /// The day is the TRAINING day the session STARTED in — `DayStamp(trainingDayOf:)`,
    /// never the clock's day at the moment of saving. The two differ for a session that
    /// runs across 04:00 (or sits on its summary past it), and the stamp has to be the
    /// same answer `repairTrainingDays` would reach from the same frozen column, or a
    /// relaunch would move a session the writer had just filed.
    @discardableResult
    func recordSession(plan: SessionPlan,
                       template: SessionTemplate?,
                       reps: [RepSummary],
                       startedAt: Date,
                       finishedAt: Date,
                       rpe: RPE?,
                       newMaxes: [MaxRecord] = []) -> WorkoutLog? {
        guard newMaxes.allSatisfy({ $0.kg.isFinite && $0.kg > 0 }) else {
            saveError = String(localized: "Couldn't save this workout. Please try again.")
            return nil
        }
        let log = WorkoutLog(
            plan: plan,
            templateID: template?.id,
            // FROZEN at save: renaming a routine later must not retro-rename history.
            templateName: template?.name ?? plan.name,
            sessionsPerDayTarget: template?.sessionsPerDay ?? 1,
            reps: reps,
            startedAt: startedAt,
            finishedAt: finishedAt,
            day: DayStamp(trainingDayOf: startedAt)
        )
        log.rpe = rpe?.rawValue
        context.insert(log)
        for max in newMaxes { context.insert(max) }
        saveError = nil
        do {
            try context.save()
        } catch {
            // Roll back so memory matches disk: a phantom session that dies with the
            // process — while the day's count and the strip both confirm it — is far
            // worse than a visible error.
            context.rollback()
            saveError = String(localized: "That change couldn't be saved — \(error.localizedDescription)")
            return nil
        }
        return log
    }

    /// Bumped only if the repair's RULE changes, which re-runs it once more everywhere.
    static let trainingDayRepairVersion = 1
    /// Device-local on purpose: the flag describes what THIS install has already done to
    /// the store it reads, and a synced flag would let a device that never ran the repair
    /// believe it had.
    static let trainingDayRepairKey = "sessionLedger.trainingDayRepair"

    /// **The repair runs ONCE per install**, not on every launch. It used to walk every
    /// `WorkoutLog` ever written — blobs and all — before the first frame, every launch,
    /// and re-derive each day in the device's CURRENT time zone: travel re-filed old
    /// history, and two devices in different zones rewrote each other's rows over
    /// CloudKit. What it repairs is finite (rows written by builds whose clock turned at
    /// midnight), so once is enough: the last device to update repairs whatever the older
    /// builds wrote, and every row written since is stamped by the same rule the repair
    /// applies. The flag is set only when the repair completed — a failed fetch or save
    /// retries next launch. Returns how many rows moved.
    @discardableResult
    func repairTrainingDaysIfNeeded(defaults: UserDefaults,
                                    calendar: Calendar = .current,
                                    now: Date = .now) -> Int {
        guard defaults.integer(forKey: Self.trainingDayRepairKey) < Self.trainingDayRepairVersion
        else { return 0 }
        guard let moved = repairTrainingDays(calendar: calendar, before: now) else { return 0 }
        defaults.set(Self.trainingDayRepairVersion, forKey: Self.trainingDayRepairKey)
        return moved
    }

    /// **Re-file sessions the app itself timed under the training day they started in.**
    /// Until 2026-09-20 the clock turned at midnight, so a session that ran across it —
    /// Nuri's 23:47 hang, finished 44 seconds into the 20th — was stamped with the
    /// morning after, and one evening scored as two days. The day now turns at
    /// `DayStamp.rolloverHour`, and this brings the rows written under the old rule into
    /// line with it. Returns how many rows moved, or nil when the fetch or save failed.
    ///
    /// Narrow on purpose, because every row it touches is history somebody lived:
    /// - **Only the kinds the app stamps itself** — the runner's `.hang` and the
    ///   `.benchmark` marker — in the predicate. A hand log's day is the one the person
    ///   chose, and a kind from a NEWER build is left exactly as that build wrote it
    ///   (`SessionKind(fallback:)` would read it as a hang and rewrite it).
    /// - **Only rows started before `cutoff`** — the moment this runs. Rows written after
    ///   it come from this build's writer, which already stamps the training day.
    /// - **Only rows still filed the midnight way**: the calendar day of some instant
    ///   between the start and a save just after the finish. A row outside that window
    ///   was not written by the old clock in this time zone — stamped by the new rule on
    ///   a device elsewhere, most likely — and re-deriving it here is how travel used to
    ///   move history.
    /// - **Four columns fetched, not the row.** The plan and rep blobs are never read.
    @discardableResult
    func repairTrainingDays(calendar: Calendar = .current, before cutoff: Date = .distantFuture) -> Int? {
        let hang = SessionKind.hang.rawValue
        let benchmark = SessionKind.benchmark.rawValue
        var descriptor = FetchDescriptor<WorkoutLog>(predicate: #Predicate<WorkoutLog> {
            ($0.kindRaw == hang || $0.kindRaw == benchmark) && $0.startedAt < cutoff
        })
        descriptor.propertiesToFetch = [\.dayKey, \.startedAt, \.finishedAt, \.kindRaw]
        guard let logs = try? context.fetch(descriptor) else { return nil }
        var moved = 0
        for log in logs {
            let day = DayStamp(trainingDayOf: log.startedAt, calendar: calendar).raw
            guard log.dayKey != day else { continue }
            // The midnight clock stamped the calendar day at SAVE time: no earlier than
            // the start's, and at most one past the finish's for a summary left open
            // across midnight.
            let earliest = DayStamp(date: log.startedAt, calendar: calendar).raw
            let latest = DayStamp(date: log.finishedAt, calendar: calendar).raw + 1
            guard (earliest...max(earliest, latest)).contains(log.dayKey) else { continue }
            log.dayKey = day
            moved += 1
        }
        guard moved > 0 else { return 0 }
        do {
            try context.save()
        } catch {
            context.rollback()
            return nil
        }
        return moved
    }
}
