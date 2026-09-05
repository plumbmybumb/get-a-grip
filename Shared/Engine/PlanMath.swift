// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

import Foundation

/// One executable rep, fully resolved. EVERYTHING the app says about a routine — the
/// "≈21 min" in the nav subtitle, the per-side totals, the collapsed row's clock, and
/// the order M3 actually executes — is a fold over this ONE list. A parallel closed
/// form would eventually disagree with the runner, and the disagreement would be
/// invisible without a stopwatch.
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
    /// The load to aim for, in kilograms, ALREADY RESOLVED — percentages are baked down
    /// by `PlanMath.resolvingTargets` before a session starts, so nothing downstream of
    /// here needs a max lookup. nil when the routine sets no target for this set, or
    /// when its grip has no max on file.
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

    /// The load for ONE rep, resolved against the hand that rep is pulled with.
    ///
    /// This is `targetBand` plus the one fact a `SetPlan` cannot carry: a set covers both
    /// hands, and the two hands do not have the same max. So the per-hand answer has to
    /// be computed per REP, which is the only place `Side` is known — see `sequence`.
    ///
    /// An explicit kilogram band typed on the set stays hand-agnostic, deliberately. The
    /// precedence rule this file already states is that *a number a person typed must
    /// never be second-guessed by arithmetic*, and splitting a typed 8 kg into 8.4 and
    /// 7.6 because of a ratio derived elsewhere is exactly that. Per-hand loads come
    /// from percentages, which is the app's primary path anyway.
    static func targetBand(_ set: SetPlan, in plan: SessionPlan,
                           side: Side, maxes: MaxTable) -> ClosedRange<Double>? {
        targetBand(set, in: plan, maxKg: maxes.max(grip: set.grip.key, side: side))
    }

    /// Bake every percentage target down to the kilograms it means TODAY, keyed by each
    /// set's own grip.
    ///
    /// Called once when a session starts, and the result is what the runner executes and
    /// what the `WorkoutLog` freezes — so history records the load you were actually
    /// aiming at that morning, and recording a new max next month cannot retroactively
    /// rewrite what you were told to pull. That is the same freeze-at-save rule the
    /// routine's NAME already follows.
    ///
    /// **Hand-agnostic on purpose:** it fills the SET's band, which is what surfaces
    /// that speak about a set rather than a rep need. The per-rep, per-hand load is
    /// resolved in `sequence(for:maxes:)`, and that is what the runner and the log
    /// actually use.
    ///
    /// Sets whose grip has no max on file keep an empty band and simply show no target;
    /// the percentages stay in the returned plan untouched, so nothing is lost — this
    /// only ADDS the resolved kilograms.
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
    static func side(forRep r: Int, mode: HandMode, repsPerSide: Int) -> Side {
        let rep = Swift.max(0, r)
        switch mode {
        case .bothHands:
            return .both
        case .alternateEachRep:
            return rep.isMultiple(of: 2) ? mode.startSide : mode.startSide.other
        case .alternateEachSet:
            return rep < Swift.max(0, repsPerSide) ? mode.startSide : mode.startSide.other
        }
    }

    static func handSequence(_ set: SetPlan, in plan: SessionPlan) -> [Side] {
        (0..<repCount(set, mode: plan.handMode)).map {
            side(forRep: $0, mode: plan.handMode, repsPerSide: set.repsPerSide)
        }
    }

    // MARK: - The sequence

    /// The whole session, rep by rep. Zero-rep sets are dropped ENTIRELY — no lead-in,
    /// no set break — because a set with nothing in it is not a pause, it is a row the
    /// user has emptied out.
    ///
    /// **`maxes` is what makes loads per-hand.** A set covers both hands and a rep does
    /// not, so this loop is the only place that can ask "what is the target for the LEFT
    /// hand on this grip". Pass an empty table (the default) for every caller that wants
    /// the shape of a session rather than its loads — totals, estimates, the row clocks —
    /// none of which read `targetBand`.
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
                                   repsPerSide: set.repsPerSide)
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

    /// Wall-clock estimate. A fold over `sequence`, never a parallel formula.
    static func totalSeconds(_ plan: SessionPlan) -> Int {
        sequence(for: plan).reduce(0) { $0 + $1.totalSeconds }
    }

    /// Time under tension across the whole session (both sides together).
    static func tensionSeconds(_ plan: SessionPlan) -> Int {
        sequence(for: plan).reduce(0) { $0 + $1.holdSeconds }
    }

    static func totalReps(_ plan: SessionPlan) -> Int {
        sequence(for: plan).count
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
    /// `"L 5.5–8.0 · R 5.0–7.5 kg"` when they do not.
    ///
    /// THE one place per-hand load copy is decided. The builder, the wrap-up and the
    /// runner all speak through it, because three hand-rolled versions of "which hand
    /// gets what" is three chances to disagree about the number someone trains against.
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
        // Exactly one hand resolves — which happens when a max was recorded for ONE hand
        // and no both-hands max exists. Naming the hand is the point: the other one
        // genuinely has no target, and a bare band would read as applying to both.
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
            seen.insert(set.grip.key)
            let sides: [Side] = live.handMode.sideCount > 1 ? [.left, .right] : [.both]
            if sides.contains(where: { targetBand(set, in: live, side: $0, maxes: maxes) == nil }) {
                count += 1
            }
        }
        return count
    }

    static func bandText(_ band: ClosedRange<Double>, withUnit: Bool = true) -> String {
        let lo = band.lowerBound.formatted(.number.precision(.fractionLength(1)))
        let hi = band.upperBound.formatted(.number.precision(.fractionLength(1)))
        return withUnit ? String(localized: "\(lo)–\(hi) kg") : "\(lo)–\(hi)"
    }

    // MARK: - Peak intensity

    /// How hard a routine PRESCRIBES, as four bands rather than a bare number — the
    /// vocabulary a card colours itself from (Nuri, 2026-08-17: red at 80–100 %, orange
    /// between, green for light, bleu when nothing resolves). The band → colour mapping
    /// lives in the UI; this file decides only WHICH band, so the boundaries are pinned by
    /// a test rather than by a screenshot.
    ///
    /// **Boundary ownership is explicit and must stay that way: 0.30 is `light` and 0.80
    /// is `nearMax`.** Each edge belongs to the quieter side at the bottom and the louder
    /// side at the top, because the direction of the error matters: describing a max
    /// effort as merely moderate is the harmful way to be wrong.
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
            // A non-finite fraction is nobody's intensity. It is named rather than left
            // to fall through, because a NaN fails both comparisons below and would
            // otherwise be reported as `moderate` — a confident answer about nothing.
            guard let fraction, fraction.isFinite else { return .unknown }
            if fraction <= 0.30 { return .light }
            if fraction >= 0.80 { return .nearMax }
            return .moderate
        }
    }

    /// The hardest load this routine prescribes, as a fraction of max — `0.85` is 85 %.
    /// ONE number for the whole routine, because "how hard is this" is the difference
    /// between the daily 20 % no-hangs and a max day, and today that fact is only
    /// readable by opening the routine and reading six set rows.
    ///
    /// Per set, in `targetBand`'s OWN precedence — kilograms typed on the set, then the
    /// set's percentage, then the routine's — because an intensity that disagreed with the
    /// loads the runner will actually prescribe would be a second source of truth about
    /// the number somebody trains against:
    ///
    /// - **A kg band** is divided by the max of each hand this set's reps are actually
    ///   pulled with — `handSequence`, so which sides a mode covers is resolved in the one
    ///   place allowed to answer that, and a `bothHands` set never invents a left and a
    ///   right. `MaxTable` supplies the fallback rules unchanged: a `.left`/`.right` rep
    ///   may fall back to the both-hands max, a `.both` rep resolves against the
    ///   both-hands max ONLY and never a synthesised sum. **The HIGHEST fraction across
    ///   those hands wins** — the same 8 kg is a harder morning for the weaker hand, and
    ///   that hand's experience is what names the day.
    /// - **A percentage band** IS the intensity and needs no max at all: "80–100 %" states
    ///   how hard a routine is whether or not anybody has ever measured that grip.
    ///
    /// **A set that cannot resolve contributes NOTHING rather than making the whole answer
    /// unknown.** Five sets at 22 % plus one kg set on an unmeasured grip is still a 22 %
    /// routine, and blanking the badge would be the loudest possible way to say "no max
    /// on file". nil comes back only when NO set contributed, which is the honest reading
    /// of a routine that prescribes no load anywhere — Nuri's own daily routine, which
    /// deliberately goes by feel.
    ///
    /// The HI bound is the intensity throughout. A lone endpoint needs no special case:
    /// `SetPlan.band` normalizes one to `lo...lo`, so a from-only band reads as itself.
    /// Nothing is clamped to 1.0 — a 110 % prescription against a stale max is a real
    /// thing to author, and reporting it as 100 % would hide exactly the case worth
    /// seeing. See `IntensityBand`, which puts it in `nearMax` on purpose.
    static func peakIntensity(of plan: SessionPlan, maxes: MaxTable) -> Double? {
        var peak: Double?
        // Only a finite fraction may raise the peak: a NaN wins no comparison, so an
        // unguarded `Swift.max` would silently keep it and poison the whole routine's
        // answer with one nonsense band.
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
                // Deliberately NOT reached by a set carrying kilograms: the precedence
                // rule is that a typed number is never second-guessed by arithmetic, so a
                // kg band with no max on file falls through to nothing rather than back
                // to a percentage the set also happens to carry.
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
