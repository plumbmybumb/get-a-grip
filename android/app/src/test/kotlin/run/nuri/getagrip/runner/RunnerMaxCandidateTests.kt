// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

import run.nuri.getagrip.FakeClock
import run.nuri.getagrip.RecordingProgressorClient
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.MaxTable
import run.nuri.getagrip.engine.RepOutcome
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.Side
import run.nuri.getagrip.inertScope
import run.nuri.getagrip.store.DeviceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunnerMaxCandidateTests {
    private val grip = GripSpec()

    private fun session(maxes: MaxTable = MaxTable()): RunnerSession {
        val clock = FakeClock()
        val scope = inertScope()
        return RunnerSession(
            plan = SessionPlan(), routineName = "Per-hand peaks",
            device = DeviceStore(RecordingProgressorClient(), scope = scope, clock = clock),
            maxes = maxes, scope = scope, clock = clock,
        )
    }

    private fun rep(side: Side, kg: Double, outcome: RepOutcome = RepOutcome.completed) =
        RepSummary(grip = grip, side = side, peakKg = kg, outcome = outcome)

    @Test fun repeatedGripKeepsTheBestCompletedPeakForEachHand() {
        val candidates = session().maxCandidates(listOf(
            rep(Side.left, 31.0), rep(Side.right, 30.0),
            rep(Side.left, 35.0).copy(setIndex = 1), rep(Side.right, 29.0).copy(setIndex = 1),
        ))
        assertEquals(listOf(Side.left, Side.right), candidates.map { it.side })
        assertEquals(listOf(35.0, 30.0), candidates.map { it.kg })
        assertEquals(2, candidates.map { it.id }.toSet().size)
        assertTrue(candidates.all { it.previous == null })
    }

    @Test fun sharedTargetFallbackDoesNotSuppressFirstHandSpecificPeaks() {
        val maxes = MaxTable().apply { record(40.0, grip.key, Side.both) }
        val candidates = session(maxes).maxCandidates(listOf(rep(Side.left, 35.0), rep(Side.right, 30.0)))

        assertEquals(listOf(Side.left, Side.right), candidates.map { it.side })
        assertEquals(listOf(35.0, 30.0), candidates.map { it.kg })
        assertTrue(candidates.all { it.previous == null })
        // Reviewing candidates must neither write a max nor change target fallback.
        assertNull(maxes.exact(grip.key, Side.left))
        assertNull(maxes.exact(grip.key, Side.right))
        assertEquals(40.0, maxes.max(grip.key, Side.left))
        assertEquals(40.0, maxes.max(grip.key, Side.right))
    }

    @Test fun anExactLeftRecordSuppressesOnlyTheLeftCandidate() {
        val maxes = MaxTable().apply {
            record(40.0, grip.key, Side.both)
            record(36.0, grip.key, Side.left)
        }
        val session = session(maxes)
        val candidates = session.maxCandidates(listOf(rep(Side.left, 35.0), rep(Side.right, 30.0)))
        assertEquals(listOf(MaxCandidate(grip, Side.right, 30.0, null)), candidates)
        assertTrue(session.maxCandidates(listOf(rep(Side.left, 36.0))).isEmpty())
        assertEquals(listOf(MaxCandidate(grip, Side.left, 37.0, 36.0)),
            session.maxCandidates(listOf(rep(Side.left, 37.0))))
    }

    @Test fun bothHandsUsesOnlyItsOwnRecordAndKeepsItsOwnIdentity() {
        val maxes = MaxTable().apply {
            record(60.0, grip.key, Side.left)
            record(55.0, grip.key, Side.right)
        }
        assertEquals(listOf(MaxCandidate(grip, Side.both, 45.0, null)),
            session(maxes).maxCandidates(listOf(rep(Side.both, 45.0))))
        maxes.record(44.0, grip.key, Side.both)
        assertEquals(listOf(MaxCandidate(grip, Side.both, 45.0, 44.0)),
            session(maxes).maxCandidates(listOf(rep(Side.both, 43.0), rep(Side.both, 45.0))))
        assertTrue(session(maxes).maxCandidates(listOf(rep(Side.both, 44.0))).isEmpty())
    }

    @Test fun incompleteUnloadedAndInvalidPeaksCannotBecomeMaxes() {
        val excluded = listOf(
            rep(Side.left, 100.0, RepOutcome.skipped),
            rep(Side.left, 100.0, RepOutcome.earlyRelease),
            rep(Side.left, 100.0, RepOutcome.aborted),
        ) + listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -2.0, 0.0, 1.0)
            .map { rep(Side.left, it) }

        assertTrue(session().maxCandidates(excluded).isEmpty())
        assertEquals(listOf(MaxCandidate(grip, Side.right, 30.0, null)),
            session().maxCandidates(excluded + rep(Side.right, 30.0)))
    }
}
