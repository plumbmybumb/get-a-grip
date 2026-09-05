// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.util.Locale
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// Per-hand maxes, end to end: the lookup rule, and the per-rep loads it produces.
///
/// The thing under test is really a claim about safety — that the right hand never
/// trains against the left hand's number. Every test here is one way that could happen.
///
/// TRANSLATION NOTE (from Tests/MaxTableTests.swift): one case,
/// `eachRepFreezesTheLoadItsOwnHandWasAskedFor`, drives a whole `SessionRunner` with
/// simulated `ForceSample`s; it was deferred until the runner wave and is now the last
/// case in this file. `MaxTable` is a class rather than a Swift struct, so Swift's
/// `var bothHands = oneHandAtATime` is `oneHandAtATime.copy()` here.
class MaxTableTests {

    /// `PlanMath.targetText` is display copy and formats through the default locale.
    @BeforeTest
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    private fun gripped(fingers: FingerSet): GripSpec =
        GripSpec(edgeMM = 20, fingers = fingers, position = GripPosition.halfCrimp)

    // MARK: - The lookup rule

    @Test
    fun aSideSpecificMaxBeatsTheBothHandsOne() {
        val table = MaxTable()
        table.record(40.0, "g", Side.both)
        table.record(36.0, "g", Side.right)

        assertEquals(36.0, table.max("g", Side.right))
        assertEquals(40.0, table.max("g", Side.left), "no left max, so the general one")
        assertEquals(40.0, table.max("g", Side.both))
    }

    /// The whole app worked this way before hands existed, and a single recorded max has
    /// to keep behaving identically for everyone who never opens the picker.
    @Test
    fun oneBothHandsMaxCoversEveryHand() {
        val table = MaxTable()
        table.record(40.0, "g", Side.both)

        assertEquals(40.0, table.max("g", Side.left))
        assertEquals(40.0, table.max("g", Side.right))
        assertEquals(40.0, table.max("g", Side.both))
    }

    /// THE SAFETY RULE. Choosing a hand is a statement that the other one is different,
    /// so it must not leak across. Nuri's right is ~10 % down on his left; borrowing the
    /// left number for the right hand would prescribe loads he cannot hold.
    @Test
    fun aOneHandedMaxNeverLeaksToTheOtherHand() {
        val table = MaxTable()
        table.record(40.0, "g", Side.left)

        assertEquals(40.0, table.max("g", Side.left))
        assertNull(
            table.max("g", Side.right),
            "the right hand has no max, and inventing one is the failure that hurts",
        )
    }

    /// A two-handed pull is not the sum, the mean, or the weaker hand — it is a separate
    /// measurement. Deriving one would be a silent, doubled guess pointed at fingers.
    @Test
    fun aTwoHandedRepNeverSynthesisesAMaxFromTheSingleHands() {
        val table = MaxTable()
        table.record(40.0, "g", Side.left)
        table.record(36.0, "g", Side.right)

        assertNull(table.max("g", Side.both))
    }

    @Test
    fun zeroAndNegativeAreNotRecorded() {
        val table = MaxTable()
        table.record(0.0, "g", Side.both)
        table.record(-5.0, "g", Side.left)
        table.record(Double.NaN, "g", Side.right)
        assertTrue(table.isEmpty)
    }

    @Test
    fun differsByHand() {
        val same = MaxTable()
        same.record(40.0, "g", Side.both)
        assertFalse(same.differsByHand("g"))

        val split = MaxTable()
        split.record(40.0, "g", Side.left)
        split.record(36.0, "g", Side.right)
        assertTrue(split.differsByHand("g"))

        assertFalse(MaxTable().differsByHand("g"), "nothing on file is not a difference")
    }

    @Test
    fun exactIgnoresTheFallback() {
        val table = MaxTable()
        table.record(40.0, "g", Side.both)
        assertEquals(40.0, table.exact("g", Side.both))
        assertNull(
            table.exact("g", Side.left),
            "the builder has to be able to see that this hand has nothing of its own",
        )
    }

    // MARK: - Through the plan, per rep

    /// The payoff: ONE percentage on the routine, two different loads, because the reps
    /// alternate hands and the hands have different maxes.
    @Test
    fun alternatingRepsGetTheirOwnHandsLoad() {
        val plan = SessionPlan(
            sets = listOf(SetPlan(grip = gripped(FingerSet.four), repsPerSide = 2)),
            handMode = HandMode.alternateEachRep, // L R L R …
            targetLoPercent = 0.20,
            targetHiPercent = 0.30,
        )

        val maxes = MaxTable()
        maxes.record(40.0, gripped(FingerSet.four).key, Side.left)
        maxes.record(36.0, gripped(FingerSet.four).key, Side.right)

        val slots = PlanMath.sequence(plan, maxes)
        assertEquals(4, slots.size)
        assertEquals(listOf(Side.left, Side.right, Side.left, Side.right), slots.map { it.side })
        assertEquals(8.0..12.0, slots[0].targetBand, "20–30 % of 40")
        assertEquals(7.0..11.0, slots[1].targetBand, "20–30 % of 36, on the half-kilo grid")
        assertEquals(slots[0].targetBand, slots[2].targetBand)
        assertEquals(slots[1].targetBand, slots[3].targetBand)
    }

    /// The hand a rep belongs to must not shift the load when only one max exists —
    /// the no-hands-configured case has to stay exactly as it was.
    @Test
    fun oneMaxGivesBothHandsTheSameLoad() {
        val plan = SessionPlan(
            sets = listOf(SetPlan(grip = gripped(FingerSet.four), repsPerSide = 2)),
            handMode = HandMode.alternateEachRep,
            targetLoPercent = 0.20,
            targetHiPercent = 0.30,
        )

        val maxes = MaxTable()
        maxes.record(40.0, gripped(FingerSet.four).key, Side.both)

        val bands = PlanMath.sequence(plan, maxes).map { it.targetBand }
        assertEquals(1, bands.mapNotNull { it?.toString() }.toSet().size)
    }

    /// A left-only max leaves the RIGHT reps with no target rather than the left one's.
    @Test
    fun repsOnAHandWithNoMaxGetNoTarget() {
        val plan = SessionPlan(
            sets = listOf(SetPlan(grip = gripped(FingerSet.four), repsPerSide = 1)),
            handMode = HandMode.alternateEachRep,
            targetLoPercent = 0.20,
            targetHiPercent = 0.30,
        )

        val maxes = MaxTable()
        maxes.record(40.0, gripped(FingerSet.four).key, Side.left)

        val slots = PlanMath.sequence(plan, maxes)
        assertEquals(8.0..12.0, slots[0].targetBand)
        assertNull(slots[1].targetBand)
    }

    /// An explicit kilogram band typed on the set stays hand-agnostic — see
    /// `PlanMath.targetBand`. Splitting a number a person typed is exactly the
    /// second-guessing the precedence rule forbids.
    @Test
    fun aTypedKilogramBandIsTheSameForBothHands() {
        val plan = SessionPlan(
            sets = listOf(
                SetPlan(
                    grip = gripped(FingerSet.four), repsPerSide = 1,
                    targetLoKg = 8.0, targetHiKg = 10.0,
                )
            ),
            handMode = HandMode.alternateEachRep,
        )

        val maxes = MaxTable()
        maxes.record(40.0, gripped(FingerSet.four).key, Side.left)
        maxes.record(20.0, gripped(FingerSet.four).key, Side.right)

        val slots = PlanMath.sequence(plan, maxes)
        assertEquals(8.0..10.0, slots[0].targetBand)
        assertEquals(8.0..10.0, slots[1].targetBand)
    }

    // MARK: - What the builder says

    @Test
    fun targetTextNamesBothHandsOnlyWhenTheyDiffer() {
        val set = SetPlan(grip = gripped(FingerSet.four))
        val plan = SessionPlan(
            sets = listOf(set),
            handMode = HandMode.alternateEachRep,
            targetLoPercent = 0.20,
            targetHiPercent = 0.30,
        )

        val same = MaxTable()
        same.record(40.0, gripped(FingerSet.four).key, Side.both)
        assertEquals("8.0–12.0 kg", PlanMath.targetText(set, plan, same))

        val split = MaxTable()
        split.record(40.0, gripped(FingerSet.four).key, Side.left)
        split.record(36.0, gripped(FingerSet.four).key, Side.right)
        assertEquals("L 8.0–12.0 · R 7.0–11.0 kg", PlanMath.targetText(set, plan, split))

        assertNull(PlanMath.targetText(set, plan, MaxTable()))
    }

    /// A two-handed routine has no per-hand question, so it must never draw "L … · R …".
    @Test
    fun aTwoHandedRoutineNeverSplitsTheText() {
        val set = SetPlan(grip = gripped(FingerSet.four))
        val plan = SessionPlan(
            sets = listOf(set),
            handMode = HandMode.bothHands,
            targetLoPercent = 0.20,
            targetHiPercent = 0.30,
        )

        val maxes = MaxTable()
        maxes.record(50.0, gripped(FingerSet.four).key, Side.both)
        maxes.record(40.0, gripped(FingerSet.four).key, Side.left)

        assertEquals("10.0–15.0 kg", PlanMath.targetText(set, plan, maxes))
    }

    /// Counted per HAND: a grip with a left max and no right one is half unloaded, and a
    /// count that missed it would report the routine as fully targeted.
    @Test
    fun untargetedGripsAreCountedPerHand() {
        val plan = SessionPlan(
            sets = listOf(
                SetPlan(grip = gripped(FingerSet.four)),
                SetPlan(grip = gripped(FingerSet.frontTwo)),
            ),
            handMode = HandMode.alternateEachRep,
            targetLoPercent = 0.20,
            targetHiPercent = 0.30,
        )

        val maxes = MaxTable()
        maxes.record(40.0, gripped(FingerSet.four).key, Side.left) // right missing
        maxes.record(20.0, gripped(FingerSet.frontTwo).key, Side.both) // fine

        assertEquals(1, PlanMath.untargetedGripCount(plan, maxes))
    }

    // MARK: - The load a rep freezes

    /// The per-rep, per-hand load, driven through the real runner: a `left` rep is asked
    /// for the left max's band and a `right` rep for the right's, and each `RepSummary`
    /// freezes the one it was asked for.
    @Test
    fun eachRepFreezesTheLoadItsOwnHandWasAskedFor() {
        val plan = SessionPlan(
            // Short and lead-in free: this test is about the number each rep carries, not
            // about timing, and a 10 s hold is 800 simulated samples of nothing.
            sets = listOf(SetPlan(grip = gripped(FingerSet.four), repsPerSide = 1)),
            handMode = HandMode.alternateEachRep,
            holdSeconds = 2,
            restSeconds = 1,
            leadInSeconds = 0,
            targetLoPercent = 0.20,
            targetHiPercent = 0.30,
        )

        val maxes = MaxTable()
        maxes.record(40.0, gripped(FingerSet.four).key, Side.left)
        maxes.record(36.0, gripped(FingerSet.four).key, Side.right)

        val runner = SessionRunner(plan = plan, maxes = maxes)
        runner.handle(RunnerEvent.Start, at = 0.0)

        var micros: UInt = 0u
        var now = 0.0
        var releaseUntil: Double? = null
        while (runner.results.size < 2 && now < 60) {
            micros += 12_500u
            now += 0.0125
            // Come off the edge for two seconds once the first rep is banked — the rest
            // is release-gated, so holding on forever would never start it, and letting
            // go forever would never arm the second rep.
            if (runner.results.size == 1 && releaseUntil == null) releaseUntil = now + 2
            val released = releaseUntil?.let { now < it } ?: false
            // 10 kg sits inside BOTH hands' bands (8–12 left, 7–11 right). It used to be
            // 30 — "pull hard", back when any load over the session threshold banked the
            // rep. The clock is gated on the band now, so 30 would be over the top of both
            // and this session would never start a single rep.
            runner.handle(
                RunnerEvent.Sample(ForceSample(if (released) 0.2 else 10.0, micros)),
                at = now,
            )
            runner.handle(RunnerEvent.Tick, at = now)
        }

        assertEquals(2, runner.results.size, "expected two logged reps")
        assertEquals(Side.left, runner.results[0].side)
        assertEquals(8.0..12.0, runner.results[0].targetBand)
        assertEquals(Side.right, runner.results[1].side)
        assertEquals(7.0..11.0, runner.results[1].targetBand)
    }
}
