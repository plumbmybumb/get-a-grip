// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import Observation
import SwiftData

/// The ONE way a finished session becomes a `WorkoutLog` — shared by the phone's
/// `TemplateStore` and by the watch, which has no store of its own.
///
/// One implementation, so the watch saves exactly what the phone would: the same frozen
/// name, the same training day, the same refusal of a max that is not a number.
///
/// Foundation, Observation and SwiftData only — no UIKit, no reminders, no derived state.
/// The store composes it and recomputes its own world afterwards.
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
    /// never the clock's day at saving — the same answer `repairTrainingDays` reaches, or
    /// a relaunch would move a session the writer had just filed.
    @discardableResult
    func recordSession(plan: SessionPlan,
                       template: SessionTemplate?,
                       reps: [RepSummary],
                       startedAt: Date,
                       finishedAt: Date,
                       rpe: RPE?,
                       newMaxes: [MaxRecord] = []) -> WorkoutLog? {
        guard newMaxes.allSatisfy({ $0.kg.isFinite && $0.kg > 0 }) else {
            saveError = String(localized: "Couldn't save this workout. Try again.")
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
            // Roll back so memory matches disk: a phantom session is worse than an error.
            context.rollback()
            saveError = String(localized: "Couldn't save the change: \(error.localizedDescription)")
            return nil
        }
        return log
    }

    /// Bumped only if the repair's RULE changes, which re-runs it once more everywhere.
    static let trainingDayRepairVersion = 1
    /// Device-local: a synced flag would let a device that never ran the repair believe
    /// it had.
    static let trainingDayRepairKey = "sessionLedger.trainingDayRepair"

    /// **The repair runs ONCE per install**, not on every launch: re-deriving every day
    /// in the CURRENT time zone let travel re-file old history and two devices in
    /// different zones rewrite each other's rows over CloudKit. What it repairs is finite
    /// (rows from builds whose clock turned at midnight), so once is enough. The flag is
    /// set only when the repair completed. Returns how many rows moved.
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
    /// The clock used to turn at midnight, so a 23:47 hang finished after it was stamped
    /// with the morning after. The day now turns at `DayStamp.rolloverHour`. Returns how
    /// many rows moved, or nil when the fetch or save failed.
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
    ///   was not written by the old clock in this time zone, and re-deriving it is how
    ///   travel used to move history.
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
