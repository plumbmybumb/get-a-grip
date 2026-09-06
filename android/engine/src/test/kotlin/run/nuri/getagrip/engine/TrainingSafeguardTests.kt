// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrainingSafeguardTests {
    @Test fun importedMaxTableRejectsNonFiniteAndNonPositiveValues() {
        for (invalid in listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN, 0.0, -5.0)) {
            val table = MaxTable(mapOf(MaxTable.key(GripSpec().key, Side.both) to invalid))
            assertNull(table.max(GripSpec().key, Side.left))
        }
        val table = MaxTable(mapOf(MaxTable.key(GripSpec().key, Side.both) to 40.0))
        assertEquals(40.0, table.max(GripSpec().key, Side.left))
    }

    @Test fun laterPercentageSetWithSameGripStillReportsMissingBenchmark() {
        val plan = SessionPlan(sets = listOf(SetPlan(targetLoKg = 5.0), SetPlan(targetLoPercent = 0.25), SetPlan(targetLoPercent = 0.3)))
        assertEquals(1, PlanMath.untargetedGripCount(plan, MaxTable()))
        assertEquals(1, PlanMath.missingBenchmarkGripCount(plan, MaxTable()))
    }

    @Test fun fixedTargetsUnplannedTargetsAndSkippedSetsDoNotRequireBenchmark() {
        val plan = SessionPlan(sets = listOf(SetPlan(targetLoKg = 5.0), SetPlan(), SetPlan(repsPerSide = 0, targetLoPercent = 0.25)))
        assertEquals(0, PlanMath.missingBenchmarkGripCount(plan, MaxTable()))
    }

    @Test fun percentageTargetsNeedBothHandsBeforeWarningDisappears() {
        val plan = RoutineDraft.starter.plan.copy(sets = listOf(SetPlan(targetLoPercent = 0.25)))
        val maxes = MaxTable()
        maxes.record(40.0, GripSpec().key, Side.left)
        assertEquals(1, PlanMath.missingBenchmarkGripCount(plan, maxes))
        maxes.record(50.0, GripSpec().key, Side.right)
        assertEquals(0, PlanMath.missingBenchmarkGripCount(plan, maxes))
    }
}
