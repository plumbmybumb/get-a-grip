// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// Target loads as a percentage of CRITICAL FORCE (`TargetBasis`, `CriticalForceTable`,
/// `docs/TARGET_BASIS.md`) — the iOS `TargetBasisTests`, rule for rule. The cross-platform
/// cases are `TargetBasisFixtureTests`.
class TargetBasisTests {
    private val grip = GripSpec()
    private val key = grip.key

    private fun cfTable(vararg entries: Pair<Side, Double>) =
        CriticalForceTable().apply { for ((side, kg) in entries) record(kg, key, side) }

    private fun maxTable(vararg entries: Pair<Side, Double>) =
        MaxTable().apply { for ((side, kg) in entries) record(kg, key, side) }

    private fun plan(set: SetPlan, mode: HandMode = HandMode.alternateEachRep) =
        SessionPlan(sets = listOf(set), handMode = mode)

    private fun cfSet(lo: Double, hi: Double) =
        SetPlan(grip = grip, repsPerSide = 2, targetLoPercent = lo, targetHiPercent = hi)
            .withTargetBasis(TargetBasis.criticalForce)

    private fun decodeSet(text: String) = SetPlan.fromJson(Json.parseToJsonElement(text))!!

    // MARK: - The model

    @Test fun aMissingOrUnknownBasisReadsAsMaxAndTheUnknownRawSurvives() {
        val old = decodeSet("""{"repsPerSide":3,"targetLoPercent":0.2,"targetHiPercent":0.3}""")
        assertNull(old.targetBasisRaw)
        assertEquals(TargetBasis.max, old.targetBasis)

        val future = decodeSet("""{"repsPerSide":3,"targetLoPercent":0.2,"targetBasis":"wPrime"}""")
        assertEquals(TargetBasis.max, future.targetBasis, "a basis this build cannot read is a max")
        assertTrue(BlobCodec.encode(future)!!.contains(""""targetBasis":"wPrime""""), "a newer build's raw survives")

        assertNull(decodeSet("""{"repsPerSide":3,"targetBasis":""}""").targetBasisRaw)
        assertNull(decodeSet("""{"repsPerSide":3,"targetBasis":7}""").targetBasisRaw)
        assertEquals(TargetBasis.criticalForce,
            decodeSet("""{"repsPerSide":3,"targetLoPercent":0.7,"targetBasis":"criticalForce"}""").targetBasis)
    }

    @Test fun anOldPlanEncodesExactlyAsItDidBefore() {
        val draft = RoutineDraft.maxDay.normalized
        val bytes = BlobCodec.encode(draft.plan)!!
        assertFalse(bytes.contains("targetBasis"))
        val back = SessionPlan.fromJson(Json.parseToJsonElement(bytes))!!
        assertEquals(draft.plan, back)
        assertEquals(bytes, BlobCodec.encode(back))
    }

    @Test fun choosingMaxIsEncodedOnceChosen() {
        val set = SetPlan(grip = grip, repsPerSide = 1, targetLoPercent = 0.2, targetHiPercent = 0.3)
            .withTargetBasis(TargetBasis.max)
        assertTrue(BlobCodec.encode(set)!!.contains(""""targetBasis":"max""""))
    }

    @Test fun normalizedDemotesTheRoutineBasisWithItsBandAndDropsOrphans() {
        val kgSet = SetPlan(grip = grip, repsPerSide = 1, targetLoKg = 8.0, targetHiKg = 10.0)
            .withTargetBasis(TargetBasis.criticalForce)
        val orphan = SetPlan(grip = grip, repsPerSide = 1).withTargetBasis(TargetBasis.criticalForce)
        val cfWithLength = cfSet(0.5, 0.6).copy(targetMaxSeconds = 10)
        val draft = RoutineDraft.blank().let {
            it.copy(plan = it.plan.copy(
                sets = listOf(kgSet, orphan, cfWithLength),
                targetLoPercent = 0.7, targetHiPercent = 0.8,
                targetBasisRaw = TargetBasis.criticalForce.rawValue, targetMaxSeconds = 7,
            ))
        }
        val saved = draft.normalized.plan
        assertNull(saved.targetBasisRaw, "the routine-level basis is never persisted")
        assertNull(saved.targetPercentBand)
        assertNull(saved.sets[0].targetBasisRaw, "a kg band carries no basis")
        assertEquals(TargetBasis.criticalForce, saved.sets[1].targetBasis, "the inheriting set took the routine's basis")
        assertEquals(0.7..0.8, saved.sets[1].targetPercentBand)
        assertNull(saved.sets[1].targetMaxSeconds, "a critical force has no length")
        assertNull(saved.sets[2].targetMaxSeconds)
        // And the sets blob carries it — the only place it lives once saved.
        assertTrue(BlobCodec.encodeAll(saved.sets)!!.contains(""""targetBasis":"criticalForce""""))
    }

    @Test fun aMaxRoutineBandDemotesItsLengthButNoBasisKey() {
        val draft = RoutineDraft.blank().let {
            it.copy(plan = it.plan.copy(
                sets = listOf(SetPlan(grip = grip, repsPerSide = 1)),
                targetLoPercent = 0.8, targetHiPercent = 0.9, targetMaxSeconds = 7,
            ))
        }
        val set = draft.normalized.plan.sets[0]
        assertNull(set.targetBasisRaw, "a routine nobody chose a basis for writes no key")
        assertEquals(7, set.targetMaxSeconds)
    }

    // MARK: - The table

    @Test fun theTableKeepsMaxTablesHandRules() {
        val t = cfTable(Side.left to 14.0, Side.both to 15.0)
        assertEquals(14.0, t.criticalForce(key, Side.left))
        assertEquals(15.0, t.criticalForce(key, Side.right), "a hand falls back to both")
        assertEquals(15.0, t.criticalForce(key, Side.both))
        assertNull(t.exact(key, Side.right))

        val oneHand = cfTable(Side.left to 14.0)
        assertNull(oneHand.criticalForce(key, Side.right), "a one-handed CF never leaks")
        assertNull(oneHand.criticalForce(key, Side.both), "both never sums or borrows a hand")

        assertTrue(cfTable(Side.left to 0.0, Side.right to -3.0, Side.both to Double.NaN).isEmpty)
    }

    @Test fun theFoldTakesTheNewestValidStandardTestPerGripAndHand() {
        val t0 = Instant.ofEpochSecond(1_000_000)
        fun entry(side: Side, kg: Double, dt: Long, proto: String = "7:3x24") =
            CriticalForceTable.Entry(key, side, proto, kg, t0.plusSeconds(dt))
        val entries = listOf(
            entry(Side.left, 14.0, 0),
            entry(Side.left, 16.0, 100),
            entry(Side.left, 0.0, 200),
            entry(Side.left, 30.0, 300, proto = "5:5x20"),
            entry(Side.right, 12.0, 50),
        )
        val now = CriticalForceTable.folding(entries)
        assertEquals(16.0, now.criticalForce(key, Side.left))
        assertEquals(12.0, now.criticalForce(key, Side.right))
        assertEquals(14.0, CriticalForceTable.folding(entries, t0.plusSeconds(99)).criticalForce(key, Side.left))
        assertEquals(16.0, CriticalForceTable.folding(entries, t0.plusSeconds(100)).criticalForce(key, Side.left))
        val tie = CriticalForceTable.folding(listOf(entry(Side.left, 14.0, 0), entry(Side.left, 15.0, 0)))
        assertEquals(15.0, tie.criticalForce(key, Side.left), "a tie goes to the later entry")
    }

    // MARK: - Resolution

    @Test fun aCriticalForceBandResolvesAgainstCriticalForceAndNeverTheMax() {
        val p = plan(cfSet(0.8, 0.9))
        val maxes = maxTable(Side.both to 40.0)
        val cfs = cfTable(Side.left to 15.0, Side.right to 20.0)
        val set = p.sets[0]
        assertEquals(12.0..13.5, PlanMath.targetBand(set, p, Side.left, maxes, cfs))
        assertEquals(16.0..18.0, PlanMath.targetBand(set, p, Side.right, maxes, cfs))
        assertNull(PlanMath.targetBand(set, p, Side.left, maxes), "no CF on file is no target — never the max instead")
        assertNull(PlanMath.targetBand(set, p, Side.both, maxes, cfs), "a both-hands rep needs a both-hands CF")
    }

    @Test fun precedenceAndTheBasisTravelsWithItsLevel() {
        val inherits = SetPlan(grip = grip, repsPerSide = 1)
        val ownMax = SetPlan(grip = grip, repsPerSide = 1, targetLoPercent = 0.25, targetHiPercent = 0.25)
        val kg = SetPlan(grip = grip, repsPerSide = 1, targetLoKg = 9.0, targetHiKg = 9.0)
        val p = SessionPlan(
            handMode = HandMode.bothHands, sets = listOf(inherits, ownMax, kg),
            targetLoPercent = 0.5, targetHiPercent = 0.5,
        ).withTargetBasis(TargetBasis.criticalForce)
        val maxes = maxTable(Side.both to 40.0)
        val cfs = cfTable(Side.both to 16.0)
        assertEquals(TargetBasis.criticalForce, PlanMath.targetBasis(inherits, p))
        assertEquals(8.0..8.0, PlanMath.targetBand(inherits, p, Side.both, maxes, cfs))
        assertEquals(TargetBasis.max, PlanMath.targetBasis(ownMax, p), "its own band, its own (max) basis")
        assertEquals(10.0..10.0, PlanMath.targetBand(ownMax, p, Side.both, maxes, cfs))
        assertEquals(9.0..9.0, PlanMath.targetBand(kg, p, Side.both, maxes, cfs))
    }

    @Test fun aTimedLengthNeverSendsACriticalForceSetToATimedMax() {
        val p = plan(cfSet(0.5, 0.5).copy(targetMaxSeconds = 10), HandMode.bothHands)
        assertNull(PlanMath.maxSeconds(p.sets[0], p))
        val maxes = MaxTable().apply { record(30.0, key, Side.both, 10) }
        assertNull(PlanMath.targetBand(p.sets[0], p, Side.both, maxes))
    }

    @Test fun theRunnerFreezesCriticalForceBandsPerHandAtStart() {
        val p = plan(cfSet(0.6, 0.8))
        val runner = SessionRunner(plan = p, maxes = maxTable(Side.both to 40.0),
            criticalForces = cfTable(Side.left to 15.0, Side.right to 20.0))
        assertEquals(listOf(9.0..12.0, 12.0..16.0, 9.0..12.0, 12.0..16.0), runner.slots.map { it.targetBand })
        val noCF = SessionRunner(plan = p, maxes = maxTable(Side.both to 40.0))
        assertTrue(noCF.slots.mapNotNull { it.targetBand }.isEmpty(), "no CF, no target")
    }

    @Test fun untargetedCountsAMissingCriticalForcePerHand() {
        val p = plan(cfSet(0.6, 0.8))
        assertEquals(1, PlanMath.untargetedGripCount(p, maxTable(Side.both to 40.0), cfTable(Side.left to 15.0)))
        assertEquals(0, PlanMath.untargetedGripCount(p, MaxTable(), cfTable(Side.both to 15.0)), "a CF set needs no max")
        assertEquals(
            listOf(PlanMath.MissingReference(grip, Side.right, TargetBasis.criticalForce, null)),
            PlanMath.missingReferences(p, MaxTable(), cfTable(Side.left to 15.0)),
        )
    }

    @Test fun intensityRestatesCriticalForceAgainstThePeakOrStaysUnknown() {
        val p = plan(cfSet(0.6, 0.8), HandMode.bothHands)
        assertNull(PlanMath.peakIntensity(p, MaxTable()))
        assertNull(PlanMath.peakIntensity(p, maxTable(Side.both to 40.0)))
        assertNull(PlanMath.peakIntensity(p, MaxTable(), cfTable(Side.both to 16.0)))
        val fraction = assertNotNull(PlanMath.peakIntensity(p, maxTable(Side.both to 40.0), cfTable(Side.both to 16.0)))
        assertTrue(kotlin.math.abs(fraction - 0.8 * 16 / 40) < 1e-12)
        assertEquals(PlanMath.IntensityBand.moderate, PlanMath.IntensityBand.band(fraction))
    }

    @Test fun aMaxReceiptNeverMovesACriticalForceSet() {
        val p = SessionPlan(
            handMode = HandMode.bothHands,
            sets = listOf(cfSet(0.2, 0.3), SetPlan(grip = grip, repsPerSide = 1, targetLoPercent = 0.2, targetHiPercent = 0.3)),
        )
        val receipt = MaxReceiptMath.receipt(
            listOf(MaxSave(grip, Side.both, 40.0, MaxSource.manual)),
            maxTable(Side.both to 30.0), maxTable(Side.both to 40.0),
            listOf(MaxReceiptMath.Routine(UUID.randomUUID(), "R", p)),
        )
        assertEquals(1, receipt.percentMoves.size)
        assertEquals(8.0..12.0, receipt.percentMoves.first().move.newBand)
    }
}
