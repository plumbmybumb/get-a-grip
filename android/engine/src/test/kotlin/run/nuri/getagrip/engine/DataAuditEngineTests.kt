// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip.engine

import kotlinx.serialization.json.Json
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DataAuditEngineTests {
    @Test fun unknownMissingAndMalformedOutcomesNeverBecomeCompletedPulls() {
        for (json in listOf("""{"outcome":"futureOutcome"}""", """{"outcome":4}""", "{}")) {
            assertEquals(RepOutcome.aborted, RepSummary.fromJson(Json.parseToJsonElement(json))?.outcome)
        }
        for (outcome in RepOutcome.entries) {
            assertEquals(outcome, assertNotNull(RepSummary.fromJson(RepSummary(outcome = outcome).toJson())).outcome)
        }
    }

    @Test fun reminderNormalizationPreservesGoalAndRestoresDistinctTimes() {
        val morning = ReminderTime(hour = 8, minute = 0)
        val blank = RoutineDraft.blank()
        val draft = blank.copy(plan = blank.plan.copy(sets = listOf(SetPlan())), sessionsPerDay = 3,
            reminders = listOf(morning, morning), parkedReminders = listOf(morning, ReminderTime(hour = 19, minute = 0)))
        assertNotNull(draft.validationIssue)
        val normalized = draft.normalized
        assertEquals(3, normalized.sessionsPerDay)
        assertEquals(3, normalized.reminders.size)
        assertEquals(3, normalized.reminders.toSet().size)
        assertNull(normalized.validationIssue)
        assertEquals(3, draft.copy(reminders = listOf(morning)).setSessionsPerDay(3).reminders.size)
    }

    @Test fun missingMidnightRoundTripsOnActualSantiagoTransitions() {
        val zone = ZoneId.of("America/Santiago")
        for (day in listOf(DayStamp.of(2026, 9, 6), DayStamp.of(2027, 9, 5))) {
            val date = day.startOfDay(zone)
            assertEquals(day, DayStamp.of(date.toInstant(), zone))
            assertEquals(1, date.hour)
        }
    }

    @Test fun targetLinesEngageWithoutExactEqualityButOrdinaryBandsStayStrict() {
        for (high in listOf(null, 10.0)) {
            val runner = makeRunner(10.0, high)
            feed(runner, 10.1)
            assertEquals(RunnerPhase.Working(0), runner.phase)
        }
        for ((low, high, kg) in listOf(Triple(10.0, 10.0, 10.4), Triple(10.0, 11.0, 9.9), Triple(0.0, 0.0, 0.0))) {
            val runner = makeRunner(low, high)
            feed(runner, kg)
            assertEquals(RunnerPhase.Armed(0), runner.phase)
        }
    }

    @Test fun malformedZeroGoalUsesMinimumOneSession() {
        val summary = RoutineSummary(id = java.util.UUID.randomUUID(), name = "Zero goal", ladder = emptyList(),
            setCount = 0, totalReps = 0, sharedEdgeMM = null, estimatedSeconds = 0,
            sessionsPerDay = 0, completedToday = 1, nextReminder = null)
        kotlin.test.assertTrue(summary.targetMet)
    }

    private fun makeRunner(low: Double, high: Double?): SessionRunner {
        val runner = SessionRunner(SessionPlan(sets = listOf(SetPlan(repsPerSide = 1, targetLoKg = low, targetHiKg = high)),
            handMode = HandMode.bothHands, holdSeconds = 2, leadInSeconds = 0, pausesOutsideTargetBand = true))
        runner.handle(RunnerEvent.Start, at = 0.0)
        return runner
    }

    private fun feed(runner: SessionRunner, kg: Double) {
        for (sample in 1..32) runner.handle(RunnerEvent.Sample(ForceSample(kg, (sample * 12_500).toUInt())), at = sample * 0.0125)
    }
}
