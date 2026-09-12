// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PullCountTests {
    @Test fun largerCountsSurviveBlobAndRoutineShareRoundTrips() {
        for (count in listOf(21, 36, 99, 100)) {
            val draft = RoutineDraft.blank("Long set").let {
                it.copy(plan = it.plan.copy(sets = listOf(SetPlan(repsPerSide = count))))
            }
            val saved = assertNotNull(BlobCodec.decode(assertNotNull(BlobCodec.encode(draft))) { RoutineDraft.fromJson(it) })
            val imported = RoutineShare.draft(assertNotNull(RoutineShare.url(saved)))
            assertEquals(count, saved.plan.sets[0].repsPerSide)
            assertEquals(count, imported.plan.sets[0].repsPerSide)
        }
    }

    @Test fun thirtySixPullsPerSideExecuteWithTheCorrectHandsAndCounts() {
        for (mode in HandMode.entries) {
            val plan = SessionPlan(sets = listOf(SetPlan(repsPerSide = 36)), handMode = mode,
                holdSeconds = 1, restSeconds = 0, leadInSeconds = 0)
            val runner = SessionRunner(plan, timerOnly = true)
            val expected = if (mode == HandMode.bothHands) 36 else 72
            assertEquals(expected, runner.plannedRepCount)
            runner.handle(RunnerEvent.Start, 0.0)
            assertEquals(expected, runner.repsInCurrentSet)
            for (second in 1..expected) runner.handle(RunnerEvent.Tick, second.toDouble())
            assertTrue(runner.isFinished, "$mode")
            assertEquals(expected, runner.results.size)
            assertTrue(runner.results.all { it.outcome == RepOutcome.completed && it.heldSeconds == 1.0 })
            if (mode == HandMode.bothHands) {
                assertTrue(runner.results.all { it.side == Side.both })
            } else {
                assertEquals(36, runner.results.count { it.side == Side.left })
                assertEquals(36, runner.results.count { it.side == Side.right })
            }
        }
    }

    @Test fun skippingALargerSetPreservesSummaryAndNextSetPosition() {
        val plan = SessionPlan(sets = listOf(SetPlan(repsPerSide = 36), SetPlan(repsPerSide = 3)),
            handMode = HandMode.alternateEachSet, holdSeconds = 1, restSeconds = 0, leadInSeconds = 0)
        val runner = SessionRunner(plan, timerOnly = true)
        runner.handle(RunnerEvent.Start, 0.0)
        runner.handle(RunnerEvent.SkipSet, 0.0)
        assertEquals(72, runner.completedRepCount)
        assertTrue(runner.results.all { it.outcome == RepOutcome.skipped })
        assertEquals(2, runner.setNumber)
        assertEquals(6, runner.repsInCurrentSet)
        assertEquals(1, runner.repNumberInSet)
        assertEquals(Side.left, runner.currentSlot?.side)
    }

    @Test fun aggregateTotalsAgreeWithExecutionAcrossEmptySetsAndOverrides() {
        for (mode in HandMode.entries) {
            for (count in listOf(0, 1, 36, 100)) {
                val plan = SessionPlan(sets = listOf(SetPlan(repsPerSide = 0),
                    SetPlan(repsPerSide = count, holdSeconds = 2, restSeconds = 7),
                    SetPlan(repsPerSide = 0), SetPlan(repsPerSide = 3), SetPlan(repsPerSide = 0)),
                    handMode = mode, holdSeconds = 11, restSeconds = 13, setBreakSeconds = 17, leadInSeconds = 5)
                val slots = PlanMath.sequence(plan)
                assertEquals(slots.size, PlanMath.totalReps(plan))
                assertEquals(slots.sumOf { it.totalSeconds }, PlanMath.totalSeconds(plan))
                assertEquals(slots.sumOf { it.holdSeconds }, PlanMath.tensionSeconds(plan))
            }
        }
        assertEquals(0, PlanMath.totalSeconds(SessionPlan(sets = listOf(SetPlan(repsPerSide = 0)))))
    }

    @Test fun maximumShareShapeHasRepresentableTotalsWithoutExpandingItsPulls() {
        val plan = SessionPlan(sets = List(50) { SetPlan(repsPerSide = 100) },
            handMode = HandMode.alternateEachRep, holdSeconds = 120,
            restSeconds = 600, setBreakSeconds = 900, leadInSeconds = 60)
        assertEquals(10_000, PlanMath.totalReps(plan))
        assertEquals(1_200_000, PlanMath.tensionSeconds(plan))
        assertEquals(7_217_100, PlanMath.totalSeconds(plan))
    }

    @Test fun largestSharedPlanStartsAndSkipsOneSetWithBoundedWork() {
        val plan = SessionPlan(sets = List(50) { SetPlan(repsPerSide = 100) },
            handMode = HandMode.alternateEachRep, leadInSeconds = 0)
        val initTimes = mutableListOf<Long>()
        val skipTimes = mutableListOf<Long>()
        repeat(20) {
            val beforeInit = System.nanoTime()
            val runner = SessionRunner(plan, timerOnly = true)
            initTimes += System.nanoTime() - beforeInit
            assertEquals(10_000, runner.plannedRepCount)
            runner.handle(RunnerEvent.Start, 0.0)
            val beforeSkip = System.nanoTime()
            runner.handle(RunnerEvent.SkipSet, 0.0)
            skipTimes += System.nanoTime() - beforeSkip
            assertEquals(200, runner.results.size)
            assertEquals(2, runner.setNumber)
            assertEquals(200, runner.repsInCurrentSet)
        }
        // Diagnostic, not a flaky wall-clock gate on shared CI hardware.
        println("Pull-count 50x100/side: init avg ${initTimes.average() / 1e6} ms, max ${initTimes.max() / 1e6} ms; skip avg ${skipTimes.average() / 1e6} ms, max ${skipTimes.max() / 1e6} ms")
    }
}
