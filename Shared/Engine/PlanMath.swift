// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// One executable rep, fully resolved. EVERYTHING the app says about a routine — the
/// "≈21 min", the per-side totals, the row clocks, the order the runner executes — is
/// a fold over this ONE list. A parallel closed form would eventually disagree with the
/// runner, invisibly without a stopwatch.
struct RepSlot: Hashable, Sendable, Identifiable {
    /// Index into `plan.executable.sets` — NOT into the authored sets, so a zero-rep
    /// set can never shift what a logged rep points at.
    var setIndex: Int
    /// 0-based within its set, counting BOTH sides.
    var repIndex: Int
    var side: Side
    var grip: GripSpec
    var holdSeconds: Int
    /// The set's lead-in, on rep 0 only; 0 otherwise.
    var leadInBefore: Int
    /// The load to aim for in kilograms, ALREADY RESOLVED per hand by `PlanMath.sequence`
    /// against the max table frozen at session start, so nothing downstream looks up a
    /// max. nil when the set has no target or its grip has no max on file.
    var targetBand: ClosedRange<Double>? = nil
    /// The intra-set rest, the SET BREAK on a set's last rep, or 0 at the very end of
    /// the session — nobody rests after the last pull, and counting that rest would put
    /// a minute of nothing into every estimate.
    var restAfter: Int
    var isFirstOfSet: Bool
    var isLastOfSet: Bool

    var id: String { "\(setIndex).\(repIndex)" }
    var totalSeconds: Int { leadInBefore + holdSeconds + restAfter }
}

/// The routine's arithmetic and its frozen copy, as pure functions. No state, no
/// clock, no formatter that behaves differently in another locale.
enum PlanMath {

    // MARK: - Inheritance resolution

    /// The ONLY readers of `SetPlan.holdSeconds`. Everything else asks for the resolved
    /// value, so "nil means follow the routine" is expressed once.
    static func hold(_ set: SetPlan, in plan: SessionPlan) -> Int {
        set.holdSeconds ?? plan.holdSeconds
    }

    /// The ONLY readers of `SetPlan.restSeconds`.
    static func rest(_ set: SetPlan, in plan: SessionPlan) -> Int {
        set.restSeconds ?? plan.restSeconds
    }

    /// The ONE place a target load is decided, in strict precedence:
    ///
    /// 1. **kilograms typed on this set** — the most specific thing anyone can say, and
    ///    a number a person typed must never be second-guessed by arithmetic;
    /// 2. **this set's own percentage** of `maxKg`;
    /// 3. **the routine's percentage** of `maxKg` — the inheritance that makes "all six
    ///    sets at 17–22 %" a single edit, exactly like `hold` and `rest`;
    /// 4. **nothing**.
    ///
    /// nil `maxKg` collapses 2 and 3 to nothing rather than to a guess: there is no
    /// percentage of a max nobody has recorded, and inventing one would put a confident
    /// kilogram figure on a screen somebody trains against.
    static func targetBand(_ set: SetPlan, in plan: SessionPlan,
                           maxKg: Double?) -> ClosedRange<Double>? {
        if let explicit = set.targetBand { return explicit }
        guard let percent = set.targetPercentBand ?? plan.targetPercentBand,
              let maxKg, maxKg > 0 else { return nil }
        let lo = roundedToHalfKg(maxKg * percent.lowerBound)
        let hi = roundedToHalfKg(maxKg * percent.upperBound)
        return Swift.min(lo, hi)...Swift.max(lo, hi)
    }

    /// The percentage band a set will USE, ignoring any kg override — what the builder
    /// shows as "following the routine". nil when neither level sets one.
    static func targetPercent(_ set: SetPlan, in plan: SessionPlan) -> ClosedRange<Double>? {
        set.targetPercentBand ?? plan.targetPercentBand
    }

    /// The load for ONE rep, resolved against that rep's hand. A set covers both hands and
    /// they do not share a max, so this is computed per REP — see `sequence`. A kg band
    /// typed on the set stays hand-agnostic: splitting a typed 8 kg into 8.4 / 7.6 would
    /// second-guess a number a person typed. Per-hand loads come from percentages.
    static func targetBand(_ set: SetPlan, in plan: SessionPlan,
                           side: Side, maxes: MaxTable) -> ClosedRange<Double>? {
        targetBand(set, in: plan, maxKg: maxes.max(grip: set.grip.key, side: side))
    }

    /// Bake every percentage target down to today's kilograms, keyed by each set's grip.
    ///
    /// A retained set-level utility; no current screen depends on it. NOT the
    /// session-start path: the runner freezes a MaxTable and `sequence` resolves each
    /// hand, whereas one band baked onto a set would hand both hands the same kilograms.
    /// Sets without a max keep no band; percentages stay untouched.
    static func resolvingTargets(_ plan: SessionPlan, maxes: MaxTable) -> SessionPlan {
        var out = plan
        out.sets = plan.sets.map { set in
            var s = set
            guard let band = targetBand(set, in: plan, side: .both, maxes: maxes) else { return s }
            s.targetLoKg = band.lowerBound
            s.targetHiKg = band.upperBound
            return s
        }
        return out
    }

    /// THE single ×2 resolver: `repsPerSide × mode.sideCount`. Nowhere else in the app
    /// may multiply by a side count — that is how a routine quietly becomes twice as
    /// long as its own summary claims.
    static func repCount(_ set: SetPlan, mode: HandMode) -> Int {
        Swift.max(0, set.repsPerSide) * mode.sideCount
    }

    // MARK: - Sides

    /// Which hand pulls rep `r` of a set. `setIndex` is deliberately NOT a parameter:
    /// alternation RESETS to the start side at every set boundary, so the answer depends
    /// only on the position WITHIN the set, and a setIndex argument would be dead weight
    /// that invites someone to "use" it and break the rule.
    ///
    ///   `.alternateEachRep`, 3/side → L R L R L R
    ///   `.alternateEachSet`, 3/side → L L L R R R
    ///   `.bothHands`,        3/side → B B B
    ///
    /// `startingHand: .right` mirrors the two alternating rows (R L R L R L, R R R L L L)
    /// and changes nothing under `.bothHands`.
    static func side(forRep r: Int, mode: HandMode, repsPerSide: Int,
                     startingHand: Side = .left) -> Side {
        let rep = Swift.max(0, r)
        let first = mode.startSide(startingHand: startingHand)
        switch mode {
        case .bothHands:
            return .both
        case .alternateEachRep:
            return rep.isMultiple(of: 2) ? first : first.other
        case .alternateEachSet:
            return rep < Swift.max(0, repsPerSide) ? first : first.other
        }
    }

    static func handSequence(_ set: SetPlan, in plan: SessionPlan) -> [Side] {
        (0..<repCount(set, mode: plan.handMode)).map {
            side(forRep: $0, mode: plan.handMode, repsPerSide: set.repsPerSide,
                 startingHand: plan.startingHand)
        }
    }

    // MARK: - The sequence

    /// The whole session, rep by rep. Zero-rep sets are dropped ENTIRELY (no lead-in, no
    /// set break): an emptied row is not a pause.
    ///
    /// `maxes` makes loads per-hand, since only a rep knows its hand. Callers that want
    /// the session's shape rather than its loads (totals, estimates) pass the empty default.
    static func sequence(for plan: SessionPlan, maxes: MaxTable = MaxTable()) -> [RepSlot] {
        let live = plan.executable
        var slots: [RepSlot] = []
        for (setIndex, set) in live.sets.enumerated() {
            let reps = repCount(set, mode: live.handMode)
            guard reps > 0 else { continue }
            let holdSeconds = hold(set, in: live)
            let restSeconds = rest(set, in: live)
            let isLastSet = setIndex == live.sets.count - 1
            for repIndex in 0..<reps {
                let isFirst = repIndex == 0
                let isLast = repIndex == reps - 1
                let repSide = side(forRep: repIndex, mode: live.handMode,
                                   repsPerSide: set.repsPerSide,
                                   startingHand: live.startingHand)
                slots.append(RepSlot(
                    setIndex: setIndex,
                    repIndex: repIndex,
                    side: repSide,
                    grip: set.grip,
                    holdSeconds: holdSeconds,
                    leadInBefore: isFirst ? live.leadInSeconds : 0,
                    // A band already BAKED onto the set wins: it is either a number the
                    // user typed or one `resolvingTargets` froze, and both outrank a
                    // fresh lookup. Otherwise resolve for THIS hand.
                    targetBand: set.targetBand
                        ?? targetBand(set, in: live, side: repSide, maxes: maxes),
                    restAfter: isLast ? (isLastSet ? 0 : live.setBreakSeconds) : restSeconds,
                    isFirstOfSet: isFirst,
                    isLastOfSet: isLast))
            }
        }
        return slots
    }

    // MARK: - Totals

    /// One set, EXCLUDING its trailing break: `leadIn + reps·hold + (reps−1)·rest`.
    /// The break belongs to the gap between two sets, not to either of them, which is
    /// what makes the last set's row clock honest.
    static func setSeconds(_ set: SetPlan, in plan: SessionPlan) -> Int {
        let reps = repCount(set, mode: plan.handMode)
        guard reps > 0 else { return 0 }
        return plan.leadInSeconds + reps * hold(set, in: plan) + (reps - 1) * rest(set, in: plan)
    }

    /// Fold the same resolved set timing used by `sequence`, without allocating every
    /// pull just to show a builder total. Zero-pull sets contribute no lead-in or break.
    static func totalSeconds(_ plan: SessionPlan) -> Int {
        let live = plan.executable
        return live.sets.reduce(0) { $0 + setSeconds($1, in: live) }
            + max(0, live.sets.count - 1) * live.setBreakSeconds
    }

    /// Time under tension across the whole session (both sides together).
    static func tensionSeconds(_ plan: SessionPlan) -> Int {
        plan.executable.sets.reduce(0) { $0 + repCount($1, mode: plan.handMode) * hold($1, in: plan) }
    }

    static func totalReps(_ plan: SessionPlan) -> Int {
        plan.executable.sets.reduce(0) { $0 + repCount($1, mode: plan.handMode) }
    }

    static func setCount(_ plan: SessionPlan) -> Int {
        plan.executable.sets.count
    }

    /// nil when `handMode.sideCount == 1` — the copy then drops "per side" rather than
    /// lying about a division that did not happen.
    static func repsPerSide(_ plan: SessionPlan) -> Int? {
        let sides = plan.handMode.sideCount
        guard sides > 1 else { return nil }
        return totalReps(plan) / sides
    }

    static func tensionSecondsPerSide(_ plan: SessionPlan) -> Int? {
        let sides = plan.handMode.sideCount
        guard sides > 1 else { return nil }
        return tensionSeconds(plan) / sides
    }

    static func tensionSecondsPerSide(_ set: SetPlan, in plan: SessionPlan) -> Int? {
        tensionSecondsPerSide(set, mode: plan.handMode, resolvedHold: hold(set, in: plan))
    }

    /// The same figure for a caller that has already resolved the hold — the grip card,
    /// which deliberately does not hold a whole `SessionPlan`. Delegated to by the
    /// variant above, so the two can never drift.
    static func tensionSecondsPerSide(_ set: SetPlan, mode: HandMode,
                                      resolvedHold: Int) -> Int? {
        guard mode.sideCount > 1 else { return nil }
        return Swift.max(0, set.repsPerSide) * resolvedHold
    }

    /// The edge, only when every set agrees on one. nil → the meta line omits it rather
    /// than naming one of several.
    static func sharedEdgeMM(_ plan: SessionPlan) -> Int? {
        let edges = plan.executable.sets.map(\.grip.edgeMM)
        guard let first = edges.first, edges.allSatisfy({ $0 == first }) else { return nil }
        return first
    }

    // MARK: - Per-grip totals

    struct GripTotals: Hashable, Sendable {
        var grip: GripSpec
        /// Summed across every set using this grip, in reps PER SIDE.
        var repsPerSide: Int
        var totalReps: Int
        var tensionSeconds: Int

        /// Computed, never stored: a stored copy of derived copy is a second source of
        /// truth, and this one would be the string a user reads.
        var line: String {
            let sides = repsPerSide > 0 ? totalReps / repsPerSide : 1
            guard sides > 1 else {
                let pullWord = totalReps == 1 ? String(localized: "pull") : String(localized: "pulls")
                let pulls = String(localized: "\(totalReps) \(pullWord)")
                return String(localized: "\(pulls) · \(PlanMath.clockText(tensionSeconds)) under tension")
            }
            return String(localized: "\(repsPerSide) per side · \(PlanMath.clockText(tensionSeconds / sides)) under tension per side")
        }
    }

    /// Merged by CANONICAL KEY, in first-appearance order. Order is first-appearance and
    /// not sorted because the routine's own order is the thing the user recognises.
    static func gripTotals(_ plan: SessionPlan) -> [GripTotals] {
        let live = plan.executable
        var order: [String] = []
        var merged: [String: GripTotals] = [:]
        for set in live.sets {
            let reps = repCount(set, mode: live.handMode)
            guard reps > 0 else { continue }
            let key = set.grip.key
            let tension = reps * hold(set, in: live)
            if var existing = merged[key] {
                existing.repsPerSide += set.repsPerSide
                existing.totalReps += reps
                existing.tensionSeconds += tension
                merged[key] = existing
            } else {
                order.append(key)
                merged[key] = GripTotals(grip: set.grip,
                                         repsPerSide: set.repsPerSide,
                                         totalReps: reps,
                                         tensionSeconds: tension)
            }
        }
        return order.compactMap { merged[$0] }
    }

    // MARK: - Load

    /// nil when `maxKg <= 0`: there is no percentage of a max nobody has recorded, and
    /// a 0 % caption is worse than no caption.
    static func percentOfMax(_ kg: Double, maxKg: Double) -> Double? {
        guard maxKg > 0 else { return nil }
        return kg / maxKg
    }

    /// The Emil-Abrahamsson low-intensity band by default: 20–30 % of max.
    static func suggestedBand(maxKg: Double,
                              percent: ClosedRange<Double> = 0.20...0.30) -> ClosedRange<Double>? {
        guard maxKg > 0 else { return nil }
        let lo = roundedToHalfKg(maxKg * percent.lowerBound)
        let hi = roundedToHalfKg(maxKg * percent.upperBound)
        return Swift.min(lo, hi)...Swift.max(lo, hi)
    }

    /// Half-kilo steps, because that is the resolution a person can actually hold — and
    /// because a target of 7.3 kg reads as a measurement rather than as a suggestion.
    static func roundedToHalfKg(_ kg: Double) -> Double {
        (kg * 2).rounded() / 2
    }

    // MARK: - Per-hand loads, as words

    /// What this set asks of each hand: `"5.5–8.0 kg"` when the hands agree, and
    /// `"L 5.5–8.0 · R 5.0–7.5 kg"` when they do not. THE one place per-hand load copy is
    /// decided, so the builder, wrap-up and runner cannot disagree.
    ///
    /// nil when neither hand resolves — the caller then says "no max" rather than
    /// printing an empty band.
    static func targetText(_ set: SetPlan, in plan: SessionPlan, maxes: MaxTable) -> String? {
        // One side means the hands are on the edge together; there is no per-hand
        // question to answer and "L … · R …" would invent one.
        guard plan.handMode.sideCount > 1 else {
            return targetBand(set, in: plan, side: .both, maxes: maxes).map { bandText($0) }
        }
        let left = targetBand(set, in: plan, side: .left, maxes: maxes)
        let right = targetBand(set, in: plan, side: .right, maxes: maxes)
        switch (left, right) {
        case (nil, nil):
            return nil
        case let (l?, r?) where l == r:
            return bandText(l)
        case let (l?, r?):
            return String(localized: "L \(bandText(l, withUnit: false)) · R \(bandText(r))")
        // Exactly one hand resolves (a one-handed max, no both-hands max). Name it: a bare
        // band would read as applying to both.
        case let (l?, nil):
            return String(localized: "L \(bandText(l))")
        case let (nil, r?):
            return String(localized: "R \(bandText(r))")
        }
    }

    /// True when this set will ask the two hands for different loads — what a caller
    /// checks before spending the width on two figures.
    static func targetDiffersByHand(_ set: SetPlan, in plan: SessionPlan,
                                    maxes: MaxTable) -> Bool {
        guard plan.handMode.sideCount > 1 else { return false }
        return targetBand(set, in: plan, side: .left, maxes: maxes)
            != targetBand(set, in: plan, side: .right, maxes: maxes)
    }

    /// Distinct grips in the plan that cannot resolve a target for at least ONE hand.
    /// Per hand, not per grip: a left-only max leaves the right hand with nothing, and a
    /// count that missed it would report a fully-loaded routine that is half unloaded.
    static func untargetedGripCount(_ plan: SessionPlan, maxes: MaxTable) -> Int {
        let live = plan.executable
        var seen = Set<String>()
        var count = 0
        for set in live.sets where !seen.contains(set.grip.key) {
            let sides: [Side] = live.handMode.sideCount > 1 ? [.left, .right] : [.both]
            if sides.contains(where: { targetBand(set, in: live, side: $0, maxes: maxes) == nil }) {
                seen.insert(set.grip.key)
                count += 1
            }
        }
        return count
    }

    /// Missing benchmarks only; explicit kg targets and deliberately untargeted sets do not qualify.
    static func missingBenchmarkGripCount(_ plan: SessionPlan, maxes: MaxTable) -> Int {
        let live = plan.executable
        var percentagePlan = live
        percentagePlan.sets = live.sets.filter {
            $0.targetBand == nil && targetPercent($0, in: live) != nil
        }
        return untargetedGripCount(percentagePlan, maxes: maxes)
    }

    static func bandText(_ band: ClosedRange<Double>, withUnit: Bool = true) -> String {
        let lo = band.lowerBound.formatted(.number.precision(.fractionLength(1)))
        let hi = band.upperBound.formatted(.number.precision(.fractionLength(1)))
        return withUnit ? String(localized: "\(lo)–\(hi) kg") : "\(lo)–\(hi)"
    }

    // MARK: - Peak intensity

    /// How hard a routine PRESCRIBES, as four bands a card colours itself from (Nuri,
    /// 2026-08-17). The UI owns the colours; this decides only WHICH band, so the
    /// boundaries are pinned by a test. **0.30 is `light` and 0.80 is `nearMax`** —
    /// calling a max effort moderate is the harmful direction to be wrong in.
    enum IntensityBand: Hashable, Sendable {
        /// No set prescribes a load anything can resolve — NOT "easy". See
        /// `peakIntensity(of:maxes:)` for what makes a set contribute.
        case unknown
        /// ≤ 30 % of max: the Emil-Abrahamsson end of the range this app was built around.
        case light
        /// Between 30 % and 80 %, exclusive at both ends.
        case moderate
        /// ≥ 80 %, INCLUDING above 100 %: a percentage measured against a stale max is
        /// still a max effort, and clamping it would hide the one case worth seeing.
        case nearMax

        /// nil → `unknown`, which is the whole reason the fraction is an Optional: a
        /// routine that prescribes no load is not a light routine.
        static func band(for fraction: Double?) -> IntensityBand {
            // Named, because a NaN fails both comparisons below and would otherwise be
            // reported as `moderate` — a confident answer about nothing.
            guard let fraction, fraction.isFinite else { return .unknown }
            if fraction <= 0.30 { return .light }
            if fraction >= 0.80 { return .nearMax }
            return .moderate
        }
    }

    /// The hardest load this routine prescribes, as a fraction of max (`0.85` is 85 %) —
    /// the difference between the daily 20 % no-hangs and a max day, in one number.
    ///
    /// Per set, in `targetBand`'s OWN precedence, so it cannot disagree with the loads
    /// the runner prescribes:
    /// - **A kg band** is divided by the max of each hand the set's reps use
    ///   (`handSequence`, with `MaxTable`'s fallbacks; a `.both` rep never uses a
    ///   synthesised sum). The HIGHEST fraction wins: the weaker hand names the day.
    /// - **A percentage band** IS the intensity and needs no max.
    ///
    /// A set that cannot resolve contributes NOTHING rather than blanking the answer;
    /// nil only when no set contributed (a routine trained by feel). The HI bound is the
    /// intensity; a lone endpoint normalizes to `lo...lo`. Nothing is clamped to 1.0: a
    /// 110 % prescription against a stale max belongs in `nearMax`, not hidden at 100 %.
    static func peakIntensity(of plan: SessionPlan, maxes: MaxTable) -> Double? {
        var peak: Double?
        // Only a finite fraction may raise the peak: a NaN wins no comparison, so an
        // unguarded max would keep it and poison the whole answer.
        func consider(_ fraction: Double) {
            guard fraction.isFinite else { return }
            peak = peak.map { Swift.max($0, fraction) } ?? fraction
        }
        // `executable`, like every other fold in this file: a zero-rep set is a row the
        // user has emptied out, and a band left on it prescribes nothing.
        let live = plan.executable
        for set in live.sets {
            if let kg = set.targetBand {
                for side in Set(handSequence(set, in: live)) {
                    guard let maxKg = maxes.max(grip: set.grip.key, side: side),
                          maxKg > 0 else { continue }
                    consider(kg.upperBound / maxKg)
                }
            } else if let percent = targetPercent(set, in: live) {
                // A kg set never falls back to a percentage it also carries: a typed
                // number is never second-guessed by arithmetic.
                consider(percent.upperBound)
            }
        }
        return peak
    }

    // MARK: - Formatting

    /// "45 s" · "21 min 30 s" · "19 min". Hand-built rather than
    /// `DateComponentsFormatter`, which spells numbers out in some locales and drops the
    /// seconds component in others.
    static func durationText(_ seconds: Int) -> String {
        let s = Swift.max(0, seconds)
        guard s >= 60 else { return String(localized: "\(s) s") }
        let minutes = s / 60
        let remainder = s % 60
        return remainder == 0 ? String(localized: "\(minutes) min") : String(localized: "\(minutes) min \(remainder) s")
    }

    /// "5:45" · "21:30" — the stopwatch form, for anything sitting next to a number.
    static func clockText(_ seconds: Int) -> String {
        let s = Swift.max(0, seconds)
        return "\(s / 60):" + String(format: "%02d", s % 60)
    }

    /// "≈21 min". TRUNCATES (integer division), deliberately: 1290 s rounds to 22 and
    /// would contradict every frozen copy string in the app. Under 60 s falls back to
    /// "N s", because "≈0 min" is not an estimate of anything.
    static func approxMinutes(_ seconds: Int) -> String {
        let s = Swift.max(0, seconds)
        guard s >= 60 else { return String(localized: "\(s) s") }
        return String(localized: "≈\(s / 60) min")
    }

    // MARK: - The four frozen line builders

    /// "6 sets · 36 pulls · ≈21 min"
    static func summaryLine(_ plan: SessionPlan) -> String {
        String(localized: "\(setsText(plan)) · \(pullsText(plan)) · \(approxMinutes(totalSeconds(plan)))")
    }

    /// "≈21 min · 36 pulls" — the nav subtitle, where the estimate comes FIRST because
    /// the commitment is the thing being decided while the chips are still one tap away.
    static func subtitleLine(_ plan: SessionPlan) -> String {
        String(localized: "\(approxMinutes(totalSeconds(plan))) · \(pullsText(plan))")
    }

    /// "6 sets · 36 pulls · 21:30 in total" — the exact figure, not the estimate.
    static func totalsLine(_ plan: SessionPlan) -> String {
        String(localized: "\(setsText(plan)) · \(pullsText(plan)) · \(clockText(totalSeconds(plan))) in total")
    }

    /// "18 pulls per side · 3:00 under tension per side". nil when the mode has one
    /// side, so the copy drops "per side" instead of dividing by a hand that isn't there.
    static func perSideLine(_ plan: SessionPlan) -> String? {
        guard let reps = repsPerSide(plan), let tension = tensionSecondsPerSide(plan) else { return nil }
        return String(localized: "\(reps) pulls per side · \(clockText(tension)) under tension per side")
    }

    private static func setsText(_ plan: SessionPlan) -> String {
        let n = setCount(plan)
        let word = n == 1 ? String(localized: "set") : String(localized: "sets")
        return String(localized: "\(n) \(word)")
    }

    private static func pullsText(_ plan: SessionPlan) -> String {
        let n = totalReps(plan)
        let word = n == 1 ? String(localized: "pull") : String(localized: "pulls")
        return String(localized: "\(n) \(word)")
    }
}
