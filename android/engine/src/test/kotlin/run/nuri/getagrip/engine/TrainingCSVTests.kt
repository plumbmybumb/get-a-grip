// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.engine

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrainingCSVTests {
    private fun pullCSV(input: AnalysisExport.Input, scope: AnalysisExport.CSVScope = AnalysisExport.CSVScope.recent) =
        AnalysisExport.csv(input, scope, AnalysisExport.CSVDetail.pulls)
    private val today = DayStamp(20_000)
    private fun session(day: DayStamp = today) = AnalysisExport.Session(day = day,
        startedAt = Instant.ofEpochSecond(day.raw.toLong() * 86_400), routineName = "Daily",
        plannedReps = 1, completedReps = 1, reps = listOf(RepSummary(peakKg = 12.0, avgKg = 10.0)),
        sessionsPerDayTarget = 2)
    private fun input(sessions: List<AnalysisExport.Session>, maxes: List<AnalysisExport.MaxEntry> = emptyList()) =
        AnalysisExport.Input(sessions = sessions, maxes = maxes, today = today, generatedOn = today)
    private fun records(csv: AnalysisExport.CSVDocument, type: String): List<Map<String, String>> {
        val lines = csv.text.split("\r\n")
        val keys = lines[0].split(",")
        return lines.filter { it.startsWith("$type,") }.map { keys.zip(it.split(",")).toMap() }
    }
    @Test fun rangeIncludesTheFirstDayAndAllHistoryKeepsOldPulls() {
        val data = input(listOf(session(), session(today - 55), session(today - 56)))
        assertEquals(2, pullCSV(data).sessionCount)
        assertEquals(3, pullCSV(data, AnalysisExport.CSVScope.all).pullCount)
        assertEquals("2", records(pullCSV(data), "workout")[0]["daily_target"])
    }
    @Test fun singleWorkoutUsesOldMaxButNeverFutureMaxOrUnrelatedRecords() {
        val s = session()
        val old = AnalysisExport.MaxEntry(grip = GripSpec(), side = Side.left, kg = 30.0,
            day = today - 100, recordedAt = s.startedAt.minusSeconds(8_640_000))
        val future = old.copy(kg = 90.0, day = today, recordedAt = s.startedAt.plusSeconds(1))
        val csv = pullCSV(input(listOf(s), listOf(future, old)), AnalysisExport.CSVScope.workout)
        assertEquals(1, csv.sessionCount)
        assertEquals(0, csv.maxCount)
        assertEquals("30.0", records(csv, "pull")[0]["max_at_start_kg"])
        assertFalse(csv.text.contains("90.0"))
    }
    @Test fun unmeasuredForceIsBlankAndMeasuredZeroSurvives() {
        val s = session().copy(reps = listOf(RepSummary(peakKg = 0.0), RepSummary(peakKg = 20.0, outcome = RepOutcome.skipped)))
        val rows = records(pullCSV(input(listOf(s))), "pull")
        assertEquals("0.0", rows[0]["peak_kg"])
        assertEquals("", rows[1]["peak_kg"])
        assertTrue(records(pullCSV(input(listOf(s.copy(timing = AnalysisExport.Timing.timerOnly)))), "pull")
            .all { it["peak_kg"] == "" && it["avg_kg"] == "" })
    }
    @Test fun quotesUnicodeAndMultilineNotesArePreservedAndFormulasProtected() {
        val s = session().copy(routineName = "=HYPERLINK(\"test\")", notes = "Flexion, café\n\"felt good\"")
        val csv = pullCSV(input(listOf(s))).text
        assertTrue(csv.contains("\"'=HYPERLINK(\"\"test\"\")\""))
        assertTrue(csv.contains("\"Flexion, café\n\"\"felt good\"\"\""))
    }
    @Test fun curlAndFrozenPrescriptionRemainExplicit() {
        val s = session().copy(reps = listOf(RepSummary(grip = GripSpec(position = GripPosition.fingerCurl),
            targetLoKg = 8.0, targetHiKg = 12.0, outcome = RepOutcome.earlyRelease)))
        val pull = records(pullCSV(input(listOf(s))), "pull")[0]
        assertEquals("fingerCurl", pull["position"])
        assertEquals("IMRL", pull["fingers"])
        assertEquals("8.0", pull["target_low_kg"])
        assertEquals("12.0", pull["target_high_kg"])
        assertEquals("earlyRelease", pull["outcome"])
    }

    @Test fun stableKeysAndSelfContainedContextSurviveNewWorkouts() {
        val original = session(today - 1)
        val alone = records(pullCSV(input(listOf(original)), AnalysisExport.CSVScope.workout), "pull")[0]
        val expanded = records(pullCSV(input(listOf(session(), original)), AnalysisExport.CSVScope.all), "pull")[1]
        assertEquals(original.id.toString(), alone["workout"])
        assertEquals(alone, expanded)
        assertEquals("Daily", alone["routine"])
        assertEquals(AnalysisExport.isoDay(today - 1), alone["date"])
        assertEquals("not_recorded", alone["time_source"])
    }

    @Test fun summaryPreservesWeightedForcesOutcomesHandsAndPrescriptions() {
        val s = session().copy(reps = listOf(
            RepSummary(heldSeconds = 2.0, peakKg = 12.0, avgKg = 10.0),
            RepSummary(heldSeconds = 6.0, peakKg = 24.0, avgKg = 20.0, outcome = RepOutcome.earlyRelease),
            RepSummary(peakKg = 999.0, outcome = RepOutcome.skipped),
            RepSummary(side = Side.right, heldSeconds = 3.0, peakKg = 9.0, avgKg = 8.0),
            RepSummary(targetLoKg = 5.0, targetHiKg = 10.0)))
        val rows = records(AnalysisExport.csv(input(listOf(s))), "set")
        assertEquals(3, rows.size)
        assertEquals("3", rows[0]["recorded"])
        assertEquals("1", rows[0]["completed"])
        assertEquals("8.0", rows[0]["held_s"])
        assertEquals("24.0", rows[0]["peak_kg"])
        assertEquals("17.5", rows[0]["avg_kg"])
        assertEquals("completed:1|earlyRelease:1|skipped:1", rows[0]["outcome"])
        assertEquals("right", rows[1]["hand"])
        assertEquals("5.0", rows[2]["target_low_kg"])
    }

    @Test fun elapsedTimingSavedRestAndMaxProvenanceAreExplicit() {
        val s = session().copy(plan = SessionPlan(sets = listOf(SetPlan(repsPerSide = 2)), handMode = HandMode.bothHands,
            holdSeconds = 10, restSeconds = 20, leadInSeconds = 3),
            reps = listOf(RepSummary(side = Side.both, startedElapsedSeconds = 3.0, endedElapsedSeconds = 13.0),
                RepSummary(repIndex = 1, side = Side.both, startedElapsedSeconds = 43.0, endedElapsedSeconds = 53.0)))
        val max = AnalysisExport.MaxEntry(kg = 40.0, recordedAt = s.startedAt.minusSeconds(1))
        val rows = records(pullCSV(input(listOf(s), listOf(max))), "pull")
        assertEquals("3", rows[0]["planned_lead_in_s"])
        assertEquals("20", rows[0]["planned_rest_s"])
        assertEquals("30.0", rows[1]["gap_before_s"])
        assertEquals("both_hands", rows[0]["max_reference"])
        val left = s.copy(reps = listOf(s.reps.first().copy(side = Side.left)))
        assertEquals("both_hands_fallback", records(pullCSV(input(listOf(left), listOf(max))), "pull")[0]["max_reference"])
        assertEquals("no_recorded_max_at_start", records(pullCSV(input(listOf(left))), "pull")[0]["max_reference"])
    }

    @Test fun summaryDoesNotMergePrescriptionsThatRoundToTheSameDisplayValue() {
        val s = session().copy(reps = listOf(RepSummary(targetLoKg = 8.01, targetHiKg = 12.0), RepSummary(targetLoKg = 8.04, targetHiKg = 12.0)))
        assertEquals(2, records(AnalysisExport.csv(input(listOf(s))), "set").size)
    }
}
