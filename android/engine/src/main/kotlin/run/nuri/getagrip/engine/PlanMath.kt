// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.text.NumberFormat
import java.util.Locale

/// One executable rep, fully resolved. EVERYTHING the app says about a routine — the
/// "≈21 min" in the nav subtitle, the per-side totals, the collapsed row's clock, and
/// the order M3 actually executes — is a fold over this ONE list. A parallel closed
/// form would eventually disagree with the runner, and the disagreement would be
/// invisible without a stopwatch.
data class RepSlot(
    /// Index into `plan.executable.sets` — NOT into the authored sets, so a zero-rep
    /// set can never shift what a logged rep points at.
    val setIndex: Int,

    /// 0-based within its set, counting BOTH sides.
    val repIndex: Int,
    val side: Side,
    val grip: GripSpec,
    val holdSeconds: Int,

    /// The set's lead-in, on rep 0 only; 0 otherwise.
    val leadInBefore: Int,

    /// The intra-set rest, the SET BREAK on a set's last rep, or 0 at the very end of
    /// the session — nobody rests after the last pull, and counting that rest would put
    /// a minute of nothing into every estimate.
    val restAfter: Int,
    val isFirstOfSet: Boolean,
    val isLastOfSet: Boolean,

    /// The load to aim for, in kilograms, ALREADY RESOLVED — percentages are baked down
    /// per hand by `PlanMath.sequence` against the max table frozen at session start,
    /// so nothing downstream of here needs a live max lookup. null when the routine sets no target for this set, or
    /// when its grip has no max on file.
    val targetBand: ClosedFloatingPointRange<Double>? = null,
) {
    val id: String get() = "$setIndex.$repIndex"
    val totalSeconds: Int get() = leadInBefore + holdSeconds + restAfter
}

/// The routine's arithmetic and its frozen copy, as pure functions. No state, no
/// clock, no formatter that behaves differently in another locale.
object PlanMath {

    // MARK: - Inheritance resolution

    /// The ONLY readers of `SetPlan.holdSeconds`. Everything else asks for the resolved
    /// value, so "null means follow the routine" is expressed once.
    fun hold(set: SetPlan, plan: SessionPlan): Int = set.holdSeconds ?: plan.holdSeconds

    /// The ONLY readers of `SetPlan.restSeconds`.
    fun rest(set: SetPlan, plan: SessionPlan): Int = set.restSeconds ?: plan.restSeconds

    /// The ONE place a target load is decided, in strict precedence:
    ///
    /// 1. **kilograms typed on this set** — the most specific thing anyone can say, and
    ///    a number a person typed must never be second-guessed by arithmetic;
    /// 2. **this set's own percentage** of `maxKg`;
    /// 3. **the routine's percentage** of `maxKg` — the inheritance that makes "all six
    ///    sets at 17–22 %" a single edit, exactly like `hold` and `rest`;
    /// 4. **nothing**.
    ///
    /// A null `maxKg` collapses 2 and 3 to nothing rather than to a guess: there is no
    /// percentage of a max nobody has recorded, and inventing one would put a confident
    /// kilogram figure on a screen somebody trains against.
    fun targetBand(set: SetPlan, plan: SessionPlan, maxKg: Double?): ClosedFloatingPointRange<Double>? {
        set.targetBand?.let { return it }
        val percent = set.targetPercentBand ?: plan.targetPercentBand ?: return null
        if (maxKg == null || maxKg <= 0) return null
        val lo = roundedToHalfKg(maxKg * percent.start)
        val hi = roundedToHalfKg(maxKg * percent.endInclusive)
        return minOf(lo, hi)..maxOf(lo, hi)
    }

    /// The percentage band a set will USE, ignoring any kg override — what the builder
    /// shows as "following the routine". null when neither level sets one.
    fun targetPercent(set: SetPlan, plan: SessionPlan): ClosedFloatingPointRange<Double>? =
        set.targetPercentBand ?: plan.targetPercentBand

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
    fun targetBand(
        set: SetPlan,
        plan: SessionPlan,
        side: Side,
        maxes: MaxTable,
    ): ClosedFloatingPointRange<Double>? =
        targetBand(set, plan, maxes.max(set.grip.key, side))

    /// Bake every percentage target down to the kilograms it means TODAY, keyed by each
    /// set's own grip.
    ///
    /// A set-level projection for callers that need one band per set. This is not the
    /// session-start path: the runner freezes a MaxTable and sequence resolves each
    /// hand separately; its RepSummary freezes the resulting target.
    ///
    /// **Hand-agnostic on purpose:** it fills the SET's band, which is what surfaces
    /// that speak about a set rather than a rep need. The per-rep, per-hand load is
    /// resolved in `sequence(plan, maxes)`, and that is what the runner and the log
    /// actually use.
    ///
    /// Sets whose grip has no max on file keep an empty band and simply show no target;
    /// the percentages stay in the returned plan untouched, so nothing is lost — this
    /// only ADDS the resolved kilograms.
    /// Retained set-level utility; current training freezes a MaxTable and resolves
    /// each rep through sequence instead. No current screen depends on this helper.
    fun resolvingTargets(plan: SessionPlan, maxes: MaxTable): SessionPlan = plan.copy(
        sets = plan.sets.map { set ->
            val band = targetBand(set, plan, Side.both, maxes) ?: return@map set
            set.copy(targetLoKg = band.start, targetHiKg = band.endInclusive)
        }
    )

    /// THE single ×2 resolver: `repsPerSide × mode.sideCount`. Nowhere else in the app
    /// may multiply by a side count — that is how a routine quietly becomes twice as
    /// long as its own summary claims.
    fun repCount(set: SetPlan, mode: HandMode): Int = maxOf(0, set.repsPerSide) * mode.sideCount

    // MARK: - Sides

    /// Which hand pulls rep `r` of a set. `setIndex` is deliberately NOT a parameter:
    /// alternation RESETS to the start side at every set boundary, so the answer depends
    /// only on the position WITHIN the set, and a setIndex argument would be dead weight
    /// that invites someone to "use" it and break the rule.
    ///
    ///   `alternateEachRep`, 3/side → L R L R L R
    ///   `alternateEachSet`, 3/side → L L L R R R
    ///   `bothHands`,        3/side → B B B
    fun side(forRep: Int, mode: HandMode, repsPerSide: Int): Side {
        val rep = maxOf(0, forRep)
        return when (mode) {
            HandMode.bothHands -> Side.both
            HandMode.alternateEachRep -> if (rep % 2 == 0) mode.startSide else mode.startSide.other
            HandMode.alternateEachSet ->
                if (rep < maxOf(0, repsPerSide)) mode.startSide else mode.startSide.other
        }
    }

    fun handSequence(set: SetPlan, plan: SessionPlan): List<Side> =
        (0 until repCount(set, plan.handMode)).map {
            side(forRep = it, mode = plan.handMode, repsPerSide = set.repsPerSide)
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
    fun sequence(plan: SessionPlan, maxes: MaxTable = MaxTable()): List<RepSlot> {
        val live = plan.executable
        val slots = mutableListOf<RepSlot>()
        for ((setIndex, set) in live.sets.withIndex()) {
            val reps = repCount(set, live.handMode)
            if (reps <= 0) continue
            val holdSeconds = hold(set, live)
            val restSeconds = rest(set, live)
            val isLastSet = setIndex == live.sets.size - 1
            for (repIndex in 0 until reps) {
                val isFirst = repIndex == 0
                val isLast = repIndex == reps - 1
                val repSide = side(forRep = repIndex, mode = live.handMode, repsPerSide = set.repsPerSide)
                slots.add(
                    RepSlot(
                        setIndex = setIndex,
                        repIndex = repIndex,
                        side = repSide,
                        grip = set.grip,
                        holdSeconds = holdSeconds,
                        leadInBefore = if (isFirst) live.leadInSeconds else 0,
                        // A band already BAKED onto the set wins: it is either a number the
                        // user typed or one `resolvingTargets` froze, and both outrank a
                        // fresh lookup. Otherwise resolve for THIS hand.
                        targetBand = set.targetBand ?: targetBand(set, live, repSide, maxes),
                        restAfter = if (isLast) (if (isLastSet) 0 else live.setBreakSeconds) else restSeconds,
                        isFirstOfSet = isFirst,
                        isLastOfSet = isLast,
                    )
                )
            }
        }
        return slots
    }

    // MARK: - Totals

    /// One set, EXCLUDING its trailing break: `leadIn + reps·hold + (reps−1)·rest`.
    /// The break belongs to the gap between two sets, not to either of them, which is
    /// what makes the last set's row clock honest.
    fun setSeconds(set: SetPlan, plan: SessionPlan): Int {
        val reps = repCount(set, plan.handMode)
        if (reps <= 0) return 0
        return plan.leadInSeconds + reps * hold(set, plan) + (reps - 1) * rest(set, plan)
    }

    /// Fold the same resolved set timing used by `sequence`, without allocating every
    /// pull just to show a builder total. Zero-pull sets contribute no lead-in or break.
    fun totalSeconds(plan: SessionPlan): Int {
        val live = plan.executable
        return live.sets.sumOf { setSeconds(it, live) } + maxOf(0, live.sets.size - 1) * live.setBreakSeconds
    }

    /// Time under tension across the whole session (both sides together).
    fun tensionSeconds(plan: SessionPlan): Int =
        plan.executable.sets.sumOf { repCount(it, plan.handMode) * hold(it, plan) }

    fun totalReps(plan: SessionPlan): Int = plan.executable.sets.sumOf { repCount(it, plan.handMode) }

    fun setCount(plan: SessionPlan): Int = plan.executable.sets.size

    /// null when `handMode.sideCount == 1` — the copy then drops "per side" rather than
    /// lying about a division that did not happen.
    fun repsPerSide(plan: SessionPlan): Int? {
        val sides = plan.handMode.sideCount
        if (sides <= 1) return null
        return totalReps(plan) / sides
    }

    fun tensionSecondsPerSide(plan: SessionPlan): Int? {
        val sides = plan.handMode.sideCount
        if (sides <= 1) return null
        return tensionSeconds(plan) / sides
    }

    fun tensionSecondsPerSide(set: SetPlan, plan: SessionPlan): Int? =
        tensionSecondsPerSide(set, plan.handMode, hold(set, plan))

    /// The same figure for a caller that has already resolved the hold — the grip card,
    /// which deliberately does not hold a whole `SessionPlan`. Delegated to by the
    /// variant above, so the two can never drift.
    fun tensionSecondsPerSide(set: SetPlan, mode: HandMode, resolvedHold: Int): Int? {
        if (mode.sideCount <= 1) return null
        return maxOf(0, set.repsPerSide) * resolvedHold
    }

    /// The edge, only when every set agrees on one. null → the meta line omits it rather
    /// than naming one of several.
    fun sharedEdgeMM(plan: SessionPlan): Int? {
        val edges = plan.executable.sets.map { it.grip.edgeMM }
        val first = edges.firstOrNull() ?: return null
        return if (edges.all { it == first }) first else null
    }

    // MARK: - Per-grip totals

    data class GripTotals(
        val grip: GripSpec,
        /// Summed across every set using this grip, in reps PER SIDE.
        val repsPerSide: Int,
        val totalReps: Int,
        val tensionSeconds: Int,
    ) {
        /// Computed, never stored: a stored copy of derived copy is a second source of
        /// truth, and this one would be the string a user reads.
        val line: String
            get() {
                val sides = if (repsPerSide > 0) totalReps / repsPerSide else 1
                if (sides <= 1) {
                    val pullWord = if (totalReps == 1) L10n.tr("pull") else L10n.tr("pulls")
                    val pulls = L10n.tr("%d %s", totalReps, pullWord)
                    return L10n.tr("%s · %s under tension", pulls, clockText(tensionSeconds))
                }
                return L10n.tr(
                    "%d per side · %s under tension per side",
                    repsPerSide,
                    clockText(tensionSeconds / sides),
                )
            }
    }

    /// Merged by CANONICAL KEY, in first-appearance order. Order is first-appearance and
    /// not sorted because the routine's own order is the thing the user recognises.
    fun gripTotals(plan: SessionPlan): List<GripTotals> {
        val live = plan.executable
        val order = mutableListOf<String>()
        val merged = mutableMapOf<String, GripTotals>()
        for (set in live.sets) {
            val reps = repCount(set, live.handMode)
            if (reps <= 0) continue
            val key = set.grip.key
            val tension = reps * hold(set, live)
            val existing = merged[key]
            if (existing != null) {
                merged[key] = existing.copy(
                    repsPerSide = existing.repsPerSide + set.repsPerSide,
                    totalReps = existing.totalReps + reps,
                    tensionSeconds = existing.tensionSeconds + tension,
                )
            } else {
                order.add(key)
                merged[key] = GripTotals(
                    grip = set.grip,
                    repsPerSide = set.repsPerSide,
                    totalReps = reps,
                    tensionSeconds = tension,
                )
            }
        }
        return order.mapNotNull { merged[it] }
    }

    // MARK: - Load

    /// null when `maxKg <= 0`: there is no percentage of a max nobody has recorded, and
    /// a 0 % caption is worse than no caption.
    fun percentOfMax(kg: Double, maxKg: Double): Double? {
        if (maxKg <= 0) return null
        return kg / maxKg
    }

    /// The Emil-Abrahamsson low-intensity band by default: 20–30 % of max.
    fun suggestedBand(
        maxKg: Double,
        percent: ClosedFloatingPointRange<Double> = 0.20..0.30,
    ): ClosedFloatingPointRange<Double>? {
        if (maxKg <= 0) return null
        val lo = roundedToHalfKg(maxKg * percent.start)
        val hi = roundedToHalfKg(maxKg * percent.endInclusive)
        return minOf(lo, hi)..maxOf(lo, hi)
    }

    /// Half-kilo steps, because that is the resolution a person can actually hold — and
    /// because a target of 7.3 kg reads as a measurement rather than as a suggestion.
    ///
    /// TRANSLATION NOTE: Swift's `Double.rounded()` is half-away-from-zero. Neither
    /// `kotlin.math.round` (`Math.rint`, half to EVEN) nor `Math.round` (half UP, so
    /// −0.5 → 0) matches it, and both differ on exactly the tie a half-kilo grid
    /// manufactures, so the rule is written out.
    fun roundedToHalfKg(kg: Double): Double = roundedHalfAwayFromZero(kg * 2) / 2

    private fun roundedHalfAwayFromZero(v: Double): Double =
        if (v < 0) -Math.floor(-v + 0.5) else Math.floor(v + 0.5)

    // MARK: - Per-hand loads, as words

    /// What this set asks of each hand: `"5.5–8.0 kg"` when the hands agree, and
    /// `"L 5.5–8.0 · R 5.0–7.5 kg"` when they do not.
    ///
    /// THE one place per-hand load copy is decided. The builder, the wrap-up and the
    /// runner all speak through it, because three hand-rolled versions of "which hand
    /// gets what" is three chances to disagree about the number someone trains against.
    ///
    /// null when neither hand resolves — the caller then says "no max" rather than
    /// printing an empty band.
    fun targetText(set: SetPlan, plan: SessionPlan, maxes: MaxTable): String? {
        // One side means the hands are on the edge together; there is no per-hand
        // question to answer and "L … · R …" would invent one.
        if (plan.handMode.sideCount <= 1) {
            return targetBand(set, plan, Side.both, maxes)?.let { bandText(it) }
        }
        val left = targetBand(set, plan, Side.left, maxes)
        val right = targetBand(set, plan, Side.right, maxes)
        return when {
            left == null && right == null -> null
            left != null && right != null && left == right -> bandText(left)
            left != null && right != null ->
                L10n.tr("L %s · R %s", bandText(left, withUnit = false), bandText(right))
            // Exactly one hand resolves — which happens when a max was recorded for ONE hand
            // and no both-hands max exists. Naming the hand is the point: the other one
            // genuinely has no target, and a bare band would read as applying to both.
            left != null -> L10n.tr("L %s", bandText(left))
            else -> L10n.tr("R %s", bandText(right!!))
        }
    }

    /// True when this set will ask the two hands for different loads — what a caller
    /// checks before spending the width on two figures.
    fun targetDiffersByHand(set: SetPlan, plan: SessionPlan, maxes: MaxTable): Boolean {
        if (plan.handMode.sideCount <= 1) return false
        return targetBand(set, plan, Side.left, maxes) != targetBand(set, plan, Side.right, maxes)
    }

    /// Distinct grips in the plan that cannot resolve a target for at least ONE hand.
    /// Per hand, not per grip: a left-only max leaves the right hand with nothing, and a
    /// count that missed it would report a fully-loaded routine that is half unloaded.
    fun untargetedGripCount(plan: SessionPlan, maxes: MaxTable): Int {
        val live = plan.executable
        val seen = mutableSetOf<String>()
        var count = 0
        for (set in live.sets) {
            if (seen.contains(set.grip.key)) continue
            val sides = if (live.handMode.sideCount > 1) listOf(Side.left, Side.right) else listOf(Side.both)
            if (sides.any { targetBand(set, live, it, maxes) == null }) {
                seen.add(set.grip.key)
                count += 1
            }
        }
        return count
    }

    /// TRANSLATION NOTE: Swift's `.formatted(.number.precision(.fractionLength(1)))` is
    /// LOCALE-SENSITIVE display text — a French phone reads "8,0–12,0 kg" — so its twin
    /// is `NumberFormat` on the DEFAULT locale, not `Fmt.fixed` (which is the locale-free
    /// wire formatter and belongs to keys and exports). Tests that assert these strings
    /// must pin `Locale.US`.
    fun missingBenchmarkGripCount(plan: SessionPlan, maxes: MaxTable): Int {
        val live = plan.executable
        return untargetedGripCount(live.copy(sets = live.sets.filter {
            it.targetBand == null && targetPercent(it, live) != null
        }), maxes)
    }

    fun bandText(band: ClosedFloatingPointRange<Double>, withUnit: Boolean = true): String {
        val formatter = NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        val lo = formatter.format(band.start)
        val hi = formatter.format(band.endInclusive)
        return if (withUnit) L10n.tr("%s–%s kg", lo, hi) else "$lo–$hi"
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
    enum class IntensityBand {
        /// No set prescribes a load anything can resolve — NOT "easy". See
        /// `peakIntensity` for what makes a set contribute.
        unknown,

        /// ≤ 30 % of max: the Emil-Abrahamsson end of the range this app was built around.
        light,

        /// Between 30 % and 80 %, exclusive at both ends.
        moderate,

        /// ≥ 80 %, INCLUDING above 100 %: a percentage measured against a stale max is
        /// still a max effort, and clamping it would hide the one case worth seeing.
        nearMax;

        companion object {
            /// null → `unknown`, which is the whole reason the fraction is nullable: a
            /// routine that prescribes no load is not a light routine.
            fun band(fraction: Double?): IntensityBand {
                // A non-finite fraction is nobody's intensity. It is named rather than left
                // to fall through, because a NaN fails both comparisons below and would
                // otherwise be reported as `moderate` — a confident answer about nothing.
                if (fraction == null || !fraction.isFinite()) return unknown
                if (fraction <= 0.30) return light
                if (fraction >= 0.80) return nearMax
                return moderate
            }
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
    ///   right. `MaxTable` supplies the fallback rules unchanged: a `left`/`right` rep
    ///   may fall back to the both-hands max, a `both` rep resolves against the
    ///   both-hands max ONLY and never a synthesised sum. **The HIGHEST fraction across
    ///   those hands wins** — the same 8 kg is a harder morning for the weaker hand, and
    ///   that hand's experience is what names the day.
    /// - **A percentage band** IS the intensity and needs no max at all: "80–100 %" states
    ///   how hard a routine is whether or not anybody has ever measured that grip.
    ///
    /// **A set that cannot resolve contributes NOTHING rather than making the whole answer
    /// unknown.** Five sets at 22 % plus one kg set on an unmeasured grip is still a 22 %
    /// routine, and blanking the badge would be the loudest possible way to say "no max
    /// on file". null comes back only when NO set contributed, which is the honest reading
    /// of a routine that prescribes no load anywhere — Nuri's own daily routine, which
    /// deliberately goes by feel.
    ///
    /// The HI bound is the intensity throughout. A lone endpoint needs no special case:
    /// `SetPlan.band` normalizes one to `lo..lo`, so a from-only band reads as itself.
    /// Nothing is clamped to 1.0 — a 110 % prescription against a stale max is a real
    /// thing to author, and reporting it as 100 % would hide exactly the case worth
    /// seeing. See `IntensityBand`, which puts it in `nearMax` on purpose.
    fun peakIntensity(plan: SessionPlan, maxes: MaxTable): Double? {
        var peak: Double? = null
        // Only a finite fraction may raise the peak: a NaN wins no comparison, so an
        // unguarded `max` would silently keep it and poison the whole routine's answer
        // with one nonsense band.
        fun consider(fraction: Double) {
            if (!fraction.isFinite()) return
            val current = peak
            peak = if (current == null) fraction else maxOf(current, fraction)
        }
        // `executable`, like every other fold in this file: a zero-rep set is a row the
        // user has emptied out, and a band left on it prescribes nothing.
        val live = plan.executable
        for (set in live.sets) {
            val kg = set.targetBand
            if (kg != null) {
                for (side in handSequence(set, live).toSet()) {
                    val maxKg = maxes.max(set.grip.key, side) ?: continue
                    if (maxKg <= 0) continue
                    consider(kg.endInclusive / maxKg)
                }
            } else {
                // Deliberately NOT reached by a set carrying kilograms: the precedence
                // rule is that a typed number is never second-guessed by arithmetic, so a
                // kg band with no max on file falls through to nothing rather than back
                // to a percentage the set also happens to carry.
                val percent = targetPercent(set, live) ?: continue
                consider(percent.endInclusive)
            }
        }
        return peak
    }

    // MARK: - Formatting

    /// "45 s" · "21 min 30 s" · "19 min". Hand-built rather than a platform components
    /// formatter, which spells numbers out in some locales and drops the seconds
    /// component in others.
    fun durationText(seconds: Int): String {
        val s = maxOf(0, seconds)
        if (s < 60) return L10n.tr("%d s", s)
        val minutes = s / 60
        val remainder = s % 60
        return if (remainder == 0) L10n.tr("%d min", minutes)
        else L10n.tr("%d min %d s", minutes, remainder)
    }

    /// "5:45" · "21:30" — the stopwatch form, for anything sitting next to a number.
    fun clockText(seconds: Int): String {
        val s = maxOf(0, seconds)
        return "${s / 60}:" + (s % 60).toString().padStart(2, '0')
    }

    /// "≈21 min". TRUNCATES (integer division), deliberately: 1290 s rounds to 22 and
    /// would contradict every frozen copy string in the app. Under 60 s falls back to
    /// "N s", because "≈0 min" is not an estimate of anything.
    fun approxMinutes(seconds: Int): String {
        val s = maxOf(0, seconds)
        if (s < 60) return L10n.tr("%d s", s)
        return L10n.tr("≈%d min", s / 60)
    }

    // MARK: - The four frozen line builders

    /// "6 sets · 36 pulls · ≈21 min"
    fun summaryLine(plan: SessionPlan): String = L10n.tr(
        "%s · %s · %s",
        setsText(plan),
        pullsText(plan),
        approxMinutes(totalSeconds(plan)),
    )

    /// "≈21 min · 36 pulls" — the nav subtitle, where the estimate comes FIRST because
    /// the commitment is the thing being decided while the chips are still one tap away.
    fun subtitleLine(plan: SessionPlan): String =
        L10n.tr("%s · %s", approxMinutes(totalSeconds(plan)), pullsText(plan))

    /// "6 sets · 36 pulls · 21:30 in total" — the exact figure, not the estimate.
    fun totalsLine(plan: SessionPlan): String = L10n.tr(
        "%s · %s · %s in total",
        setsText(plan),
        pullsText(plan),
        clockText(totalSeconds(plan)),
    )

    /// "18 pulls per side · 3:00 under tension per side". null when the mode has one
    /// side, so the copy drops "per side" instead of dividing by a hand that isn't there.
    fun perSideLine(plan: SessionPlan): String? {
        val reps = repsPerSide(plan) ?: return null
        val tension = tensionSecondsPerSide(plan) ?: return null
        return L10n.tr("%d pulls per side · %s under tension per side", reps, clockText(tension))
    }

    private fun setsText(plan: SessionPlan): String {
        val n = setCount(plan)
        val word = if (n == 1) L10n.tr("set") else L10n.tr("sets")
        return L10n.tr("%d %s", n, word)
    }

    private fun pullsText(plan: SessionPlan): String {
        val n = totalReps(plan)
        val word = if (n == 1) L10n.tr("pull") else L10n.tr("pulls")
        return L10n.tr("%d %s", n, word)
    }
}
