// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RoutineDraft

/// The builder's decisions that are not drawings — lifted out of the screen so each can be
/// asserted in a JVM test instead of by tapping a phone.
///
/// Every rule here was paid for once already on iOS, and the reason they are pinned is that
/// they are easy to break by accident: a stash that outlives a Cancel comes back as a
/// ghost; a coach step that can move backwards runs away from someone still reading; a
/// disabled Save with its explanation a screenful away is a dead control.
object BuilderDraft {

    /// Materialize legacy inherited loads only in the local editor draft. No persistence
    /// occurs until Save, and explicit set targets keep their existing precedence.
    fun editable(draft: RoutineDraft): RoutineDraft {
        val band = draft.plan.targetPercentBand ?: return draft
        return draft.copy(plan = draft.plan.copy(
            sets = draft.plan.sets.map { set ->
                if (set.hasTarget || set.hasPercentTarget) set else set.copy(
                    targetLoPercent = band.start, targetHiPercent = band.endInclusive)
            },
            targetLoPercent = null, targetHiPercent = null,
        ))
    }

    /// **The rescue copy COALESCES; it does not cancel-and-restart.** Re-creating the
    /// debounce on every change allocated one task per slider frame, each with a 500 ms
    /// sleep thrown away a frame later. One pending write that reads the LATEST draft when
    /// it fires gives an identical result with none of the churn.
    const val stashDebounceMillis: Long = 500

    /// How long a removed set can be put back. The house window, shared with the store's
    /// own undo — a 10 s bar costs the confident nothing and beats a confirmation dialog
    /// people learn to dismiss blindly.
    const val undoWindowMillis: Long = 10_000

    /// Where the guide sits when it is off. One past the closing card, so `maxOf`-style
    /// advancement can never revive it.
    const val retiredCoachStep: Int = 7

    /// The closing card's step. The five numbered cards are 1…5.
    const val closingCoachStep: Int = 6

    const val coachTotal: Int = 5

    /// **The rescue copy is CREATE-ONLY.** Restoring a stale draft into an edit could
    /// overwrite a merge the user never saw.
    fun stashes(mode: BuilderMode): Boolean = mode.isCreating

    /// Cancel IS undo, so the only question a Cancel has to ask is whether there is
    /// anything to lose. A swipe that discards six sets of authored intent has no undo,
    /// unlike everything else in this document.
    fun isDirty(draft: RoutineDraft, seed: RoutineDraft): Boolean = draft != seed

    /// Save refuses exactly when the draft says why.
    fun canSave(draft: RoutineDraft): Boolean = draft.validationIssue == null

    /// **The live totals, ordinarily — the validation reason instead, the moment Save would
    /// refuse.** One line, quoted by the top bar under the title, so a disabled Save always
    /// has its explanation right beside it rather than at the bottom of a document that can
    /// run a dozen rows.
    fun subtitle(draft: RoutineDraft): String =
        draft.validationIssue ?: PlanMath.subtitleLine(draft.plan)

    /// Where the guide starts. CREATING only: the guide walks an empty document into a
    /// routine, and opening it over one that already exists narrates work already done. By
    /// the time anyone edits it has been walked anyway — saving a routine retires it.
    fun startingCoachStep(mode: BuilderMode, guideDone: Boolean): Int =
        if (mode.isCreating && !guideDone) 1 else retiredCoachStep

    /// **Advance on a real value EDIT only, and only forwards** — not on a scroll, not on
    /// expanding a row — so the guide can never run away from someone still reading, and a
    /// retired guide can never come back.
    fun advancing(current: Int, reaching: Int): Int =
        if (current < reaching && current <= closingCoachStep) reaching else current

    fun anchorForStep(step: Int): BuilderAnchor = when (step) {
        1 -> BuilderAnchor.Name
        2 -> BuilderAnchor.Rhythm
        3 -> BuilderAnchor.Sets
        4 -> BuilderAnchor.Totals
        5 -> BuilderAnchor.EveryDay
        else -> BuilderAnchor.Finish
    }

    /// Cheap string signatures, because the guide needs one comparable value per thing it
    /// can react to and the draft as a whole changes on every keystroke.
    fun rhythmSignature(draft: RoutineDraft): String =
        "${draft.plan.setBreakSeconds}|${draft.plan.handMode.rawValue}|${draft.plan.waitForReleaseBeforeRest}"

    fun gripSignature(draft: RoutineDraft): String =
        draft.plan.sets.joinToString(",") { it.grip.key }

    fun repsSignature(draft: RoutineDraft): String =
        draft.plan.sets.joinToString(",") { it.repsPerSide.toString() }

    fun everyDaySignature(draft: RoutineDraft): String =
        "${draft.sessionsPerDay}|${draft.remindersEnabled}|${draft.reminders.joinToString("-") { it.slot }}"

    /// An hour of no-hangs is a CHOICE, not an error — this flags and never blocks.
    fun isVeryLong(draft: RoutineDraft): Boolean = PlanMath.totalSeconds(draft.plan) > 3600

    /// Whether the SETS disagree about their percentage band. A ramp is the whole shape of a
    /// max protocol and has to be readable straight down the list — 50–60, 65–75, 80–90 —
    /// whereas six sets that all say 18–22 % is the card talking to itself.
    fun percentBandsVary(draft: RoutineDraft): Boolean =
        draft.plan.executable.sets.map { it.targetPercentBand }.toSet().size > 1
}


/** One pending rescue write reads the latest draft even during a continuous drag. */
internal class DraftStashCoalescer(
    private val scope: CoroutineScope,
    private val stashLatest: () -> Unit,
) {
    private var pending: Job? = null

    fun changed() {
        if (pending?.isActive == true) return
        pending = scope.launch {
            delay(BuilderDraft.stashDebounceMillis)
            stashLatest()
        }
    }
}
