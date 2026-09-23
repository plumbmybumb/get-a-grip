// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// One rung of the grip ladder Today draws: a finger glyph over a per-side rep count,
/// one column per set, in the order they will be pulled.
struct LadderRung: Identifiable, Hashable, Sendable {
    /// The set index — order IS the identity. Two sets can be byte-identical and still
    /// be distinct rungs, and a `SetPlan.id` would re-animate the ladder whenever a set
    /// was replaced rather than edited.
    let id: Int
    let grip: GripSpec
    let repsPerSide: Int
}

/// Everything Today needs to draw a routine card, as a VALUE.
///
/// A value rather than a `SessionTemplate`, so the card is previewable without a
/// `ModelContext` and every derived number is computed once in the store, not per frame.
struct RoutineSummary: Identifiable, Hashable, Sendable {
    let id: UUID
    let name: String
    let ladder: [LadderRung]
    let setCount: Int
    let totalReps: Int
    /// The edge, only when every set agrees on one. nil drops it from `metaLine` rather
    /// than quoting one set's edge as if it were the routine's.
    let sharedEdgeMM: Int?
    let estimatedSeconds: Int
    let sessionsPerDay: Int
    let completedToday: Int
    let nextReminder: ReminderTime?
    /// A climb logged today, if any. Carried on the SUMMARY so the card stays a pure
    /// value view and cannot disagree with `TemplateStore.isDoneForToday`.
    var climbedToday: SessionKind? = nil
    /// Whether today is a benchmark day — measured maxes landed. Settles the card the
    /// same way a climb does, with its own copy.
    var benchmarkedToday: Bool = false
    /// A WHENEVER routine: no daily target, no reminders, never owed. The card drops
    /// the dots and the daily-guilt copy for it.
    var isOnDemand: Bool = false
    /// The routine's highest prescribed target intensity as a fraction of max
    /// (`PlanMath.peakIntensity`, against `maxTable`). nil when nothing resolves — no
    /// targets, or kilogram bands with no max to divide by. Drives the `EdgeMark`
    /// colour and the spoken suffix.
    var peakIntensity: Double? = nil

    /// **A climb — or a benchmark — meets the target**, or the card offers "Start first
    /// session" beside a sentence saying you were at the gym. A whenever routine has no
    /// target — doing it once today earns the checkmark and demotes Start.
    var targetMet: Bool {
        if isOnDemand { return climbedToday != nil || benchmarkedToday || completedToday > 0 }
        return climbedToday != nil || benchmarkedToday
            || completedToday >= max(1, sessionsPerDay)
    }

    /// The edge column as the routine actually runs it: one number when every set
    /// agrees, a SPAN when they differ — never silence, which read as the app not knowing
    /// its own routine (Nuri, 2026-08-17). The span runs in LADDER order ("20–10 mm" for
    /// a ladder that thins out): it states the protocol's direction. A first edge that is
    /// neither extreme falls back to ascending.
    var edgeLine: String? {
        let edges = ladder.map(\.grip.edgeMM)
        guard let first = edges.first, let lo = edges.min(), let hi = edges.max() else {
            return nil
        }
        if lo == hi { return String(localized: "\(hi) mm") }
        return first == hi ? String(localized: "\(hi)–\(lo) mm") : String(localized: "\(lo)–\(hi) mm")
    }

    /// The grip the card's `EdgeMark` draws — the routine's SIGNATURE, not its
    /// inventory. Weighted by PULLS, not set count: counting sets let two one-pull crimp
    /// sets outvote twelve four-finger pulls. `repsPerSide` is the honest mass (hands
    /// multiply every rung equally). A tie goes to the first rung, the opening grip.
    var signatureFingers: FingerSet? {
        guard !ladder.isEmpty else { return nil }
        var weights: [FingerSet: Int] = [:]
        for rung in ladder { weights[rung.grip.fingers, default: 0] += max(1, rung.repsPerSide) }
        let best = weights.values.max() ?? 0
        return ladder.first { weights[$0.grip.fingers] == best }?.grip.fingers
    }

    /// "20 mm · 6 sets · 36 pulls · ≈21 min" — `PlanMath.summaryLine` with the edge
    /// line in front, rebuilt from the parts because a summary holds no plan.
    var metaLine: String {
        var parts: [String] = []
        if let edge = edgeLine { parts.append(edge) }
        let setWord = setCount == 1 ? String(localized: "set") : String(localized: "sets")
        parts.append(String(localized: "\(setCount) \(setWord)"))
        let pullWord = totalReps == 1 ? String(localized: "pull") : String(localized: "pulls")
        parts.append(String(localized: "\(totalReps) \(pullWord)"))
        parts.append(PlanMath.approxMinutes(estimatedSeconds))
        return parts.joined(separator: " · ")
    }
}

extension RoutineSummary {
    /// A summary for a routine that does not exist yet — the QR import preview.
    ///
    /// Every fold is `TemplateStore.summary(for:)`'s, verbatim, so the preview and the
    /// card it becomes quote the same edge, counts and estimate.
    ///
    /// A fresh `id`, no completions, no reminder, and no climb or benchmark — those are
    /// facts about the READER's day, not somebody else's plan.
    init(previewing draft: RoutineDraft) {
        let plan = draft.plan
        let ladder = plan.executable.sets.enumerated().map { index, set in
            LadderRung(id: index, grip: set.grip, repsPerSide: set.repsPerSide)
        }
        self.init(
            id: UUID(),
            name: plan.name,
            ladder: ladder,
            setCount: PlanMath.setCount(plan),
            totalReps: PlanMath.totalReps(plan),
            sharedEdgeMM: PlanMath.sharedEdgeMM(plan),
            estimatedSeconds: PlanMath.totalSeconds(plan),
            sessionsPerDay: draft.sessionsPerDay,
            completedToday: 0,
            nextReminder: nil,
            isOnDemand: draft.isOnDemand,
            // Against an EMPTY table: a percentage band IS the intensity and needs no
            // max, so it colours the mark as it will on Today. A kilogram band cannot
            // resolve without the reader's max, so the mark can only under-claim into
            // `unknown`, never over-claim a load somebody's fingers would pay for.
            peakIntensity: PlanMath.peakIntensity(of: plan, maxes: MaxTable()))
    }
}

/// One cell of the 14-day consistency strip.
struct DayRecord: Identifiable, Hashable, Sendable {
    let day: DayStamp
    let completed: Int
    let target: Int
    /// false = earlier than any routine existed. NOT a missed day — a hairline, not a
    /// hole: empty circles would say you failed on days before you had the app.
    /// Computed ONCE in the store from `trackingSince`.
    let tracked: Bool
    /// The climb logged that day, if any — `nil` on an ordinary day. Carried as the
    /// KIND rather than a Bool so the grid can tell a limit day from a volume one
    /// without a second lookup.
    let climb: SessionKind?
    /// A benchmark day: fills the cell like a climb, but plain — the notch stays a
    /// climbing mark.
    var benchmarked: Bool = false

    var id: Int { day.raw }

    /// The continuous fill, not three buckets: it reduces to exactly full/half/empty at
    /// two sessions a day and stays truthful at three.
    ///
    /// **A climb fills the cell outright**, whatever the hang count beside it — the day
    /// is complete, so a half-full circle would contradict the sentence on Today.
    var fraction: Double {
        if climb != nil { return 1 }
        guard target > 0 else { return 0 }
        return min(1, Double(completed) / Double(target))
    }
}
