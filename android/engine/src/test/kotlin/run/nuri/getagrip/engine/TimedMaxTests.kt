// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// Timed maxes (2026-10-01): "the most you can hold for 10 s", measured as the AVERAGE
/// over the window, stored beside the peak, and named by a routine's percentage.
/// Twin of Tests/TimedMaxTests.swift.
class TimedMaxTests {
    private val grip = GripSpec()

    /// Feeds `kg(t)` at 80 Hz from `start` for `seconds`; returns the time after.
    private fun feed(attempt: MaxAttempt, seconds: Double, start: Double = 0.0, kg: (Double) -> Double): Double {
        var t = start
        while (t < start + seconds) {
            attempt.add(kg(t), t)
            t += 1.0 / 80
        }
        return t
    }

    private fun near(expected: Double, actual: Double?, tolerance: Double) {
        assertTrue(actual != null && kotlin.math.abs(expected - actual) <= tolerance, "expected $expected, got $actual")
    }

    // MARK: - The measurement

    @Test fun aTimedResultIsTheAverageOverTheWindow() {
        val attempt = MaxAttempt(window = 10.0)
        val t = feed(attempt, 10.5) { 40 - it }
        feed(attempt, 1.5, t) { 0.0 }
        assertTrue(attempt.hasResult)
        near(35.0, attempt.resultKg, 0.1)
        near(40.0, attempt.peakKg, 0.01)
    }

    @Test fun lettingGoEarlyIsNoResult() {
        val attempt = MaxAttempt(window = 10.0)
        val t = feed(attempt, 6.0) { 40.0 }
        feed(attempt, 3.0, t) { 0.0 }
        assertTrue(attempt.isComplete)
        assertFalse(attempt.hasResult)
        assertNull(attempt.resultKg)
        near(6.0, attempt.heldSeconds, 0.05)
    }

    @Test fun lettingGoJustShortIsNoResultEvenAfterTheReleaseWait() {
        val attempt = MaxAttempt(endsAfter = 1.0, window = 10.0)
        val t = feed(attempt, 9.2) { 40.0 }
        feed(attempt, 2.0, t) { 0.0 }
        assertTrue(attempt.isComplete)
        assertNull(attempt.resultKg)
        assertTrue(attempt.heldSeconds < 10)
    }

    @Test fun loadAfterTheWindowIsNotCounted() {
        val attempt = MaxAttempt(window = 5.0)
        var t = feed(attempt, 5.2) { 30.0 }
        t = feed(attempt, 4.0, t) { 10.0 }
        near(30.0, attempt.resultKg, 0.05)
        assertEquals(0.0, attempt.remainingSeconds)
    }

    @Test fun readingsAreWeightedByHowLongTheyStood() {
        val attempt = MaxAttempt(window = 3.0)
        attempt.add(20.0, 0.0)
        attempt.add(40.0, 1.0)
        attempt.add(40.0, 3.0)
        near((20.0 * 1 + 40.0 * 2) / 3.0, attempt.resultKg, 0.001)
    }

    @Test fun aPeakAttemptStillRecordsThePeak() {
        val attempt = MaxAttempt()
        val t = feed(attempt, 2.0) { 40 - it * 5 }
        feed(attempt, 3.0, t) { 0.0 }
        near(40.0, attempt.resultKg, 0.01)
    }

    // MARK: - The visit

    @Test fun aTimedVisitLogsAveragesAndRemembersAShortPull() {
        val log = MaxAttemptLog(Side.left, windowSeconds = 5)
        var t = 0.0
        fun hold(kg: Double, seconds: Double) {
            val end = t + seconds
            while (t < end) { log.add(kg, t); t += 1.0 / 80 }
        }
        hold(30.0, 3.0); hold(0.0, 2.0)
        assertTrue(log.attempts.isEmpty())
        near(3.0, log.lastShortSeconds, 0.05)
        hold(32.0, 5.5); hold(0.0, 2.0)
        assertEquals(1, log.attempts.size)
        near(32.0, log.attempts.first().kg, 0.05)
        assertNull(log.lastShortSeconds)
    }

    // MARK: - The table

    @Test fun peakKeysAreUnchangedAndTimedKeysAreDistinct() {
        assertEquals("${grip.key}|left", MaxTable.key(grip.key, Side.left))
        assertEquals("${grip.key}|left", MaxTable.key(grip.key, Side.left, 0))
        assertEquals("${grip.key}|left|10s", MaxTable.key(grip.key, Side.left, 10))
    }

    @Test fun aTimedLookupNeverFallsBackToThePeak() {
        val table = MaxTable()
        table.record(40.0, grip.key, Side.both)
        assertNull(table.max(grip.key, Side.left, 10))
        table.record(33.0, grip.key, Side.both, 10)
        assertEquals(33.0, table.max(grip.key, Side.left, 10))
        assertEquals(40.0, table.max(grip.key, Side.left))
        table.record(7.0, grip.key, Side.right, 7)
        assertEquals(listOf(7, 10), table.timedLengths(grip.key))
        assertEquals(listOf(7, 10), table.timedLengths)
        assertEquals(emptyList(), table.timedLengths(GripSpec(edgeMM = 15).key))
    }

    // MARK: - The plan

    private fun plan(
        setPercent: ClosedFloatingPointRange<Double>? = null, setSeconds: Int? = null,
        routinePercent: ClosedFloatingPointRange<Double>? = null, routineSeconds: Int? = null,
    ): SessionPlan {
        val set = SetPlan(grip = grip, repsPerSide = 2,
            targetLoPercent = setPercent?.start, targetHiPercent = setPercent?.endInclusive,
            targetMaxSeconds = setSeconds)
        return SessionPlan(handMode = HandMode.bothHands, sets = listOf(set),
            targetLoPercent = routinePercent?.start, targetHiPercent = routinePercent?.endInclusive,
            targetMaxSeconds = routineSeconds)
    }

    private val table: MaxTable
        get() = MaxTable().apply {
            record(40.0, grip.key, Side.both)
            record(30.0, grip.key, Side.both, 10)
        }

    @Test fun aPercentageResolvesAgainstTheMaxItNames() {
        val peak = plan(setPercent = 0.9..0.9)
        assertEquals(36.0..36.0, PlanMath.targetBand(peak.sets[0], peak, Side.both, table))
        val timed = plan(setPercent = 0.9..0.9, setSeconds = 10)
        assertEquals(27.0..27.0, PlanMath.targetBand(timed.sets[0], timed, Side.both, table))
    }

    @Test fun aTimedBasisWithNoTimedMaxIsNoTargetNeverThePeak() {
        val p = plan(setPercent = 0.9..0.9, setSeconds = 7)
        assertNull(PlanMath.targetBand(p.sets[0], p, Side.both, table))
        assertEquals(1, PlanMath.untargetedGripCount(p, table))
    }

    @Test fun theBasisComesFromTheLevelTheBandComesFrom() {
        val inherits = plan(routinePercent = 0.9..0.9, routineSeconds = 10)
        assertEquals(10, PlanMath.maxSeconds(inherits.sets[0], inherits))
        val own = plan(setPercent = 0.5..0.5, routinePercent = 0.9..0.9, routineSeconds = 10)
        assertNull(PlanMath.maxSeconds(own.sets[0], own))
    }

    @Test fun intensityRestatesATimedPercentageAgainstThePeak() {
        val p = plan(setPercent = 0.8..0.9, setSeconds = 10)
        near(0.675, PlanMath.peakIntensity(p, table), 0.0001)
        val noPeak = MaxTable().apply { record(30.0, grip.key, Side.both, 10) }
        assertNull(PlanMath.peakIntensity(p, noPeak))
    }

    // MARK: - Storage

    @Test fun theBasisRoundTripsAndOldBlobsReadAsPeak() {
        val p = plan(setPercent = 0.9..0.9, setSeconds = 10, routineSeconds = 7)
        val decoded = SessionPlan.fromJson(p.toJson())!!
        assertEquals(10, decoded.sets[0].targetMaxSeconds)
        assertEquals(7, decoded.targetMaxSeconds)
        val legacy = Json.parseToJsonElement("""{"repsPerSide":3,"targetLoPercent":0.2,"targetHiPercent":0.3}""")
        assertNull(SetPlan.fromJson(legacy)!!.targetMaxSeconds)
        val zero = Json.parseToJsonElement("""{"repsPerSide":3,"targetMaxSeconds":0}""") as JsonObject
        assertNull(SetPlan.fromJson(zero).targetMaxSeconds)
    }

    @Test fun normalizingCarriesTheRoutineBasisOntoTheSets() {
        val draft = RoutineDraft.blank().copy(plan = plan(routinePercent = 0.9..0.9, routineSeconds = 10))
        val saved = draft.normalized.plan
        assertNull(saved.targetPercentBand)
        assertNull(saved.targetMaxSeconds)
        assertEquals(10, saved.sets[0].targetMaxSeconds)
        assertEquals(27.0..27.0, PlanMath.targetBand(saved.sets[0], saved, Side.both, table))
    }

    @Test fun normalizingDropsAnOrphanedBasis() {
        val draft = RoutineDraft.blank().copy(plan = plan(setSeconds = 10))
        assertNull(draft.normalized.plan.sets[0].targetMaxSeconds)
    }
}
