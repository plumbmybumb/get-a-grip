// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.ui.builder.BuilderDraft
import run.nuri.getagrip.ui.builder.BuilderMode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/// The builder's non-drawing decisions: the draft-rescue policy, the dirty guard and the
/// validation copy.
///
/// They live in `BuilderDraft` precisely so they can be asserted here — each is easy to
/// break by accident and impossible to notice from a screenshot.
class BuilderDraftTests {

    // MARK: - The rescue copy

    /// **CREATE-ONLY.** Restoring a stale stash into an EDIT could overwrite a merge the
    /// user never saw.
    @Test
    fun onlyTheCreateModesStashADraft() {
        assertTrue(BuilderDraft.stashes(BuilderMode.FirstRun))
        assertTrue(BuilderDraft.stashes(BuilderMode.AddAnother))
        assertFalse(BuilderDraft.stashes(BuilderMode.Edit(UUID.randomUUID())))
    }

    /// The debounce is a COALESCING window, not a per-change task. The number is the
    /// contract: shortening it is a write per slider frame, lengthening it loses more work
    /// when the process dies.
    @Test
    fun theStashDebounceIsHalfASecond() {
        assertEquals(500L, BuilderDraft.stashDebounceMillis)
    }

    // MARK: - Dirty

    /// Cancel IS undo, so the only question Cancel asks is whether there is anything to
    /// lose. An untouched document must not raise a discard dialog.
    @Test
    fun anUntouchedDocumentIsNotDirty() {
        val seed = RoutineDraft.starter
        assertFalse(BuilderDraft.isDirty(seed, seed))
    }

    @Test
    fun aChangedNameMakesTheDocumentDirty() {
        val seed = RoutineDraft.starter
        val edited = seed.copy(plan = seed.plan.copy(name = "Rest day"))
        assertTrue(BuilderDraft.isDirty(edited, seed))
    }

    /// A set edited in place counts too — the dirty check has to see through the plan into
    /// the rows, or a whole afternoon of set work discards silently.
    @Test
    fun anEditedSetMakesTheDocumentDirty() {
        val seed = RoutineDraft.starter
        val sets = seed.plan.sets.toMutableList()
        sets[0] = sets[0].copy(repsPerSide = 8)
        val edited = seed.copy(plan = seed.plan.copy(sets = sets))
        assertTrue(BuilderDraft.isDirty(edited, seed))
    }

    // MARK: - Save and its reason

    /// A blank routine has no pulls in it, so Save refuses AND says why — and the subtitle
    /// is where that reason is quoted, right beside the control that refused.
    @Test
    fun aBlankRoutineCannotBeSavedAndTheSubtitleSaysWhy() {
        val blank = RoutineDraft.blank()
        assertFalse(BuilderDraft.canSave(blank))
        assertEquals(blank.validationIssue, BuilderDraft.subtitle(blank))
        assertNotEquals(PlanMath.subtitleLine(blank.plan), BuilderDraft.subtitle(blank))
    }

    /// With a real set the subtitle goes back to being the price of the edit — "≈21 min ·
    /// 36 pulls" — which is what makes it worth the space when nothing is wrong.
    @Test
    fun aValidRoutineShowsItsTotalsInsteadOfAReason() {
        val draft = RoutineDraft.starter
        assertTrue(BuilderDraft.canSave(draft))
        assertEquals(PlanMath.subtitleLine(draft.plan), BuilderDraft.subtitle(draft))
    }

    /// Reminders ON with no times is the other refusal, and it must reach the same one line.
    @Test
    fun remindersWithNoTimesRefuseTheSave() {
        val draft = RoutineDraft.starter.copy(remindersEnabled = true, reminders = emptyList())
        assertFalse(BuilderDraft.canSave(draft))
        assertEquals(draft.validationIssue, BuilderDraft.subtitle(draft))
    }

    // MARK: - What the rows show

    /// **A percentage shows on a collapsed row only where the sets DISAGREE.** A ramp is the
    /// whole shape of a max protocol and has to be readable straight down the list; six sets
    /// that all say 18–22 % is the card talking to itself.
    @Test
    fun aUniformBandDoesNotVaryButARampDoes() {
        val grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        val uniform = RoutineDraft.starter.let { draft ->
            draft.copy(
                plan = draft.plan.copy(
                    sets = draft.plan.sets.map {
                        it.copy(targetLoPercent = 0.20, targetHiPercent = 0.30)
                    },
                ),
            )
        }
        assertFalse(BuilderDraft.percentBandsVary(uniform))

        val ramp = RoutineDraft.maxDay
        assertTrue(BuilderDraft.percentBandsVary(ramp))

        // A single set cannot disagree with anything.
        val one = RoutineDraft.blank().let { draft ->
            draft.copy(plan = draft.plan.copy(sets = listOf(SetPlan(grip = grip))))
        }
        assertFalse(BuilderDraft.percentBandsVary(one))
    }

    /// An hour of no-hangs is a CHOICE, not an error — the advisory flags and never blocks,
    /// so a long routine still saves.
    @Test
    fun aVeryLongRoutineIsFlaggedButStillSaveable() {
        val long = RoutineDraft.starter.let { draft ->
            draft.copy(
                plan = draft.plan.copy(
                    sets = draft.plan.sets.map { it.copy(repsPerSide = 20) },
                ),
            )
        }
        assertTrue(BuilderDraft.isVeryLong(long))
        assertTrue(BuilderDraft.canSave(long))
    }
}
