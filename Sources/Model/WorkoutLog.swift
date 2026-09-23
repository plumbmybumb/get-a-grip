// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation
import SwiftData

/// One finished session, frozen.
///
/// Everything here that could have come from a `SessionTemplate` is a SNAPSHOT instead:
/// the name, the plan, and every rep's own `GripSpec`. Editing a routine must never
/// rewrite history — which is what makes edits cheap enough to offer behind a swipe and
/// an undo bar rather than a dialog. Deleting one takes its sessions with it, and the
/// same Undo puts them back (`TemplateStore.delete`, Nuri 2026-09-20).
///
/// Same CloudKit rules as `SessionTemplate`: every attribute defaulted or optional, no
/// uniqueness constraint, no relationships, additive changes only.
///
/// Schema-only in M2 — the M3 runner is what writes these.
@Model
final class WorkoutLog {
    var id: UUID = UUID()
    var startedAt: Date = Date.now
    var finishedAt: Date = Date.now
    /// Epoch day FROZEN at save — the TRAINING day, which turns at
    /// `DayStamp.rolloverHour`, so a 00:30 session stays on the evening it belonged to.
    /// It is the join for "2 of 2 today": a cheap Int predicate rather than a `Calendar`
    /// pass over every log. Stamped from `startedAt` by `SessionLedger`; rows the older
    /// midnight-turning clock stamped are re-filed by `SessionLedger.repairTrainingDays`,
    /// which runs ONCE per install (versioned flag), touches only the kinds the app
    /// stamps itself, and only rows still filed the midnight way.
    var dayKey: Int = 0
    /// Best-effort grouping ONLY. The routine may be gone; nothing here needs it back.
    var templateID: UUID? = nil
    /// FROZEN: renaming a routine must not retro-rename the history it produced.
    var templateName: String = ""
    var planData: Data = Data()        // SessionPlan (already .executable), write-once
    var resultsData: Data = Data()     // [RepSummary], write-once
    /// What "a full day" meant when this was logged — a later change to sessions-a-day
    /// must not re-score days already lived.
    var sessionsPerDayTarget: Int = 1
    var totalHeldSeconds: Double = 0
    /// Denormalized at save so History can draw a list without decoding a blob per row.
    var peakKg: Double = 0
    var avgKg: Double = 0
    var completedReps: Int = 0
    var plannedReps: Int = 0
    /// nil until the user grades the session; `RPE`'s raw value when they do.
    var rpe: Int? = nil
    /// The LOCAL strain axis; `rpe` is reused as the systemic one. nil = not answered.
    var fingerStrainRaw: Int? = nil
    /// Wall-clock length of a session logged BY HAND. nil for runner sessions, which
    /// carry a real `startedAt`/`finishedAt` span instead — see `sessionMinutes`.
    var durationMinutes: Int? = nil
    var notes: String = ""
    /// What kind of training this was — see `SessionKind`. **Defaulted to "hang"**,
    /// which is exactly what every row written before climbing existed means, so the
    /// migration is additive with no backfill.
    var kindRaw: String = SessionKind.hang.rawValue

    init(plan: SessionPlan,
         templateID: UUID?,
         templateName: String,
         sessionsPerDayTarget: Int,
         reps: [RepSummary],
         startedAt: Date,
         finishedAt: Date,
         day: DayStamp) {
        // `.executable` here rather than trusting the caller: `RepSummary.setIndex`
        // indexes THIS list, so the invariant has to be true by construction. It is
        // idempotent, so a runner that already froze the executable plan pays nothing.
        let frozen = plan.executable
        let held = reps.reduce(0.0) { $0 + $1.heldSeconds }

        self.id = UUID()
        self.startedAt = startedAt
        self.finishedAt = finishedAt
        self.dayKey = day.raw
        self.templateID = templateID
        self.templateName = templateName
        self.planData = BlobCodec.encode(frozen) ?? Data()
        self.resultsData = BlobCodec.encode(reps) ?? Data()
        self.sessionsPerDayTarget = max(1, sessionsPerDayTarget)
        self.totalHeldSeconds = held
        self.peakKg = reps.map(\.peakKg).max() ?? 0
        // Time-weighted, not a mean of means: a rep that dropped off after one second
        // would otherwise weigh as much as a full ten-second hang.
        self.avgKg = held > 0 ? reps.reduce(0.0) { $0 + $1.avgKg * $1.heldSeconds } / held : 0
        // `.completed` only. An early release is a pull that happened, not a pull that
        // counted, and this number is what "the session is done" is measured against.
        self.completedReps = reps.filter { $0.outcome == .completed }.count
        self.plannedReps = PlanMath.totalReps(frozen)
        self.rpe = nil
        self.notes = ""
    }
}

extension Collection where Element == WorkoutLog {
    /// The climb logged on `day` — **hardest first**, so a limit session is what a day is
    /// remembered by even when an easy evening followed it.
    ///
    /// ONE implementation. The store folds it for Today's strip and History folds its own
    /// `@Query` for the 5-week grid, and two hand-written copies of this rule would
    /// eventually draw two different calendars from the same rows.
    func climb(on day: DayStamp) -> SessionKind? {
        let kinds = filter { $0.dayKey == day.raw && $0.kind.isClimb }.map(\.kind)
        return kinds.contains(.climbLimit) ? .climbLimit : kinds.first
    }

    /// Whether anything logged on `day` SETTLES it — a climb or a benchmark. The grid
    /// and the tally fill on this; the notch and the gym copy still key on `climb(on:)`,
    /// because a benchmark is a full day but not a climbing day.
    func settled(on day: DayStamp) -> Bool {
        contains { $0.dayKey == day.raw && $0.kind.settlesDay }
    }

    /// Whether a benchmark was logged on `day`. Local writes avoid a second marker;
    /// concurrent CloudKit devices can still merge multiple markers for the same day.
    func benchmark(on day: DayStamp) -> Bool {
        contains { $0.dayKey == day.raw && $0.kind == .benchmark }
    }

    /// HANG sessions only, per routine, on `day` — the "1 of 2 today" join. A climb has
    /// no routine to attribute to and does not fill a slot; it settles the whole day,
    /// which is the separate question `climb(on:)` answers. A log whose routine was
    /// deleted still counts as a session trained but has nothing to attribute to —
    /// grouping is best-effort by design.
    ///
    /// Here rather than in the store so the watch, which has no store, counts a day
    /// exactly as the phone does.
    func hangCompletions(on day: DayStamp) -> [UUID: Int] {
        var counts: [UUID: Int] = [:]
        for log in self where log.dayKey == day.raw && !log.kind.isClimb {
            guard let id = log.templateID else { continue }
            counts[id, default: 0] += 1
        }
        return counts
    }

    /// `.hangManual` ONLY, deliberately — not every log with a nil `templateID`. A
    /// runner session whose routine was later deleted also has no id, and it is dropped
    /// on purpose (see `hangCompletions`); crediting those here would retroactively
    /// change how old days score. A hand-logged hang never had a routine to begin with.
    func unattributedHangs(on day: DayStamp) -> Int {
        filter { $0.dayKey == day.raw && $0.kind == .hangManual }.count
    }

    /// The all-time tally at the top of Settings (Nuri, 2026-09-20: "lifetime stats").
    /// Folded from the DENORMALIZED columns only — never from the rep blobs — so it costs
    /// a row per session, not a decode, and can be recomputed every time the tab opens.
    var lifetime: LifetimeStats {
        var stats = LifetimeStats()
        var days = Set<Int>()
        var climbDays = Set<Int>()
        for log in self {
            days.insert(log.dayKey)
            stats.since = stats.since.map { Swift.min($0, log.day) } ?? log.day
            switch log.kind {
            case .hang, .hangManual:
                stats.sessions += 1
                stats.pulls += log.completedReps
                stats.heldSeconds += log.totalHeldSeconds
                // The lifting convention — load × reps, added up — from the session's
                // time-weighted mean and its completed count. Exact when every hold in a
                // session ran its full length, which is what a completed pull means.
                stats.volumeKg += log.avgKg * Double(log.completedReps)
                stats.heaviestPullKg = Swift.max(stats.heaviestPullKg, log.peakKg)
            case .climbVolume, .climbLimit:
                climbDays.insert(log.dayKey)
            case .benchmark:
                break
            }
        }
        stats.daysTrained = days.count
        stats.climbDays = climbDays.count
        return stats
    }
}

/// What a lifetime of sessions adds up to — see `Collection.lifetime` and `LifetimeCard`.
/// Hangboard sessions the app ran or that were logged by hand count as sessions; a climb
/// is a day at the gym; a benchmark day is a day trained and nothing else.
struct LifetimeStats: Equatable, Sendable {
    var sessions = 0
    /// Completed pulls only — a skipped pull is a pull that did not happen.
    var pulls = 0
    /// Every second on the edge, across every completed or partial hold.
    var heldSeconds = 0.0
    /// Load × pulls, summed — the number a lifter calls volume.
    var volumeKg = 0.0
    /// Distinct days with a climb logged — two climbs on one day are one day at the gym.
    var climbDays = 0
    /// Distinct training days with anything on them, climbs and benchmarks included.
    var daysTrained = 0
    var heaviestPullKg = 0.0
    /// The earliest training day on record.
    var since: DayStamp?

    var isEmpty: Bool { sessions == 0 && climbDays == 0 && daysTrained == 0 }
}

extension WorkoutLog {
    /// A logged session with no plan, no reps and no gauge behind it. `.benchmark` uses
    /// the same initializer even though it is written by `recordMax`, so this is about
    /// the shape of the row rather than who is allowed to create it.
    ///
    /// Deliberately the same table as a hangboard session rather than a model of its
    /// own: it is a session that happened, History is one list, and the consistency
    /// grids fold over one stream. The blob columns are simply empty, which every
    /// reader already tolerates (`plan` returns nil, `reps` returns []) because a
    /// corrupt snapshot had to be survivable anyway.
    convenience init(logged kind: SessionKind, day: DayStamp, at when: Date,
                     sessionsPerDayTarget: Int, minutes: Int? = nil,
                     rpe: RPE? = nil, fingerStrain: FingerStrain? = nil,
                     notes: String = "") {
        self.init(plan: SessionPlan(sets: []),
                  templateID: nil,
                  templateName: kind.name,
                  sessionsPerDayTarget: sessionsPerDayTarget,
                  reps: [],
                  startedAt: when,
                  finishedAt: when,
                  day: day)
        self.kindRaw = kind.rawValue
        self.durationMinutes = minutes
        self.rpe = rpe?.rawValue
        self.fingerStrainRaw = fingerStrain?.rawValue
        self.notes = notes
    }

    /// What to CALL this session's routine — the routine's live name while it still
    /// exists, the frozen copy once it doesn't.
    ///
    /// The log freezes `templateName` at save time and must keep doing so: a deleted
    /// routine has to leave its history with something to be called. But that made a
    /// rename invisible in History (Nuri, 2026-08-11) — rename "Daily no-hangs" to
    /// "Morning ladder" and every past session stayed filed under a name that appeared
    /// nowhere else in the app. Worse, it was already INCONSISTENT: the trend card titled
    /// itself from the newest log in the group, so one new session made the card say the
    /// new name while every row beneath it said the old one.
    ///
    /// Resolving at DISPLAY time rather than rewriting the logs is the cheaper and more
    /// honest fix: renaming stays a routine edit instead of a write across the whole
    /// history, renaming back needs no second migration, and what the session actually
    /// WAS — its plan, its reps, its grips — is still frozen and still untouchable.
    ///
    /// `routineNames` is `TemplateStore.routineNames`. History's rows, its trend cards and
    /// the analysis export all name a session through this one rule.
    func displayName(in routineNames: [UUID: String]) -> String {
        Self.displayName(templateID: templateID, templateName: templateName, in: routineNames)
    }

    /// The same rule for a session already copied off its model, where the trend fold
    /// works on values off the main actor.
    static func displayName(templateID: UUID?, templateName: String,
                            in routineNames: [UUID: String]) -> String {
        templateID.flatMap { routineNames[$0] } ?? templateName
    }

    /// An unknown kind from a newer build reads as `.hang` — see `SessionKind`.
    var kind: SessionKind {
        get { SessionKind(fallback: kindRaw) }
        set { kindRaw = newValue.rawValue }
    }

    /// Write-once, so read-only: nothing may re-encode a session after it happened.
    var reps: [RepSummary] { BlobCodec.decodeArray(RepSummary.self, from: resultsData) }

    /// nil when the snapshot is missing or unreadable. History then falls back to the
    /// denormalized columns, which is why they exist.
    var plan: SessionPlan? { BlobCodec.decode(SessionPlan.self, from: planData) }

    var day: DayStamp { DayStamp(raw: dayKey) }

    /// The date History shows for this row: its TRAINING day, for every kind of row.
    /// A hand log records when the entry was created, not when the training happened,
    /// so its chosen day was always the one to show; a runner session used to show its
    /// start instant instead, which is a different calendar day from the training day
    /// for anything that began in the small hours — the row said the 19th while the grid
    /// and the tally credited the 20th (Nuri, 2026-09-20). One date per row now, the
    /// same one every other surface counts by.
    func historyDate(calendar: Calendar = .current) -> Date {
        day.date(calendar: calendar)
    }

    /// nil for an ungraded session, or for a scale value from a future build.
    var grade: RPE? { rpe.flatMap(RPE.init(rawValue:)) }

    var fingerStrain: FingerStrain? {
        fingerStrainRaw.flatMap(FingerStrain.init(rawValue:))
    }

    /// One duration for every kind of session. A hand-entered duration wins; runner
    /// sessions already have a real span, so their existing rows gain a duration
    /// without a backfill or a migration.
    var sessionMinutes: Int? {
        if let durationMinutes, durationMinutes > 0 { return durationMinutes }
        let elapsed = finishedAt.timeIntervalSince(startedAt)
        guard elapsed > 0 else { return nil }
        let minutes = Int((elapsed / 60).rounded())
        return minutes > 0 ? minutes : nil
    }

    var wasCompleted: Bool { plannedReps > 0 && completedReps >= plannedReps }

    /// What this session asked of each grip, keyed by the CANONICAL key — the join
    /// between a pull made in March and one made in December.
    ///
    /// Folded out of the frozen plan rather than counted off the reps, so the per-grip
    /// line reads identically here and in the builder: one formatter, one set of totals.
    /// `uniquingKeysWith` rather than `uniqueKeysWithValues` because a trap here would
    /// take History down for one malformed blob.
    func totalsByGripKey() -> [String: PlanMath.GripTotals] {
        guard let plan else { return [:] }
        return Dictionary(PlanMath.gripTotals(plan).map { ($0.grip.key, $0) },
                          uniquingKeysWith: { first, _ in first })
    }
}
