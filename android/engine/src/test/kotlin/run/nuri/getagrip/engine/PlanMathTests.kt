// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.util.Locale
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// Deterministic generator so the randomized invariant below fails the same way twice.
/// SplitMix64 — small enough to read, good enough to shuffle plan parameters.
private class SeededGenerator(seed: ULong) {
    private var state: ULong = seed

    fun next(): ULong {
        state += 0x9E37_79B9_7F4A_7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58_476D_1CE4_E5B9uL
        z = (z xor (z shr 27)) * 0x94D0_49BB_1331_11EBuL
        return z xor (z shr 31)
    }

    /// TRANSLATION NOTE: Swift's `Int.random(in:using:)` / `randomElement(using:)`.
    /// The DRAWS need not match Swift's — this test asserts an invariant over whatever
    /// plans come out, not a golden sequence.
    fun int(range: IntRange): Int {
        val span = (range.last - range.first + 1).toULong()
        return range.first + (next() % span).toInt()
    }

    fun bool(): Boolean = next() % 2uL == 0uL

    fun <T> element(list: List<T>): T = list[int(list.indices.first..list.indices.last)]
}

/// Everything the app says about a routine is a fold over `PlanMath.sequence` — the
/// estimate, the per-side totals, the collapsed row's clock, and the order M3 will
/// actually execute. These tests pin the numbers as literals so a change to any
/// default shows up as a diff rather than a shrug, and pin the invariant that keeps
/// the card and the runner from ever drifting apart.
/// Translated from Tests/PlanMathTests.swift.
class PlanMathTests {

    private val starter: SessionPlan get() = RoutineDraft.starter.plan

    /// `PlanMath.bandText` formats through the DEFAULT locale on purpose (it is display
    /// copy, not wire text), so the frozen English strings below need the locale pinned.
    @BeforeTest
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    // MARK: - The starter routine, number by number

    /// Spelled out because this single number is the commitment Nuri is agreeing to,
    /// twice a day:
    ///   set 1  5 + 12·10 + 11·20 = 345
    ///   set 2  5 + 12·10 + 11·20 = 345
    ///   set 3  5 +  4·10 +  3·20 = 105
    ///   set 4  5 +  4·10 +  3·20 = 105
    ///   set 5  5 +  2·10 +  1·20 =  45
    ///   set 6  5 +  2·10 +  1·20 =  45   → 990
    ///   + 5 set breaks × 60             = 300
    ///                                    1290
    /// Mutation check: dropping the six lead-ins gives 1260 and a 30 s set break gives
    /// 1140 — both must fail this test.
    @Test
    fun starterTotalsExactly1290Seconds() {
        assertEquals(1290, PlanMath.totalSeconds(starter))
    }

    @Test
    fun starterDerivedNumbers() {
        assertEquals(6, PlanMath.setCount(starter))
        assertEquals(36, PlanMath.totalReps(starter))
        assertEquals(18, PlanMath.repsPerSide(starter))
        assertEquals(360, PlanMath.tensionSeconds(starter))
        assertEquals(180, PlanMath.tensionSecondsPerSide(starter))
        assertEquals(20, PlanMath.sharedEdgeMM(starter))
    }

    @Test
    fun starterSetSecondsPerRow() {
        val plan = starter
        assertEquals(
            listOf(345, 345, 105, 105, 45, 45),
            plan.sets.map { PlanMath.setSeconds(it, plan) },
        )
        // The trailing value on the collapsed set row.
        assertEquals("5:45", PlanMath.clockText(345))
    }

    /// The four frozen line builders. Every one of these is copy the user reads, and
    /// all four are computed — none of them is a literal anywhere in the app.
    @Test
    fun starterFrozenLines() {
        val plan = starter
        assertEquals("≈21 min · 36 pulls", PlanMath.subtitleLine(plan))
        assertEquals("6 sets · 36 pulls · ≈21 min", PlanMath.summaryLine(plan))
        assertEquals("6 sets · 36 pulls · 21:30 in total", PlanMath.totalsLine(plan))
        assertEquals("18 pulls per side · 3:00 under tension per side", PlanMath.perSideLine(plan))
    }

    // MARK: - The anti-drift invariant

    /// `totalSeconds` is a fold over `sequence`, never a parallel formula. A second
    /// formula would eventually disagree, and the disagreement would be invisible
    /// without a stopwatch.
    @Test
    fun totalSecondsAlwaysEqualsTheFoldOverTheSequence() {
        val rng = SeededGenerator(0xD016_7E57uL)
        for (iteration in 0 until 200) {
            val plan = randomPlan(rng)
            val folded = PlanMath.sequence(plan).sumOf { it.totalSeconds }
            assertEquals(folded, PlanMath.totalSeconds(plan), "iteration $iteration")
            assertEquals(
                PlanMath.totalReps(plan), PlanMath.sequence(plan).size,
                "iteration $iteration",
            )
        }
    }

    private fun randomPlan(rng: SeededGenerator): SessionPlan = SessionPlan(
        handMode = rng.element(HandMode.entries),
        holdSeconds = rng.int(3..30),
        restSeconds = rng.int(0..60),
        setBreakSeconds = rng.int(0..180),
        leadInSeconds = rng.int(0..15),
        sets = (0 until rng.int(0..8)).map {
            SetPlan(
                grip = GripSpec(
                    edgeMM = rng.int(6..35),
                    fingers = FingerSet(rng.int(1..15)),
                    position = rng.element(GripPosition.known),
                ),
                repsPerSide = rng.int(0..20),
                holdSeconds = if (rng.bool()) rng.int(3..40) else null,
                restSeconds = if (rng.bool()) rng.int(0..90) else null,
            )
        },
    )

    // MARK: - The hand-alternation matrix

    /// THE silent-factor-of-two catch: with one pull covering both hands there is no
    /// "per side", and the copy drops the phrase rather than printing half a truth.
    @Test
    fun perSideValuesAreNilForBothHands() {
        val plan = starter.copy(handMode = HandMode.bothHands)
        assertNull(PlanMath.repsPerSide(plan))
        assertNull(PlanMath.tensionSecondsPerSide(plan))
        assertNull(PlanMath.perSideLine(plan))
        assertNull(PlanMath.tensionSecondsPerSide(plan.sets[0], plan))
    }

    @Test
    fun totalRepsCountsBothSidesUnderAlternatingModes() {
        for (mode in listOf(HandMode.alternateEachRep, HandMode.alternateEachSet)) {
            val plan = SessionPlan(
                handMode = mode,
                sets = listOf(SetPlan(grip = GripSpec(), repsPerSide = 6)),
            )
            assertEquals(12, PlanMath.totalReps(plan), "$mode")
            assertEquals(12, PlanMath.repCount(plan.sets[0], mode))
            assertEquals(2, mode.sideCount)
        }
    }

    @Test
    fun bothHandsDoesNotDoubleTheReps() {
        val plan = SessionPlan(
            handMode = HandMode.bothHands,
            sets = listOf(SetPlan(grip = GripSpec(), repsPerSide = 6)),
        )

        val slots = PlanMath.sequence(plan)
        assertEquals(6, slots.size)
        assertEquals(6, PlanMath.totalReps(plan))
        assertTrue(slots.all { it.side == Side.both })
    }

    /// Stated over `sideCount` rather than over the one case that has it today, so an
    /// injured-hand mode added in a later milestone inherits the guarantee.
    @Test
    fun singleSidedModesDoNotDoubleTheReps() {
        for (mode in HandMode.entries.filter { it.sideCount == 1 }) {
            val plan = SessionPlan(
                handMode = mode,
                sets = listOf(SetPlan(grip = GripSpec(), repsPerSide = 6)),
            )
            assertEquals(6, PlanMath.totalReps(plan), "$mode")
            assertNull(PlanMath.repsPerSide(plan), "$mode")
            assertEquals(
                List(6) { mode.startSide },
                PlanMath.sequence(plan).map { it.side },
                "$mode",
            )
        }
    }

    @Test
    fun alternateEachRepInterleavesSides() {
        val set = SetPlan(grip = GripSpec(), repsPerSide = 3)
        val plan = SessionPlan(handMode = HandMode.alternateEachRep, sets = listOf(set))
        assertEquals(
            listOf(Side.left, Side.right, Side.left, Side.right, Side.left, Side.right),
            PlanMath.handSequence(set, plan),
        )
    }

    @Test
    fun alternateEachSetGroupsSides() {
        val set = SetPlan(grip = GripSpec(), repsPerSide = 3)
        val plan = SessionPlan(handMode = HandMode.alternateEachSet, sets = listOf(set))
        assertEquals(
            listOf(Side.left, Side.left, Side.left, Side.right, Side.right, Side.right),
            PlanMath.handSequence(set, plan),
        )
    }

    /// The odd rep counts are the point: a running counter that did not reset would
    /// start set 1 on the right. `side(forRep:mode:repsPerSide:)` deliberately takes no
    /// setIndex, so the rule is unrepresentable to break.
    @Test
    fun alternationResetsToTheStartSideAtEverySetBoundary() {
        for (mode in listOf(HandMode.alternateEachRep, HandMode.alternateEachSet)) {
            val plan = SessionPlan(
                handMode = mode,
                sets = listOf(1, 2, 3).map { SetPlan(grip = GripSpec(), repsPerSide = it) },
            )

            val slots = PlanMath.sequence(plan)
            for (setIndex in 0 until 3) {
                val first = slots.firstOrNull { it.setIndex == setIndex && it.repIndex == 0 }
                assertEquals(Side.left, first?.side, "set $setIndex under $mode")
                assertEquals(true, first?.isFirstOfSet)
            }
            assertEquals(Side.left, PlanMath.side(forRep = 0, mode = mode, repsPerSide = 3))
            assertEquals(Side.left, mode.startSide)
        }
    }

    @Test
    fun everySetIsSideBalanced() {
        for (mode in listOf(HandMode.alternateEachRep, HandMode.alternateEachSet)) {
            for (repsPerSide in 1..10) {
                val plan = SessionPlan(
                    handMode = mode,
                    sets = listOf(SetPlan(grip = GripSpec(), repsPerSide = repsPerSide)),
                )
                val sides = PlanMath.handSequence(plan.sets[0], plan)
                assertEquals(repsPerSide, sides.count { it == Side.left }, "$mode $repsPerSide")
                assertEquals(repsPerSide, sides.count { it == Side.right }, "$mode $repsPerSide")
            }
        }
    }

    // MARK: - Rests, breaks and lead-ins

    @Test
    fun lastRepOfASetRestsForTheSetBreakNotTheIntraSetRest() {
        val plan = SessionPlan(
            sets = listOf(
                SetPlan(grip = GripSpec(), repsPerSide = 6),
                SetPlan(grip = GripSpec(), repsPerSide = 6),
            ),
            restSeconds = 20,
            setBreakSeconds = 60,
        )

        val firstSet = PlanMath.sequence(plan).filter { it.setIndex == 0 }
        assertEquals(12, firstSet.size)
        assertEquals(20, firstSet[10].restAfter)
        assertEquals(60, firstSet[11].restAfter)
        assertTrue(firstSet[11].isLastOfSet)
        assertFalse(firstSet[10].isLastOfSet)
    }

    /// Nobody rests after the last pull. Without this the estimate is one whole set
    /// break too long, and the runner would sit on a rest screen at the end.
    @Test
    fun finalRepOfTheSessionHasZeroRestAfter() {
        val slots = PlanMath.sequence(starter)
        assertEquals(0, slots.last().restAfter)
        assertEquals(true, slots.last().isLastOfSet)
        assertEquals(5, slots.last().setIndex)
    }

    @Test
    fun leadInIsChargedOncePerSetOnTheFirstRepOnly() {
        val slots = PlanMath.sequence(starter)
        for (slot in slots) {
            assertEquals(
                if (slot.isFirstOfSet) 5 else 0, slot.leadInBefore,
                "set ${slot.setIndex} rep ${slot.repIndex}",
            )
        }
        assertEquals(6 * 5, slots.sumOf { it.leadInBefore })
    }

    /// The structural expression of "one edit across all six sets": null means follow
    /// the routine, so moving the routine's rest moves every non-overriding set at once.
    @Test
    fun perSetOverrideBeatsTheRoutineAndNilInherits() {
        val overriding = SetPlan(
            grip = GripSpec(), repsPerSide = 2, holdSeconds = 12, restSeconds = null,
        )
        val inheriting = SetPlan(grip = GripSpec(), repsPerSide = 2)
        var plan = SessionPlan(
            sets = listOf(overriding, inheriting), holdSeconds = 10, restSeconds = 20,
        )

        assertEquals(12, PlanMath.hold(overriding, plan))
        assertEquals(20, PlanMath.rest(overriding, plan))
        assertEquals(10, PlanMath.hold(inheriting, plan))
        assertEquals(20, PlanMath.rest(inheriting, plan))
        assertTrue(overriding.overridesTiming)
        assertFalse(inheriting.overridesTiming)

        plan = plan.copy(restSeconds = 45)
        assertEquals(45, PlanMath.rest(overriding, plan))
        assertEquals(45, PlanMath.rest(inheriting, plan))
        assertEquals(12, PlanMath.hold(overriding, plan), "the override still stands")
    }

    // MARK: - Degenerate plans

    /// A zero-rep set is not a pause: it costs no lead-in and no set break, and it must
    /// not leave a hole in the setIndex values the runner walks.
    @Test
    fun zeroRepSetIsDroppedEntirelyAndAddsNoSetBreak() {
        val sets = starter.sets.toMutableList()
        sets.add(
            2,
            SetPlan(
                grip = GripSpec(25, FingerSet.four, GripPosition.drag),
                repsPerSide = 0,
            ),
        )
        val plan = starter.copy(sets = sets)

        assertEquals(1290, PlanMath.totalSeconds(plan))
        assertEquals(6, PlanMath.setCount(plan))
        assertEquals(6, plan.executable.sets.size)
        assertEquals((0 until 6).toSet(), PlanMath.sequence(plan).map { it.setIndex }.toSet())
    }

    @Test
    fun emptyPlanHasZeroDurationZeroRepsAndAnEmptySequence() {
        val plan = SessionPlan()
        assertEquals(emptyList(), plan.sets)
        assertEquals(emptyList(), PlanMath.sequence(plan))
        assertEquals(0, PlanMath.totalSeconds(plan))
        assertEquals(0, PlanMath.tensionSeconds(plan))
        assertEquals(0, PlanMath.totalReps(plan))
        assertEquals(0, PlanMath.setCount(plan))
        assertNull(PlanMath.sharedEdgeMM(plan))
        assertEquals(emptyList(), PlanMath.gripTotals(plan))
    }

    // MARK: - Grip roll-up

    @Test
    fun gripTotalsMergeByCanonicalKeyInFirstAppearanceOrder() {
        val open = GripSpec(20, FingerSet.frontTwo, GripPosition.openHand)
        val crimped = GripSpec(20, FingerSet.frontTwo, GripPosition.fullCrimp)
        val plan = SessionPlan(
            sets = listOf(
                SetPlan(grip = open, repsPerSide = 2),
                SetPlan(grip = crimped, repsPerSide = 1),
                SetPlan(grip = open, repsPerSide = 3),
            ),
            handMode = HandMode.alternateEachRep,
            holdSeconds = 10,
        )

        val totals = PlanMath.gripTotals(plan)
        assertEquals(
            listOf("20|IM|openHand", "20|IM|fullCrimp"),
            totals.map { it.grip.key },
            "merged by key, in first-appearance order",
        )
        assertEquals(5, totals[0].repsPerSide)
        assertEquals(10, totals[0].totalReps)
        assertEquals(100, totals[0].tensionSeconds)
        assertEquals(1, totals[1].repsPerSide)
        assertEquals(2, totals[1].totalReps)
        assertEquals(20, totals[1].tensionSeconds)
        assertFalse(totals[0].line.isEmpty())
    }

    /// Today's meta line starts at "6 sets" rather than claiming an edge the routine
    /// does not actually share.
    @Test
    fun sharedEdgeIsNilWhenSetsDisagree() {
        val plan = starter
        assertEquals(20, PlanMath.sharedEdgeMM(plan))
        val edited = plan.copy(
            sets = plan.sets.mapIndexed { index, set ->
                if (index == 3) set.copy(grip = set.grip.withEdgeMM(18)) else set
            }
        )
        assertNull(PlanMath.sharedEdgeMM(edited))
    }

    // MARK: - Loads

    /// The band is rounded to half kilos: a target you can actually read off a gauge,
    /// and never wider than the percentage it claims to be.
    @Test
    fun suggestedBandRoundsToHalfKilos() {
        // 23.7 × 0.20 = 4.74, whose nearest half-kilo is 4.5 (not 5.0 — 4.74 is 0.24
        // below 4.5 and 0.26 under 5.0). 23.7 × 0.30 = 7.11 → 7.0.
        // `roundedToHalfKgLandsOnTheHalfKiloGrid` asserts no value moves more than
        // half a step, which 4.74 → 5.0 would break; these two must agree.
        assertEquals(4.5..7.0, PlanMath.suggestedBand(maxKg = 23.7))
        assertNull(PlanMath.suggestedBand(maxKg = 0.0), "no max is not a 0...0 band")
        assertNull(PlanMath.suggestedBand(maxKg = -4.0))
    }

    @Test
    fun roundedToHalfKgLandsOnTheHalfKiloGrid() {
        for (kg in listOf(0.0, 4.74, 7.11, 12.0, 23.7, 41.26)) {
            val rounded = PlanMath.roundedToHalfKg(kg)
            assertEquals(rounded * 2, roundedAwayFromZero(rounded * 2), 0.0001, "$kg")
            assertTrue(abs(rounded - kg) <= 0.2501, "$kg moved more than half a step")
        }
    }

    private fun roundedAwayFromZero(v: Double): Double = Math.floor(abs(v) + 0.5) * (if (v < 0) -1.0 else 1.0)

    @Test
    fun percentOfMaxIsNilWithoutAUsableMax() {
        val quarter = PlanMath.percentOfMax(5.0, maxKg = 20.0)
        assertNotNull(quarter)
        assertEquals(0.25, quarter, 0.0001)
        assertNull(PlanMath.percentOfMax(5.0, maxKg = 0.0))
        assertNull(PlanMath.percentOfMax(5.0, maxKg = -1.0))
    }

    // MARK: - Target loads

    private fun gripped(fingers: FingerSet, position: GripPosition = GripPosition.halfCrimp): GripSpec =
        GripSpec(edgeMM = 20, fingers = fingers, position = position)

    /// The precedence, all four rungs, on one plan. Kilograms typed on a set are the most
    /// specific thing anyone can say and must never be second-guessed by arithmetic.
    @Test
    fun targetBandPrecedenceRunsSetKgThenSetPercentThenRoutinePercent() {
        val plan = SessionPlan(targetLoPercent = 0.20, targetHiPercent = 0.30)

        val typed = SetPlan(
            grip = gripped(FingerSet.four),
            targetLoKg = 6.0, targetHiKg = 9.0,
            targetLoPercent = 0.50, // present, and deliberately ignored
        )
        assertEquals(
            6.0..9.0, PlanMath.targetBand(typed, plan, maxKg = 20.0),
            "a number a person typed wins over every percentage",
        )

        val ownPercent = SetPlan(
            grip = gripped(FingerSet.four), targetLoPercent = 0.40, targetHiPercent = 0.50,
        )
        assertEquals(
            8.0..10.0, PlanMath.targetBand(ownPercent, plan, maxKg = 20.0),
            "the set's own percentage beats the routine's",
        )

        val inherits = SetPlan(grip = gripped(FingerSet.four))
        assertEquals(
            4.0..6.0, PlanMath.targetBand(inherits, plan, maxKg = 20.0),
            "and with neither, the routine's band applies",
        )

        assertNull(
            PlanMath.targetBand(inherits, SessionPlan(), maxKg = 20.0),
            "no band anywhere is no target",
        )
    }

    /// There is no percentage of a max nobody has recorded. Inventing one would put a
    /// confident kilogram figure on a screen somebody trains against.
    @Test
    fun aPercentageTargetResolvesToNothingWithoutAMax() {
        val plan = SessionPlan(targetLoPercent = 0.20, targetHiPercent = 0.30)
        val set = SetPlan(grip = gripped(FingerSet.four))

        assertNull(PlanMath.targetBand(set, plan, maxKg = null))
        assertNull(PlanMath.targetBand(set, plan, maxKg = 0.0))
        assertNull(PlanMath.targetBand(set, plan, maxKg = -3.0))

        // But an explicit kg band needs no max at all — that is the whole point of it.
        val typed = set.copy(targetLoKg = 4.0, targetHiKg = 5.0)
        assertEquals(4.0..5.0, PlanMath.targetBand(typed, plan, maxKg = null))
    }

    /// ONE band, the right load on every grip — the reason this is a percentage and not
    /// six numbers to author.
    @Test
    fun oneRoutinePercentageResolvesPerGripFromEachGripsOwnMax() {
        val plan = SessionPlan(
            sets = listOf(
                SetPlan(grip = gripped(FingerSet.four)),
                SetPlan(grip = gripped(FingerSet.frontTwo)),
                SetPlan(grip = gripped(FingerSet.middleTwo)),
            ),
            targetLoPercent = 0.20,
            targetHiPercent = 0.30,
        )

        val maxes = MaxTable()
        maxes.record(40.0, gripped(FingerSet.four).key, Side.both)
        maxes.record(20.0, gripped(FingerSet.frontTwo).key, Side.both)
        // middleTwo deliberately absent
        val resolved = PlanMath.resolvingTargets(plan, maxes)

        assertEquals(8.0..12.0, resolved.sets[0].targetBand)
        assertEquals(4.0..6.0, resolved.sets[1].targetBand)
        assertNull(resolved.sets[2].targetBand, "no max, no target — and no guess")
        assertEquals(0.20, resolved.targetLoPercent, "the percentages survive resolution")
    }

    /// Resolution happens ONCE, at session start, and the resolved kilograms are what the
    /// runner executes — so a max recorded later cannot rewrite what a session told you
    /// to pull. Same freeze-at-save rule the routine's name already follows.
    @Test
    fun resolvedTargetsReachEveryRepSlot() {
        val plan = SessionPlan(
            sets = listOf(SetPlan(grip = gripped(FingerSet.four), repsPerSide = 2)),
            handMode = HandMode.bothHands,
            targetLoPercent = 0.20,
            targetHiPercent = 0.25,
        )

        val maxes = MaxTable()
        maxes.record(30.0, gripped(FingerSet.four).key, Side.both)

        val resolved = PlanMath.resolvingTargets(plan, maxes)
        val slots = PlanMath.sequence(resolved)

        assertEquals(2, slots.size)
        assertTrue(slots.all { it.targetBand == 6.0..7.5 })

        // The same answer straight from the table, which is the path a real session
        // takes now that loads resolve per REP — see `sequence(plan, maxes)`.
        val direct = PlanMath.sequence(plan, maxes)
        assertTrue(direct.all { it.targetBand == 6.0..7.5 })

        // With NEITHER a baked band nor a table, a percentage never becomes kilograms.
        assertTrue(PlanMath.sequence(plan).all { it.targetBand == null })
    }

    /// Half-kilo steps, like every other load in the app: 22.3 × 0.17 = 3.791.
    @Test
    fun resolvedPercentagesLandOnTheHalfKiloGrid() {
        val plan = SessionPlan(targetLoPercent = 0.17, targetHiPercent = 0.22)
        val band = PlanMath.targetBand(SetPlan(grip = gripped(FingerSet.four)), plan, maxKg = 22.3)
        assertNotNull(band)
        assertEquals(4.0..5.0, band, "3.791 → 4.0 and 4.906 → 5.0")
    }

    // MARK: - Folding deck overrides back into inheritance

    /// Uniform TIMING still consolidates up (the sets resolve identically either way).
    /// TARGETS deliberately do not: load lives per set (2026-08-10), the builder has no
    /// routine-level load editor left, and a promoted band would be active yet
    /// invisible. Every set keeps the band it was given.
    @Test
    fun uniformPerSetOverridesArePromotedToTheRoutine() {
        val draft = RoutineDraft().let { d ->
            d.copy(
                plan = d.plan.copy(
                    handMode = HandMode.bothHands,
                    sets = (0 until 3).map {
                        SetPlan(
                            grip = gripped(FingerSet.four), repsPerSide = 6,
                            holdSeconds = 7, // every set, the same value
                            restSeconds = 25,
                            targetLoPercent = 0.17, targetHiPercent = 0.22,
                        )
                    },
                )
            )
        }

        val clean = draft.normalized

        assertEquals(7, clean.plan.holdSeconds, "the routine now says what every set said")
        assertEquals(25, clean.plan.restSeconds)
        assertNull(clean.plan.targetPercentBand, "targets are never promoted")
        assertTrue(
            clean.plan.sets.all { it.holdSeconds == null },
            "and the sets go back to inheriting, so one edit moves them all",
        )
        assertTrue(clean.plan.sets.all { it.restSeconds == null })
        assertTrue(
            clean.plan.sets.all { it.targetPercentBand == 0.17..0.22 },
            "each set keeps its own band",
        )
    }

    /// Genuinely different per-grip timing is the whole point of the grip card, so it
    /// must survive untouched — only the override that AGREES with the routine is cleared,
    /// because one that merely agrees silently skips that set the next time the rhythm
    /// changes.
    @Test
    fun mixedOverridesSurviveAndOnlyAgreeingOnesAreCleared() {
        val crimp = SetPlan(
            grip = gripped(FingerSet.frontTwo, GripPosition.fullCrimp), repsPerSide = 1,
            holdSeconds = 5, // deliberately different
        )
        val drag = SetPlan(
            grip = gripped(FingerSet.four, GripPosition.openHand), repsPerSide = 6,
            holdSeconds = 20, // also different
        )
        val agrees = SetPlan(
            grip = gripped(FingerSet.four), repsPerSide = 6,
            holdSeconds = 10, // same as the routine
        )
        val draft = RoutineDraft().let { d ->
            d.copy(
                plan = d.plan.copy(
                    handMode = HandMode.bothHands,
                    holdSeconds = 10,
                    sets = listOf(crimp, drag, agrees),
                )
            )
        }

        val clean = draft.normalized

        assertEquals(10, clean.plan.holdSeconds, "nothing to promote — they disagree")
        assertEquals(5, clean.plan.sets[0].holdSeconds, "5 s crimps survive")
        assertEquals(20, clean.plan.sets[1].holdSeconds, "20 s drags survive")
        assertNull(clean.plan.sets[2].holdSeconds, "the one that agreed now follows")
    }

    /// THE PROPERTY THAT MAKES IT SAFE TO RUN ON EVERY SAVE: consolidation may move a
    /// value between the routine and its sets, but no set may resolve to anything
    /// different afterwards.
    @Test
    fun consolidationNeverChangesWhatASetResolvesTo() {
        val a = SetPlan(
            grip = gripped(FingerSet.four), repsPerSide = 6,
            holdSeconds = 10, // agrees — will be cleared
            restSeconds = 45, // differs — survives
        )
        val b = SetPlan(
            grip = gripped(FingerSet.frontTwo), repsPerSide = 2,
            targetLoPercent = 0.40, // differs — survives
            targetHiPercent = 0.50,
        )
        val c = SetPlan(grip = gripped(FingerSet.middleTwo), repsPerSide = 2) // inherits everything
        val draft = RoutineDraft().let { d ->
            d.copy(
                plan = d.plan.copy(
                    handMode = HandMode.bothHands,
                    holdSeconds = 10,
                    restSeconds = 20,
                    targetLoPercent = 0.20,
                    targetHiPercent = 0.30,
                    sets = listOf(a, b, c),
                )
            )
        }

        val maxes = mapOf(
            gripped(FingerSet.four).key to 40.0,
            gripped(FingerSet.frontTwo).key to 20.0,
            gripped(FingerSet.middleTwo).key to 30.0,
        )
        val before = draft.plan.sets.map {
            Triple(
                PlanMath.hold(it, draft.plan),
                PlanMath.rest(it, draft.plan),
                PlanMath.targetBand(it, draft.plan, maxKg = maxes[it.grip.key]),
            )
        }

        val clean = draft.normalized
        val after = clean.plan.sets.map {
            Triple(
                PlanMath.hold(it, clean.plan),
                PlanMath.rest(it, clean.plan),
                PlanMath.targetBand(it, clean.plan, maxKg = maxes[it.grip.key]),
            )
        }

        assertEquals(before.size, after.size)
        for ((index, pair) in before.zip(after).withIndex()) {
            assertEquals(pair.first.first, pair.second.first, "hold changed on set $index")
            assertEquals(pair.first.second, pair.second.second, "rest changed on set $index")
            assertEquals(pair.first.third, pair.second.third, "target changed on set $index")
        }
    }

    /// A one-grip routine is the deck's starting state, and every value on it is
    /// "uniform" by definition — so it must promote rather than leave the routine
    /// quoting a default the single set does not use.
    @Test
    fun aSingleGripRoutinePromotesItsOwnValues() {
        val only = SetPlan(grip = gripped(FingerSet.four), repsPerSide = 10, holdSeconds = 12)
        val draft = RoutineDraft().let { d ->
            d.copy(plan = d.plan.copy(handMode = HandMode.bothHands, sets = listOf(only)))
        }

        val clean = draft.normalized
        assertEquals(12, clean.plan.holdSeconds)
        assertNull(clean.plan.sets[0].holdSeconds)
        assertEquals(12, PlanMath.hold(clean.plan.sets[0], clean.plan))
    }

    // MARK: - Formatting

    @Test
    fun durationAndClockFormatting() {
        assertEquals("45 s", PlanMath.durationText(45))
        assertEquals("21 min 30 s", PlanMath.durationText(1290))
        assertEquals("19 min", PlanMath.durationText(1140))
        assertEquals("0 s", PlanMath.durationText(0))
        assertEquals("0 s", PlanMath.durationText(-5))

        assertEquals("5:45", PlanMath.clockText(345))
        assertEquals("21:30", PlanMath.clockText(1290))
        assertEquals("0:45", PlanMath.clockText(45))
    }

    /// Truncation, not rounding: 1290 s rounds to 22 and would contradict every frozen
    /// copy string in the app.
    @Test
    fun approxMinutesTruncatesRatherThanRounds() {
        assertEquals("≈21 min", PlanMath.approxMinutes(1290))
        assertEquals("≈19 min", PlanMath.approxMinutes(1140))
        assertEquals("45 s", PlanMath.approxMinutes(45))
    }

    // MARK: - Peak intensity

    /// A percentage IS the intensity: "20–30 %" says how hard the routine is whether or
    /// not anybody has ever measured that grip, and the HI bound is the figure — the
    /// hardest thing prescribed, not the average of the band.
    @Test
    fun routinePercentageNamesThePeakIntensityWithNoMaxOnFile() {
        val plan = SessionPlan(
            sets = listOf(
                SetPlan(grip = gripped(FingerSet.four), repsPerSide = 6),
                SetPlan(grip = gripped(FingerSet.frontTwo), repsPerSide = 2),
            ),
            handMode = HandMode.bothHands,
            targetLoPercent = 0.20,
            targetHiPercent = 0.30,
        )

        val peak = PlanMath.peakIntensity(plan, MaxTable())
        assertNotNull(peak)
        assertEquals(0.30, peak, 0.0001, "the hi bound, with no max needed")
        assertEquals(PlanMath.IntensityBand.light, PlanMath.IntensityBand.band(peak))
    }

    /// `targetBand`'s own precedence, and the fold that names the routine: the set's own
    /// percentage beats the one it would otherwise inherit, and the HARDEST set speaks for
    /// the whole routine.
    @Test
    fun aSetsOwnPercentageBeatsTheRoutinesAndTheHardestSetSpeaks() {
        val plan = SessionPlan(
            sets = listOf(
                SetPlan(grip = gripped(FingerSet.four), repsPerSide = 6), // inherits 20–30 %
                SetPlan(
                    grip = gripped(FingerSet.four), repsPerSide = 2,
                    targetLoPercent = 0.80, targetHiPercent = 0.90,
                ),
            ),
            handMode = HandMode.bothHands,
            targetLoPercent = 0.20,
            targetHiPercent = 0.30,
        )

        val peak = PlanMath.peakIntensity(plan, MaxTable())
        assertNotNull(peak)
        assertEquals(0.90, peak, 0.0001)
        assertEquals(PlanMath.IntensityBand.nearMax, PlanMath.IntensityBand.band(peak))
    }

    /// `MaxTable`'s refusal to guess, read through the intensity: a `both` rep resolves
    /// against a both-hands max ONLY. Adding the two hands together would be a silent,
    /// doubled guess pointed at somebody's fingers.
    @Test
    fun aKgBandOnABothHandsSetResolvesOnlyAgainstABothHandsMax() {
        val plan = SessionPlan(
            sets = listOf(
                SetPlan(
                    grip = gripped(FingerSet.four), repsPerSide = 4,
                    targetLoKg = 20.0, targetHiKg = 30.0,
                )
            ),
            handMode = HandMode.bothHands,
        )

        val oneHandAtATime = MaxTable()
        oneHandAtATime.record(40.0, gripped(FingerSet.four).key, Side.left)
        oneHandAtATime.record(40.0, gripped(FingerSet.four).key, Side.right)
        val unresolved = PlanMath.peakIntensity(plan, oneHandAtATime)
        assertNull(unresolved, "two one-handed maxes never synthesise a two-handed one")
        assertEquals(
            PlanMath.IntensityBand.unknown, PlanMath.IntensityBand.band(unresolved),
            "and unknown is not light",
        )

        val bothHands = oneHandAtATime.copy()
        bothHands.record(40.0, gripped(FingerSet.four).key, Side.both)
        val peak = PlanMath.peakIntensity(plan, bothHands)
        assertNotNull(peak)
        assertEquals(0.75, peak, 0.0001, "30 kg of a 40 kg two-handed max")
        assertEquals(PlanMath.IntensityBand.moderate, PlanMath.IntensityBand.band(peak))
    }

    /// The same kilograms are a harder morning for the weaker hand, and that hand's
    /// experience names the day. The fallback half matters just as much: one both-hands
    /// max still serves an alternating routine exactly as it did before hands existed.
    @Test
    fun aKgBandTakesTheHarderHandsFraction() {
        val plan = SessionPlan(
            sets = listOf(
                SetPlan(
                    grip = gripped(FingerSet.four), repsPerSide = 2,
                    targetLoKg = 18.0, targetHiKg = 24.0,
                )
            ),
            handMode = HandMode.alternateEachRep,
        )

        val perHand = MaxTable()
        perHand.record(40.0, gripped(FingerSet.four).key, Side.left)
        perHand.record(30.0, gripped(FingerSet.four).key, Side.right) // the weaker hand
        val peak = PlanMath.peakIntensity(plan, perHand)
        assertNotNull(peak)
        assertEquals(0.80, peak, 0.0001, "24/30 on the right, not the flattering 24/40 on the left")
        assertEquals(PlanMath.IntensityBand.nearMax, PlanMath.IntensityBand.band(peak))

        val shared = MaxTable()
        shared.record(30.0, gripped(FingerSet.four).key, Side.both)
        val sharedPeak = PlanMath.peakIntensity(plan, shared)
        assertNotNull(sharedPeak)
        assertEquals(0.80, sharedPeak, 0.0001, "both hands fall back to the shared max")
    }

    /// A set that cannot resolve contributes NOTHING rather than making the whole answer
    /// unknown — five sets at 25 % plus one unmeasured grip is still a 25 % routine. And
    /// the kg band does not fall back to a percentage the same set happens to carry: a
    /// number a person typed is never second-guessed by arithmetic.
    @Test
    fun aKgSetWithNoMaxIsSilentWhileAPercentageSetStillSpeaks() {
        val plan = SessionPlan(
            sets = listOf(
                SetPlan(
                    grip = gripped(FingerSet.middleTwo), repsPerSide = 2,
                    targetLoKg = 30.0, targetHiKg = 34.0,
                    targetLoPercent = 0.90, targetHiPercent = 0.95,
                ),
                SetPlan(
                    grip = gripped(FingerSet.four), repsPerSide = 6,
                    targetLoPercent = 0.20, targetHiPercent = 0.25,
                ),
            ),
            handMode = HandMode.bothHands,
        )

        val peak = PlanMath.peakIntensity(plan, MaxTable())
        assertNotNull(peak)
        assertEquals(
            0.25, peak, 0.0001,
            "the percentage set speaks; the unmeasured kg set is silent, and its " +
                "own 95 % is never reached because kilograms outrank it",
        )
        assertEquals(PlanMath.IntensityBand.light, PlanMath.IntensityBand.band(peak))
    }

    /// A max on file is not a prescription, and null is the honest reading of a routine
    /// that goes by feel.
    @Test
    fun aRoutineWithNoTargetAnywhereHasNoPeakIntensity() {
        val plan = SessionPlan(
            sets = listOf(SetPlan(grip = gripped(FingerSet.four), repsPerSide = 6)),
            handMode = HandMode.bothHands,
        )
        val maxes = MaxTable()
        maxes.record(40.0, gripped(FingerSet.four).key, Side.both)

        assertNull(PlanMath.peakIntensity(plan, maxes))
        assertNull(PlanMath.peakIntensity(SessionPlan(), maxes))
        assertEquals(PlanMath.IntensityBand.unknown, PlanMath.IntensityBand.band(null))
    }

    /// A set the user emptied out prescribes nothing — the same `executable` rule every
    /// other fold in `PlanMath` obeys.
    @Test
    fun aZeroRepSetsBandNeverNamesTheRoutinesIntensity() {
        val plan = SessionPlan(
            sets = listOf(
                SetPlan(
                    grip = gripped(FingerSet.four), repsPerSide = 0,
                    targetLoPercent = 0.85, targetHiPercent = 0.95,
                ),
                SetPlan(
                    grip = gripped(FingerSet.four), repsPerSide = 6,
                    targetLoPercent = 0.20, targetHiPercent = 0.25,
                ),
            ),
            handMode = HandMode.bothHands,
        )

        val peak = PlanMath.peakIntensity(plan, MaxTable())
        assertNotNull(peak)
        assertEquals(0.25, peak, 0.0001)
    }

    /// Nothing is clamped to 1.0. A kg band authored against a max that has since gone
    /// stale genuinely prescribes 120 %, and reporting it as 100 % would hide exactly the
    /// case worth seeing.
    @Test
    fun aPrescriptionOverOneHundredPercentIsReportedRatherThanClamped() {
        val plan = SessionPlan(
            sets = listOf(
                SetPlan(
                    grip = gripped(FingerSet.four), repsPerSide = 2,
                    targetLoKg = 25.0, targetHiKg = 30.0,
                )
            ),
            handMode = HandMode.bothHands,
        )
        val maxes = MaxTable()
        maxes.record(25.0, gripped(FingerSet.four).key, Side.both)

        val peak = PlanMath.peakIntensity(plan, maxes)
        assertNotNull(peak)
        assertEquals(1.20, peak, 0.0001)
        assertEquals(
            PlanMath.IntensityBand.nearMax, PlanMath.IntensityBand.band(peak),
            "120 % is a max effort, not an error",
        )

        // Same for a percentage over 1.0. `SetPlan.percentRange` clamps on DECODE; a value
        // set in memory is not clamped, and it must still report rather than trap.
        val overPercent = SessionPlan(
            sets = listOf(
                SetPlan(
                    grip = gripped(FingerSet.four), repsPerSide = 2,
                    targetLoPercent = 1.00, targetHiPercent = 1.10,
                )
            ),
            handMode = HandMode.bothHands,
        )
        val overPeak = PlanMath.peakIntensity(overPercent, MaxTable())
        assertNotNull(overPeak)
        assertEquals(1.10, overPeak, 0.0001)
        assertEquals(PlanMath.IntensityBand.nearMax, PlanMath.IntensityBand.band(1.10))
    }

    /// The boundaries the UI colours from, owned explicitly: 0.30 is light and 0.80 is
    /// nearMax. Understating a max effort is the harmful direction, so both edges belong
    /// to the louder side at the top and the quieter side at the bottom.
    @Test
    fun intensityBandBoundariesAreOwnedByLightAndNearMax() {
        assertEquals(
            PlanMath.IntensityBand.light, PlanMath.IntensityBand.band(0.30),
            "0.30 is light, not moderate",
        )
        assertEquals(
            PlanMath.IntensityBand.nearMax, PlanMath.IntensityBand.band(0.80),
            "0.80 is nearMax, not moderate",
        )
        assertEquals(PlanMath.IntensityBand.moderate, PlanMath.IntensityBand.band(0.31))
        assertEquals(PlanMath.IntensityBand.moderate, PlanMath.IntensityBand.band(0.79))
        assertEquals(PlanMath.IntensityBand.light, PlanMath.IntensityBand.band(0.20))
        assertEquals(PlanMath.IntensityBand.nearMax, PlanMath.IntensityBand.band(0.95))
        assertEquals(PlanMath.IntensityBand.unknown, PlanMath.IntensityBand.band(null))
        // A NaN fails both comparisons and would otherwise be reported as `moderate` — a
        // confident answer about nothing.
        assertEquals(PlanMath.IntensityBand.unknown, PlanMath.IntensityBand.band(Double.NaN))
        assertEquals(
            PlanMath.IntensityBand.unknown,
            PlanMath.IntensityBand.band(Double.POSITIVE_INFINITY),
        )
    }

    /// The two shipping prefills, as fixtures: the daily ritual prescribes nothing by
    /// design, and the C4 ladder's top ramp set is what makes a max day read as one.
    @Test
    fun theShippingPrefillsReportTheIntensityTheyPrescribe() {
        assertNull(
            PlanMath.peakIntensity(starter, MaxTable()),
            "the daily no-hangs go by feel and set no target at all",
        )

        val maxDay = PlanMath.peakIntensity(RoutineDraft.maxDay.plan, MaxTable())
        assertNotNull(maxDay)
        assertEquals(0.90, maxDay, 0.0001, "the 80–90 % ramp set")
        assertEquals(PlanMath.IntensityBand.nearMax, PlanMath.IntensityBand.band(maxDay))
    }
}
