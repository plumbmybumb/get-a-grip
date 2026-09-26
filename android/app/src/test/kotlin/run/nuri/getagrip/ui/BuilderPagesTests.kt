// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.PlanMath
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.ui.builder.BuilderDraft
import run.nuri.getagrip.ui.builder.BuilderPage
import run.nuri.getagrip.ui.builder.SetRowContext
import run.nuri.getagrip.ui.builder.loadText
import run.nuri.getagrip.ui.builder.timingOverrideText
import run.nuri.getagrip.ui.components.LadderMath

/// The three-page builder's non-drawing rules: the ladder's step, the routine-wide band, Custom
/// timing and the subtitle. Each is what a screenshot cannot show.
class BuilderPagesTests {
    private val hold = listOf(1.0, 3.0, 5.0, 7.0, 10.0, 12.0, 15.0, 20.0, 30.0, 45.0, 60.0)

    @Test fun theLadderStepsToItsNeighboursEvenFromATypedValue() {
        assertEquals(12.0, LadderMath.neighbour(hold, 10.0, up = true))
        assertEquals(7.0, LadderMath.neighbour(hold, 10.0, up = false))
        // A typed 22 sits between stops and steps to either side of itself.
        assertEquals(30.0, LadderMath.neighbour(hold, 22.0, up = true))
        assertEquals(20.0, LadderMath.neighbour(hold, 22.0, up = false))
        // The ends disable their button: nothing below 1, nothing above 60.
        assertNull(LadderMath.neighbour(hold, 1.0, up = false))
        assertNull(LadderMath.neighbour(hold, 60.0, up = true))
        // A typed value past the ladder still steps back onto it.
        assertEquals(60.0, LadderMath.neighbour(hold, 90.0, up = false))
    }

    @Test fun aBandEverySetSharesFoldsUpToTheRhythmPageAndBackDownOnSave() {
        val band = SetPlan(grip = GripSpec(), targetLoPercent = 0.2, targetHiPercent = 0.3)
        val draft = RoutineDraft(plan = SessionPlan(sets = listOf(band, band.copy(id = java.util.UUID.randomUUID()))))
        val opened = BuilderDraft.opening(draft)
        assertEquals(0.2..0.3, opened.plan.targetPercentBand)
        assertTrue(opened.plan.sets.none { it.hasPercentTarget })
        // Resolution is untouched in both directions.
        for (maxKg in listOf(null, 20.0, 60.0)) {
            for (i in draft.plan.sets.indices) {
                assertEquals(
                    PlanMath.targetBand(draft.plan.sets[i], draft.plan, maxKg),
                    PlanMath.targetBand(opened.plan.sets[i], opened.plan, maxKg),
                )
            }
        }
        val saved = opened.normalized
        assertNull(saved.plan.targetPercentBand)
        assertTrue(saved.plan.sets.all { it.targetPercentBand == 0.2..0.3 })
    }

    @Test fun aRampOrAKilogramBandStaysOnTheSets() {
        val ramp = RoutineDraft(plan = SessionPlan(sets = listOf(
            SetPlan(targetLoPercent = 0.5, targetHiPercent = 0.6),
            SetPlan(targetLoPercent = 0.7, targetHiPercent = 0.8),
        )))
        assertEquals(ramp, BuilderDraft.promotingUniformBand(ramp))
        val kilograms = RoutineDraft(plan = SessionPlan(sets = listOf(
            SetPlan(targetLoPercent = 0.2, targetHiPercent = 0.3, targetLoKg = 5.0, targetHiKg = 6.0),
        )))
        assertEquals(kilograms, BuilderDraft.promotingUniformBand(kilograms))
    }

    @Test fun theRoutineBandIsOneBandForEverySet() {
        val draft = RoutineDraft(plan = SessionPlan(sets = listOf(
            SetPlan(targetLoKg = 5.0, targetHiKg = 6.0),
            SetPlan(targetLoPercent = 0.5, targetHiPercent = 0.6),
        )))
        val banded = BuilderDraft.withRoutineBand(draft, 0.2..0.3)
        assertEquals(0.2..0.3, banded.plan.targetPercentBand)
        assertTrue(banded.plan.sets.none { it.hasTarget || it.hasPercentTarget })
        val cleared = BuilderDraft.withRoutineBand(banded, null)
        assertNull(cleared.plan.targetPercentBand)
    }

    @Test fun customTimingSeedsFromTheRoutineAndOffFollowsItAgain() {
        val plan = SessionPlan(holdSeconds = 7, restSeconds = 3)
        val set = SetPlan()
        val on = BuilderDraft.withCustomTiming(set, plan, on = true)
        assertEquals(7, on.holdSeconds)
        assertEquals(3, on.restSeconds)
        assertTrue(on.overridesTiming)
        // Seeded but untouched: the closed row names nothing, because nothing differs.
        assertEquals("", timingOverrideText(on, SetRowContext.of(plan)))
        assertEquals("12 s hold", timingOverrideText(on.copy(holdSeconds = 12), SetRowContext.of(plan)))
        val off = BuilderDraft.withCustomTiming(on.copy(holdSeconds = 12), plan, on = false)
        assertNull(off.holdSeconds)
        assertNull(off.restSeconds)
        // Save clears an ON switch whose values equal the routine's: it comes back OFF.
        val saved = RoutineDraft(plan = plan.copy(sets = listOf(on, SetPlan()))).normalized
        assertTrue(saved.plan.sets.none { it.overridesTiming })
    }

    @Test fun theClosedRowStatesTheLoadItWillRun() {
        val routine = SetRowContext(targetLoPercent = 0.2, targetHiPercent = 0.3)
        assertEquals("20–30 %", loadText(SetPlan(), routine))
        assertEquals("40–50 %", loadText(SetPlan(targetLoPercent = 0.4, targetHiPercent = 0.5), routine))
        assertNull(loadText(SetPlan(), SetRowContext()))
    }

    @Test fun theSubtitleStaysQuietUntilTheSetsPageWhileCreating() {
        val blank = RoutineDraft.blank()
        assertEquals("", BuilderDraft.subtitle(blank, creating = true, page = BuilderPage.Rhythm))
        assertEquals(blank.validationIssue, BuilderDraft.subtitle(blank, creating = true, page = BuilderPage.Sets))
        assertEquals(blank.validationIssue, BuilderDraft.subtitle(blank, creating = false, page = BuilderPage.Rhythm))
        val starter = RoutineDraft.starter
        assertEquals(PlanMath.subtitleLine(starter.plan),
            BuilderDraft.subtitle(starter, creating = true, page = BuilderPage.Rhythm))
    }

    @Test fun pagesWalkInOrder() {
        assertEquals(listOf(BuilderPage.Rhythm, BuilderPage.Sets, BuilderPage.Schedule), BuilderPage.entries)
        assertNull(BuilderPage.Rhythm.previous)
        assertNull(BuilderPage.Schedule.next)
        assertEquals(BuilderPage.Sets, BuilderPage.Rhythm.next)
    }
}
