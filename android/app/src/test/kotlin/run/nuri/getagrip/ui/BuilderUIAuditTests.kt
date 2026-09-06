// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import run.nuri.getagrip.ui.builder.DraftStashCoalescer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.ui.builder.BuilderDraft
import run.nuri.getagrip.ui.components.BandTrimmerMath
import run.nuri.getagrip.ui.components.BatteryDisplay
import run.nuri.getagrip.ui.today.ConsistencyEmptyState

@OptIn(ExperimentalCoroutinesApi::class)
class BuilderUIAuditTests {
    @Test fun heldInputSavesTheLatestDraftEveryWindowAndCancelsWithTheBuilder() = runTest {
        val owner = CoroutineScope(coroutineContext + Job())
        val written = mutableListOf<Int>()
        var draft = 1
        val stash = DraftStashCoalescer(owner) { written += draft }
        stash.changed()
        runCurrent()
        repeat(4) {
            advanceTimeBy(100)
            draft += 1
            stash.changed()
        }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(5), written)
        draft = 6
        stash.changed()
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(5, 6), written)
        draft = 7
        stash.changed()
        runCurrent()
        owner.cancel()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(5, 6), written)
    }

    @Test fun legacyTargetsBecomeEditableWithoutChangingExecutionOrOtherDraftValues() {
        val inherited = SetPlan(grip = GripSpec(), repsPerSide = 0)
        val kilograms = SetPlan(grip = GripSpec(), targetLoKg = 6.0, targetHiKg = 9.0)
        val percentage = SetPlan(grip = GripSpec(), targetLoPercent = 0.4, targetHiPercent = 0.5)
        val original = RoutineDraft(plan = SessionPlan(name = "  Unfinished  ",
            sets = listOf(inherited, kilograms, percentage), targetLoPercent = 0.2, targetHiPercent = 0.3))
        val edited = BuilderDraft.editable(original)
        assertEquals(original.plan.name, edited.plan.name)
        assertEquals(0, edited.plan.sets[0].repsPerSide)
        assertEquals(kilograms, edited.plan.sets[1])
        assertEquals(percentage, edited.plan.sets[2])
        assertNull(edited.plan.targetPercentBand)
        assertEquals(0.2..0.3, original.plan.targetPercentBand)
        assertEquals(edited, BuilderDraft.editable(edited))
        for (maxKg in listOf(null, 0.5, 20.0, 60.0)) {
            for (index in original.plan.sets.indices) {
                assertEquals(PlanMath.targetBand(original.plan.sets[index], original.plan, maxKg),
                    PlanMath.targetBand(edited.plan.sets[index], edited.plan, maxKg))
            }
        }
    }

    @Test fun clearingMaterializedTargetDoesNotRevealAnInvisibleRoutineFallback() {
        val original = RoutineDraft(plan = SessionPlan(sets = listOf(SetPlan(grip = GripSpec())),
            targetLoPercent = 0.2, targetHiPercent = 0.3))
        val edited = BuilderDraft.editable(original)
        val cleared = edited.plan.sets[0].copy(targetLoPercent = null, targetHiPercent = null)
        assertNull(PlanMath.targetBand(cleared, edited.plan, 30.0))
        val blank = RoutineDraft.blank("")
        assertEquals(blank, BuilderDraft.editable(blank))
    }

    @Test fun bandDraggingPreservesWidthAtBothWallsAndFitsOversizedIncomingValues() {
        assertEquals(15.0..20.0, BandTrimmerMath.translated(5.0, 10.0, 100.0, 0.0..20.0, 0.5))
        assertEquals(0.0..5.0, BandTrimmerMath.translated(5.0, 10.0, -100.0, 0.0..20.0, 0.5))
        assertEquals(0.0..20.0, BandTrimmerMath.translated(-5.0, 30.0, 1.0, 0.0..20.0, 0.5))
        assertEquals(0.0..0.0, BandTrimmerMath.translated(0.0, 0.0, 1.0, 0.0..0.0, 0.5))
        assertEquals(1.0..2.0, BandTrimmerMath.translated(1.0, 2.0, 1.0, 1.0..100.0, 5.0))
    }

    @Test fun consistencyHintDoesNotPretendALapsedUserIsNew() {
        fun record(tracked: Boolean, completed: Int = 0) =
            DayRecord(DayStamp(20_000), completed, 2, tracked, null)
        assertTrue(ConsistencyEmptyState.showsFirstUseHint(listOf(record(false), record(true))))
        assertFalse(ConsistencyEmptyState.showsFirstUseHint(listOf(record(true), record(true))))
        assertFalse(ConsistencyEmptyState.showsFirstUseHint(listOf(record(false), record(true, 1))))
        assertFalse(ConsistencyEmptyState.showsFirstUseHint(emptyList()))
    }

    @Test fun batteryPercentageUsesOneBoundedRoundingRule() {
        assertEquals(56, BatteryDisplay.percentage(0.556))
        assertEquals(55, BatteryDisplay.percentage(0.554))
        assertEquals(0, BatteryDisplay.percentage(-0.1))
        assertEquals(100, BatteryDisplay.percentage(1.2))
        assertEquals(0, BatteryDisplay.percentage(Double.NaN))
    }
}
