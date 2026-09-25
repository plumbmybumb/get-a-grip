// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import run.nuri.getagrip.engine.CriticalForceTest.Cue
import run.nuri.getagrip.engine.CriticalForceTest.Phase
import run.nuri.getagrip.engine.CriticalForceTest.VoidReason

/// The critical force test: the fixed cadence, the arithmetic, and the honesty rules.
///
/// The ones that matter most are the honesty rules. Force pulled after the bell is never
/// credited, a test stopped before the plateau keeps nothing, and an interruption after it
/// keeps what was really run.
/// Translated from Tests/CriticalForceTests.swift.
class CriticalForceTests {

    private val proto = CriticalForceProtocol.standard
    private val epoch = 800_000_000.0   // playback time is on a reference-date footing

    /// A climber whose all-out pulls decay from 40 kg to a 20 kg plateau.
    private fun level(rep: Int): Double = 20 + 20 * exp(-rep.toDouble() / 5)

    /// Force at `rel` seconds into the test: a square-ish pull (0.2 s ramp) held
    /// `holdSeconds` from each window's start, zero otherwise.
    private fun force(rel: Double, holdSeconds: Double = 7.0, level: (Int) -> Double): Double {
        if (rel < 0) return 0.0
        val rep = (rel / proto.cycleSeconds).toInt()
        if (rep >= proto.reps) return 0.0
        val into = rel - proto.workStart(rep)
        if (into >= holdSeconds) return 0.0
        return level(rep) * min(1.0, into / 0.2 + 0.5)
    }

    /// Run a whole test at 80 Hz with a tick every 0.1 s. Returns every cue.
    private fun run(
        test: CriticalForceTest,
        until: Double,
        hold: Double = 7.0,
        level: ((Int) -> Double)? = null,
        dropping: (Double) -> Boolean = { false },
    ): List<Cue> {
        val lvl = level ?: ::level
        val cues = ArrayList<Cue>()
        // A pull that crosses the start threshold at rel 0.
        cues += test.sample(5.0, epoch)
        var rel = 1.0 / 80
        var nextTick = 0.1
        while (rel <= until) {
            if (!dropping(rel)) {
                cues += test.sample(force(rel, hold, lvl), epoch + rel)
            }
            if (rel >= nextTick) {
                cues += test.tick(epoch + rel)
                nextTick += 0.1
            }
            rel += 1.0 / 80
        }
        return cues
    }

    private fun CriticalForceTest.success(): CriticalForceResult {
        val outcome = assertNotNull(result(), "the test has not finished")
        return assertIs<CriticalForceOutcome.Success>(outcome, "expected a result, got $outcome").result
    }

    // MARK: - The protocol

    @Test
    fun theStandardProtocolIsSevenThreeTimesTwentyFour() {
        assertEquals("7:3x24", proto.key)
        assertEquals(237.0, proto.totalSeconds, 1e-9)
        assertEquals(proto, CriticalForceProtocol.fromKey("7:3x24"))
        assertEquals(proto, CriticalForceProtocol.fromKey("garbage"), "an unreadable key is the standard test")
        assertEquals(1.5, CriticalForceProtocol.fromKey("1.5:1.5x80").workSeconds)
    }

    // MARK: - The cadence

    @Test
    fun theFirstPullStartsTheClockAndNothingBeforeIt() {
        val test = CriticalForceTest()
        assertEquals(emptyList(), test.sample(3.9, epoch), "below the start threshold is setting up")
        assertEquals(emptyList(), test.tick(epoch + 30), "no clock runs while armed")
        assertEquals(listOf<Cue>(Cue.Pull(0)), test.sample(4.2, epoch + 31))
        assertEquals(Phase.Pulling(0), test.phase)
    }

    @Test
    fun theBellRingsOnTheClockAndCountsDownIntoTheNextPull() {
        val test = CriticalForceTest()
        test.sample(30.0, epoch)
        assertEquals(emptyList(), test.tick(epoch + 6.9))
        assertEquals(listOf<Cue>(Cue.LetGo(0)), test.tick(epoch + 7.0))
        assertEquals(Phase.Resting(0), test.phase)
        assertEquals(listOf<Cue>(Cue.Countdown(2)), test.tick(epoch + 8.0))
        assertEquals(emptyList(), test.tick(epoch + 8.5))
        assertEquals(listOf<Cue>(Cue.Countdown(1)), test.tick(epoch + 9.0))
        assertEquals(listOf<Cue>(Cue.Pull(1)), test.tick(epoch + 10.0))
        assertEquals(4.5, test.remaining(epoch + 12.5), 1e-9)
    }

    /// The rest does NOT wait for release: holding on through the bell changes nothing
    /// about when the next pull starts. That is the protocol; see `CriticalForceProtocol`.
    @Test
    fun holdingThroughTheBellDoesNotMoveTheNextPull() {
        val test = CriticalForceTest()
        test.sample(30.0, epoch)
        for (step in 1..95) {
            val rel = step.toDouble() / 10
            test.sample(30.0, epoch + rel)   // still pulling at 9.5 s
            test.tick(epoch + rel)
        }
        assertEquals(listOf<Cue>(Cue.Pull(1)), test.tick(epoch + 10))
    }

    @Test
    fun theTestFinishesAtTheLastBellWithNoTrailingRest() {
        val test = CriticalForceTest()
        val cues = run(test, until = proto.totalSeconds + 1)
        assertEquals(24, cues.count { it is Cue.Pull })
        assertEquals(24, cues.count { it is Cue.LetGo })
        assertEquals(Cue.Finished, cues.last())
        assertEquals(Phase.Finished, test.phase)
        assertEquals(24, test.repsRun)
        assertEquals(24, test.closedReps.size)
    }

    /// The bar in progress is LIVE, its running average moving with the pull, and the
    /// bell locks it to exactly the number the result will use.
    @Test
    fun thePullInProgressHasALiveAverageThatLocksAtTheBell() {
        val test = CriticalForceTest()
        test.sample(20.0, epoch)
        for (i in 1..160) test.sample(20.0, epoch + i.toDouble() / 80)   // 2 s at 20
        test.tick(epoch + 2)
        assertEquals(1, test.displayMeans().size)
        assertEquals(20.0, test.displayMeans()[0]!!, 1e-9)

        for (i in 161..559) test.sample(40.0, epoch + i.toDouble() / 80)   // then 40
        val live = test.displayMeans()[0]!!
        assertTrue(live > 30, "the running average rises with the pull")

        test.tick(epoch + 7.05)                                          // the bell
        test.sample(0.0, epoch + 7.1)
        val locked = test.displayMeans()[0]!!
        test.tick(epoch + 10.5)                                          // next pull, closed
        assertEquals(locked, test.closedReps.firstOrNull()?.meanKg ?: 0.0, 1e-9,
            "the locked bar is the saved number")
    }

    // MARK: - The arithmetic

    @Test
    fun criticalForceIsTheMeanOfTheLastSixPulls() {
        val test = CriticalForceTest()
        run(test, until = proto.totalSeconds + 1)
        val result = test.success()

        // The window mean of this synthetic pull, integrated at the same resolution.
        val expected = (18 until 24).sumOf { test.closedReps[it].meanKg!! } / 6
        assertEquals(expected, result.criticalForceKg, 1e-9)
        assertEquals(level(21), result.criticalForceKg, 0.4)
        assertEquals(19..24, result.criticalForceReps)
        assertEquals(level(0), result.peakKg, 0.01)
        assertEquals(24, result.repsRun)
    }

    @Test
    fun wPrimeIsTheImpulseAboveCriticalForce() {
        val test = CriticalForceTest()
        run(test, until = proto.totalSeconds + 1)
        val result = test.success()
        // Each pull holds ~level(rep) for ~6.9 s after its ramp; W′ ≈ Σ (level − CF)⁺ × 7.
        val approx = (0 until 24).sumOf { max(0.0, level(it) - result.criticalForceKg) * 7 }
        assertEquals(approx, result.wPrimeKgS, approx * 0.05)
        assertTrue(result.wPrimeKgS > 0)
    }

    /// THE HONESTY RULE: force after the bell counts for nothing. A climber who hangs on
    /// two seconds into every rest scores exactly what a clean climber does, and is told
    /// the rests were not kept.
    @Test
    fun forcePulledAfterTheBellIsNeverCredited() {
        val clean = CriticalForceTest()
        run(clean, until = proto.totalSeconds + 1, hold = 7.0)
        val late = CriticalForceTest()
        run(late, until = proto.totalSeconds + 1, hold = 9.0)

        val a = clean.success()
        val b = late.success()
        assertEquals(a.criticalForceKg, b.criticalForceKg, 0.05)
        assertEquals(a.wPrimeKgS, b.wPrimeKgS, a.wPrimeKgS * 0.01)
        assertEquals(23, a.restsTotal)
        assertEquals(23, a.restsKept)
        assertEquals(0, b.restsKept, "two seconds on the edge in every rest")
    }

    @Test
    fun endForceIsTheLastSecondOfTheLastThreePulls() {
        // A pull that fades within itself: the end force sits under the mean.
        val test = CriticalForceTest()
        run(test, until = proto.totalSeconds + 1)
        val result = test.success()
        assertNotNull(result.endForceKg)
        assertEquals((21 until 24).sumOf { level(it) } / 3, result.endForceKg, 0.05)
    }

    // MARK: - Stopping and interruptions

    @Test
    fun stoppingBeforeTheFloorKeepsNothing() {
        val test = CriticalForceTest()
        run(test, until = 100.0)   // ten bells
        assertFalse(test.canFinishEarly)
        assertEquals(listOf<Cue>(Cue.Voided), test.stop(epoch + 100))
        assertEquals(Phase.Voided(VoidReason.tooFewReps), test.phase)
        assertNull(test.result())
    }

    @Test
    fun stoppingAfterSixteenKeepsTheRepsRun() {
        val test = CriticalForceTest()
        run(test, until = 165.0)   // bells 1…16 have rung, rep 17 underway
        assertTrue(test.canFinishEarly)
        assertEquals(listOf<Cue>(Cue.Finished), test.stop(epoch + 165))
        test.tick(epoch + 165.5)
        assertEquals(Phase.Finished, test.phase)
        val result = test.success()
        assertEquals(16, result.repsRun, "the pull in progress is not counted")
        assertEquals(11..16, result.criticalForceReps)
        assertNull(result.reps.last().restLoadSeconds, "the final counted pull has no rest after it")
    }

    @Test
    fun losingTheGaugeBeforeTheFloorVoidsAndAfterItEnds() {
        val early = CriticalForceTest()
        run(early, until = 50.0)
        assertEquals(listOf<Cue>(Cue.Voided), early.interrupt(VoidReason.lostGauge, epoch + 50))
        assertEquals(Phase.Voided(VoidReason.lostGauge), early.phase)

        val late = CriticalForceTest()
        run(late, until = 203.0)
        assertEquals(listOf<Cue>(Cue.Finished), late.interrupt(VoidReason.leftApp, epoch + 203))
        late.tick(epoch + 204)
        assertEquals(20, late.success().repsRun)
    }

    // MARK: - Data quality

    /// A batch that arrives after its bell was rung still lands in the pull it was
    /// measured in: windows are sorted by the reading's own time, not arrival.
    @Test
    fun lateDeliveredReadingsLandInTheirOwnWindow() {
        val test = CriticalForceTest()
        test.sample(30.0, epoch)
        test.tick(epoch + 7.2)          // the bell has rung…
        for (i in 1..559) {             // …and only now does rep 1's data arrive
            test.sample(30.0, epoch + i.toDouble() / 80)
        }
        val summary = CriticalForceAnalysis.summarize(0, test.points, proto, isFinal = false)
        assertEquals(30.0, summary.meanKg!!, 1e-9)
        assertEquals(6.9875 / 7, summary.coverage, 0.001)
    }

    @Test
    fun aHoleIsNotInterpolatedAcross() {
        val points = listOf(
            CriticalForcePoint(0.0, 20.0), CriticalForcePoint(1.0, 20.0),
            CriticalForcePoint(3.0, 20.0), CriticalForcePoint(3.1, 20.0),
        )
        val integral = CriticalForceAnalysis.integrate(points, 0.0, 7.0)
        assertEquals(0.1, integral.covered, 1e-9, "only the 0.1 s pair is close enough")
    }

    @Test
    fun tooManyHolesInTheFinalPullsIsNoResult() {
        val test = CriticalForceTest()
        // The radio loses reps 20–22 entirely: three of the final six have no mean.
        run(test, until = proto.totalSeconds + 1, dropping = { rel -> rel >= 190.0 && rel < 220.0 })
        val outcome = test.result()
        if (outcome != CriticalForceOutcome.Failure(CriticalForceFailure.TooLittleData)) {
            fail("expected tooLittleData, got $outcome")
        }
    }

    @Test
    fun nobodyPullingIsNoResult() {
        val points = generateSequence(0.0) { it + 1.0 / 80 }.takeWhile { it < 240 }
            .map { CriticalForcePoint(it, 0.3) }.toList()
        assertEquals(
            CriticalForceOutcome.Failure(CriticalForceFailure.NoPull),
            CriticalForceAnalysis.analyze(points, repsRun = 24),
            "a flat trace is not a critical force",
        )
    }

    @Test
    fun analysisRefusesFewerThanSixteenReps() {
        assertEquals(
            CriticalForceOutcome.Failure(CriticalForceFailure.TooFewReps(run = 15)),
            CriticalForceAnalysis.analyze(emptyList(), repsRun = 15),
        )
    }

    @Test
    fun lineCrossingsIntegrateExactly() {
        // 0 → 20 kg over one second: the area above 10 is the triangle ½ × 0.5 s × 10 kg.
        val points = listOf(
            CriticalForcePoint(0.0, 0.0), CriticalForcePoint(0.2, 4.0),
            CriticalForcePoint(0.4, 8.0), CriticalForcePoint(0.6, 12.0),
            CriticalForcePoint(0.8, 16.0), CriticalForcePoint(1.0, 20.0),
        )
        assertEquals(2.5, CriticalForceAnalysis.integrate(points, 0.0, 1.0, above = 10.0).area, 1e-9)
        assertEquals(0.5, CriticalForceAnalysis.timeAbove(10.0, points, 0.0, 1.0), 1e-9)
    }

    // MARK: - The stored trace

    @Test
    fun theStoredTraceRoundTripsAndStillYieldsTheSameResult() {
        val test = CriticalForceTest()
        run(test, until = proto.totalSeconds + 1)
        val full = test.success()

        val data = CriticalForceTrace.encode(test.points)
        assertTrue(data.size < 12_000, "about ten kilobytes for four minutes")
        val decoded = CriticalForceTrace.decode(data)
        assertEquals(test.points.last().t * 20 + 1, decoded.size.toDouble(), 2.0)

        val again = assertIs<CriticalForceOutcome.Success>(CriticalForceAnalysis.analyze(decoded, repsRun = 24)).result
        assertEquals(full.criticalForceKg, again.criticalForceKg, 0.3)
        assertEquals(full.wPrimeKgS, again.wPrimeKgS, full.wPrimeKgS * 0.05)
    }

    @Test
    fun theTraceKeepsHolesAndNegativeReadings() {
        val points = listOf(CriticalForcePoint(0.01, -0.4), CriticalForcePoint(0.5, 12.34))
        val decoded = CriticalForceTrace.decode(CriticalForceTrace.encode(points))
        assertEquals(2, decoded.size, "empty slots between are holes, not zeros")
        assertEquals(-0.4, decoded[0].kg, 0.005)
        assertEquals(12.34, decoded[1].kg, 0.005)
    }

    @Test
    fun malformedTraceDecodesToNothing() {
        assertEquals(emptyList(), CriticalForceTrace.decode(byteArrayOf(9, 9, 9)))
        assertEquals(emptyList(), CriticalForceTrace.decode(ByteArray(0)))
    }

    @Test
    fun repBlobRoundTrips() {
        val reps = listOf(
            CriticalForceRep(index = 0, meanKg = 30.0, peakKg = 35.0, endKg = 28.0, impulseKgS = 210.0,
                coverage = 1.0, restLoadSeconds = 0.4),
            CriticalForceRep(index = 1, meanKg = null, peakKg = 0.0, endKg = null, impulseKgS = 0.0,
                coverage = 0.0, restLoadSeconds = null),
        )
        assertEquals(reps, CriticalForceRepsCodec.decode(CriticalForceRepsCodec.encode(reps)))
        assertEquals(emptyList(), CriticalForceRepsCodec.decode("nope"))
    }

    /// Android-only: the blob uses the iOS `Codable` key names, absent optionals omitted,
    /// so a row read off either platform means the same thing.
    @Test
    fun repBlobUsesTheSwiftKeyNames() {
        val text = CriticalForceRepsCodec.encode(listOf(
            CriticalForceRep(index = 2, meanKg = null, peakKg = 3.5, endKg = null, impulseKgS = 1.0,
                coverage = 0.5, restLoadSeconds = null),
        ))
        assertEquals("""[{"coverage":0.5,"impulseKgS":1,"index":2,"peakKg":3.5}]""", text)
    }

    /// Android-only: the hands' order. One at a time never alternates, and a "both" asked
    /// of a single hand is the left.
    @Test
    fun theHandsRunInOrderAndNeverAlternate() {
        assertEquals(listOf(Side.left, Side.right), CriticalForceHands.OneAtATime(Side.left).sides)
        assertEquals(listOf(Side.right, Side.left), CriticalForceHands.OneAtATime(Side.right).sides)
        assertEquals(listOf(Side.both), CriticalForceHands.BothHands.sides)
        assertEquals(listOf(Side.left), CriticalForceHands.Single(Side.both).sides)
    }
}
