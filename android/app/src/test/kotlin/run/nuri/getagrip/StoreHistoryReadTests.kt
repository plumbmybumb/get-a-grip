// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.store.DayClock
import run.nuri.getagrip.store.InMemoryRoutineSettings
import run.nuri.getagrip.store.RecordingAlarmScheduler
import run.nuri.getagrip.store.RoomStoreGateway
import run.nuri.getagrip.store.StoreGateway
import run.nuri.getagrip.store.TemplateStore
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/// **No path that touches one session reads them all.** Deleting a session, deleting a
/// routine and the launch repair each used to load every log ever written — blobs and all
/// — and the repair did it on every cold launch, ahead of the first frame. These pin the
/// narrower reads and the repair's one-shot, old-rule-only scope.
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class StoreHistoryReadTests {

    /// Every whole-history read FAILS. Anything still reaching for `allLogs` breaks here.
    private class NoFullHistoryGateway(private val inner: StoreGateway) : StoreGateway by inner {
        var fullReads = 0
        override suspend fun allLogs(): List<WorkoutLogEntity>? {
            fullReads += 1
            return null
        }
    }

    private val paris: ZoneId = ZoneId.of("Europe/Paris")
    private val opened = mutableListOf<GetAGripDatabase>()

    @After
    fun tearDown() = opened.forEach { it.close() }

    private class World(
        val db: GetAGripDatabase,
        val gateway: NoFullHistoryGateway,
        val settings: InMemoryRoutineSettings,
        val store: TemplateStore,
    )

    private fun world(): World {
        val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
        opened.add(db)
        val gateway = NoFullHistoryGateway(RoomStoreGateway(db, UnconfinedTestDispatcher()))
        val settings = InMemoryRoutineSettings()
        val store = TemplateStore(
            gateway = gateway, clock = DayClock(), settings = settings,
            scheduler = RecordingAlarmScheduler(), scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        return World(db, gateway, settings, store)
    }

    private fun at(day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 9, day, hour, minute, 0, 0, paris).toInstant()

    private fun hang(startedDay: Int, hour: Int, minute: Int, filedUnder: DayStamp, minutes: Long = 13): WorkoutLogEntity {
        val plan = RoutineDraft.starter.normalized.plan.executable
        val started = at(startedDay, hour, minute)
        return WorkoutLogEntity.from(
            plan = plan, templateID = null, templateName = "Daily burn", sessionsPerDayTarget = 2,
            reps = listOf(RepSummary(grip = plan.sets.first().grip)),
            startedAt = started, finishedAt = started.plusSeconds(minutes * 60), day = filedUnder,
        )
    }

    @Test
    fun deletingASessionAndARoutineReadOnlyTheirOwnRows() = runTest {
        val w = world()
        val routine = assertNotNull(w.store.create(RoutineDraft.starter))
        val logged = assertNotNull(w.store.recordSession(
            plan = routine.plan, template = routine, reps = emptyList(),
            startedAt = java.time.Instant.now(), finishedAt = java.time.Instant.now(), rpe = null,
        ))
        val other = assertNotNull(w.store.recordSession(
            plan = routine.plan, template = routine, reps = emptyList(),
            startedAt = java.time.Instant.now(), finishedAt = java.time.Instant.now(), rpe = null,
        ))

        assertTrue(w.store.deleteSession(logged), "a point read finds the one row")
        assertTrue(w.store.delete(routine), "and a routine's delete finds only its own")
        assertEquals(listOf(other.id), w.store.lastDeleted?.sessions?.map { it.id })
        assertEquals(0, w.gateway.fullReads, "no whole-history read on either path")
    }

    /// The writer and the repair agree: a session is filed under the training day it
    /// STARTED in, wherever the save happens to land.
    @Test
    fun aSessionIsFiledUnderTheTrainingDayItStartedIn() = runTest {
        val w = world()
        // Begun 03:50 on the 21st — still the 20th's training day — and saved at 04:10.
        val log = assertNotNull(w.store.recordSession(
            plan = RoutineDraft.starter.normalized.plan, template = null, reps = emptyList(),
            startedAt = at(21, 3, 50), finishedAt = at(21, 4, 10), rpe = null, zone = paris,
        ))
        assertEquals(DayStamp.of(2026, 9, 20), log.day)
        assertEquals(0, w.store.repairTrainingDays(paris), "and the repair has nothing to say about it")
    }

    /// Saving the same finished session twice — from the summary and again from launch
    /// recovery — replaces one row rather than logging the workout twice.
    @Test
    fun aSessionSavedTwiceUnderItsOwnIdIsOneRow() = runTest {
        val w = world()
        val id = java.util.UUID.randomUUID()
        repeat(2) {
            assertNotNull(w.store.recordSession(
                plan = RoutineDraft.starter.normalized.plan, template = null, reps = emptyList(),
                startedAt = at(21, 18, 0), finishedAt = at(21, 18, 20), rpe = null, id = id,
            ))
        }
        assertEquals(listOf(id), w.db.logs().all().map { it.id })
    }

    /// **Once per device, old-rule rows only, and never a kind this build cannot name.**
    @Test
    fun theLaunchRepairRunsOnceAndOnlyOnOldRuleRows() = runTest {
        val w = world()
        val sept19 = DayStamp.of(2026, 9, 19)
        val sept20 = DayStamp.of(2026, 9, 20)
        // Crossed midnight, filed by the old clock under the morning after: moves.
        val crossed = hang(19, 23, 47, filedUnder = sept20)
        // Filed under a day that is neither its start's nor its finish's calendar day: some
        // other path chose it, and the repair does not second-guess it.
        val deliberate = hang(19, 23, 47, filedUnder = DayStamp.of(2026, 9, 1))
        // A kind from a newer build: `fallback` would read it as a hang and move it.
        val unknown = hang(19, 23, 47, filedUnder = sept20).copy(kindRaw = "fromTheFuture")
        listOf(crossed, deliberate, unknown).forEach { w.db.logs().upsert(it) }

        assertEquals(1, w.store.repairTrainingDaysOnce(paris))
        val after = w.db.logs().all().associateBy { it.id }
        assertEquals(sept19, after.getValue(crossed.id).day)
        assertEquals(DayStamp.of(2026, 9, 1), after.getValue(deliberate.id).day)
        assertEquals(sept20, after.getValue(unknown.id).day)
        assertEquals(TemplateStore.trainingDayRepairVersion, w.settings.trainingDayRepairVersion)
        assertEquals(0, w.gateway.fullReads, "a projection, never the whole history")

        // Done is done: a later launch does not read the history again.
        w.db.logs().upsert(hang(19, 23, 50, filedUnder = sept20))
        assertEquals(0, w.store.repairTrainingDaysOnce(paris))
    }

    /// A pass that could not run is not a pass that found nothing: the flag stays down and
    /// the next launch tries again.
    @Test
    fun aFailedRepairIsRetriedOnTheNextLaunch() = runTest {
        val w = world()
        val failing = object : StoreGateway by w.gateway {
            override suspend fun dayStamps(before: java.time.Instant) = null
        }
        val store = TemplateStore(
            gateway = failing, clock = DayClock(), settings = w.settings,
            scheduler = RecordingAlarmScheduler(), scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        assertEquals(0, store.repairTrainingDaysOnce(paris))
        assertEquals(0, w.settings.trainingDayRepairVersion)
        assertEquals(0, w.store.repairTrainingDaysOnce(paris))
        assertEquals(TemplateStore.trainingDayRepairVersion, w.settings.trainingDayRepairVersion)
    }

    @Test
    fun handLoggedSessionsKeepTheDayThePersonChose() = runTest {
        val w = world()
        val climb = WorkoutLogEntity.logged(SessionKind.climbLimit, DayStamp.of(2026, 9, 20), at(19, 23, 50), 2)
        w.db.logs().upsert(climb)
        assertEquals(0, w.store.repairTrainingDays(paris))
    }
}
