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
/// `dayKey` from the app's own `DayClock`, the same refusal of a max that is not a
/// number. Two hand-written copies of "how a session is saved" would eventually save two
/// different sessions.
///
/// Foundation, Observation and SwiftData only — no UIKit, no reminders, no derived state.
/// The store composes it and recomputes its own world afterwards; the watch has no
/// derived world to recompute, so the ledger is all it needs.
@Observable @MainActor
final class SessionLedger {
    private let context: ModelContext
    private let clock: DayClock

    /// The last write that failed, in words; nil after a successful one. The store
    /// mirrors it into its own `saveError`, which is what every screen already reads.
    private(set) var saveError: String?

    init(context: ModelContext, clock: DayClock) {
        self.context = context
        self.clock = clock
    }

    /// Write a finished session. Rolls the context back and returns nil when the save
    /// fails or a max in `newMaxes` is not a positive, finite number — a zero or NaN max
    /// would make every percentage-of-max caption in the app lie.
    ///
    /// `day` comes from `DayClock`, not `Date.now`, so a session finished at 00:30 lands
    /// on the day the climber actually lived through.
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
            day: clock.today
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

    /// **Re-file every session the app itself timed under the training day it started
    /// in.** Until 2026-09-20 the clock turned at midnight, so a session that ran across
    /// it — Nuri's 23:47 hang, finished 44 seconds into the 20th — was stamped with the
    /// morning after, and one evening scored as two days. The day now turns at
    /// `DayStamp.rolloverHour`, and this brings the rows written under the old rule into
    /// line with it. Hand-logged sessions are left alone: their day is the one the person
    /// chose. Deterministic from a frozen column, so every synced device reaches the same
    /// answer and a row already right is never touched — which is what makes it safe,
    /// and cheap, to run on every launch. Returns how many rows moved.
    @discardableResult
    func repairTrainingDays(calendar: Calendar = .current) -> Int {
        guard let logs = try? context.fetch(FetchDescriptor<WorkoutLog>()) else { return 0 }
        var moved = 0
        for log in logs where !log.kind.isLoggedByHand {
            let day = DayStamp(trainingDayOf: log.startedAt, calendar: calendar).raw
            guard log.dayKey != day else { continue }
            log.dayKey = day
            moved += 1
        }
        guard moved > 0 else { return 0 }
        do {
            try context.save()
        } catch {
            context.rollback()
            return 0
        }
        return moved
    }
}
