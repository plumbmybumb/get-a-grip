// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.engine
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionRunnerEdgeCoverageTests {
    private fun plan(hold: Int = 10) = SessionPlan(sets = listOf(SetPlan(repsPerSide = 2)),
        handMode = HandMode.bothHands, holdSeconds = hold, restSeconds = 20, leadInSeconds = 0)

    @Test fun averageWeightsElapsedTimeRatherThanSampleCountAndExcludesDips() {
        for (dip in listOf(false, true)) {
            val runner = SessionRunner(plan())
            runner.handle(RunnerEvent.Start, 0.0)
            var micros = 0u
            fun feed(kg: Double, step: UInt, count: Int) {
                repeat(count) {
                    runner.handle(RunnerEvent.Sample(ForceSample(kg, micros)), micros.toDouble() / 1e6)
                    micros += step
                }
            }
            feed(20.0, 100_000u, 2)
            val plateauSeconds = if (dip) 6 else 9
            feed(20.0, 100_000u, plateauSeconds * 10)
            if (dip) feed(0.0, 100_000u, 40)
            micros -= 100_000u
            micros += 12_500u
            feed(5.0, 12_500u, (10 - plateauSeconds) * 80)
            assertEquals(10.0, runner.results.first().heldSeconds)
            assertEquals(if (dip) 14.0 else 18.5, runner.results.first().avgKg, 1e-9)
        }
    }
    @Test fun timerHoldCannotCreditAThirtySecondMainThreadStall() {
        val runner = SessionRunner(plan(60), timerOnly = true)
        runner.handle(RunnerEvent.Start, 0.0)
        runner.handle(RunnerEvent.Tick, 0.1)
        val before = runner.heldSeconds
        runner.handle(RunnerEvent.Tick, 30.1)
        assertEquals(1.0, runner.heldSeconds - before, 1e-9)
    }
    @Test fun disconnectedPausedReleaseBecomesPausedRestAndKeepsTheFullRest() {
        val runner = SessionRunner(plan(1))
        runner.handle(RunnerEvent.Start, 0.0)
        for (index in 0..12) runner.handle(RunnerEvent.Sample(ForceSample(10.0, (index * 100_000).toUInt())), index / 10.0)
        assertEquals(RunnerPhase.Releasing(0), runner.phase)
        runner.handle(RunnerEvent.Pause, 2.0)
        runner.handle(RunnerEvent.ConnectionLost, 3.0)
        assertEquals(RunnerPhase.Paused(RunnerPhase.Resting(0)), runner.phase)
        assertEquals(20, runner.secondsRemaining(9.0))
        runner.handle(RunnerEvent.Resume, 10.0)
        assertEquals(RunnerPhase.Resting(0), runner.phase)
        assertEquals(20, runner.secondsRemaining(10.0))
    }
}
