// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.engine.*
import run.nuri.getagrip.ui.runner.RunnerProgressStyle
import run.nuri.getagrip.ui.runner.SessionProgressModel
import run.nuri.getagrip.ui.runner.TimeBarMode
import run.nuri.getagrip.ui.runner.ZoomLayout
import run.nuri.getagrip.ui.runner.isHoldLive
import run.nuri.getagrip.ui.runner.routineLeftSpoken
import run.nuri.getagrip.ui.runner.timeBarFraction
import run.nuri.getagrip.ui.runner.timeBarMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The pure half of STACKED (iOS `RunnerProgressBars.swift`): where the session is, how the
/// routine's pills are laid out, and what the one time bar measures in each phase.
class StackedProgressModelTests {
    private val plan = SessionPlan(
        sets = listOf(SetPlan(grip = GripSpec(edgeMM = 20), repsPerSide = 2),
            SetPlan(grip = GripSpec(edgeMM = 15), repsPerSide = 3)),
        handMode = HandMode.bothHands,
    )
    private val slots = PlanMath.sequence(plan)

    @Test fun theModelFoldsSetsResultsAndThePullBeingPulled() {
        val results = listOf(RepSummary(outcome = RepOutcome.completed), RepSummary(outcome = RepOutcome.skipped))
        val model = SessionProgressModel.of(slots, results, RunnerPhase.Working(2))
        assertEquals(listOf(2, 3), model.setSizes)
        assertEquals(listOf(true, false), model.finished, "A skipped pull is used up, not done")
        assertEquals(2, model.current)
        assertTrue(model.isLive)
        assertEquals(3, model.pullsLeft)
        // Paused mid-pull still carries the live pull; a rest does not.
        assertTrue(SessionProgressModel.of(slots, results, RunnerPhase.Paused(RunnerPhase.Working(2))).isLive)
        assertFalse(SessionProgressModel.of(slots, results, RunnerPhase.Resting(1)).isLive)
        // Finished: no current pull.
        val all = List(5) { RepSummary() }
        assertNull(SessionProgressModel.of(slots, all, RunnerPhase.Finished).current)
    }

    @Test fun pillsKeepPullAndSetGapsAndNeverShrinkToDots() {
        val cells = ZoomLayout.cells(listOf(2, 3), width = 100f)
        assertEquals(5, cells.size)
        assertEquals(2f, cells[1].start - cells[0].endInclusive, 0.001f, "2 between pulls")
        assertEquals(6f, cells[2].start - cells[1].endInclusive, 0.001f, "6 between sets")
        assertEquals(100f, cells.last().endInclusive, 0.01f, "The routine spans the bar")
        // Sixty pulls on 300: the pull gap goes, so each pill stays longer than it is tall.
        val dense = ZoomLayout.cells(List(6) { 10 }, width = 300f)
        assertEquals(0f, dense[1].start - dense[0].endInclusive, 0.001f)
        assertTrue(dense[0].endInclusive - dense[0].start >= 1.5f * 3f, "a 3 pt pill must not become a dot")
        // Density scales the gaps, not the rule.
        val scaled = ZoomLayout.cells(listOf(2, 3), width = 300f, unit = 3f)
        assertEquals(6f, scaled[1].start - scaled[0].endInclusive, 0.001f)
        assertTrue(ZoomLayout.cells(emptyList(), 100f).isEmpty())
    }

    @Test fun oneBarMeasuresTheHoldThenDrainsTheRest() {
        assertEquals(TimeBarMode.hold, timeBarMode(RunnerPhase.Working(0)))
        assertEquals(TimeBarMode.released, timeBarMode(RunnerPhase.Releasing(0)))
        assertEquals(TimeBarMode.armed, timeBarMode(RunnerPhase.Armed(0)))
        assertEquals(TimeBarMode.countdown, timeBarMode(RunnerPhase.Resting(0)))
        assertEquals(TimeBarMode.countdown, timeBarMode(RunnerPhase.LeadIn(0)))
        assertEquals(TimeBarMode.countdown, timeBarMode(RunnerPhase.Paused(RunnerPhase.Resting(0))), "Paused freezes, it does not vanish")
        assertEquals(TimeBarMode.none, timeBarMode(RunnerPhase.Finished))
        assertEquals(0.4f, timeBarFraction(TimeBarMode.hold, 0.4f, null))
        assertEquals(1f, timeBarFraction(TimeBarMode.released, 0f, null), "A recorded hold stays full")
        assertEquals(0.25f, timeBarFraction(TimeBarMode.countdown, 0.9f, 0.25), "The rest is time REMAINING")
        assertEquals(0f, timeBarFraction(TimeBarMode.armed, 0.9f, 0.5))
        assertTrue(isHoldLive(RunnerPhase.Releasing(0)))
        assertFalse(isHoldLive(RunnerPhase.Armed(0)))
    }

    @Test fun theStyleDefaultsToStackedAndKeepsIosRawValues() {
        assertEquals(RunnerProgressStyle.stacked, RunnerProgressStyle.fromRaw(null))
        assertEquals(RunnerProgressStyle.stacked, RunnerProgressStyle.fromRaw("zoom"), "A style this build lacks")
        assertEquals(RunnerProgressStyle.baseline, RunnerProgressStyle.fromRaw("baseline"))
        assertEquals(listOf("Stacked", "Underline", "Today"), RunnerProgressStyle.selectable.map { it.settingsName })
        assertEquals(", 1 pull left", routineLeftSpoken(36, 35))
        assertEquals(", 12 pulls left", routineLeftSpoken(36, 24))
    }
}
