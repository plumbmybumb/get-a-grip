// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.math.round
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The runner's correctness gate.
///
/// Unlike the Tindeq codec there is no published oracle to replay against, so this
/// matrix carries the whole weight: every timing rule that matters is asserted from a
/// synthetic force trace, with no audio, no haptics, no BLE and no waiting.
///
/// TRANSLATION NOTE (from Tests/SessionRunnerTests.swift): `Feeder` is a Swift struct
/// with `mutating` methods taking `inout SessionRunner`; here both are classes, so the
/// runner is passed by reference and nothing is copied back. `RunnerSessionDisplayTests`
/// is app-layer (it reads `RunnerSession.secondsShown`) and is NOT translated —
/// `pausedWorkingFreezesNumeralAndPhaseFraction` below keeps the engine half only, which
/// is exactly the split the Swift file's own comment insists on.
class SessionRunnerTests {

    // MARK: - Harness

    private companion object {
        /// 80 Hz, exactly like the device.
        const val sampleMicros: UInt = 12_500u
        const val sampleSeconds: Double = 0.0125
    }

    /// Drives a force trace into the runner the way the gauge would: device timestamps
    /// advancing 12.5 ms per sample, wall clock advancing with them.
    private class Feeder {
        var micros: UInt = 0u
        var now: Double = 0.0

        /// Holds `kg` for `seconds`, returning every cue the runner emitted.
        fun hold(runner: SessionRunner, kg: Double, seconds: Double): List<RunnerCue> {
            val cues = mutableListOf<RunnerCue>()
            val count = round(seconds / sampleSeconds).toInt()
            repeat(maxOf(0, count)) {
                micros += sampleMicros
                now += sampleSeconds
                cues += runner.handle(RunnerEvent.Sample(ForceSample(kg, micros)), at = now)
                cues += runner.handle(RunnerEvent.Tick, at = now)
            }
            return cues
        }

        /// Come off the edge. A tenth of a second below release is all it takes, and it
        /// is what a human does the moment a hold ends — which is exactly why
        /// `waitForReleaseBeforeRest` defaults to on, and why every test that expects a
        /// rest to be running has to do it.
        fun letGo(runner: SessionRunner): List<RunnerCue> = hold(runner, kg = 0.2, seconds = 0.1)

        /// Wall-clock only — no samples. What a rest period actually looks like.
        fun wait(runner: SessionRunner, seconds: Double): List<RunnerCue> {
            val cues = mutableListOf<RunnerCue>()
            val count = round(seconds / 0.1).toInt()
            repeat(maxOf(0, count)) {
                now += 0.1
                cues += runner.handle(RunnerEvent.Tick, at = now)
            }
            return cues
        }
    }

    private fun littleEndianBytes(value: UInt): List<Int> = listOf(
        (value and 0xFFu).toInt(),
        ((value shr 8) and 0xFFu).toInt(),
        ((value shr 16) and 0xFFu).toInt(),
        ((value shr 24) and 0xFFu).toInt(),
    )

    private fun weightPacket(samples: List<Pair<Float, UInt>>): ByteArray {
        val payload = samples.flatMap { sample ->
            littleEndianBytes(sample.first.toRawBits().toUInt()) + littleEndianBytes(sample.second)
        }
        return bytes(*(listOf(0x01, payload.size) + payload).toIntArray())
    }

    private fun deliver(packet: ByteArray, runner: SessionRunner, at: Double): List<RunnerCue> {
        val cues = mutableListOf<RunnerCue>()
        for (event in ProgressorCodec.decode(packet)) {
            if (event !is ProgressorEvent.Sample) continue
            cues += runner.handle(RunnerEvent.Sample(event.sample), at = at)
        }
        return cues
    }

    private fun plan(
        reps: Int = 1,
        sets: Int = 1,
        hold: Int = 10,
        rest: Int = 20,
        setBreak: Int = 60,
        leadIn: Int = 0,
        threshold: Double = 2.0,
        mode: HandMode = HandMode.bothHands,
    ): SessionPlan = SessionPlan(
        name = "Test",
        sets = (0 until sets).map {
            SetPlan(
                grip = GripSpec(20, FingerSet.four, GripPosition.halfCrimp),
                repsPerSide = reps,
            )
        },
        handMode = mode,
        holdSeconds = hold,
        restSeconds = rest,
        setBreakSeconds = setBreak,
        leadInSeconds = leadIn,
        thresholdKg = threshold,
    )

    /// TRANSLATION NOTE: `SessionPlan` and `SetPlan` are Kotlin data classes with `val`
    /// fields, so Swift's `p.sets[1].grip = …` becomes an explicit copy.
    private fun SessionPlan.replacingGrip(index: Int, grip: GripSpec): SessionPlan =
        copy(sets = sets.mapIndexed { i, set -> if (i == index) set.copy(grip = grip) else set })

    /// Above engage by a comfortable margin — a real working load.
    private val pulling: Double = 20.0

    /// Below the release threshold (2.0 kg engage → 1.5 kg release).
    private val released: Double = 0.2

    // MARK: - A clean rep

    @Test
    fun aCleanRepCompletesExactlyAtItsTargetAndBanksTheTime() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()

        val opening = runner.handle(RunnerEvent.Start, at = 0.0)
        assertEquals(listOf(RunnerCue.Armed(Side.both)), opening)
        val cues = feeder.hold(runner, kg = pulling, seconds = 11.0)

        assertTrue(cues.contains(RunnerCue.RepStarted))
        assertTrue(cues.contains(RunnerCue.RepHalfway))
        assertTrue(cues.contains(RunnerCue.RepEnded(true)))
        assertTrue(cues.contains(RunnerCue.SessionCompleted))
        assertEquals(RunnerPhase.Finished, runner.phase)

        val rep = runner.results.firstOrNull()
        assertNotNull(rep)
        assertEquals(RepOutcome.completed, rep.outcome)
        // The debounce is real time the climber spent pulling but is deliberately not
        // banked, so held lands just at target rather than over it.
        assertEquals(10.0, rep.heldSeconds, 0.05)
        assertEquals(pulling, rep.peakKg, 0.001)
        assertEquals(pulling, rep.avgKg, 0.001)
    }

    @Test
    fun theClockDoesNotStartUntilTheDebounceIsSatisfied() {
        val runner = SessionRunner(plan = plan())
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        // A single spike — bumping the edge on the way to gripping it.
        val spike = feeder.hold(runner, kg = pulling, seconds = 0.05)
        assertFalse(spike.contains(RunnerCue.RepStarted), "50 ms is under the 100 ms debounce")
        assertEquals(RunnerPhase.Armed(0), runner.phase)

        feeder.hold(runner, kg = released, seconds = 0.2)
        assertEquals(RunnerPhase.Armed(0), runner.phase, "letting go must re-arm, not start")
    }

    // MARK: - No gauge at all

    /// THE RULE (Nuri, 2026-08-09): with no Progressor the session still runs — count-in,
    /// hold, rest, both hands — it just measures nothing. The clock is the tick.
    @Test
    fun aGaugeFreeSessionRunsOnTicksAloneAndBooksRealReps() {
        val runner = SessionRunner(
            plan = plan(reps = 2, hold = 5, rest = 3, leadIn = 3),
            timerOnly = true,
        )
        val feeder = Feeder()

        val opening = runner.handle(RunnerEvent.Start, at = 0.0)
        assertEquals(RunnerPhase.LeadIn(0), runner.phase, "the count-in still happens")
        assertTrue(opening.contains(RunnerCue.LeadInTick(3)))

        // NOT ONE SAMPLE from here on — only wall-clock ticks.
        val cues = feeder.wait(runner, seconds = 40.0)

        assertTrue(cues.contains(RunnerCue.RepStarted), "the hold starts when the count-in ends")
        assertTrue(cues.contains(RunnerCue.RepHalfway))
        assertTrue(cues.contains(RunnerCue.SessionCompleted))
        assertEquals(2, runner.results.size)
        assertTrue(runner.results.all { it.outcome == RepOutcome.completed })
        assertEquals(5.0, runner.results[0].heldSeconds, 0.3, "a full hold, timed")
        assertEquals(0.0, runner.results[0].peakKg, "and nothing measured — honestly zero")
    }

    /// There is no `armed` phase without a gauge: nothing can observe you taking the load,
    /// so waiting for it would be waiting forever.
    @Test
    fun withoutAGaugeThereIsNoArmedPhaseToWaitIn() {
        val runner = SessionRunner(plan = plan(hold = 5, leadIn = 0), timerOnly = true)
        runner.handle(RunnerEvent.Start, at = 0.0)
        assertEquals(RunnerPhase.Working(0), runner.phase)
    }

    /// `waitForReleaseBeforeRest` has to be skipped too — the release it waits for is a
    /// force reading, and a gauge-free session would sit on LET GO for ever.
    @Test
    fun waitingForReleaseIsSkippedWithNoGauge() {
        val p = plan(reps = 2, hold = 3, rest = 5, leadIn = 0)
            .copy(waitForReleaseBeforeRest = true)
        val runner = SessionRunner(plan = p, timerOnly = true)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.wait(runner, seconds = 4.0)
        assertEquals(RunnerPhase.Resting(0), runner.phase, "straight into the rest, not releasing")
    }

    /// A pause must not bank the time it was paused for — the same contract the force
    /// path has, and the one a wall-clock accumulator is most likely to get wrong.
    @Test
    fun pausingAGaugeFreeHoldBanksNoneOfThePause() {
        val runner = SessionRunner(plan = plan(hold = 10, leadIn = 0), timerOnly = true)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.wait(runner, seconds = 3.0)
        runner.handle(RunnerEvent.Pause, at = feeder.now)
        feeder.wait(runner, seconds = 30.0)
        runner.handle(RunnerEvent.Resume, at = feeder.now)
        feeder.wait(runner, seconds = 1.0)

        assertEquals(
            4.0, runner.heldSeconds, 0.3,
            "three seconds plus one, and none of the thirty",
        )
        assertEquals(RunnerPhase.Working(0), runner.phase)
    }

    /// The dial describes the phase it is in, not just the hold. Lead-in, working and
    /// rest must all begin filled; wiring it to `repProgress` would leave the first and
    /// third phases empty because no hold time has been banked there.
    /// **Every wait past a phase boundary carries a margin, and that is not sloppiness.**
    /// `Feeder.wait` accumulates `now += 0.1`, so "5 seconds" lands on 4.999999999999998
    /// and a countdown ending at exactly 5.0 has not elapsed. Waiting the nominal duration
    /// leaves the runner one tick short of the transition — which is how the first draft
    /// of this test failed while the engine was correct.
    @Test
    fun phaseRemainingFractionCoversLeadInWorkingAndResting() {
        val runner = SessionRunner(
            plan = plan(reps = 2, hold = 2, rest = 5, leadIn = 5),
            timerOnly = true,
        )
        val feeder = Feeder()

        runner.handle(RunnerEvent.Start, at = 0.0)
        assertEquals(RunnerPhase.LeadIn(0), runner.phase)
        assertEquals(
            1.0, runner.phaseRemainingFraction(0.0) ?: -1.0, 0.001,
            "a phase begins full",
        )

        feeder.wait(runner, seconds = 1.0)
        assertEquals(RunnerPhase.LeadIn(0), runner.phase, "still counting in")
        assertEquals(
            0.8, runner.phaseRemainingFraction(feeder.now) ?: -1.0, 0.02,
            "one of five seconds gone",
        )

        // Past the boundary, not onto it — so these land a little INTO the new phase and
        // cannot be exactly 1. What is being proven is that the ring is meaningfully
        // FILLED here at all: wired to `repProgress` both of these would read 0, which is
        // the bug this whole change exists to fix.
        feeder.wait(runner, seconds = 4.5)
        assertEquals(RunnerPhase.Working(0), runner.phase)
        assertTrue(
            (runner.phaseRemainingFraction(feeder.now) ?: -1.0) > 0.6,
            "the hold starts nearly full, not empty",
        )

        feeder.wait(runner, seconds = 2.5)
        assertEquals(RunnerPhase.Resting(0), runner.phase)
        assertTrue(
            (runner.phaseRemainingFraction(feeder.now) ?: -1.0) > 0.6,
            "and so does the rest — the other phase repProgress leaves empty",
        )

        feeder.wait(runner, seconds = 1.0)
        assertEquals(RunnerPhase.Resting(0), runner.phase)
        val resting = runner.phaseRemainingFraction(feeder.now) ?: -1.0
        assertTrue(resting < 0.9, "the rest ring is draining")
        assertTrue(resting > 0.5)
    }

    /// Pausing is a visual freeze as well as a timing freeze. Advancing the caller's clock
    /// materially after `.pause` proves the readouts use `pausedAt`, rather than merely
    /// passing because the assertion happened in the same instant as the pause.
    @Test
    fun pausedWorkingFreezesNumeralAndPhaseFraction() {
        val runner = SessionRunner(plan = plan(hold = 10, leadIn = 0), timerOnly = true)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.wait(runner, seconds = 3.0)

        runner.handle(RunnerEvent.Pause, at = feeder.now)
        // **The engine half only.** Re-deriving the numeral here would be a tautology:
        // this file cannot reach `RunnerSession.secondsShown`, so an independent
        // `10 - heldSeconds` would stay green even with the paused display branch
        // deleted. `RunnerSessionDisplayTests` covers the numeral through the real
        // property instead; keep both.
        val fractionBefore = runner.phaseRemainingFraction(feeder.now)
        val heldBefore = runner.heldSeconds
        feeder.wait(runner, seconds = 30.0)

        assertEquals(heldBefore, runner.heldSeconds, 0.001, "a paused hold banks no more time")
        assertEquals(
            fractionBefore ?: -2.0, runner.phaseRemainingFraction(feeder.now) ?: -1.0, 0.001,
        )
    }

    @Test
    fun pausedRestingFreezesNumeralAndPhaseFraction() {
        val runner = SessionRunner(
            plan = plan(reps = 2, hold = 1, rest = 10, leadIn = 0),
            timerOnly = true,
        )
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        // Margin past the one-second hold — see the note on the fraction test above.
        feeder.wait(runner, seconds = 1.5)
        assertEquals(RunnerPhase.Resting(0), runner.phase)
        feeder.wait(runner, seconds = 3.0)

        runner.handle(RunnerEvent.Pause, at = feeder.now)
        val secondsBefore = runner.secondsRemaining(feeder.now)
        val fractionBefore = runner.phaseRemainingFraction(feeder.now)
        feeder.wait(runner, seconds = 30.0)

        assertEquals(secondsBefore, runner.secondsRemaining(feeder.now))
        assertEquals(
            fractionBefore ?: -2.0, runner.phaseRemainingFraction(feeder.now) ?: -1.0, 0.001,
        )
    }

    // MARK: - The target band gates the clock

    /// A plan whose reps carry an absolute 20–30 kg band.
    private fun bandedPlan(hold: Int = 10): SessionPlan {
        val p = plan(hold = hold, rest = 20)
        return p.copy(sets = p.sets.map { it.copy(targetLoKg = 20.0, targetHiKg = 30.0) })
    }

    /// THE RULE (Nuri, 2026-08-09): with a target range, only load INSIDE it banks time.
    /// Pulling 12 kg on a rep prescribed at 20–30 is not the rep the routine asked for,
    /// and the old engine banked it in full because 12 cleared the session threshold.
    @Test
    fun underTheBandNeverStartsTheRep() {
        val runner = SessionRunner(plan = bandedPlan())
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        val cues = feeder.hold(runner, kg = 12.0, seconds = 5.0)

        assertFalse(cues.contains(RunnerCue.RepStarted), "12 kg is not in a 20–30 kg rep")
        assertEquals(RunnerPhase.Armed(0), runner.phase, "still waiting to be pulled properly")
        assertEquals(0.0, runner.heldSeconds)
    }

    /// The other end, and the one that did not exist before: blowing straight through the
    /// ceiling is also not the prescribed rep.
    @Test
    fun overTheBandStopsTheClockAndSaysSoTheOtherWay() {
        val runner = SessionRunner(plan = bandedPlan())
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = 25.0, seconds = 4.0)
        assertTrue(runner.heldSeconds > 3, "in range, the clock runs")

        val cues = feeder.hold(runner, kg = 45.0, seconds = 5.0)
        assertTrue(cues.contains(RunnerCue.DropoutWarning), "it SAYS the clock stopped")
        assertFalse(
            cues.any { it is RunnerCue.RepEnded },
            "over the top never ends a rep either",
        )
        assertTrue(runner.isOverTarget, "the screen is saying EASE OFF")
        assertFalse(runner.isDropped, "and it must NOT say RE-GRIP — opposite instruction")
        assertEquals(3.9, runner.heldSeconds, 0.2, "it banked what it had")

        // Back into range, and it carries on from where it paused.
        feeder.hold(runner, kg = 25.0, seconds = 6.5)
        assertEquals(RepOutcome.completed, runner.results.firstOrNull()?.outcome)
        assertEquals(10.0, runner.results.firstOrNull()?.heldSeconds ?: 0.0, 0.15)
    }

    /// The hysteresis has to work in BOTH directions, or a hold sitting on the ceiling
    /// chatters the clock on and off exactly the way one sitting on the floor used to.
    @Test
    fun smallOvershootAtTheCeilingDoesNotStallTheClock() {
        val runner = SessionRunner(plan = bandedPlan(hold = 4))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        // Enter the band properly — engaging is strictly INSIDE it, deliberately, so a
        // pull that starts over the ceiling waits rather than banking at the wrong load.
        val cues = mutableListOf<RunnerCue>()
        cues += feeder.hold(runner, kg = 25.0, seconds = 1.0)
        assertTrue(cues.contains(RunnerCue.RepStarted))
        // Then drift to 31: over the 30 kg ceiling, inside the release band above it.
        cues += feeder.hold(runner, kg = 31.0, seconds = 3.6)
        assertTrue(cues.contains(RunnerCue.RepEnded(true)))
        assertFalse(cues.contains(RunnerCue.DropoutWarning), "1 kg over is not a stall")
    }

    /// No band, no ceiling: the original rule is untouched for every routine that never
    /// set a load. Pulling as hard as you like still banks the full hold.
    @Test
    fun withoutABandThereIsStillNoUpperLimit() {
        val runner = SessionRunner(plan = plan(hold = 5))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        val cues = feeder.hold(runner, kg = 90.0, seconds = 5.6)
        assertTrue(cues.contains(RunnerCue.RepEnded(true)))
        assertFalse(runner.isOverTarget)
    }

    // MARK: - The band can be told to stop refereeing

    /// `pausesOutsideTargetBand == false` (Nuri, 2026-08-11): the band still exists and
    /// still draws, but it no longer stops the clock. A load far over the ceiling — which
    /// would stall a normal banded rep dead — banks the full hold.
    @Test
    fun withTheBandGateOffAnOverloadStillBanksTheRep() {
        val p = bandedPlan(hold = 5).copy(pausesOutsideTargetBand = false)
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        // 60 kg is double the 30 kg ceiling. With the gate on, this never even starts.
        val cues = feeder.hold(runner, kg = 60.0, seconds = 5.6)
        assertTrue(cues.contains(RunnerCue.RepStarted), "engaging no longer needs to be inside")
        assertTrue(cues.contains(RunnerCue.RepEnded(true)), "and it banks the hold")
        assertFalse(cues.contains(RunnerCue.DropoutWarning), "nothing stalled, so nothing warns")
        assertFalse(runner.isOverTarget, "and the screen must not say EASE OFF")
    }

    /// What the switch must NOT loosen. Letting go of the edge is not a question about
    /// range — it is a question about whether you are pulling at all — so the engagement
    /// threshold still stops the clock with the gate off.
    @Test
    fun theBandGateOffStillStopsWhenYouLetGo() {
        val p = bandedPlan(hold = 10).copy(pausesOutsideTargetBand = false)
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = 25.0, seconds = 4.0)
        val cues = feeder.hold(runner, kg = 0.0, seconds = 3.0)
        assertTrue(cues.contains(RunnerCue.DropoutWarning), "coming off the edge still stalls")
        assertTrue(runner.isDropped, "and the screen says RE-GRIP")
        assertFalse(
            cues.any { it is RunnerCue.RepEnded },
            "a drop never ends the rep, gate or no gate",
        )
        assertEquals(4.0, runner.heldSeconds, 0.2, "it banked only what it held")
    }

    /// The default is unchanged, so every routine authored before the switch existed
    /// still gets the rule the band was introduced to enforce.
    @Test
    fun theBandGateDefaultsToOn() {
        assertTrue(SessionPlan().pausesOutsideTargetBand)
        assertTrue(RoutineDraft.starter.plan.pausesOutsideTargetBand)
    }

    // MARK: - Dips and hysteresis

    /// THE RULE: coming off the edge never ends a rep, however long you are off it.
    /// Re-gripping honestly takes more than three seconds, so any grace long enough to
    /// be fair is long enough to be pointless. Skip is the only way out.
    @Test
    fun aLongDropNeverEndsTheRepItJustStopsTheClock() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = pulling, seconds = 4.0)
        val cues = feeder.hold(runner, kg = released, seconds = 30.0)

        assertTrue(cues.contains(RunnerCue.DropoutWarning), "it still SAYS the clock stopped")
        assertFalse(
            cues.any { it is RunnerCue.RepEnded },
            "half a minute off the edge is still not a finished rep",
        )
        assertEquals(RunnerPhase.Working(0), runner.phase)
        assertEquals(3.9, runner.heldSeconds, 0.15, "and it banked what it had")
        assertTrue(runner.isDropped, "the screen is saying RE-GRIP")

        // Back on, and it carries on from where it paused rather than restarting.
        feeder.hold(runner, kg = pulling, seconds = 6.5)
        assertEquals(RepOutcome.completed, runner.results.firstOrNull()?.outcome)
        assertEquals(10.0, runner.results.firstOrNull()?.heldSeconds ?: 0.0, 0.1)
    }

    /// A shaky hold dips below the line ten times and still finishes — it just takes
    /// longer in wall-clock terms, because only time ON the edge is banked.
    @Test
    fun aShakyHoldCompletesAndOnlyLosesTheSecondsItActuallyLost() {
        val runner = SessionRunner(plan = plan(hold = 5))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        val cues = mutableListOf<RunnerCue>()
        repeat(10) {
            cues += feeder.hold(runner, kg = pulling, seconds = 0.6)
            cues += feeder.hold(runner, kg = released, seconds = 0.9) // a real fumble
        }

        assertTrue(cues.contains(RunnerCue.RepEnded(true)), "dips never end a rep")
        val rep = runner.results.firstOrNull()
        assertNotNull(rep)
        assertEquals(RepOutcome.completed, rep.outcome)
        // Accrual PAUSES during a dip rather than resetting, so the rep still banks its
        // full target — it just takes longer in wall-clock terms to get there.
        assertEquals(5.0, rep.heldSeconds, 0.1)
    }

    /// Force sitting exactly on the threshold must not chatter the rep on and off.
    @Test
    fun forceHoveringAtTheThresholdEngagesExactlyOnce() {
        // A long hold so the rep cannot simply finish during the 8 s of wobbling —
        // this test is about how many times it STARTS, and a completed rep would mask
        // a second engagement behind a legitimate ending.
        val runner = SessionRunner(plan = plan(hold = 60, threshold = 2.0))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        var starts = 0
        repeat(20) {
            // 2.1 and 1.9 straddle ENGAGE (2.0) but both sit above RELEASE (1.5),
            // which is the entire point of the hysteresis band.
            starts += feeder.hold(runner, kg = 2.1, seconds = 0.2)
                .count { it == RunnerCue.RepStarted }
            starts += feeder.hold(runner, kg = 1.9, seconds = 0.2)
                .count { it == RunnerCue.RepStarted }
        }
        assertEquals(1, starts, "one engagement, no flicker")
        assertEquals(0, runner.results.size, "and it is still running")
    }

    @Test
    fun afterDroppingBelowTheFloorTheDeadBandCannotRelatchTheClock() {
        val runner = SessionRunner(plan = plan(hold = 10, threshold = 2.0))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.0)

        val firstDip = feeder.hold(runner, kg = 1.4, seconds = 0.1)
        val heldAtExit = runner.heldSeconds
        assertEquals(1, firstDip.count { it == RunnerCue.DropoutWarning })

        val deadBand = feeder.hold(runner, kg = 1.6, seconds = 1.0)
        assertEquals(
            heldAtExit, runner.heldSeconds, 0.001,
            "above release but below engage remains latched",
        )
        assertFalse(
            deadBand.contains(RunnerCue.DropoutWarning),
            "dead-band samples do not re-arm the one-shot warning",
        )

        val sameDip = feeder.hold(runner, kg = 1.4, seconds = 0.1)
        assertFalse(sameDip.contains(RunnerCue.DropoutWarning), "this is still the same dip")
        feeder.hold(runner, kg = 2.0, seconds = 0.1)
        assertTrue(runner.heldSeconds > heldAtExit, "engage clears the latch")
        val secondDip = feeder.hold(runner, kg = 1.4, seconds = 0.1)
        assertTrue(secondDip.contains(RunnerCue.DropoutWarning), "engage also re-arms the cue")
    }

    @Test
    fun afterExceedingTheCeilingTheUpperDeadBandCannotRelatchTheClock() {
        val runner = SessionRunner(plan = bandedPlan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = 25.0, seconds = 1.0)

        feeder.hold(runner, kg = 32.0, seconds = 0.1) // above releaseHi (31.5)
        val heldAtExit = runner.heldSeconds
        feeder.hold(runner, kg = 31.0, seconds = 1.0) // dead band above engageHi (30)
        assertEquals(heldAtExit, runner.heldSeconds, 0.001)
        assertTrue(runner.isOverTarget, "dead-band samples keep the original stall visible")

        feeder.hold(runner, kg = 30.0, seconds = 0.1)
        assertTrue(
            runner.heldSeconds > heldAtExit,
            "only an in-band sample clears the upper latch",
        )
    }

    @Test
    fun aLateStartIsFineBecauseArmedNeverTimesOut() {
        val runner = SessionRunner(plan = plan(hold = 3))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.wait(runner, seconds = 120.0)
        assertEquals(RunnerPhase.Armed(0), runner.phase, "chalking up is not a failure")

        feeder.hold(runner, kg = pulling, seconds = 4.0)
        assertEquals(RepOutcome.completed, runner.results.firstOrNull()?.outcome)
    }

    // MARK: - The link

    @Test
    fun aDisconnectFreezesAccrualAndReconnectingDoesNotCreditTheOutage() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 3.0)

        val lost = runner.handle(RunnerEvent.ConnectionLost, at = feeder.now)
        assertEquals(listOf(RunnerCue.ConnectionLost), lost)
        feeder.wait(runner, seconds = 5.0)
        assertEquals(RunnerPhase.Working(0), runner.phase, "still inside the grace window")

        // Device timestamps jump by the whole outage; the runner must not bank it.
        feeder.micros += 5_000_000u
        runner.handle(RunnerEvent.ConnectionRestored, at = feeder.now)
        feeder.hold(runner, kg = pulling, seconds = 1.0)

        assertEquals(
            4.0, runner.heldSeconds, 0.2,
            "3 s before + 1 s after, never the 5 s nobody was measuring",
        )
    }

    /// Same rule for a dropped link: the rep waits for the gauge rather than abandoning
    /// work somebody actually did.
    @Test
    fun anUnrecoveredDisconnectWaitsRatherThanAbandoningTheRep() {
        val runner = SessionRunner(plan = plan(sets = 1, hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 3.0)
        runner.handle(RunnerEvent.ConnectionLost, at = feeder.now)

        feeder.wait(runner, seconds = 60.0)
        assertEquals(RunnerPhase.Working(0), runner.phase, "still the same rep, a minute later")
        assertTrue(runner.results.isEmpty())

        // Reconnect and finish it. The outage is not credited as hang time.
        feeder.micros += 60_000_000u
        runner.handle(RunnerEvent.ConnectionRestored, at = feeder.now)
        feeder.hold(runner, kg = pulling, seconds = 7.5)
        assertEquals(RepOutcome.completed, runner.results.firstOrNull()?.outcome)
        assertEquals(10.0, runner.results.firstOrNull()?.heldSeconds ?: 0.0, 0.15)
    }

    /// Taring re-zeroes the gauge, so the sample right after it can read near zero
    /// through no fault of the climber.
    @Test
    fun taringMidRepDoesNotSpuriouslyEndIt() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 2.0)

        runner.handle(RunnerEvent.TareCommitted, at = feeder.now)
        feeder.hold(runner, kg = released, seconds = 0.1) // the post-tare blip
        feeder.hold(runner, kg = pulling, seconds = 2.0)

        assertEquals(RunnerPhase.Working(0), runner.phase)
        assertTrue(runner.results.isEmpty())
    }

    /// The device's µs clock is a UInt32 and rolls over every ~71.6 minutes. A session
    /// that straddles the wrap must not lose or invent a rep.
    @Test
    fun aRepSpanningTheTimestampWrapKeepsCountingCorrectly() {
        val runner = SessionRunner(plan = plan(hold = 4))
        val feeder = Feeder()
        feeder.micros = UInt.MAX_VALUE - 25_000u // two samples from the wrap
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = pulling, seconds = 5.0)
        val rep = runner.results.firstOrNull()
        assertNotNull(rep)
        assertEquals(RepOutcome.completed, rep.outcome)
        assertEquals(4.0, rep.heldSeconds, 0.05)
    }

    // MARK: - Notification integrity

    @Test
    fun finiteFiveThousandKgSpikeNeverReachesTheRepPeak() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.0)

        val start = feeder.micros
        val packet = weightPacket(
            listOf(
                20f to start + 12_500u,
                5_000f to start + 25_000u,
                20f to start + 37_500u,
            ),
        )
        deliver(packet, runner, feeder.now)
        runner.handle(RunnerEvent.Abort, at = feeder.now)

        assertEquals(20.0, runner.results.firstOrNull()?.peakKg ?: 0.0, 0.001)
    }

    @Test
    fun seventeenTwoHundredMillisecondSamplesCannotFinishAnAccruedRep() {
        val runner = SessionRunner(plan = plan(hold = 3))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 2.7)
        val heldBefore = runner.heldSeconds
        assertEquals(2.6, heldBefore, 0.03)

        val packet = weightPacket((1..17).map { 20f to feeder.micros + it.toUInt() * 200_000u })
        val cues = deliver(packet, runner, feeder.now)

        assertEquals(heldBefore, runner.heldSeconds, 0.000_001)
        assertEquals(RunnerPhase.Working(0), runner.phase)
        assertFalse(cues.contains(RunnerCue.RepEnded(true)))
    }

    @Test
    fun fiveOneSecondTimestampSamplesCannotCreateAPhantomRep() {
        val runner = SessionRunner(plan = plan(hold = 3))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 2.7)
        val heldBefore = runner.heldSeconds

        val packet = weightPacket((1..5).map { 20f to feeder.micros + it.toUInt() * 1_000_000u })
        val cues = deliver(packet, runner, feeder.now)

        assertEquals(heldBefore, runner.heldSeconds, 0.000_001)
        assertEquals(RunnerPhase.Working(0), runner.phase)
        assertFalse(cues.contains(RunnerCue.RepEnded(true)))
    }

    @Test
    fun sameCorruptNotificationCannotEngageAnArmedRep() {
        val runner = SessionRunner(plan = plan(hold = 3))
        runner.handle(RunnerEvent.Start, at = 0.0)
        val packet = weightPacket((1..17).map { 20f to it.toUInt() * 200_000u })

        val cues = deliver(packet, runner, 0.0)
        assertEquals(RunnerPhase.Armed(0), runner.phase)
        assertFalse(cues.contains(RunnerCue.RepStarted))
    }

    @Test
    fun replayingAWholeNotificationCreditsZeroAdditionalTime() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.0)

        val samples = mutableListOf(5_000f to feeder.micros + sampleMicros)
        samples += (1..8).map { 20f to feeder.micros + (it + 1).toUInt() * sampleMicros }
        val packet = weightPacket(samples)
        val emitted = ProgressorCodec.decode(packet)
        val first = (emitted.firstOrNull() as? ProgressorEvent.Sample)?.sample
        assertNotNull(first, "the valid samples must survive the rejected leading spike")
        assertTrue(first.isBatchStart, "the first emitted sample still marks the batch")
        deliver(packet, runner, feeder.now)
        val heldAfterFirstDelivery = runner.heldSeconds
        deliver(packet, runner, feeder.now)

        assertEquals(heldAfterFirstDelivery, runner.heldSeconds, 0.000_001)
    }

    @Test
    fun staleEpochFailsClosedUntilAnExplicitStreamRestart() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.0)
        val heldBefore = runner.heldSeconds

        val staleOne = weightPacket(listOf(20f to 1_000u, 20f to 13_500u, 20f to 26_000u))
        val staleTwo = weightPacket(listOf(20f to 50_000u, 20f to 62_500u, 20f to 75_000u))
        deliver(staleOne, runner, feeder.now)
        deliver(staleTwo, runner, feeder.now)
        assertEquals(
            heldBefore, runner.heldSeconds, 0.000_001,
            "consecutive stale batches can never authorize their own epoch",
        )
        assertTrue(runner.isRejectingStaleBatches)

        runner.handle(RunnerEvent.StreamRestarted, at = feeder.now)
        deliver(staleOne, runner, feeder.now)
        assertEquals(
            heldBefore + 0.025, runner.heldSeconds, 0.000_001,
            "the explicit break accepts and re-anchors the fresh epoch",
        )
        assertFalse(runner.isRejectingStaleBatches)
    }

    @Test
    fun armedHealRecoversWhenQueuedOldEpochDataWinsTheForegroundRace() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.0)

        // Foreground re-kick: the engine breaks for a possible fresh epoch, but queued
        // pre-background samples from epoch A arrive first and legitimately re-anchor it.
        runner.handle(RunnerEvent.StreamRestarted, at = feeder.now)
        val queuedEpochA = weightPacket(
            listOf(
                20f to feeder.micros + 12_500u,
                20f to feeder.micros + 25_000u,
                20f to feeder.micros + 37_500u,
            ),
        )
        deliver(queuedEpochA, runner, feeder.now)
        val heldAfterQueuedBurst = runner.heldSeconds
        assertTrue(heldAfterQueuedBurst > 0)

        // The restarted live stream is epoch B near zero. Without a second authorized
        // break it remains fail-closed behind epoch A's high-water mark.
        val liveEpochB = weightPacket(listOf(20f to 1_000u, 20f to 13_500u, 20f to 26_000u))
        deliver(liveEpochB, runner, feeder.now)
        assertEquals(heldAfterQueuedBurst, runner.heldSeconds, 0.000_001)
        assertTrue(runner.isRejectingStaleBatches)

        val decision = StaleBatchHealer.decision(
            armed = true, armAge = 1.0,
            consecutiveRejectingChecks = 2,
            timeSinceLastHeal = Double.POSITIVE_INFINITY,
        )
        assertEquals(StaleBatchHealDecision.fire, decision)
        if (decision == StaleBatchHealDecision.fire) {
            runner.handle(RunnerEvent.StreamRestarted, at = feeder.now)
        }
        deliver(liveEpochB, runner, feeder.now)

        assertTrue(
            runner.heldSeconds > heldAfterQueuedBurst,
            "the armed healing break re-anchors on the live epoch",
        )
        assertFalse(runner.isRejectingStaleBatches)
    }

    @Test
    fun streamRestartMidHoldPreservesAccrualAndOnlyResetsTheAnchor() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.0)
        val heldBeforeRestart = runner.heldSeconds

        runner.handle(RunnerEvent.StreamRestarted, at = feeder.now)
        assertEquals(heldBeforeRestart, runner.heldSeconds, 0.000_001)

        runner.handle(RunnerEvent.Sample(ForceSample(pulling, 1_000u)), at = feeder.now)
        assertEquals(
            heldBeforeRestart, runner.heldSeconds, 0.000_001,
            "the first fresh-epoch sample establishes the anchor",
        )
        runner.handle(RunnerEvent.Sample(ForceSample(pulling, 13_500u)), at = feeder.now)
        assertEquals(heldBeforeRestart + sampleSeconds, runner.heldSeconds, 0.000_001)
    }

    @Test
    fun rejectingFlagClearsOnTheFirstAcceptedBatch() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.0)

        deliver(weightPacket(listOf(20f to 1_000u, 20f to 13_500u)), runner, feeder.now)
        assertTrue(runner.isRejectingStaleBatches)

        val accepted = weightPacket(listOf(20f to feeder.micros + 12_500u))
        deliver(accepted, runner, feeder.now)
        assertFalse(runner.isRejectingStaleBatches)
    }

    @Test
    fun staleBatchHealerFiresExactlyOncePerArm() {
        assertEquals(
            StaleBatchHealDecision.hold,
            StaleBatchHealer.decision(
                armed = true, armAge = 0.5,
                consecutiveRejectingChecks = 1,
                timeSinceLastHeal = Double.POSITIVE_INFINITY,
            ),
        )

        val first = StaleBatchHealer.decision(
            armed = true, armAge = 1.0,
            consecutiveRejectingChecks = 2,
            timeSinceLastHeal = Double.POSITIVE_INFINITY,
        )
        assertEquals(StaleBatchHealDecision.fire, first)

        // RunnerSession consumes the arm before emitting the event. The same ongoing
        // rejection therefore cannot authorize a second break.
        val afterConsumption = StaleBatchHealer.decision(
            armed = false, armAge = 1.5,
            consecutiveRejectingChecks = 20,
            timeSinceLastHeal = 0.5,
        )
        assertEquals(StaleBatchHealDecision.hold, afterConsumption)
    }

    @Test
    fun staleBatchHealerNeverFiresWithoutARecentRekick() {
        assertEquals(
            StaleBatchHealDecision.hold,
            StaleBatchHealer.decision(
                armed = false, armAge = 100.0,
                consecutiveRejectingChecks = 1_000,
                timeSinceLastHeal = 100.0,
            ),
        )
    }

    @Test
    fun staleBatchHealerArmExpires() {
        assertEquals(
            StaleBatchHealDecision.expire,
            StaleBatchHealer.decision(
                armed = true, armAge = StaleBatchHealer.armLifetime,
                consecutiveRejectingChecks = 2,
                timeSinceLastHeal = Double.POSITIVE_INFINITY,
            ),
        )
    }

    @Test
    fun staleBatchHealerHonorsTheTwoSecondRateLimit() {
        assertEquals(
            StaleBatchHealDecision.hold,
            StaleBatchHealer.decision(
                armed = true, armAge = 1.0,
                consecutiveRejectingChecks = 2,
                timeSinceLastHeal = 1.99,
            ),
        )
        assertEquals(
            StaleBatchHealDecision.fire,
            StaleBatchHealer.decision(
                armed = true, armAge = 1.0,
                consecutiveRejectingChecks = 2,
                timeSinceLastHeal = 2.0,
            ),
        )
    }

    @Test
    fun aForwardGapOverTwoHundredMillisecondsLosesOnlyThatDelta() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.0)
        val heldBefore = runner.heldSeconds

        val afterGap = feeder.micros + 200_001u
        runner.handle(RunnerEvent.Sample(ForceSample(pulling, afterGap)), at = feeder.now)
        assertEquals(heldBefore, runner.heldSeconds, 0.000_001)

        runner.handle(
            RunnerEvent.Sample(ForceSample(pulling, afterGap + sampleMicros)),
            at = feeder.now,
        )
        assertEquals(heldBefore + sampleSeconds, runner.heldSeconds, 0.000_001)
    }

    // MARK: - Human overrides

    @Test
    fun skipRepRecordsItAndMovesOn() {
        val runner = SessionRunner(
            plan = plan(reps = 2, hold = 10, rest = 5, mode = HandMode.bothHands),
        )
        runner.handle(RunnerEvent.Start, at = 0.0)

        val cues = runner.handle(RunnerEvent.SkipRep, at = 1.0)
        assertTrue(cues.contains(RunnerCue.RepEnded(false)))
        assertEquals(listOf(RepOutcome.skipped), runner.results.map { it.outcome })
        assertEquals(RunnerPhase.Resting(0), runner.phase, "a skip still takes its rest")
    }

    @Test
    fun skipSetRecordsEveryRemainingRepInThatSetAndJumpsToTheNext() {
        val runner = SessionRunner(
            plan = plan(reps = 3, sets = 2, hold = 10, mode = HandMode.bothHands),
        )
        runner.handle(RunnerEvent.Start, at = 0.0)

        val cues = runner.handle(RunnerEvent.SkipSet, at = 1.0)
        assertEquals(3, runner.results.size, "the whole set is accounted for")
        assertTrue(runner.results.all { it.outcome == RepOutcome.skipped })
        assertTrue(cues.contains(RunnerCue.SetCompleted(0)))
        assertEquals(1, runner.currentSlot?.setIndex)
    }

    /// Found by driving a real session: skipping from the REST screen re-recorded the
    /// rep that had just finished, and a six-set routine reported "37 of 36 pulls".
    /// A summary can never exceed the plan it came from.
    @Test
    fun skippingWhileRestingNeverRecordsMoreRepsThanThePlanHas() {
        val p = plan(reps = 2, sets = 3, hold = 1, rest = 5, setBreak = 5, mode = HandMode.bothHands)
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        // Finish rep 1 honestly, then skip every remaining set from the rest screen —
        // exactly what a tap on "Skip set" during a break does.
        feeder.hold(runner, kg = pulling, seconds = 1.3)
        feeder.letGo(runner)
        assertEquals(RunnerPhase.Resting(0), runner.phase)
        repeat(6) {
            if (!runner.isFinished) {
                runner.handle(RunnerEvent.SkipSet, at = feeder.now)
                feeder.wait(runner, seconds = 5.2)
            }
        }

        assertTrue(runner.isFinished)
        assertEquals(runner.plannedRepCount, runner.results.size, "6 planned, 6 recorded")
        assertTrue(runner.completedRepCount <= runner.plannedRepCount)
        // And the honest rep keeps its outcome rather than being overwritten by a skip.
        assertEquals(RepOutcome.completed, runner.results.firstOrNull()?.outcome)
    }

    @Test
    fun skippingAPullWhileRestingSkipsTheNextOneNotTheFinishedOne() {
        val runner = SessionRunner(
            plan = plan(reps = 3, hold = 1, rest = 5, mode = HandMode.bothHands),
        )
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.3)
        feeder.letGo(runner)
        assertEquals(RunnerPhase.Resting(0), runner.phase)

        runner.handle(RunnerEvent.SkipRep, at = feeder.now)
        assertEquals(
            listOf(RepOutcome.completed, RepOutcome.skipped),
            runner.results.map { it.outcome },
            "the finished rep is untouched; the upcoming one is skipped",
        )
    }

    @Test
    fun abortEndsTheSessionAndRecordsTheRepInFlight() {
        val runner = SessionRunner(plan = plan(reps = 4, hold = 10, mode = HandMode.bothHands))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 3.0)

        val aborted = runner.handle(RunnerEvent.Abort, at = feeder.now)
        assertEquals(listOf(RunnerCue.SessionCompleted), aborted)
        assertEquals(RunnerPhase.Finished, runner.phase)
        assertEquals(listOf(RepOutcome.aborted), runner.results.map { it.outcome })
        assertEquals(2.9, runner.results.firstOrNull()?.heldSeconds ?: 0.0, 0.15)
    }

    // MARK: - Pause

    @Test
    fun pausingMidRepBanksNothingAndResumingContinuesTheSameRep() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 3.0)

        runner.handle(RunnerEvent.Pause, at = feeder.now)
        assertEquals(RunnerPhase.Paused(RunnerPhase.Working(0)), runner.phase)
        feeder.wait(runner, seconds = 30.0)

        feeder.micros += 30_000_000u
        runner.handle(RunnerEvent.Resume, at = feeder.now)
        feeder.hold(runner, kg = pulling, seconds = 2.0)

        assertEquals(RunnerPhase.Working(0), runner.phase)
        assertEquals(5.0, runner.heldSeconds, 0.2, "the pause is not hang time")
    }

    @Test
    fun pausingARestStealsNoRestAndGivesNoneBack() {
        val runner = SessionRunner(
            plan = plan(reps = 2, hold = 1, rest = 10, mode = HandMode.bothHands),
        )
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.3)
        feeder.letGo(runner)
        assertEquals(RunnerPhase.Resting(0), runner.phase)

        feeder.wait(runner, seconds = 4.0)
        runner.handle(RunnerEvent.Pause, at = feeder.now)
        feeder.wait(runner, seconds = 60.0)
        runner.handle(RunnerEvent.Resume, at = feeder.now)

        assertEquals(RunnerPhase.Resting(0), runner.phase, "still ~6 s of rest owed")
        feeder.wait(runner, seconds = 5.0)
        assertEquals(RunnerPhase.Resting(0), runner.phase)
        feeder.wait(runner, seconds = 2.0)
        assertEquals(RunnerPhase.Armed(1), runner.phase)
    }

    /// REGRESSION (audit rank 1, verified): `abort(at:)` used to gate the in-flight rep's
    /// booking on `!phase.isPaused` — a clause that existed solely to DROP it, since
    /// `pending(in:)` already resolves through `.paused`. Pause mid-hold, then hold to
    /// end (answer the door, catch your breath) — the rep in flight, with its accrued
    /// hang time, must land in `results` exactly as it would unpaused, or a session
    /// whose only work happened before that pause writes no `WorkoutLog` at all
    /// (`SessionSummaryView` gates saving on `didAnyWork`).
    @Test
    fun abortWhilePausedMidHoldStillRecordsTheRepAndItsAccruedTime() {
        val runner = SessionRunner(plan = plan(hold = 10))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 3.0)

        runner.handle(RunnerEvent.Pause, at = feeder.now)
        assertEquals(RunnerPhase.Paused(RunnerPhase.Working(0)), runner.phase)

        val aborted = runner.handle(RunnerEvent.Abort, at = feeder.now)
        assertEquals(listOf(RunnerCue.SessionCompleted), aborted)
        assertEquals(RunnerPhase.Finished, runner.phase)
        assertEquals(
            listOf(RepOutcome.aborted), runner.results.map { it.outcome },
            "the in-flight rep must land in results, not vanish silently",
        )
        assertEquals(
            2.9, runner.results.firstOrNull()?.heldSeconds ?: 0.0, 0.15,
            "with the hang time it had already accrued before the pause",
        )
        assertTrue(runner.didAnyWork, "so the session summary has something worth saving")
    }

    @Test
    fun pauseAndResumeAreNoOpsWhereTheyHaveNoMeaning() {
        val runner = SessionRunner(plan = plan())
        val paused = runner.handle(RunnerEvent.Pause, at = 0.0)
        assertEquals(emptyList(), paused, "nothing to pause before start")
        assertEquals(RunnerPhase.Idle, runner.phase)
        val resumed = runner.handle(RunnerEvent.Resume, at = 0.0)
        assertEquals(emptyList(), resumed)
        assertEquals(RunnerPhase.Idle, runner.phase)
    }

    // MARK: - The rest waits for you to let go

    /// THE RULE (Nuri, 2026-08-04): the hold ends on time, the REST does not start until
    /// your hand is off the edge. Standing down off a 20 mm edge takes two or three
    /// seconds, and charging them to the rest means a 20 s rest was never 20 s.
    @Test
    fun theRestDoesNotStartUntilForceComesOffTheEdge() {
        val runner = SessionRunner(
            plan = plan(reps = 2, hold = 1, rest = 10, mode = HandMode.bothHands),
        )
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        // The hold completes at 1 s; keep pulling for five more.
        val cues = feeder.hold(runner, kg = pulling, seconds = 6.0)

        assertEquals(RunnerPhase.Releasing(0), runner.phase, "still on the edge")
        assertTrue(cues.contains(RunnerCue.RepEnded(true)), "the REP is done and recorded")
        assertEquals(1, runner.results.size)
        assertEquals(
            1.0, runner.results.firstOrNull()?.heldSeconds ?: 0.0, 0.05,
            "and the extra five seconds are not hang time",
        )
        assertFalse(
            cues.any { it is RunnerCue.RestTick },
            "no rest has begun, so nothing may count down",
        )
        assertNull(runner.secondsRemaining(feeder.now), "there is no clock to read")

        // Let go, and only now does the rest start — its full length, from here.
        feeder.letGo(runner)
        assertEquals(RunnerPhase.Resting(0), runner.phase)
        feeder.wait(runner, seconds = 9.0)
        assertEquals(RunnerPhase.Resting(0), runner.phase, "~1 s still owed")
        feeder.wait(runner, seconds = 1.5)
        assertEquals(RunnerPhase.Armed(1), runner.phase, "a full 10 s AFTER letting go")
    }

    /// Off, the old behaviour is still available and still honest — a fixed cadence you
    /// pace yourself to.
    @Test
    fun withTheFlagOffTheRestStartsWhileYouAreStillGripping() {
        val p = plan(reps = 2, hold = 1, rest = 10, mode = HandMode.bothHands)
            .copy(waitForReleaseBeforeRest = false)
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = pulling, seconds = 1.3)
        assertEquals(RunnerPhase.Resting(0), runner.phase, "no release needed")
    }

    /// Waiting for a release nobody can observe would strand the session on a phase with
    /// no clock and no way out but Skip. A rep waits for the gauge to come back; a rest
    /// does not.
    @Test
    fun losingTheLinkWhileWaitingForAReleaseStartsTheRestAnyway() {
        val runner = SessionRunner(
            plan = plan(reps = 2, hold = 1, rest = 10, mode = HandMode.bothHands),
        )
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.3)
        assertEquals(RunnerPhase.Releasing(0), runner.phase)

        val cues = runner.handle(RunnerEvent.ConnectionLost, at = feeder.now)
        assertTrue(cues.contains(RunnerCue.ConnectionLost))
        assertEquals(RunnerPhase.Resting(0), runner.phase, "the rest starts rather than stalling")
        assertNotNull(runner.secondsRemaining(feeder.now))
    }

    /// The LAST pull of a session owes no rest, so there is nothing to wait for — and a
    /// session that sat on LET GO forever, after the work was finished, would be the
    /// worst possible place to strand someone. Waiting is gated on a rest existing.
    @Test
    fun theFinalPullNeverWaitsForAReleaseAndTheSessionEnds() {
        val runner = SessionRunner(
            plan = plan(reps = 2, hold = 1, rest = 10, mode = HandMode.bothHands),
        )
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = pulling, seconds = 1.3)
        feeder.letGo(runner)
        feeder.wait(runner, seconds = 11.0)
        assertEquals(RunnerPhase.Armed(1), runner.phase)

        // The second and last pull: still gripping hard when it completes.
        val cues = feeder.hold(runner, kg = pulling, seconds = 1.3)
        assertTrue(cues.contains(RunnerCue.SessionCompleted))
        assertTrue(runner.isFinished, "no rest is owed, so no release is waited for")
    }

    /// A skip from the LET GO screen must behave exactly as one from the rest screen:
    /// the finished rep keeps its outcome and the NEXT one is what gets skipped.
    @Test
    fun skippingWhileWaitingToLetGoSkipsTheNextPullNotTheFinishedOne() {
        val runner = SessionRunner(
            plan = plan(reps = 3, hold = 1, rest = 5, mode = HandMode.bothHands),
        )
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.3)
        assertEquals(RunnerPhase.Releasing(0), runner.phase)

        runner.handle(RunnerEvent.SkipRep, at = feeder.now)
        assertEquals(
            listOf(RepOutcome.completed, RepOutcome.skipped),
            runner.results.map { it.outcome },
        )
    }

    // MARK: - What the screen is told to describe

    /// Resting is PREPARATION: the grip, hand and set number are there to be read while
    /// you shake out, and the rep already behind you is the one thing you do not need.
    @Test
    fun duringARestTheScreenDescribesTheNextPullNotTheFinishedOne() {
        // Two sets of one, so the rest between them is a SET BREAK and the grips differ.
        val p = plan(reps = 1, sets = 2, hold = 1, rest = 5, setBreak = 5, mode = HandMode.bothHands)
            .replacingGrip(1, GripSpec(20, FingerSet.frontTwo, GripPosition.openHand))
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = pulling, seconds = 1.3)
        feeder.letGo(runner)
        assertEquals(RunnerPhase.Resting(0), runner.phase)

        assertEquals(p.sets[1].grip, runner.displaySlot?.grip, "the grip you are about to pull")
        assertEquals(2, runner.setNumber, "and the set you are about to start")
        assertTrue(runner.isSetBreak, "while the REST itself is still the one just earned")
        assertEquals(
            p.sets[0].grip, runner.currentSlot?.grip,
            "currentSlot still means the rep that owns this rest",
        )
    }

    /// `.releasing` deliberately does NOT look forward — you are still on the current
    /// edge, and swapping the grip out from under a hand that has not let go would be
    /// describing something that isn't happening.
    @Test
    fun whileWaitingToLetGoTheScreenStillDescribesTheGripInYourHand() {
        val p = plan(reps = 1, sets = 2, hold = 1, rest = 5, setBreak = 5, mode = HandMode.bothHands)
            .replacingGrip(1, GripSpec(20, FingerSet.frontTwo, GripPosition.openHand))
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = pulling, seconds = 1.3)
        assertEquals(RunnerPhase.Releasing(0), runner.phase)
        assertEquals(p.sets[0].grip, runner.displaySlot?.grip)
        assertFalse(runner.isSetBreak, "no rest is running yet")
    }

    /// A paused rest is still a rest for every purpose the screen has.
    @Test
    fun aPausedRestStillDescribesTheNextPull() {
        val p = plan(reps = 1, sets = 2, hold = 1, rest = 5, setBreak = 5, mode = HandMode.bothHands)
            .replacingGrip(1, GripSpec(20, FingerSet.frontTwo, GripPosition.openHand))
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.3)
        feeder.letGo(runner)
        runner.handle(RunnerEvent.Pause, at = feeder.now)

        assertEquals(p.sets[1].grip, runner.displaySlot?.grip)
        assertTrue(runner.isSetBreak)
    }

    /// The rest BEFORE the last rep of a set is not a set break, even though the rep it
    /// leads into is the last of that set. This is the case `isLastOfSet` on the
    /// forward-looking slot would get wrong.
    @Test
    fun theRestBeforeASetsLastPullIsNotASetBreak() {
        val runner = SessionRunner(
            plan = plan(
                reps = 2, sets = 2, hold = 1, rest = 5,
                setBreak = 30, mode = HandMode.bothHands,
            ),
        )
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.3)
        feeder.letGo(runner)

        assertEquals(RunnerPhase.Resting(0), runner.phase)
        assertEquals(1, runner.displaySlot?.repIndex, "the next pull IS the set's last")
        assertFalse(runner.isSetBreak, "but this rest is a between-pulls rest")
    }

    // MARK: - A grip change ahead

    /// The cue exists to be RARE (Nuri, 2026-08-19). Every rest inside a set leads back
    /// onto the same edge, so a badge that lit on all of them would say nothing.
    @Test
    fun aRestBetweenTwoPullsOnTheSameGripAnnouncesNoChange() {
        val runner = SessionRunner(
            plan = plan(reps = 2, hold = 1, rest = 5, mode = HandMode.bothHands),
        )
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = pulling, seconds = 1.3)
        feeder.letGo(runner)
        assertEquals(RunnerPhase.Resting(0), runner.phase)
        assertFalse(runner.nextGripDiffers, "the same grip is not news")
    }

    /// The case it was built for: the set break before a set on a different grip.
    @Test
    fun theSetBreakBeforeADifferentGripAnnouncesTheChange() {
        val p = plan(reps = 1, sets = 2, hold = 1, rest = 5, setBreak = 5, mode = HandMode.bothHands)
            .replacingGrip(1, GripSpec(20, FingerSet.frontTwo, GripPosition.openHand))
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = pulling, seconds = 1.3)
        feeder.letGo(runner)
        assertEquals(RunnerPhase.Resting(0), runner.phase)
        assertTrue(
            runner.nextGripDiffers,
            "the set you are about to start is a different grip",
        )
    }

    /// `.releasing` does not look forward, so it does not warn forward either: a hand
    /// still on the edge is not being asked to change anything yet.
    @Test
    fun whileWaitingToLetGoTheComingGripChangeIsNotAnnouncedYet() {
        val p = plan(reps = 1, sets = 2, hold = 1, rest = 5, setBreak = 5, mode = HandMode.bothHands)
            .replacingGrip(1, GripSpec(20, FingerSet.frontTwo, GripPosition.openHand))
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = pulling, seconds = 1.3)
        assertEquals(RunnerPhase.Releasing(0), runner.phase)
        assertFalse(runner.nextGripDiffers, "the rest it belongs to has not begun")
    }

    /// Mid-hold there is nothing to prepare for, and the screen is carrying the one word
    /// that matters. The cue belongs to the rest and to nothing else.
    @Test
    fun midHoldNoGripChangeIsAnnounced() {
        val p = plan(reps = 1, sets = 2, hold = 5, rest = 5, setBreak = 5, mode = HandMode.bothHands)
            .replacingGrip(1, GripSpec(20, FingerSet.frontTwo, GripPosition.openHand))
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = pulling, seconds = 1.0)
        assertEquals(RunnerPhase.Working(0), runner.phase)
        assertFalse(runner.nextGripDiffers)
    }

    /// A paused rest is still a rest for every purpose the screen has — and the pause is
    /// exactly when someone reads the row.
    @Test
    fun aPausedRestStillAnnouncesTheGripChange() {
        val p = plan(reps = 1, sets = 2, hold = 1, rest = 5, setBreak = 5, mode = HandMode.bothHands)
            .replacingGrip(1, GripSpec(20, FingerSet.frontTwo, GripPosition.openHand))
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = pulling, seconds = 1.3)
        feeder.letGo(runner)
        runner.handle(RunnerEvent.Pause, at = feeder.now)
        assertTrue(runner.nextGripDiffers)
    }

    /// Whole specs are compared, so a ladder that only thins the edge still warns: 20 mm
    /// to 10 mm on the same fingers is a different hold, and moving to it is the thing
    /// worth noticing.
    @Test
    fun aChangeOfEdgeAloneIsStillANewGrip() {
        val p = plan(reps = 1, sets = 2, hold = 1, rest = 5, setBreak = 5, mode = HandMode.bothHands)
            .replacingGrip(1, GripSpec(10, FingerSet.four, GripPosition.halfCrimp))
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        feeder.hold(runner, kg = pulling, seconds = 1.3)
        feeder.letGo(runner)
        assertEquals(RunnerPhase.Resting(0), runner.phase)
        assertTrue(
            runner.nextGripDiffers,
            "only the edge moved, and the hand moves with it",
        )
    }

    // MARK: - Sequencing

    @Test
    fun leadInCountsDownBeforeTheFirstRepOfEachSetOnly() {
        val runner = SessionRunner(
            plan = plan(
                reps = 2, sets = 2, hold = 1, rest = 2,
                setBreak = 3, leadIn = 3, mode = HandMode.bothHands,
            ),
        )
        val feeder = Feeder()

        val opening = runner.handle(RunnerEvent.Start, at = 0.0)
        assertEquals(listOf(RunnerCue.LeadInTick(3)), opening)
        val cues = feeder.wait(runner, seconds = 3.2)
        assertTrue(cues.contains(RunnerCue.LeadInTick(1)))
        assertTrue(cues.contains(RunnerCue.Armed(Side.both)))

        feeder.hold(runner, kg = pulling, seconds = 1.3)
        feeder.letGo(runner)
        assertEquals(RunnerPhase.Resting(0), runner.phase)
        // Rep 2 of the same set gets NO lead-in — you are already on the edge.
        val afterRest = feeder.wait(runner, seconds = 2.2)
        assertFalse(afterRest.any { it is RunnerCue.LeadInTick })
        assertEquals(RunnerPhase.Armed(1), runner.phase)
    }

    @Test
    fun theRunnerWalksTheSameSequencePlanMathAdvertised() {
        val p = RoutineDraft.starter.normalized.plan
        val runner = SessionRunner(plan = p)
        assertEquals(PlanMath.totalReps(p), runner.plannedRepCount, "36 pulls")
        assertEquals(36, runner.plannedRepCount)
        assertEquals(6, runner.setCount)
        assertEquals(PlanMath.sequence(p), runner.slots)
    }

    @Test
    fun handsAlternateAcrossRepsAndResetAtEachSetBoundary() {
        // `repsPerSide: 2` with a two-sided mode is FOUR reps per set, not two — the
        // property is named per-side precisely so this factor of two is never a surprise.
        val p = plan(reps = 2, sets = 2, mode = HandMode.alternateEachRep)
        val runner = SessionRunner(plan = p)
        assertEquals(8, runner.slots.size)
        assertEquals(
            listOf(
                Side.left, Side.right, Side.left, Side.right,
                Side.left, Side.right, Side.left, Side.right,
            ),
            runner.slots.map { it.side },
        )
        // The load-bearing part: set 1 begins on the LEFT again rather than continuing
        // the parity of set 0, so each set is balanced on its own terms.
        val firstOfSecondSet = runner.slots.firstOrNull { it.setIndex == 1 }
        assertNotNull(firstOfSecondSet)
        assertEquals(Side.left, firstOfSecondSet.side)
        assertEquals(0, firstOfSecondSet.repIndex)
    }

    @Test
    fun anEmptyPlanFinishesImmediatelyRatherThanHanging() {
        val runner = SessionRunner(plan = SessionPlan(name = "Empty", sets = emptyList()))
        val opening = runner.handle(RunnerEvent.Start, at = 0.0)
        assertEquals(listOf(RunnerCue.SessionCompleted), opening)
        assertEquals(RunnerPhase.Finished, runner.phase)
    }

    // MARK: - A whole session

    /// The integration case: three sets, mixing a clean rep, a shaky-but-completed rep,
    /// an early release and a skip, asserting the final log rather than any one step.
    @Test
    fun aFullSessionProducesOneSummaryPerPlannedRep() {
        val p = plan(reps = 2, sets = 3, hold = 3, rest = 1, setBreak = 2, mode = HandMode.bothHands)
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)

        // Set 1 — clean, then shaky but complete.
        feeder.hold(runner, kg = pulling, seconds = 3.3)
        feeder.wait(runner, seconds = 1.2)
        repeat(6) {
            feeder.hold(runner, kg = pulling, seconds = 0.6)
            feeder.hold(runner, kg = released, seconds = 0.2)
        }
        feeder.wait(runner, seconds = 2.2)

        // Set 2 — one given up on with Skip (the ONLY way to end a rep short), then a
        // second skipped outright.
        feeder.hold(runner, kg = pulling, seconds = 1.0)
        feeder.hold(runner, kg = released, seconds = 2.6)
        runner.handle(RunnerEvent.SkipRep, at = feeder.now)
        feeder.wait(runner, seconds = 1.2)
        runner.handle(RunnerEvent.SkipRep, at = feeder.now)
        feeder.wait(runner, seconds = 2.2)

        // Set 3 — skipped wholesale.
        runner.handle(RunnerEvent.SkipSet, at = feeder.now)

        assertEquals(RunnerPhase.Finished, runner.phase)
        assertEquals(p.executable.sets.size * 2, runner.results.size, "6 planned, 6 recorded")
        assertEquals(
            listOf(
                RepOutcome.completed, RepOutcome.completed, RepOutcome.skipped,
                RepOutcome.skipped, RepOutcome.skipped, RepOutcome.skipped,
            ),
            runner.results.map { it.outcome },
        )
        // Every summary carries its own grip and side, so history stays meaningful even
        // if the routine is edited beyond recognition later.
        assertTrue(runner.results.all { it.grip.key == "20|IMRL|halfCrimp" })
        assertEquals(listOf(0, 0, 1, 1, 2, 2), runner.results.map { it.setIndex })
        assertTrue(runner.didAnyWork)
    }

    @Test
    fun aSessionNobodyPulledInIsNotWorthLogging() {
        val runner = SessionRunner(plan = plan(reps = 2, mode = HandMode.bothHands))
        runner.handle(RunnerEvent.Start, at = 0.0)
        runner.handle(RunnerEvent.SkipSet, at = 1.0)
        assertEquals(RunnerPhase.Finished, runner.phase)
        assertFalse(runner.didAnyWork, "all skipped — nothing happened")
    }

    // MARK: - A gauge with no clock of its own
    //
    // Every gauge but the Progressor is stamped from host uptime by the BLE client, so a
    // gap in its timestamps is a gap in the RADIO. `maxCreditedSampleGapSeconds` decides
    // how much of one such gap a rep may bank, and these four tests are the whole rule.

    @Test
    fun aFiveSecondSyntheticGapCreditsExactlyTheCapAndNotTheGap() {
        val runner = SessionRunner(plan = plan(hold = 30), maxCreditedSampleGapSeconds = 1.0)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.0)
        val heldBefore = runner.heldSeconds
        assertTrue(heldBefore > 0)

        // Five seconds of silence, then one reading still over the line. The hand was
        // plausibly on the edge for some of it and demonstrably not measured for the rest.
        runner.handle(
            RunnerEvent.Sample(ForceSample(pulling, feeder.micros + 5_000_000u)),
            at = feeder.now + 5,
        )
        assertEquals(heldBefore + 1, runner.heldSeconds, 0.000_001)
        assertEquals(RunnerPhase.Working(0), runner.phase, "and the rep is untouched")
    }

    /// The same gap, on the one gauge that timestamps its own samples, credits NOTHING —
    /// deliberately the opposite treatment. A device clock's deltas describe the device's
    /// sampling, so 5 s at 80 Hz is a broken timeline rather than a slow reading, and
    /// nothing may be invented from an interval the gauge cannot account for.
    @Test
    fun theSameFiveSecondGapOnADeviceClockCreditsNothingAtAll() {
        val runner = SessionRunner(plan = plan(hold = 30))
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.0)
        val heldBefore = runner.heldSeconds

        runner.handle(
            RunnerEvent.Sample(ForceSample(pulling, feeder.micros + 5_000_000u)),
            at = feeder.now + 5,
        )
        assertEquals(heldBefore, runner.heldSeconds, 0.000_001)
    }

    /// Why the cap REPLACES the 200 ms plausibility limit instead of joining it: one
    /// coalesced advertisement from an 8 Hz scale is already a 250 ms gap, and the device
    /// clock's rule would throw the whole interval away. A gauge that silently
    /// under-counted every hang is the same failure the runner exists to prevent,
    /// arriving from the other direction.
    @Test
    fun aCoalescedAdvertisementIsCreditedInFullRatherThanDroppedWholesale() {
        val gapMicros: UInt = 250_000u
        val capped = SessionRunner(plan = plan(hold = 30), maxCreditedSampleGapSeconds = 1.0)
        val uncapped = SessionRunner(plan = plan(hold = 30))
        val feeder = Feeder()
        capped.handle(RunnerEvent.Start, at = 0.0)
        uncapped.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(capped, kg = pulling, seconds = 1.0)
        val mirror = Feeder()
        mirror.hold(uncapped, kg = pulling, seconds = 1.0)
        val cappedBefore = capped.heldSeconds
        val uncappedBefore = uncapped.heldSeconds

        val stamp = feeder.micros + gapMicros
        capped.handle(RunnerEvent.Sample(ForceSample(pulling, stamp)), at = feeder.now)
        uncapped.handle(RunnerEvent.Sample(ForceSample(pulling, stamp)), at = mirror.now)

        assertEquals(
            cappedBefore + 0.25, capped.heldSeconds, 0.000_001,
            "under the cap, the gap is ordinary sampling and banks in full",
        )
        assertEquals(
            uncappedBefore, uncapped.heldSeconds, 0.000_001,
            "the device-clock rule would have lost the whole interval",
        )
    }

    /// The clamp is PER SAMPLE, so silence cannot accumulate credit at wall-clock rate:
    /// fifteen seconds of nothing, punctuated by three readings, buys three seconds.
    @Test
    fun repeatedSyntheticGapsEachCreditOnlyTheCap() {
        val runner = SessionRunner(plan = plan(hold = 30), maxCreditedSampleGapSeconds = 1.0)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, at = 0.0)
        feeder.hold(runner, kg = pulling, seconds = 1.0)
        val heldBefore = runner.heldSeconds

        var stamp = feeder.micros
        var at = feeder.now
        repeat(3) {
            stamp += 5_000_000u
            at += 5
            runner.handle(RunnerEvent.Sample(ForceSample(pulling, stamp)), at = at)
        }
        assertEquals(heldBefore + 3, runner.heldSeconds, 0.000_001)
    }

    /// **What a timeline break COSTS, which is why a synthetic-clock re-kick must not send
    /// one.** `breakTimeline` clears the accrual anchor and the arming debounce together, so
    /// a break landing between every pair of samples leaves a rep that can neither arm nor
    /// finish while the screen looks perfectly alive — kg moving, samples arriving. On a
    /// gauge sampling every 125 ms the silence watchdog can do exactly that.
    ///
    /// `RunnerSession` keeps the break for the Progressor, whose µs epoch genuinely resets
    /// on a re-sent start, and skips it for every gauge stamped from host uptime.
    @Test
    fun aBreakBetweenEverySamplePairNeitherArmsNorAccrues() {
        fun drive(breakingEachGap: Boolean): SessionRunner {
            // 1 s cap, matching `SamplePacing.syntheticClockGapCapSeconds`.
            val runner = SessionRunner(plan = plan(hold = 5), maxCreditedSampleGapSeconds = 1.0)
            runner.handle(RunnerEvent.Start, at = 0.0)
            var stamp: UInt = 0u
            var at = 0.0
            // Fifty readings at 8 Hz — six seconds of honest pulling against a 5 s hold.
            repeat(50) {
                if (breakingEachGap) runner.handle(RunnerEvent.StreamRestarted, at = at)
                stamp += 125_000u
                at += 0.125
                runner.handle(RunnerEvent.Sample(ForceSample(pulling, stamp)), at = at)
                runner.handle(RunnerEvent.Tick, at = at)
            }
            return runner
        }

        val broken = drive(breakingEachGap = true)
        assertEquals(
            RunnerPhase.Armed(0), broken.phase,
            "the debounce anchor is cleared before it can ever be satisfied",
        )
        assertEquals(0.0, broken.heldSeconds, 1e-9)

        val intact = drive(breakingEachGap = false)
        assertEquals(RunnerPhase.Finished, intact.phase, "the same trace, un-broken, is a whole rep")
        assertEquals(5.0, intact.results.firstOrNull()?.heldSeconds ?: 0.0, 0.2)
    }

    // MARK: - Backgrounding, keyed to what the gauge can sustain

    @Test
    fun aDisconnectedSessionPausesWheneverTheAppLeavesTheForeground() {
        assertTrue(
            BackgroundPausePolicy.pausesOnLeavingForeground(
                isBackground = false, isConnected = false, sustainsBackgroundStreaming = true,
            ),
        )
        assertTrue(
            BackgroundPausePolicy.pausesOnLeavingForeground(
                isBackground = true, isConnected = false, sustainsBackgroundStreaming = true,
            ),
        )
    }

    /// `bluetooth-central` keeps a connected stream alive, which is what lets somebody
    /// swipe home to change the music mid-set (Nuri, 2026-08-09).
    @Test
    fun aConnectedGaugeThatSustainsStreamingKeepsRunningInTheBackground() {
        assertFalse(
            BackgroundPausePolicy.pausesOnLeavingForeground(
                isBackground = true, isConnected = true, sustainsBackgroundStreaming = true,
            ),
        )
    }

    /// A broadcast scale's "connection" is a duplicate-allowing scan, and CoreBluetooth
    /// coalesces duplicates once the app is backgrounded — so the readings stop while the
    /// state still says connected. That is the app losing the ability to measure, exactly
    /// like having no gauge, and it must pause rather than stall a rep silently.
    @Test
    fun aBroadcastScanPausesOnBackgroundButNotOnAMereBanner() {
        assertTrue(
            BackgroundPausePolicy.pausesOnLeavingForeground(
                isBackground = true, isConnected = true, sustainsBackgroundStreaming = false,
            ),
        )
        assertFalse(
            BackgroundPausePolicy.pausesOnLeavingForeground(
                isBackground = false, isConnected = true, sustainsBackgroundStreaming = false,
            ),
            "a notification banner is not a suspension, and a scenePhase pause " +
                "costs a deliberate tap to come back from",
        )
    }

    /// The policy's inputs come from the registry, not from a device check anywhere in the
    /// view — this is the link that makes the rule reach the actual hardware.
    @Test
    fun theCapabilityTableIsWhatDrivesThoseTwoAnswers() {
        assertTrue(GaugeKind.progressor.capabilities.sustainsBackgroundStreaming)
        assertFalse(GaugeKind.whc06.capabilities.sustainsBackgroundStreaming)
        assertTrue(
            GaugeKind.progressor.capabilities.hasDeviceClock,
            "the only gauge whose deltas are its own",
        )
        assertFalse(GaugeKind.whc06.capabilities.hasDeviceClock)
    }

    @Test fun recordingClockDoesNotCreditPauseAndCompletionFreezes() {
        val runner = SessionRunner(plan = plan(hold = 5, leadIn = 0), timerOnly = true)
        runner.beginRecording(100.0)
        runner.handle(RunnerEvent.Start, 0.0, 102.0)
        runner.handle(RunnerEvent.Tick, 1.0, 103.0)
        runner.handle(RunnerEvent.Tick, 2.0, 104.0)
        runner.handle(RunnerEvent.Pause, 2.0, 104.0)
        runner.handle(RunnerEvent.Resume, 32.0, 134.0)
        (32..35).forEach { runner.handle(RunnerEvent.Tick, it.toDouble(), it + 102.0) }
        assertTrue(runner.isFinished)
        assertEquals(5.0, runner.results.first().heldSeconds)
        assertEquals(2.0, runner.results.first().startedElapsedSeconds)
        assertEquals(37.0, runner.results.first().endedElapsedSeconds)
        runner.handle(RunnerEvent.Tick, 999.0, 999.0)
        assertEquals(37.0, runner.finishedElapsedSeconds)
    }

    @Test fun skippedSetHasEndObservationsButNoInventedStarts() {
        val runner = SessionRunner(plan = plan(reps = 3, leadIn = 0))
        runner.handle(RunnerEvent.Start, 10.0)
        runner.handle(RunnerEvent.SkipSet, 15.0)
        assertEquals(3, runner.results.size)
        assertTrue(runner.results.all { it.startedElapsedSeconds == null && it.endedElapsedSeconds == 5.0 })
    }

    @Test fun timingMetadataRoundTripsAndOldBlobsRemainUnknown() {
        val old = RepSummary.fromJson(kotlinx.serialization.json.JsonObject(emptyMap()))!!
        assertEquals(null, old.startedElapsedSeconds)
        assertEquals(null, old.endedElapsedSeconds)
        val timed = RepSummary(startedElapsedSeconds = 12.5, endedElapsedSeconds = 22.5)
        assertEquals(timed, RepSummary.fromJson(timed.toJson()))
    }

    @Test fun zeroRestPreviewKeepsCurrentGripAndNewCuePersistsThroughFirstPull() {
        val original = plan(reps = 1, sets = 2, hold = 6, rest = 0, setBreak = 0)
        val p = original.copy(sets = listOf(original.sets[0], original.sets[1].copy(
            grip = GripSpec(10, FingerSet.frontTwo, GripPosition.openHand), repsPerSide = 2)))
        val runner = SessionRunner(plan = p, timerOnly = true)
        runner.handle(RunnerEvent.Start, 0.0)
        assertEquals(null, runner.newGripID)
        (1..2).forEach { runner.handle(RunnerEvent.Tick, it.toDouble()) }
        assertEquals(null, runner.upcomingGrip)
        runner.handle(RunnerEvent.Tick, 3.0)
        assertEquals(p.sets[1].grip, runner.upcomingGrip)
        assertEquals(p.sets[0].grip, runner.displaySlot?.grip)
        (4..6).forEach { runner.handle(RunnerEvent.Tick, it.toDouble()) }
        assertEquals("1.0", runner.newGripID)
        runner.handle(RunnerEvent.Pause, 6.0)
        assertEquals("1.0", runner.newGripID)
        runner.handle(RunnerEvent.Resume, 20.0)
        (20..25).forEach { runner.handle(RunnerEvent.Tick, it.toDouble()) }
        assertEquals("1.0", runner.newGripID)
        runner.handle(RunnerEvent.Tick, 26.0)
        assertEquals(null, runner.newGripID)
    }

    @Test fun allGripDimensionsAndSkippedSetsAnnounceButHandSwapsDoNot() {
        listOf(GripSpec(edgeMM = 10), GripSpec(fingers = FingerSet.frontTwo), GripSpec(position = GripPosition.fingerCurl)).forEach { grip ->
            val original = plan(reps = 2, sets = 2, setBreak = 0)
            val p = original.copy(sets = listOf(original.sets[0], original.sets[1].copy(grip = grip)))
            val runner = SessionRunner(plan = p)
            runner.handle(RunnerEvent.Start, 0.0)
            runner.handle(RunnerEvent.SkipSet, 1.0)
            assertEquals("1.0", runner.newGripID)
        }
        val runner = SessionRunner(plan = plan(reps = 1, sets = 2, setBreak = 0, mode = HandMode.alternateEachRep))
        runner.handle(RunnerEvent.Start, 0.0)
        runner.handle(RunnerEvent.SkipRep, 1.0)
        assertEquals(null, runner.newGripID)
        runner.handle(RunnerEvent.SkipSet, 2.0)
        assertEquals(null, runner.newGripID)
    }

    @Test fun newGripWaitsForReleaseThenSurvivesRestAndLeadIn() {
        val original = plan(reps = 1, sets = 2, hold = 1, setBreak = 5, leadIn = 2)
        val p = original.copy(sets = listOf(original.sets[0], original.sets[1].copy(grip = GripSpec(edgeMM = 10))))
        val runner = SessionRunner(plan = p)
        val feeder = Feeder()
        runner.handle(RunnerEvent.Start, 0.0)
        feeder.wait(runner, 2.1)
        feeder.hold(runner, 20.0, 1.3)
        assertEquals(RunnerPhase.Releasing(0), runner.phase)
        assertEquals(null, runner.newGripID)
        feeder.letGo(runner)
        assertEquals("1.0", runner.newGripID)
        feeder.wait(runner, 5.1)
        assertEquals(RunnerPhase.LeadIn(1), runner.phase)
        assertEquals("1.0", runner.newGripID)
        feeder.wait(runner, 2.1)
        assertEquals(RunnerPhase.Armed(1), runner.phase)
        assertEquals("1.0", runner.newGripID)
    }

    @Test fun previewIsUnnecessaryWhenRestOrLeadInProvidesNotice() {
        listOf(5 to 0, 0 to 2).forEach { (rest, lead) ->
            val original = plan(reps = 1, sets = 2, hold = 5, setBreak = rest, leadIn = lead)
            val p = original.copy(sets = listOf(original.sets[0], original.sets[1].copy(grip = GripSpec(edgeMM = 10))))
            val runner = SessionRunner(plan = p, timerOnly = true)
            runner.handle(RunnerEvent.Start, 0.0)
            Feeder().wait(runner, lead + 3.1)
            assertEquals(null, runner.upcomingGrip)
        }
    }
}
