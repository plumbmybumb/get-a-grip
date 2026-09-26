// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.compose.runtime.Immutable
import java.util.UUID
import run.nuri.getagrip.engine.HandMode
import run.nuri.getagrip.engine.ReminderTime
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.engine.Side

/// **THE ONE WRITER of the draft, as a value every section can hold.**
///
/// A section asks for a TRANSFORM (`update { it.copy(…) }`) rather than being handed the
/// draft and a setter. Handed the draft, a section's callbacks close over it, so they are
/// new objects on every keystroke anywhere in the document and the section can never be
/// skipped. The host remembers ONE of these for the document's whole life, and each
/// transform reads the CURRENT draft when it runs — so a callback built three edits ago
/// still writes on top of the latest one.
///
/// The iOS twin is the keypath projection that replaced a subscript binding there: 15 → 3
/// view bodies per keystroke. This is the same cure in Compose's terms.
typealias DraftUpdate = ((RoutineDraft) -> RoutineDraft) -> Unit

// MARK: - What each section actually reads
//
// Each slice is the handful of fields ONE section draws, folded out of the draft by the host.
// They are `@Immutable` data classes, so Compose compares them by value: typing the routine's
// name leaves every slice below it equal, and every section below it skipped.

/// The plan-level fields a set row resolves its numbers against — the inherited hold, rest
/// and target band, the ×2 of two hands, and the lead-in inside a set's clock. Nothing else
/// about the routine reaches a row, which is what lets five rows sit still while a sixth is
/// edited.
@Immutable
data class SetRowContext(
    val handMode: HandMode = HandMode.alternateEachRep,
    val holdSeconds: Int = 10,
    val restSeconds: Int = 20,
    val leadInSeconds: Int = 5,
    /// The Rhythm page's band, which every set without its own follows.
    val targetLoPercent: Double? = null,
    val targetHiPercent: Double? = null,
) {
    /// A plan carrying ONLY those fields, so every `PlanMath` call a row makes goes through
    /// the one resolver the runner uses rather than a second copy of its arithmetic. Built
    /// once per context, not per read. Not part of equality (it is derived), and its name is
    /// blank rather than the localized default nothing here draws.
    val plan: SessionPlan = SessionPlan(
        name = "",
        handMode = handMode,
        holdSeconds = holdSeconds,
        restSeconds = restSeconds,
        leadInSeconds = leadInSeconds,
        targetLoPercent = targetLoPercent,
        targetHiPercent = targetHiPercent,
    )

    companion object {
        fun of(plan: SessionPlan): SetRowContext = SetRowContext(
            plan.handMode, plan.holdSeconds, plan.restSeconds, plan.leadInSeconds,
            plan.targetLoPercent, plan.targetHiPercent,
        )
    }
}

/// The Rhythm page's timing and hands.
@Immutable
data class RhythmValues(
    val holdSeconds: Int,
    val restSeconds: Int,
    val setBreakSeconds: Int,
    val waitForReleaseBeforeRest: Boolean,
    val handMode: HandMode,
    val startingHand: Side,
    /// The FIRST executable set's pulls, for the hand-order strip — the one the reader is
    /// about to do. `executable` so an emptied-out row cannot decide it.
    val firstRepsPerSide: Int,
) {
    companion object {
        fun of(plan: SessionPlan): RhythmValues = RhythmValues(
            holdSeconds = plan.holdSeconds,
            restSeconds = plan.restSeconds,
            setBreakSeconds = plan.setBreakSeconds,
            waitForReleaseBeforeRest = plan.waitForReleaseBeforeRest,
            handMode = plan.handMode,
            startingHand = plan.startingHand,
            firstRepsPerSide = plan.executable.sets.firstOrNull()?.repsPerSide ?: 6,
        )
    }
}

/// HOW OFTEN.
@Immutable
data class EveryDayValues(
    val isOnDemand: Boolean,
    val sessionsPerDay: Int,
    val reminders: List<ReminderTime>,
    val remindersEnabled: Boolean,
) {
    companion object {
        fun of(draft: RoutineDraft): EveryDayValues =
            EveryDayValues(draft.isOnDemand, draft.sessionsPerDay, draft.reminders, draft.remindersEnabled)
    }
}

/// FINE TUNING.
@Immutable
data class FineTuningValues(
    val thresholdKg: Double,
    val pausesOutsideTargetBand: Boolean,
    val leadInSeconds: Int,
) {
    companion object {
        fun of(plan: SessionPlan): FineTuningValues =
            FineTuningValues(plan.thresholdKg, plan.pausesOutsideTargetBand, plan.leadInSeconds)
    }
}

// MARK: - Set edits, by id

/// Every set edit is addressed by ID, never by an index captured when the callback was
/// built: an edit in flight while the list reorders must land on the set it came from.
internal fun RoutineDraft.withSets(transform: (List<SetPlan>) -> List<SetPlan>): RoutineDraft =
    copy(plan = plan.copy(sets = transform(plan.sets)))

internal fun RoutineDraft.replacingSet(updated: SetPlan): RoutineDraft =
    withSets { sets -> sets.map { if (it.id == updated.id) updated else it } }

/// Reordering, as a value. Out of bounds is a no-op rather than a crash: the buttons that
/// call it are already disabled at the ends, and a belt is cheap.
internal fun RoutineDraft.movingSet(id: UUID, offset: Int): RoutineDraft {
    val index = plan.sets.indexOfFirst { it.id == id }
    val target = index + offset
    if (index < 0 || target !in plan.sets.indices) return this
    return withSets { sets -> sets.toMutableList().apply { add(target, removeAt(index)) } }
}

/// The copy goes directly BELOW its original, under a caller-minted id so the host can open
/// it in the same breath.
internal fun RoutineDraft.duplicatingSet(id: UUID, copyID: UUID): RoutineDraft {
    val index = plan.sets.indexOfFirst { it.id == id }
    if (index < 0) return this
    return withSets { sets -> sets.toMutableList().apply { add(index + 1, sets[index].copy(id = copyID)) } }
}
