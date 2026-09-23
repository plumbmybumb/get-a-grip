// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui.builder

import androidx.compose.runtime.saveable.Saver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import run.nuri.getagrip.engine.BlobCodec
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RoutineDraft

/// The builder's non-drawing decisions, lifted out so each is JVM-testable. Each rule was paid
/// for once on iOS and is easy to break: a stash outliving a Cancel returns as a ghost; a
/// coach step moving backwards runs away from a reader; a disabled Save with its reason a
/// screen away is a dead control.
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

    /// **The rescue copy COALESCES; it does not cancel-and-restart.** Restarting allocated a task
    /// per slider frame, each with a 500 ms sleep discarded a frame later. One pending write
    /// reading the LATEST draft gives the same result.
    const val stashDebounceMillis: Long = 500

    /// How long a removed set can be put back — the house 10 s window, shared with the store's undo,
    /// in place of a confirmation dialog.
    const val undoWindowMillis: Long = 10_000

    /// The guide when off: one past the closing card, so forward-only advancement never revives it.
    const val retiredCoachStep: Int = 7

    /// The closing card's step. The five numbered cards are 1…5.
    const val closingCoachStep: Int = 6

    const val coachTotal: Int = 5

    /// **The rescue copy is CREATE-ONLY**: restoring a stale draft into an edit could overwrite an
    /// unseen merge.
    fun stashes(mode: BuilderMode): Boolean = mode.isCreating

    /// Cancel IS undo, so it asks only whether there is anything to lose.
    fun isDirty(draft: RoutineDraft, seed: RoutineDraft): Boolean = draft != seed

    /// Save refuses exactly when the draft says why.
    fun canSave(draft: RoutineDraft): Boolean = draft.validationIssue == null

    /// **The live totals — or the validation reason the moment Save would refuse**, quoted under the
    /// title so a disabled Save's explanation sits right beside it.
    fun subtitle(draft: RoutineDraft): String =
        draft.validationIssue ?: PlanMath.subtitleLine(draft.plan)

    /// Where the guide starts. CREATING only: over an existing routine it narrates work already
    /// done, and saving a routine retires it anyway.
    fun startingCoachStep(mode: BuilderMode, guideDone: Boolean): Int =
        if (mode.isCreating && !guideDone) 1 else retiredCoachStep

    /// **Advance on a real value EDIT only, and only forwards**, so the guide never runs away from
    /// a reader and a retired guide never returns.
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

    /// Cheap string signatures: one comparable value per trigger, since the draft changes on every
    /// keystroke.
    fun rhythmSignature(draft: RoutineDraft): String =
        "${draft.plan.setBreakSeconds}|${draft.plan.handMode.rawValue}|${draft.plan.startingHand.rawValue}|" +
            "${draft.plan.waitForReleaseBeforeRest}"

    fun gripSignature(draft: RoutineDraft): String =
        draft.plan.sets.joinToString(",") { it.grip.key }

    fun repsSignature(draft: RoutineDraft): String =
        draft.plan.sets.joinToString(",") { it.repsPerSide.toString() }

    fun everyDaySignature(draft: RoutineDraft): String =
        "${draft.sessionsPerDay}|${draft.remindersEnabled}|${draft.reminders.joinToString("-") { it.slot }}"

    /// An hour of no-hangs is a CHOICE, not an error — this flags and never blocks.
    fun isVeryLong(draft: RoutineDraft): Boolean = PlanMath.totalSeconds(draft.plan) > 3600

    /// Whether the SETS disagree about their percentage band — see `overrideText`.
    fun percentBandsVary(draft: RoutineDraft): Boolean =
        draft.plan.executable.sets.map { it.targetPercentBand }.toSet().size > 1
}


/// **A draft across a configuration change** (builder and Today's import preview), using the
/// rescue stash's own JSON, so one serialization must stay lossless. A draft that will not
/// encode saves nothing and the screen reopens on its seed.
val RoutineDraftSaver: Saver<RoutineDraft, String> = Saver(
    save = { BlobCodec.encode(it) },
    restore = { text -> BlobCodec.decode(text) { RoutineDraft.fromJson(it) } },
)

/// The same, for a slot that may hold nothing — Today's import preview.
val OptionalRoutineDraftSaver: Saver<RoutineDraft?, String> = Saver(
    save = { draft -> draft?.let { BlobCodec.encode(it) } },
    restore = { text -> BlobCodec.decode(text) { RoutineDraft.fromJson(it) } },
)

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
