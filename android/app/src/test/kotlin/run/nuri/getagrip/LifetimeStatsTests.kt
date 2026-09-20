// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.data.lifetime
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.SessionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// The odometer in History. Folded from denormalized columns, so what these pin is the
/// ARITHMETIC — which kinds count as sessions, that a skipped pull is not a pull, that a
/// benchmark day is a day trained and nothing else, that two climbs on one day are one
/// day at the gym.
class LifetimeStatsTests {

    private val day = DayStamp.of(2026, 9, 1)

    private fun row(kind: SessionKind, on: DayStamp, reps: Int = 0, held: Double = 0.0,
                    avg: Double = 0.0, peak: Double = 0.0) = WorkoutLogEntity(
        dayKey = on.raw, kindRaw = kind.rawValue, completedReps = reps,
        totalHeldSeconds = held, avgKg = avg, peakKg = peak,
        startedAt = on.startOfDay().toInstant().plusSeconds(10 * 3600),
    )

    @Test
    fun everyKindCountsOnceAndOnlyWhereItBelongs() {
        val logs = listOf(
            row(SessionKind.hang, day, reps = 1, held = 7.0, avg = 18.0, peak = 20.0),
            // One completed pull and one skipped: the skip is not a pull and adds no time.
            row(SessionKind.hang, day + 1, reps = 1, held = 10.0, avg = 12.0, peak = 15.0),
            row(SessionKind.hangManual, day + 1),
            row(SessionKind.climbLimit, day + 3),
            row(SessionKind.climbVolume, day + 3),
            row(SessionKind.benchmark, day + 5),
        )

        val stats = logs.lifetime

        assertEquals(3, stats.sessions, "two runner sessions and the hand-logged hang")
        assertEquals(2, stats.pulls, "completed pulls only")
        assertEquals(17.0, stats.heldSeconds)
        assertEquals(30.0, stats.volumeKg, "load × pulls, per session, added up")
        assertEquals(1, stats.climbDays, "distinct days, not climbs logged")
        assertEquals(4, stats.daysTrained, "the benchmark day counts as a day, twice-trained days once")
        assertEquals(20.0, stats.heaviestPullKg)
        assertEquals(day, stats.since)
        assertFalse(stats.isEmpty)
    }

    @Test
    fun nothingTrainedIsEmptyNotZeros() {
        val stats = emptyList<WorkoutLogEntity>().lifetime
        assertTrue(stats.isEmpty)
        assertNull(stats.since)
    }

    /// A benchmark alone is a day trained, so the card shows it rather than the empty line.
    @Test
    fun aBenchmarkDayAloneIsADayTrained() {
        val stats = listOf(row(SessionKind.benchmark, day)).lifetime
        assertEquals(0, stats.sessions)
        assertEquals(1, stats.daysTrained)
        assertFalse(stats.isEmpty)
    }
}
