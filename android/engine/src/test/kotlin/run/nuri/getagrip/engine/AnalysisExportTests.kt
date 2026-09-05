// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// The export document, which is the one artifact in this app that leaves it as prose.
///
/// Everything asserted here is a claim about HONESTY rather than about layout: that a
/// number nobody measured never appears, that a max belonging to one hand never gets
/// spent on the other, that the boundary between "written out in full" and "rolled up"
/// falls where the legend says it does, and that two exports of the same history are the
/// same bytes — because a document that shuffles is a document you cannot diff.
class AnalysisExportTests {

    // MARK: - Fixtures

    private val today = DayStamp.of(2026, 8, 28)

    private fun date(day: DayStamp, hour: Int = 9): Instant =
        Instant.ofEpochSecond(day.raw.toLong() * 86_400L + hour.toLong() * 3600L)

    private fun grip(edge: Int = 20, fingers: FingerSet = FingerSet.four,
                     position: GripPosition = GripPosition.halfCrimp): GripSpec =
        GripSpec(edgeMM = edge, fingers = fingers, position = position)

    private fun rep(index: Int, side: Side, grip: GripSpec, peak: Double,
                    held: Double = 10.0, planned: Int = 10,
                    outcome: RepOutcome = RepOutcome.completed): RepSummary =
        RepSummary(
            setIndex = 0,
            repIndex = index,
            side = side,
            grip = grip,
            targetSeconds = planned,
            heldSeconds = held,
            peakKg = peak,
            avgKg = peak * 0.9,
            outcome = outcome,
        )

    private fun session(day: DayStamp,
                        id: UUID = UUID.randomUUID(),
                        name: String = "Daily no-hangs",
                        kind: SessionKind = SessionKind.hang,
                        timing: AnalysisExport.Timing = AnalysisExport.Timing.gauge,
                        reps: List<RepSummary> = emptyList(),
                        held: Double? = null,
                        planned: Int? = null,
                        completed: Int? = null,
                        minutes: Int? = 20,
                        rpe: RPE? = null,
                        strain: FingerStrain? = null): AnalysisExport.Session =
        AnalysisExport.Session(
            id = id,
            day = day,
            startedAt = date(day),
            routineName = name,
            kind = kind,
            minutes = minutes,
            rpe = rpe,
            fingerStrain = strain,
            peakKg = reps.map { it.peakKg }.maxOrNull() ?: 0.0,
            avgKg = reps.map { it.avgKg }.maxOrNull() ?: 0.0,
            totalHeldSeconds = held ?: reps.sumOf { it.heldSeconds },
            plannedReps = planned ?: reps.size,
            completedReps = completed ?: reps.count { it.outcome == RepOutcome.completed },
            timing = timing,
            reps = reps,
            notes = "",
        )

    private fun maxEntry(kg: Double, grip: GripSpec, side: Side, on: DayStamp,
                         source: MaxSource = MaxSource.measured): AnalysisExport.MaxEntry =
        AnalysisExport.MaxEntry(grip = grip, side = side, kg = kg, day = on,
            recordedAt = date(on, hour = 8), source = source)

    private fun input(sessions: List<AnalysisExport.Session> = emptyList(),
                      maxes: List<AnalysisExport.MaxEntry> = emptyList()): AnalysisExport.Input =
        AnalysisExport.Input(sessions = sessions, maxes = maxes, today = today,
            generatedOn = today, sessionsPerDayTarget = 2)

    /// The `% max` cell of the first rep row after `header` — the tables are pipe-
    /// delimited, so a cell is an exact string rather than something to regex for.
    private fun repRows(document: String): List<List<String>> =
        document.split("\n")
            .filter { it.startsWith("| ") && it.contains(" | ") }
            .map { row -> row.split("|").map { it.trim() }.filter { it.isNotEmpty() } }

    /// A rep row is the ten-column one; everything else in the document is narrower.
    private fun repRow(document: String, pull: Int): List<String>? =
        repRows(document).firstOrNull { it.size == 10 && it.firstOrNull() == "$pull" }

    private val percentColumn = 8

    // MARK: - The legend

    @Test
    fun theLegendIsPresentAndExplainsTheSchema() {
        val doc = AnalysisExport.document(input(
            sessions = listOf(session(today, reps = listOf(rep(0, Side.left, grip(), 12.0)))),
            maxes = listOf(maxEntry(40.0, grip(), Side.both, today - 10))))

        assertTrue(doc.contains("## Legend"))
        assertTrue(doc.contains("written in English"),
            "the first legend line has to state the fixed-schema rule")
        assertTrue(doc.contains("`20mm 4F HC`"), "the grip notation is decoded by example")
        assertTrue(doc.contains("`4F` — all four fingers"))
        assertTrue(doc.contains("`HC` — half crimp"))
        assertTrue(doc.contains("`L` left, `R` right, `B` both"))
        assertTrue(doc.contains("1 easy"), "the systemic axis states its stored 1–5 scale")
        assertTrue(doc.contains("5 wrecked"), "so does the local one")
        assertTrue(doc.contains("climbing day counts as training"))
        assertTrue(doc.contains("skipped"), "skipped pulls are recorded, and the legend says so")
        assertTrue(doc.contains("## Questions worth asking"))
    }

    /// The document must never claim a per-rep number the schema does not hold, and must
    /// name what it DOES hold — `RepSummary` stores peak and average per pull.
    @Test
    fun theLegendNamesWhereTheKilogramsComeFrom() {
        val doc = AnalysisExport.document(input(
            sessions = listOf(session(today, reps = listOf(rep(0, Side.left, grip(), 12.0))))))
        assertTrue(doc.contains("Peak kg / Avg kg are stored PER REP"))
        assertTrue(doc.contains("the session-level figures"))
    }

    // MARK: - Intensity resolution

    /// The whole point of a blank: no max on file means no percentage, never a guess.
    @Test
    fun aRepWithNoMaxOnFileExportsABlankIntensity() {
        val doc = AnalysisExport.document(input(
            sessions = listOf(session(today, reps = listOf(rep(0, Side.left, grip(), 18.0))))))

        val row = repRow(doc, 1)
        assertNotNull(row)
        assertEquals(AnalysisExport.blank, row[percentColumn])
        assertFalse(row[percentColumn].contains("%"),
            "a blank is a blank — never a number and never 0 %")
    }

    @Test
    fun aHandSpecificMaxBeatsTheBothHandsOne() {
        val g = grip()
        val doc = AnalysisExport.document(input(
            sessions = listOf(session(today, reps = listOf(
                rep(0, Side.left, g, 20.0),
                rep(1, Side.right, g, 18.0),
            ))),
            maxes = listOf(maxEntry(40.0, g, Side.both, today - 20),
                maxEntry(36.0, g, Side.right, today - 10))))

        assertEquals("50%", repRow(doc, 1)?.get(percentColumn),
            "no left max, so the both-hands one")
        assertEquals("50%", repRow(doc, 2)?.get(percentColumn),
            "18 of the right hand's own 36, not of the general 40")
    }

    /// Adding two hands together to invent a two-handed max would be a silent, doubled
    /// error pointed at somebody's fingers. It stays blank.
    @Test
    fun aBothHandsRepNeverResolvesAgainstASummedGuess() {
        val g = grip()
        val doc = AnalysisExport.document(input(
            sessions = listOf(session(today, reps = listOf(rep(0, Side.both, g, 40.0)))),
            maxes = listOf(maxEntry(30.0, g, Side.left, today - 10),
                maxEntry(30.0, g, Side.right, today - 10))))

        assertEquals(AnalysisExport.blank, repRow(doc, 1)?.get(percentColumn))
    }

    /// A max recorded after a session must not rewrite what that session was pulling at.
    @Test
    fun intensityUsesTheMaxThatWasCurrentOnTheDay() {
        val g = grip()
        val old = today - 30
        val doc = AnalysisExport.document(input(
            sessions = listOf(session(old, reps = listOf(rep(0, Side.left, g, 20.0)))),
            maxes = listOf(maxEntry(40.0, g, Side.both, old - 5),
                maxEntry(50.0, g, Side.both, today))))

        assertEquals("50%", repRow(doc, 1)?.get(percentColumn),
            "20 of the 40 that stood that day, not of today's 50")
    }

    // MARK: - Timer-only sessions

    @Test
    fun aTimerOnlySessionStatesWallClockAndCarriesNoKilograms() {
        val reps = listOf(rep(0, Side.left, grip(), 0.0))
        val doc = AnalysisExport.document(input(
            sessions = listOf(session(today, timing = AnalysisExport.Timing.timerOnly, reps = reps)),
            maxes = listOf(maxEntry(40.0, grip(), Side.both, today - 10))))

        assertTrue(doc.contains("timer-only (wall clock)"),
            "the session header names how its seconds were measured")
        assertTrue(doc.contains("came off the WALL CLOCK"),
            "and the legend explains what that means")

        val row = repRow(doc, 1)
        assertEquals(AnalysisExport.blank, row?.get(5), "peak kg")
        assertEquals(AnalysisExport.blank, row?.get(6), "avg kg")
        assertEquals(AnalysisExport.blank, row?.get(percentColumn),
            "a max on file cannot manufacture an intensity for an unmeasured pull")
    }

    /// A pull that was passed over registered no load because it never happened — which
    /// is a different fact from "it registered zero", and the difference is the whole
    /// reason the blank exists. Printing 0.0 would drag any average taken down the column.
    @Test
    fun aSkippedPullCarriesNoKilogramsAtAll() {
        val g = grip()
        val doc = AnalysisExport.document(input(
            sessions = listOf(session(today, reps = listOf(
                rep(0, Side.left, g, 20.0),
                rep(1, Side.right, g, 0.0, held = 0.0, outcome = RepOutcome.skipped),
            ), planned = 2, completed = 1)),
            maxes = listOf(maxEntry(40.0, g, Side.both, today - 10))))

        val skipped = repRow(doc, 2)
        assertEquals(AnalysisExport.blank, skipped?.get(5), "peak kg")
        assertEquals(AnalysisExport.blank, skipped?.get(6), "avg kg")
        assertEquals(AnalysisExport.blank, skipped?.get(percentColumn))
        assertEquals("skipped", skipped?.get(9), "but it is still recorded, as a skip")
        assertEquals("20.0", repRow(doc, 1)?.get(5), "the pull that happened is untouched")
    }

    @Test
    fun aGaugeSessionSaysGauge() {
        val doc = AnalysisExport.document(input(
            sessions = listOf(session(today, reps = listOf(rep(0, Side.left, grip(), 12.0))))))
        assertTrue(doc.contains(" · gauge"))
    }

    // MARK: - The 8-week boundary

    @Test
    fun theEightWeekBoundarySplitsDetailedFromRolledUp() {
        val inside = today - (AnalysisExport.detailedDays - 1)   // 56 days including today
        val outside = inside - 1
        assertEquals(inside, AnalysisExport.detailCutoff(today))

        val doc = AnalysisExport.document(input(sessions = listOf(
            session(inside, name = "Inside", reps = listOf(rep(0, Side.left, grip(), 12.0))),
            session(outside, name = "Outside", reps = listOf(rep(0, Side.left, grip(), 12.0))),
        )))

        val recent = doc.indexOf("## Sessions, last 8 weeks")
        val rollup = doc.indexOf("## Older than 8 weeks, by week")
        assertTrue(recent >= 0 && rollup >= 0)

        val detailed = doc.substring(recent, rollup)
        assertTrue(detailed.contains("Inside"), "the boundary day itself is written out")
        assertFalse(detailed.contains("Outside"), "one day earlier is not")

        val rolled = doc.substring(rollup)
        assertTrue(rolled.contains(AnalysisExport.isoDay(AnalysisExport.weekStart(outside))))
    }

    @Test
    fun anEmptyWindowSaysSoRatherThanDrawingAnEmptyTable() {
        val doc = AnalysisExport.document(input(sessions = listOf(
            session(today - 100, reps = listOf(rep(0, Side.left, grip(), 12.0))),
        )))
        assertTrue(doc.contains("No sessions in this window."))
    }

    // MARK: - Weekly rollups

    @Test
    fun weeklyRollupSumsAreRight() {
        // Two hangboard sessions and one climb, all in the same Monday-start week, all
        // well outside the detailed window.
        val monday = AnalysisExport.weekStart(today - 100)
        val a = session(monday + 1, name = "A",
            reps = listOf(rep(0, Side.left, grip(), 12.0, held = 10.0),
                rep(1, Side.right, grip(), 12.0, held = 6.0, outcome = RepOutcome.earlyRelease)),
            planned = 2, completed = 1, rpe = RPE.solid, strain = FingerStrain.worked)
        val b = session(monday + 3, name = "B",
            reps = listOf(rep(0, Side.left, grip(), 12.0, held = 20.0)),
            planned = 4, completed = 1, rpe = RPE.hard, strain = FingerStrain.taxed)
        val climb = session(monday + 3, name = "Limit climbing", kind = SessionKind.climbLimit,
            timing = AnalysisExport.Timing.logged, held = 0.0, planned = 0, completed = 0)

        val doc = AnalysisExport.document(input(sessions = listOf(a, b, climb)))
        val week = AnalysisExport.isoDay(monday)
        val row = repRows(doc).firstOrNull { it.firstOrNull() == week && it.size == 7 }

        assertNotNull(row, "one row for the week")
        assertEquals("3", row[1], "three sessions")
        assertEquals("1", row[2], "one climb DAY, not one climb session per hang")
        assertEquals("2/6", row[3], "completed over planned, summed")
        assertEquals("36s", row[4], "10 + 6 + 20 seconds under tension")
        assertEquals("3.5", row[5], "median of 3 and 4")
        assertEquals("3.5", row[6], "median of 3 and 4")
    }

    @Test
    fun weekStartIsMonday() {
        // 2026-08-28 is a Friday; its week starts on Monday 2026-08-24.
        assertEquals("2026-08-24", AnalysisExport.isoDay(AnalysisExport.weekStart(today)))
        assertEquals("2026-08-28", AnalysisExport.isoDay(today))
    }

    // MARK: - Consistency

    @Test
    fun consistencyCountsDaysTrainedIncludingClimbs() {
        val monday = AnalysisExport.weekStart(today)
        val doc = AnalysisExport.document(input(sessions = listOf(
            session(monday, name = "A", reps = listOf(rep(0, Side.left, grip(), 12.0))),
            session(monday, name = "B", reps = listOf(rep(0, Side.left, grip(), 12.0))),
            session(monday + 2, name = "Gym", kind = SessionKind.climbVolume,
                timing = AnalysisExport.Timing.logged),
        )))

        assertTrue(doc.contains("Target: 2 sessions a day"))
        val row = repRows(doc).firstOrNull { it.firstOrNull() == AnalysisExport.isoDay(monday) && it.size == 3 }
        // Today (2026-08-28) is the Friday of this week, so only five of its days
        // exist yet — future days are not scored as misses.
        assertEquals("2 of 5", row?.get(1), "two distinct days, one of them a climb")
        assertEquals("3", row?.get(2), "three sessions across them")
    }

    /// The bug the feature's first real reader caught (2026-08-28): a history that
    /// begins mid-week printed its opening week as "N of 7", scoring days that predate
    /// the history as misses. The denominator is only the days inside
    /// [first recorded day … today].
    @Test
    fun aWeekIsOnlyAsLongAsTheHistoryInsideIt() {
        // History begins on a Thursday, three weeks back, trained every day through
        // Sunday: 4 of 4, never 4 of 7.
        val thursday = AnalysisExport.weekStart(today) - 18   // Mon − 18 = Thu, 3 weeks back
        val doc = AnalysisExport.document(input(sessions = (0 until 4).map {
            session(thursday + it, name = "A", reps = listOf(rep(0, Side.left, grip(), 12.0)))
        }))

        val week = AnalysisExport.weekStart(thursday)
        val row = repRows(doc).firstOrNull { it.firstOrNull() == AnalysisExport.isoDay(week) && it.size == 3 }
        assertEquals("4 of 4", row?.get(1),
            "days before the first recorded session are not misses")
        // And with the whole history inside the detailed window, the sessions header
        // claims the history's own start, not the eight-week cutoff.
        assertTrue(doc.contains("Every session on record — the history begins "
            + AnalysisExport.isoDay(thursday) + "."))
        assertFalse(doc.contains("Every session on or after"))
    }

    // MARK: - Maxes

    @Test
    fun currentMaxesKeepBothHandsApart() {
        val g = grip()
        val doc = AnalysisExport.document(input(maxes = listOf(
            maxEntry(38.0, g, Side.left, today - 20),
            maxEntry(34.0, g, Side.right, today - 10, source = MaxSource.manual),
        )))

        assertTrue(doc.contains("| 20mm 4F HC | L | 38.0 | ${AnalysisExport.isoDay(today - 20)} | measured |"))
        assertTrue(doc.contains("| 20mm 4F HC | R | 34.0 | ${AnalysisExport.isoDay(today - 10)} | typed |"),
            "a later right-hand record must not swallow the left's row")
    }

    @Test
    fun maxHistoryKeepsEveryRecord() {
        val g = grip()
        val doc = AnalysisExport.document(input(maxes = listOf(
            maxEntry(30.0, g, Side.both, today - 60),
            maxEntry(34.0, g, Side.both, today - 30),
            maxEntry(36.0, g, Side.both, today - 1),
        )))
        assertTrue(doc.contains("### 20mm 4F HC · both hands"))
        for (kg in listOf("30.0", "34.0", "36.0")) {
            assertTrue(doc.contains("| $kg |"), "$kg is part of the progression")
        }
    }

    // MARK: - Determinism

    /// Dictionaries are folded in several places here, and iteration order is not stable
    /// between instances — an export that shuffles its own sections cannot be diffed
    /// against last month's.
    @Test
    fun twoCallsProduceIdenticalDocuments() {
        val g1 = grip(20, FingerSet.four, GripPosition.halfCrimp)
        val g2 = grip(14, FingerSet.frontThree, GripPosition.openHand)
        val g3 = grip(20, FingerSet.frontTwo, GripPosition.fullCrimp)
        val sessions = (0 until 12).map { index ->
            session(today - index * 9,
                name = "Routine ${index % 3}",
                reps = listOf(rep(0, Side.left, g1, 12.0 + index),
                    rep(1, Side.right, g2, 10.0 + index),
                    rep(2, Side.both, g3, 20.0 + index)),
                rpe = RPE.fromRaw(index % 5 + 1),
                strain = FingerStrain.fromRaw(index % 5 + 1))
        }
        val maxes = listOf(maxEntry(40.0, g1, Side.both, today - 80),
            maxEntry(36.0, g1, Side.right, today - 40),
            maxEntry(22.0, g2, Side.left, today - 30),
            maxEntry(24.0, g3, Side.both, today - 5))

        val one = AnalysisExport.document(input(sessions = sessions, maxes = maxes))
        val two = AnalysisExport.document(input(sessions = sessions, maxes = maxes))
        assertEquals(one, two)

        // And the input's own arrival order must not show through either.
        val shuffled = AnalysisExport.document(input(sessions = sessions.reversed(),
            maxes = maxes.reversed()))
        assertEquals(one, shuffled, "ordering is derived from the data, not from the array")
    }

    @Test
    fun anEmptyHistoryStillProducesAWholeDocument() {
        val doc = AnalysisExport.document(input())
        assertTrue(doc.contains("## Legend"))
        assertTrue(doc.contains("No maxes recorded."))
        assertTrue(doc.contains("No sessions in this window."))
        assertTrue(doc.contains("No sessions on record."))
        assertTrue(doc.contains("## Questions worth asking"))
    }

    // MARK: - Notation

    @Test
    fun gripNotationIsEnglishAndStableWhateverTheLocale() {
        assertEquals("20mm 4F HC", AnalysisExport.gripCode(grip(20, FingerSet.four, GripPosition.halfCrimp)))
        assertEquals("14mm F2 OH", AnalysisExport.gripCode(grip(14, FingerSet.frontTwo, GripPosition.openHand)))
        assertEquals("30mm F2+T PN", AnalysisExport.gripCode(
            grip(30, FingerSet.of(listOf(FingerSet.index, FingerSet.middle, FingerSet.thumb)),
                GripPosition.pinch)))
        assertEquals("18mm B3 SLOPER", AnalysisExport.gripCode(
            grip(18, FingerSet.backThree, GripPosition("sloper"))))
    }

    @Test
    fun kilogramsAreWrittenWithADotWhateverTheLocale() {
        assertEquals("12.3", AnalysisExport.kgText(12.34))
        assertEquals("9.1", AnalysisExport.secondsText(9.06))
        assertEquals("50%", AnalysisExport.percentText(20.0, 40.0))
        assertEquals(AnalysisExport.blank, AnalysisExport.percentText(20.0, null))
        assertEquals(AnalysisExport.blank, AnalysisExport.percentText(20.0, 0.0))
    }

    @Test
    fun durationsReadAsEnglish() {
        assertEquals("0s", AnalysisExport.durationText(0.0))
        assertEquals("45s", AnalysisExport.durationText(45.0))
        assertEquals("2m 5s", AnalysisExport.durationText(125.0))
        assertEquals("1h 2m", AnalysisExport.durationText(3_725.0))
    }
}
