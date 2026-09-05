// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.store.DayLedger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/// The month grid's day-fill rule, pinned.
///
/// It used to live in `HistoryView.fraction(on:)` as a filter over every log, run once per
/// cell. Folding it into `DayLedger` made the screen O(logs) instead of O(days × logs) —
/// but it also meant rewriting the rule, and a grid that silently changed what a filled
/// square means would be a worse bug than the slowness it fixed. Every case below is one
/// the old implementation answered the same way.
///
/// Pure: `DayLedger` takes rows and answers four questions, so nothing here needs Room, a
/// `Context` or Robolectric.
class HistoryLedgerTests {

    private val today = DayStamp(20_000)

    private fun hang(day: DayStamp, target: Int = 2): WorkoutLogEntity =
        WorkoutLogEntity.from(
            plan = SessionPlan(), templateID = null, templateName = "R",
            sessionsPerDayTarget = target, reps = emptyList(),
            startedAt = day.startOfDay().toInstant(),
            finishedAt = day.startOfDay().toInstant(), day = day,
        )

    private fun logged(kind: SessionKind, day: DayStamp, target: Int = 2): WorkoutLogEntity =
        WorkoutLogEntity.logged(
            kind = kind, day = day, at = day.startOfDay().toInstant(),
            sessionsPerDayTarget = target,
        )

    private fun ledger(logs: List<WorkoutLogEntity>) = DayLedger(logs, today)

    // MARK: - Filling a day

    @Test
    fun aDayWithNothingOnItIsEmpty() {
        val l = ledger(emptyList())
        assertEquals(0.0, l.fraction(today))
        assertFalse(l.climbed(today))
        assertFalse(l.benchmarked(today))
    }

    @Test
    fun hangsFillTheirFractionOfTheDaysOwnTarget() {
        assertEquals(0.5, ledger(listOf(hang(today, 2))).fraction(today))
        assertEquals(1.0, ledger(listOf(hang(today, 2), hang(today, 2))).fraction(today))
        assertEquals(1.0, ledger(listOf(hang(today, 1))).fraction(today))
    }

    @Test
    fun aHandLoggedHangFillsOnlyItsShareOfTheDaysTarget() {
        assertEquals(0.5, ledger(listOf(logged(SessionKind.hangManual, today))).fraction(today))
    }

    /// A third session on a two-a-day routine is still one full day, not 150 % of one.
    @Test
    fun moreSessionsThanTheTargetStillFillsExactlyOnce() {
        val l = ledger(listOf(hang(today), hang(today), hang(today)))
        assertEquals(1.0, l.fraction(today))
    }

    /// The target is the one FROZEN into the logs, so a day trained under a once-a-day
    /// routine stays a full day after the routine is changed to ask for two.
    @Test
    fun theDayUsesTheLargestTargetFrozenIntoItsOwnLogs() {
        val l = ledger(listOf(hang(today, 1), hang(today, 4)))
        assertEquals(0.5, l.fraction(today), "two hangs against the larger target")
    }

    /// Defends the `max(1, …)` the log's own initializer applies: a zero target would
    /// divide by zero and paint every cell as either blank or infinite.
    @Test
    fun aZeroTargetIsTreatedAsOne() {
        assertEquals(1.0, ledger(listOf(hang(today, 0))).fraction(today))
    }

    // MARK: - Days that settle themselves

    @Test
    fun aClimbFillsTheDayOutrightAndMarksIt() {
        val l = ledger(listOf(logged(SessionKind.climbVolume, today)))
        assertEquals(1.0, l.fraction(today), "a climb settles the day whatever the target")
        assertTrue(l.climbed(today))
        assertFalse(l.benchmarked(today))
    }

    @Test
    fun aBenchmarkFillsTheDayAndMarksItSeparatelyFromAClimb() {
        val l = ledger(listOf(logged(SessionKind.benchmark, today)))
        assertEquals(1.0, l.fraction(today))
        assertTrue(l.benchmarked(today))
        assertFalse(l.climbed(today), "a benchmark is a full day, not a climbing day")
    }

    @Test
    fun aClimbAndABenchmarkOnOneDayBothRegister() {
        val l = ledger(
            listOf(
                logged(SessionKind.climbLimit, today),
                logged(SessionKind.benchmark, today),
            )
        )
        assertTrue(l.climbed(today))
        assertTrue(l.benchmarked(today))
    }

    // MARK: - Days are kept apart

    @Test
    fun eachDayAnswersOnlyForItsOwnLogs() {
        val yesterday = today - 1
        val l = ledger(listOf(hang(today, 2), hang(yesterday, 2), hang(yesterday, 2)))
        assertEquals(0.5, l.fraction(today))
        assertEquals(1.0, l.fraction(yesterday))
        assertEquals(0.0, l.fraction(today - 2))
    }

    // MARK: - Where the record starts

    @Test
    fun trackingStartsAtTheOldestLogHoweverTheyAreOrdered() {
        // Newest-first, the order History's own query actually delivers.
        val l = ledger(listOf(hang(today), hang(today - 40), hang(today - 9)))
        assertEquals(today - 40, l.trackingSince)
    }

    /// With no history at all, "since" is today — days before it are hairlines, and a
    /// sentinel leaking through here would draw 35 tracked days for a brand-new install.
    @Test
    fun withNoLogsTrackingStartsToday() {
        assertEquals(today, ledger(emptyList()).trackingSince)
    }
}
