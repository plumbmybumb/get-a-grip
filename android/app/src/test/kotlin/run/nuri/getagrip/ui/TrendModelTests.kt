// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.ui

import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.BlobCodec
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.RepOutcome
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.ui.history.TrendModel
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// History's trend deck, built off the main thread from the feed's logs. The twin of iOS's
/// `TrendModelTests`: which routines get a card and under what name, which grips a card
/// offers and in what order, and what one point on a line means.
class TrendModelTests {
    private val crimp = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
    private val front3 = GripSpec(20, FingerSet.fromToken("IMR"), GripPosition.halfCrimp)
    private val base = Instant.parse("2026-05-10T08:00:00Z")

    private fun rep(grip: GripSpec, kg: Double, held: Double = 10.0, outcome: RepOutcome = RepOutcome.completed) =
        RepSummary(grip = grip, avgKg = kg, peakKg = kg + 2, heldSeconds = held, outcome = outcome)

    private fun row(
        reps: List<RepSummary>,
        routine: UUID?,
        name: String = "Frozen",
        daysAgo: Double,
        kind: SessionKind = SessionKind.hang,
    ) = WorkoutLogEntity(
        templateID = routine,
        templateName = name,
        startedAt = base.minusSeconds((daysAgo * 86_400).toLong()),
        resultsData = BlobCodec.encodeAll(reps) ?: "",
        kindRaw = kind.rawValue,
    )

    @Test fun cardsFollowTheMostRecentlyTrainedRoutineAndItsLiveName() {
        val daily = UUID.randomUUID()
        val maxDay = UUID.randomUUID()
        val rows = listOf( // newest first, as the feed sorts them
            row(listOf(rep(crimp, 30.0)), maxDay, name = "Old max name", daysAgo = 1.0),
            row(listOf(rep(crimp, 12.0)), daily, daysAgo = 2.0),
            row(listOf(rep(crimp, 11.0)), daily, daysAgo = 3.0),
        )
        val model = TrendModel.build(rows, mapOf(daily to "Daily", maxDay to "Max day"))
        assertEquals(listOf("Max day", "Daily"), model.routines.map { it.name })
        assertEquals(
            listOf(maxDay.toString().uppercase(), daily.toString().uppercase()),
            model.routines.map { it.key },
        )
    }

    @Test fun aDeletedRoutineGetsNoCardAndANamelessOneKeepsItsFrozenName() {
        val gone = UUID.randomUUID()
        val rows = listOf(
            row(listOf(rep(crimp, 12.0)), gone, daysAgo = 1.0),
            row(listOf(rep(crimp, 12.0)), null, name = "Before IDs", daysAgo = 2.0),
        )
        assertEquals(listOf("Before IDs"), TrendModel.build(rows, emptyMap()).routines.map { it.name })
    }

    /// Gauge-free sessions log 0 kg and skipped reps never finished; neither is a load.
    @Test fun onlyMeasuredCompletedRepsChart() {
        val daily = UUID.randomUUID()
        val rows = listOf(
            row(listOf(rep(crimp, 0.0)), daily, daysAgo = 1.0),
            row(listOf(rep(crimp, 14.0, outcome = RepOutcome.skipped)), daily, daysAgo = 2.0),
        )
        assertTrue(
            TrendModel.build(rows, mapOf(daily to "Daily")).routines.isEmpty(),
            "a routine with nothing measured gets no card",
        )
    }

    /// A climb or a hand-logged hang carries no reps anyway; the kind filter says so outright.
    @Test fun onlyHangSessionsAreRead() {
        val rows = listOf(row(listOf(rep(crimp, 12.0)), null, name = "Gym", daysAgo = 1.0, kind = SessionKind.climbLimit))
        assertTrue(TrendModel.build(rows, emptyMap()).routines.isEmpty())
    }

    @Test fun gripsAreMostTrainedFirstAndSeriesAreTimeWeightedOldestFirst() {
        val daily = UUID.randomUUID()
        val rows = listOf(
            row(listOf(rep(front3, 9.0), rep(crimp, 20.0, held = 10.0), rep(crimp, 10.0, held = 30.0)), daily, daysAgo = 1.0),
            row(listOf(rep(crimp, 12.0)), daily, daysAgo = 5.0),
        )
        val routine = TrendModel.build(rows, mapOf(daily to "Daily")).routines.single()
        assertEquals(listOf(crimp.key, front3.key), routine.grips.map { it.key })
        assertEquals(listOf(3, 1), routine.grips.map { it.count })
        val series = routine.series.getValue(crimp.key)
        // Oldest first; (20·10 + 10·30) / 40 for the newer session.
        assertEquals(listOf(12.0, 12.5), series.map { it.avgKg })
        assertEquals(listOf(rows[1].id, rows[0].id), series.map { it.id })
    }

    @Test fun aSharedSelectionHoldsOnlyWhereTheRoutineTrainedIt() {
        val daily = UUID.randomUUID()
        val routine = TrendModel.build(
            listOf(row(listOf(rep(crimp, 12.0)), daily, daysAgo = 1.0)),
            mapOf(daily to "Daily"),
        ).routines.single()
        assertEquals(crimp.key, routine.selectedGrip(null))
        assertEquals(crimp.key, routine.selectedGrip(front3.key))
        assertEquals(crimp.key, routine.selectedGrip(crimp.key))
    }

    /// The feed's cache is what the deck reads through, so the build must never go around it.
    @Test fun repsComeThroughTheSuppliedReader() {
        val daily = UUID.randomUUID()
        val log = row(emptyList(), daily, daysAgo = 1.0)
        var asked = 0
        val model = TrendModel.build(listOf(log), mapOf(daily to "Daily"), reps = {
            asked++
            listOf(rep(crimp, 15.0))
        })
        assertEquals(1, asked)
        assertEquals(15.0, model.routines.single().series.getValue(crimp.key).single().avgKg)
    }

    @Test fun aCancelledBuildPublishesNothing() {
        val daily = UUID.randomUUID()
        val rows = listOf(row(listOf(rep(crimp, 12.0)), daily, daysAgo = 1.0))
        assertTrue(TrendModel.build(rows, mapOf(daily to "Daily"), isCancelled = { true }).routines.isEmpty())
    }

    /// Under half a kilo across a series is grip noise; calling it progress would be flattery.
    @Test fun theSummaryCallsHalfAKiloNoise() {
        fun series(vararg kg: Double) = kg.mapIndexed { i, v -> TrendModel.Point(UUID.randomUUID(), base.plusSeconds(i * 86_400L), v) }
        assertNull(TrendModel.summary(emptyList()))
        assertEquals(TrendModel.Summary.Steady(12.4), TrendModel.summary(series(12.0, 13.0, 12.4)))
        val up = assertIs<TrendModel.Summary.Up>(TrendModel.summary(series(12.0, 12.2, 13.0)))
        assertEquals(1.0, up.deltaKg, 1e-9)
        assertEquals(3, up.sessions)
        val down = assertIs<TrendModel.Summary.Down>(TrendModel.summary(series(14.0, 13.5)))
        assertEquals(0.5, down.deltaKg, 1e-9)
        assertEquals(2, down.sessions)
    }
}
