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
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan

/// The builder's non-drawing decisions, lifted out so each is JVM-testable. Each rule was paid
/// for once on iOS and is easy to break: a stash outliving a Cancel returns as a ghost; a
/// disabled Save with its reason a screen away is a dead control.
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

    /// **The Rhythm page edits ONE routine-wide percentage.** `editable` spreads a routine band
    /// onto the sets; when every set carries the same percentage and no kilograms, fold it
    /// back up so page 1 shows it. Resolution-preserving, and `RoutineDraft.normalized`
    /// demotes it again on Save.
    fun promotingUniformBand(draft: RoutineDraft): RoutineDraft {
        val sets = draft.plan.sets
        if (draft.plan.targetPercentBand != null) return draft
        val band = sets.firstOrNull()?.targetPercentBand ?: return draft
        if (!sets.all { !it.hasTarget && it.targetPercentBand == band }) return draft
        return draft.copy(plan = draft.plan.copy(
            targetLoPercent = band.start,
            targetHiPercent = band.endInclusive,
            sets = sets.map { it.copy(targetLoPercent = null, targetHiPercent = null) },
        ))
    }

    /// What the builder opens on: legacy inheritance materialized, then a band every set
    /// shares folded up to the Rhythm page.
    fun opening(draft: RoutineDraft): RoutineDraft = promotingUniformBand(editable(draft))

    /// The Rhythm page's band, written: the routine takes it and every set's own target gives
    /// way — one band for all sets.
    fun withRoutineBand(draft: RoutineDraft, band: ClosedFloatingPointRange<Double>?): RoutineDraft =
        draft.copy(plan = draft.plan.copy(
            targetLoPercent = band?.start,
            targetHiPercent = band?.endInclusive,
            sets = draft.plan.sets.map {
                it.copy(targetLoPercent = null, targetHiPercent = null, targetLoKg = null, targetHiKg = null)
            },
        ))

    /// **Custom timing** on one set. ON seeds both overrides with the routine's current values,
    /// so the steppers start where the set already was; OFF returns it to the routine.
    fun withCustomTiming(set: SetPlan, plan: SessionPlan, on: Boolean): SetPlan =
        if (on) set.copy(holdSeconds = PlanMath.hold(set, plan), restSeconds = PlanMath.rest(set, plan))
        else set.copy(holdSeconds = null, restSeconds = null)

    /// **The rescue copy COALESCES; it does not cancel-and-restart.** Restarting allocated a task
    /// per slider frame, each with a 500 ms sleep discarded a frame later. One pending write
    /// reading the LATEST draft gives the same result.
    const val stashDebounceMillis: Long = 500

    /// How long a removed set can be put back — the house 10 s window, shared with the store's undo,
    /// in place of a confirmation dialog.
    const val undoWindowMillis: Long = 10_000

    /// **The rescue copy is CREATE-ONLY**: restoring a stale draft into an edit could overwrite an
    /// unseen merge.
    fun stashes(mode: BuilderMode): Boolean = mode.isCreating

    /// Cancel IS undo, so it asks only whether there is anything to lose.
    fun isDirty(draft: RoutineDraft, seed: RoutineDraft): Boolean = draft != seed

    /// Save refuses exactly when the draft says why.
    fun canSave(draft: RoutineDraft): Boolean = draft.validationIssue == null

    /// **The live totals — or the validation reason the moment Save would refuse**, quoted under the
    /// title so a disabled Save's explanation sits right beside it.
    ///
    /// Creating, on the Rhythm page, with nothing built yet: no line rather than a complaint
    /// about sets the person has not reached.
    fun subtitle(draft: RoutineDraft, creating: Boolean = false, page: BuilderPage = BuilderPage.Sets): String {
        if (creating && page == BuilderPage.Rhythm && draft.plan.executable.sets.isEmpty()) return ""
        return draft.validationIssue ?: PlanMath.subtitleLine(draft.plan)
    }

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
