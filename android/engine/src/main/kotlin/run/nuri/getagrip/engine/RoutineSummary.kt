// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.util.UUID

// TRANSLATION NOTE (from Sources/Store/RoutineSummary.swift): a store-free VALUE the
// routine card is drawn from, so it lives in `:engine` here and the card and the QR
// import preview fold the same arithmetic. The Android store hands in the same plain
// values iOS's `TemplateStore` reads from a `SessionTemplate`. Swift's
// `init(previewing:)` becomes `RoutineSummary.previewing(draft)`.

/// One rung of the grip ladder Today draws: a finger glyph over a per-side rep count,
/// one column per set, in the order they will be pulled.
data class LadderRung(
    /// The set index — order IS the identity. Two sets can be byte-identical and still be
    /// distinct rungs, and a `SetPlan.id` would re-animate the ladder on every replacement.
    val id: Int,
    val grip: GripSpec,
    val repsPerSide: Int,
)

/// Everything Today needs to draw a routine card, as a VALUE.
///
/// A value rather than a `SessionTemplate`, so the card is previewable without a store
/// and every derived number is computed once, not in a view body on every scroll frame.
data class RoutineSummary(
    val id: UUID,
    val name: String,
    val ladder: List<LadderRung>,
    val setCount: Int,
    val totalReps: Int,

    /// The edge, only when every set agrees on one. null drops it from `metaLine` rather
    /// than quoting one set's edge as if it were the routine's.
    val sharedEdgeMM: Int?,
    val estimatedSeconds: Int,
    val sessionsPerDay: Int,
    val completedToday: Int,
    val nextReminder: ReminderTime?,

    /// A climb logged today, if any. Carried on the SUMMARY rather than looked up by the
    /// card, so the card stays a pure value view — and so this cannot disagree with
    /// `TemplateStore.isDoneForToday`, shared by the routine's completion surfaces.
    val climbedToday: SessionKind? = null,

    /// Whether today is a benchmark day — measured maxes landed. Settles the card the
    /// same way a climb does, with its own copy.
    val benchmarkedToday: Boolean = false,

    /// A WHENEVER routine: no daily target, no reminders, never owed. The card drops
    /// the dots and the daily-guilt copy for it.
    val isOnDemand: Boolean = false,

    /// The routine's highest prescribed intensity as a fraction of max
    /// (`PlanMath.peakIntensity` against the store's `maxTable`); null when nothing
    /// resolves. Drives the `EdgeMark` colour and the spoken suffix. Defaulted so
    /// previews and store-free tests stay buildable.
    val peakIntensity: Double? = null,
) {

    /// **A climb — or a benchmark — meets the target.** Without this the card said "at the
    /// gym today" and still offered "Start first session" with no checkmark: one fact,
    /// three surfaces, one answer. A whenever routine has no target; doing it once today
    /// earns the checkmark.
    val targetMet: Boolean
        get() {
            if (isOnDemand) return climbedToday != null || benchmarkedToday || completedToday > 0
            return climbedToday != null || benchmarkedToday ||
                completedToday >= maxOf(1, sessionsPerDay)
        }

    /// The edge as the routine runs it: one number when every set agrees, a SPAN when
    /// they differ — never silence (Nuri, 2026-08-17). The span runs in LADDER order
    /// ("20–10 mm" for a ladder that thins out), because it states the protocol's
    /// direction; a first edge that is neither extreme falls back to ascending.
    val edgeLine: String?
        get() {
            val edges = ladder.map { it.grip.edgeMM }
            val first = edges.firstOrNull() ?: return null
            val lo = edges.min()
            val hi = edges.max()
            if (lo == hi) return L10n.tr("%d mm", hi)
            return if (first == hi) L10n.tr("%d–%d mm", hi, lo) else L10n.tr("%d–%d mm", lo, hi)
        }

    /// The grip the card's `EdgeMark` draws — the routine's SIGNATURE. Weighted by PULLS
    /// (`repsPerSide`; sides cancel), not set count: counting sets let two one-pull crimp
    /// sets outvote twelve four-finger pulls (2026-08-17). A tie goes to the ladder's
    /// first rung, the grip the session opens on.
    val signatureFingers: FingerSet?
        get() {
            if (ladder.isEmpty()) return null
            val weights = mutableMapOf<FingerSet, Int>()
            for (rung in ladder) {
                weights[rung.grip.fingers] =
                    (weights[rung.grip.fingers] ?: 0) + maxOf(1, rung.repsPerSide)
            }
            val best = weights.values.maxOrNull() ?: 0
            return ladder.firstOrNull { weights[it.grip.fingers] == best }?.grip?.fingers
        }

    /// "20 mm · 6 sets · 36 pulls · ≈21 min" — `PlanMath.summaryLine` with the edge
    /// line in front. Rebuilt from the parts here rather than carried as a string,
    /// because a summary is a value the card is previewed with and does not hold a plan.
    val metaLine: String
        get() {
            val parts = mutableListOf<String>()
            edgeLine?.let { parts.add(it) }
            val setWord = if (setCount == 1) L10n.tr("set") else L10n.tr("sets")
            parts.add(L10n.tr("%d %s", setCount, setWord))
            val pullWord = if (totalReps == 1) L10n.tr("pull") else L10n.tr("pulls")
            parts.add(L10n.tr("%d %s", totalReps, pullWord))
            parts.add(PlanMath.approxMinutes(estimatedSeconds))
            return parts.joinToString(" · ")
        }

    companion object {
        /// A summary for a routine that does not exist yet — the QR import preview.
        ///
        /// Every fold is `TemplateStore.summary(for:)`'s, verbatim, so the preview and the
        /// card it becomes quote the same edge, counts and estimate — two hand-written
        /// versions is how one screen promises ≈21 min and the next ≈19. A fresh `id`, no
        /// completions, reminder, climb or benchmark: those are facts about the READER's day.
        fun previewing(draft: RoutineDraft): RoutineSummary {
            val plan = draft.plan
            val ladder = plan.executable.sets.mapIndexed { index, set ->
                LadderRung(id = index, grip = set.grip, repsPerSide = set.repsPerSide)
            }
            return RoutineSummary(
                id = UUID.randomUUID(),
                name = plan.name,
                ladder = ladder,
                setCount = PlanMath.setCount(plan),
                totalReps = PlanMath.totalReps(plan),
                sharedEdgeMM = PlanMath.sharedEdgeMM(plan),
                estimatedSeconds = PlanMath.totalSeconds(plan),
                sessionsPerDay = draft.sessionsPerDay,
                completedToday = 0,
                nextReminder = null,
                isOnDemand = draft.isOnDemand,
                // Against an EMPTY table: a percentage band needs no max, so it colours
                // the mark as it will on Today; a kilogram band cannot resolve without the
                // reader's max and contributes nothing. The mark can only under-claim into
                // `unknown`, never over-claim a load. The sheet's footnote names the gap.
                peakIntensity = PlanMath.peakIntensity(plan, MaxTable()),
            )
        }
    }
}

/// One cell of the 14-day consistency strip.
data class DayRecord(
    val day: DayStamp,
    val completed: Int,
    val target: Int,

    /// false = earlier than any routine existed: a hairline, NOT a missed day. Empty
    /// circles there would say someone failed on days they did not own the app. Computed
    /// ONCE in the store from `trackingSince`, never per cell.
    val tracked: Boolean,

    /// The climb logged that day, if any — `null` on an ordinary day. Carried as the
    /// KIND rather than a Bool so the grid can tell a limit day from a volume one
    /// without a second lookup.
    val climb: SessionKind?,

    /// A benchmark day: fills the cell like a climb, but plain — the notch stays a
    /// climbing mark.
    val benchmarked: Boolean = false,
) {
    val id: Int get() = day.raw

    /// The continuous fill, not three buckets: it reduces to exactly full/half/empty at
    /// two sessions a day and stays truthful at three.
    ///
    /// **A climb fills the cell outright**, whatever the hang count beside it — the day
    /// is complete, so a half-full circle would contradict the sentence on Today.
    val fraction: Double
        get() {
            if (climb != null) return 1.0
            if (target <= 0) return 0.0
            return minOf(1.0, completed.toDouble() / target.toDouble())
        }
}
