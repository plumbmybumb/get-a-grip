// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// One rung of the grip ladder Today draws: a finger glyph over a per-side rep count,
/// one column per set, in the order they will be pulled.
struct LadderRung: Identifiable, Hashable, Sendable {
    /// The set index — order IS the identity here. Two sets can be byte-identical and
    /// still be distinct rungs (Nuri's protocol repeats front-2 at a different position),
    /// and a `SetPlan.id` would make the ladder re-animate whenever a set was replaced
    /// rather than edited.
    let id: Int
    let grip: GripSpec
    let repsPerSide: Int
}

/// Everything Today needs to draw a routine card, as a VALUE.
///
/// The card takes one of these rather than a `SessionTemplate`, which is what makes it
/// previewable and testable without a `ModelContext` — and it is why every derived number
/// is computed once in the store instead of in a view body that runs on every frame of a
/// scroll.
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
    /// A climb logged today, if any. Carried on the SUMMARY rather than looked up by the
    /// card, so the card stays a pure value view — and so this cannot disagree with
    /// `TemplateStore.isDoneForToday`, shared by the routine's completion surfaces.
    var climbedToday: SessionKind? = nil
    /// Whether today is a benchmark day — measured maxes landed. Settles the card the
    /// same way a climb does, with its own copy.
    var benchmarkedToday: Bool = false
    /// A WHENEVER routine: no daily target, no reminders, never owed. The card drops
    /// the dots and the daily-guilt copy for it.
    var isOnDemand: Bool = false
    /// The routine's highest prescribed target intensity as a fraction of max
    /// (`PlanMath.peakIntensity`, computed by the store against `maxTable`). nil when
    /// nothing resolves — no targets anywhere, or kilogram bands with no max on file
    /// to divide by. Drives the `EdgeMark` rung's colour and the spoken suffix; a
    /// defaulted field so previews and value tests without a store stay buildable.
    var peakIntensity: Double? = nil

    /// **A climb — or a benchmark — meets the target.** Without this the card
    /// contradicted the rest of the app on a day spent at the gym: the daily completion indicator said
    /// done, the sentence said "Limit session at the gym today", and the card still
    /// offered a primary "Start first session" with no checkmark. One fact, three
    /// surfaces, one answer. A whenever routine has no target to meet — doing it once
    /// today is what earns the checkmark and demotes Start.
    var targetMet: Bool {
        if isOnDemand { return climbedToday != nil || benchmarkedToday || completedToday > 0 }
        return climbedToday != nil || benchmarkedToday
            || completedToday >= max(1, sessionsPerDay)
    }

    /// The edge column as the routine actually runs it: one number when every set
    /// agrees, a SPAN when they differ — never silence. `sharedEdgeMM` alone DROPPED
    /// the edge from the card the moment sets disagreed, which read as the app not
    /// knowing its own routine (Nuri, 2026-08-17: a 20-and-10 ladder "should say
    /// 20-10mm right?"). The span runs in LADDER order, not ascending — "20–10 mm"
    /// for a ladder that starts deep and thins out — because it is a fact about the
    /// protocol's direction, not an interval on a number line. A first edge that is
    /// neither extreme falls back to ascending, the only order left with a claim.
    var edgeLine: String? {
        let edges = ladder.map(\.grip.edgeMM)
        guard let first = edges.first, let lo = edges.min(), let hi = edges.max() else {
            return nil
        }
        if lo == hi { return String(localized: "\(hi) mm") }
        return first == hi ? String(localized: "\(hi)–\(lo) mm") : String(localized: "\(lo)–\(hi) mm")
    }

    /// The grip the card's `EdgeMark` draws — the routine's SIGNATURE, not its
    /// inventory. Weighted by PULLS, not by set count: a set is a container, and
    /// counting containers let two one-pull crimp sets outvote twelve four-finger
    /// pulls — Nuri's own Daily burn wore a two-finger mark on its first hardware
    /// day (2026-08-17) because its taper repeats the small grips as short sets.
    /// `repsPerSide` is the honest mass (hands multiply every rung equally, so sides
    /// cancel). A tie still goes to the ladder's first rung, the grip the session
    /// opens on. One derived mark per card is identity; the per-set ladder this
    /// replaced was information, and it read as clutter.
    var signatureFingers: FingerSet? {
        guard !ladder.isEmpty else { return nil }
        var weights: [FingerSet: Int] = [:]
        for rung in ladder { weights[rung.grip.fingers, default: 0] += max(1, rung.repsPerSide) }
        let best = weights.values.max() ?? 0
        return ladder.first { weights[$0.grip.fingers] == best }?.grip.fingers
    }

    /// "20 mm · 6 sets · 36 pulls · ≈21 min" — `PlanMath.summaryLine` with the edge
    /// line in front. Rebuilt from the parts here rather than carried as a string,
    /// because a summary is a value the card is previewed with and does not hold a plan.
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
    /// Every fold here is `TemplateStore.summary(for:)`'s, verbatim, and that is the
    /// whole point: the preview a stranger's code shows you and the card it becomes
    /// thirty seconds later must quote the same edge, the same set count, the same pull
    /// count and the same estimate. Two hand-written versions of that arithmetic is how
    /// one screen ends up promising ≈21 min and the next one ≈19.
    ///
    /// What it cannot carry, and why the defaults are honest: a fresh `id` (there is no
    /// routine yet), no completions and no reminder (nothing has happened and nothing is
    /// scheduled), and no climb or benchmark — those are facts about the READER's day,
    /// which belongs to Today's card rather than to a preview of somebody else's plan.
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
            // Against an EMPTY table, deliberately. A percentage band IS the intensity
            // and needs no max at all, so a routine prescribed in percentages — which is
            // the app's primary path — colours its mark here exactly as it will on
            // Today. A kilogram band cannot resolve without the reader's own max, so it
            // contributes nothing, which is `PlanMath.peakIntensity`'s existing rule for
            // an unmeasured grip: the mark can only under-claim into `unknown` (bleu,
            // "a routine"), never over-claim a load somebody's fingers would pay for.
            // The kilogram footnote on the sheet is what names that gap in words.
            peakIntensity: PlanMath.peakIntensity(of: plan, maxes: MaxTable()))
    }
}

/// One cell of the 14-day consistency strip.
struct DayRecord: Identifiable, Hashable, Sendable {
    let day: DayStamp
    let completed: Int
    let target: Int
    /// false = earlier than any routine existed. NOT a missed day — a hairline, not a
    /// hole. Drawing days that predate the app as empty circles tells someone they failed
    /// on days they did not own it, which is the single most important honesty detail on
    /// the screen. Computed ONCE in the store from `trackingSince`, never per cell.
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
