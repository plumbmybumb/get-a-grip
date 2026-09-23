// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import kotlinx.coroutines.test.runTest
import run.nuri.getagrip.engine.ReminderTime
import run.nuri.getagrip.store.RecordingAlarmScheduler
import run.nuri.getagrip.store.ReminderPlanner
import run.nuri.getagrip.store.ReminderAlarms
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// The planner is wipe-and-reschedule, so there is no incremental state to corrupt —
/// which only holds if `requests(_)` is a pure function of the routines. These tests
/// exercise that function alone; nothing here touches `AlarmManager`, and the one
/// scheduler that appears is a `RecordingAlarmScheduler` that keeps what it was handed.
class ReminderPlannerTests {

    private val routineA = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val routineB = UUID.fromString("66666666-7777-8888-9999-000000000000")

    /// `outstanding` defaults high so the plain cases keep asserting the unsuppressed
    /// plan; the suppression cases below set it deliberately.
    private fun input(
        id: UUID,
        name: String,
        times: List<ReminderTime>,
        enabled: Boolean = true,
        outstanding: Int = 99,
    ) = ReminderPlanner.RoutinePlanInput(id, name, times, enabled, outstanding)

    // MARK: - Suppression

    /// The ritual's whole promise is that the app stops asking once you have done it. A
    /// reminder firing after the second session of the day is the app failing to notice
    /// you succeeded — and that is how notifications get switched off for good.
    @Test
    fun slotsAreSuppressedFromTheFrontOfTheDayAsSessionsAreDone() {
        val morning = ReminderTime(hour = 8, minute = 0)
        val evening = ReminderTime(hour = 19, minute = 0)

        val untouched = ReminderPlanner.requests(
            listOf(input(routineA, "Daily", listOf(morning, evening), outstanding = 2))
        )
        assertEquals(2, untouched.size, "nothing done yet, both slots stand")

        // Having trained once, it is the MORNING you have satisfied. Dropping the later
        // slot instead would silence the reminder you still need.
        val halfDone = ReminderPlanner.requests(
            listOf(input(routineA, "Daily", listOf(morning, evening), outstanding = 1))
        )
        assertEquals(2, halfDone.size, "future occurrences must survive today's completion")
        assertEquals(listOf(true, false), halfDone.map { it.suppressToday })

        val finished = ReminderPlanner.requests(
            listOf(input(routineA, "Daily", listOf(morning, evening), outstanding = 0))
        )
        assertEquals(2, finished.size)
        assertTrue(finished.all { it.suppressToday }, "say nothing today, resume tomorrow")
    }

    /// Overshooting the target must not wrap around and re-arm the morning.
    @Test
    fun trainingMoreThanPlannedStillSilencesTheRestOfTheDay() {
        val times = listOf(ReminderTime(hour = 8, minute = 0), ReminderTime(hour = 19, minute = 0))
        val requests = ReminderPlanner.requests(
            listOf(input(routineA, "Daily", times, outstanding = -3))
        )
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.suppressToday })
    }

    @Test
    fun completedDayKeepsTomorrowWithoutReopeningApp() = runTest {
        val zone = ZoneId.of("Europe/Paris")
        val now = LocalDateTime.of(2026, 10, 24, 7, 0)
        val routines = listOf(input(routineA, "Daily", listOf(ReminderTime(hour = 8, minute = 0),
            ReminderTime(hour = 19, minute = 0)), outstanding = 0))
        val scheduler = RecordingAlarmScheduler()
        ReminderPlanner.replan(routines, scheduler)
        assertEquals(2, scheduler.applied.size)
        for (item in scheduler.applied) {
            val first = ReminderAlarms.nextOccurrence(item.hour, item.minute, zone, now, item.suppressToday)
            val expected = now.toLocalDate().plusDays(1).atTime(item.hour, item.minute).atZone(zone)
            assertEquals(expected.toInstant().toEpochMilli(), first)
            // The receiver schedules tomorrow by calendar day, even across DST.
            assertEquals(expected.plusDays(1).toInstant().toEpochMilli(),
                ReminderAlarms.tomorrow(item.hour, item.minute, zone, expected.toLocalDate()))
        }
    }

    /// **A session finished after midnight settles the training day that began at 04:00
    /// YESTERDAY — and must leave tomorrow morning's reminders alone.** The suppression used
    /// to skip a slot's next CALENDAR occurrence, so finishing at 00:30 silenced the 08:00
    /// that belongs to the next training day, and a whole day went unreminded (found on iOS,
    /// and the same arithmetic was here).
    @Test
    fun finishingAfterMidnightDoesNotSilenceTheNextTrainingDay() {
        val zone = ZoneId.of("Europe/Paris")
        val halfPastMidnight = LocalDateTime.of(2026, 9, 23, 0, 30)
        val morning = ReminderAlarms.nextOccurrence(8, 0, zone, halfPastMidnight, suppressToday = true)
        assertEquals(LocalDateTime.of(2026, 9, 23, 8, 0).atZone(zone).toInstant().toEpochMilli(), morning,
            "08:00 today is the next training day's slot, owed in full")

        // A slot still INSIDE the training day being settled is skipped once.
        val lateNight = ReminderAlarms.nextOccurrence(1, 0, zone, halfPastMidnight, suppressToday = true)
        assertEquals(LocalDateTime.of(2026, 9, 24, 1, 0).atZone(zone).toInstant().toEpochMilli(), lateNight)

        // And the ordinary evening case is unchanged: 19:00 today is skipped to tomorrow.
        val sixPm = LocalDateTime.of(2026, 9, 23, 18, 0)
        val evening = ReminderAlarms.nextOccurrence(19, 0, zone, sixPm, suppressToday = true)
        assertEquals(LocalDateTime.of(2026, 9, 24, 19, 0).atZone(zone).toInstant().toEpochMilli(), evening)
        // 04:00 is the NEXT day's first minute, never the settled one's last.
        val beforeRollover = LocalDateTime.of(2026, 9, 23, 3, 0)
        val atRollover = ReminderAlarms.nextOccurrence(4, 0, zone, beforeRollover, suppressToday = true)
        assertEquals(LocalDateTime.of(2026, 9, 23, 4, 0).atZone(zone).toInstant().toEpochMilli(), atRollover)
    }

    /// The front of the TRAINING day: a 01:00 reminder is the evening's last slot, so one
    /// session done suppresses the morning, not the small-hours one.
    @Test
    fun suppressionCountsFromTheFrontOfTheTrainingDay() {
        val late = ReminderTime(hour = 1, minute = 0)
        val morning = ReminderTime(hour = 8, minute = 0)
        val requests = ReminderPlanner.requests(
            listOf(input(routineA, "Daily", listOf(late, morning), outstanding = 1))
        )
        assertEquals(listOf(1, 8), requests.map { it.hour }, "still listed in clock order")
        assertEquals(listOf(false, true), requests.map { it.suppressToday })
    }

    // MARK: - Identity

    /// `ReminderTime` has no UUID: identity IS the time. That makes the notification id
    /// content-keyed, so editing 08:00 → 09:00 replaces the request rather than leaving a
    /// stale 08:00 alarm nobody can see to cancel.
    @Test
    fun identifierIsDeterministicAndContentKeyed() {
        val eight = ReminderTime(hour = 8, minute = 0)
        val nine = ReminderTime(hour = 9, minute = 0)

        assertEquals("r0480", eight.slot)
        assertEquals("r0540", nine.slot)
        assertEquals("r1290", ReminderTime(hour = 21, minute = 30).slot)
        assertEquals("doigt.routine.", ReminderPlanner.identifierPrefix)
        assertEquals(
            "doigt.routine.11111111-2222-3333-4444-555555555555.r0480",
            ReminderPlanner.identifier(routineA, eight),
        )
        // Same inputs, same string, every time — that is what makes a replan idempotent.
        assertEquals(
            ReminderPlanner.identifier(routineA, eight),
            ReminderPlanner.identifier(routineA, eight),
        )

        val before = ReminderPlanner.requests(listOf(input(routineA, "Daily no-hangs", listOf(eight))))
        val after = ReminderPlanner.requests(listOf(input(routineA, "Daily no-hangs", listOf(nine))))
        assertEquals(
            listOf("doigt.routine.11111111-2222-3333-4444-555555555555.r0480"),
            before.map { it.identifier },
        )
        assertEquals(
            listOf("doigt.routine.11111111-2222-3333-4444-555555555555.r0540"),
            after.map { it.identifier },
        )
        assertFalse(
            after.map { it.identifier }.contains(before[0].identifier),
            "the edited slot must not survive alongside its replacement",
        )
    }

    @Test
    fun plannedRequestCarriesTheRoutineNameAndAFiringTime() {
        val planned = ReminderPlanner.requests(
            listOf(input(routineA, "Rest day", listOf(ReminderTime(hour = 19, minute = 15))))
        )
        val request = planned.firstOrNull()
        assertNotNull(request)

        assertEquals("Rest day", request.title)
        assertEquals("Time for a session.", request.body)
        assertEquals(19, request.hour)
        assertEquals(15, request.minute)
    }

    // MARK: - What contributes nothing

    @Test
    fun disabledOrEmptyRoutinesProduceNoRequests() = runTest {
        val times = listOf(ReminderTime(hour = 8, minute = 0), ReminderTime(hour = 19, minute = 0))
        assertEquals(emptyList(), ReminderPlanner.requests(emptyList()))
        assertEquals(
            emptyList(),
            ReminderPlanner.requests(listOf(input(routineA, "Daily no-hangs", times, enabled = false))),
        )
        assertEquals(
            emptyList(),
            ReminderPlanner.requests(listOf(input(routineA, "Daily no-hangs", emptyList()))),
        )

        // A disabled routine must not suppress an enabled one alongside it — and the
        // scheduler is handed exactly what `requests` produced, nothing else.
        val mixed = listOf(
            input(routineA, "Daily no-hangs", times, enabled = false),
            input(routineB, "Rest day", listOf(ReminderTime(hour = 8, minute = 0))),
        )
        assertEquals(listOf("Rest day"), ReminderPlanner.requests(mixed).map { it.title })

        val scheduler = RecordingAlarmScheduler()
        ReminderPlanner.replan(mixed, scheduler)
        assertEquals(listOf("Rest day"), scheduler.applied.map { it.title })
    }

    // MARK: - Sorting and dedupe

    /// Two routines legitimately share a wall-clock time, and the identifier keeps them
    /// apart. What must NOT survive is the same slot twice within one routine — that is
    /// one alarm the user set once, however many times it got into the list.
    @Test
    fun requestsAreSortedAndDeduplicatedAcrossRoutines() {
        val planned = ReminderPlanner.requests(
            listOf(
                input(
                    routineA, "Daily no-hangs",
                    listOf(
                        ReminderTime(hour = 19, minute = 0),
                        ReminderTime(hour = 8, minute = 0),
                        ReminderTime(hour = 8, minute = 0),
                    ),
                ),
                input(routineB, "Rest day", listOf(ReminderTime(hour = 8, minute = 0))),
            )
        )

        assertEquals(3, planned.size)
        assertEquals(3, planned.map { it.identifier }.toSet().size, "identifiers must be unique")
        assertEquals(
            setOf(
                "doigt.routine.11111111-2222-3333-4444-555555555555.r0480",
                "doigt.routine.11111111-2222-3333-4444-555555555555.r1140",
                "doigt.routine.66666666-7777-8888-9999-000000000000.r0480",
            ),
            planned.map { it.identifier }.toSet(),
        )

        // Within a routine the slots come out in clock order regardless of input order.
        assertEquals(
            listOf(8, 19),
            planned.filter { it.title == "Daily no-hangs" }.map { it.hour },
        )
    }

    @Test
    fun reminderTimeClampsToTheDayAndSortsByClock() {
        assertEquals(0, ReminderTime(-30).minutesFromMidnight)
        assertEquals(1439, ReminderTime(5_000).minutesFromMidnight)
        assertEquals(480, ReminderTime(hour = 8, minute = 0).id)
        assertEquals(21, ReminderTime(hour = 21, minute = 30).hour)
        assertEquals(30, ReminderTime(hour = 21, minute = 30).minute)
        assertTrue(ReminderTime(hour = 8, minute = 0) < ReminderTime(hour = 19, minute = 0))

        // The ladder new slots are filled from, in order.
        assertEquals(
            listOf(
                ReminderTime(hour = 8, minute = 0),
                ReminderTime(hour = 19, minute = 0),
                ReminderTime(hour = 12, minute = 30),
                ReminderTime(hour = 21, minute = 30),
            ),
            ReminderTime.defaults,
        )
    }
}
