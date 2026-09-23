// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.runner

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
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.RPE
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.engine.SessionPlan
import run.nuri.getagrip.engine.SetPlan
import run.nuri.getagrip.store.DayClock
import run.nuri.getagrip.store.InMemoryRoutineSettings
import run.nuri.getagrip.store.RecordingAlarmScheduler
import run.nuri.getagrip.store.RoomStoreGateway
import run.nuri.getagrip.store.TemplateStore
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// **What the next launch does with a session that finished and was never saved.**
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class UnsavedSessionRecoveryTests {

    private val directory: File = Files.createTempDirectory("recovery").toFile()
    private val opened = mutableListOf<GetAGripDatabase>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        directory.deleteRecursively()
    }

    private fun world(): Triple<GetAGripDatabase, TemplateStore, FinishedSessionDraftStore> {
        val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
        opened.add(db)
        val store = TemplateStore(
            gateway = RoomStoreGateway(db, UnconfinedTestDispatcher()),
            clock = DayClock(), settings = InMemoryRoutineSettings(),
            scheduler = RecordingAlarmScheduler(), scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        return Triple(db, store, FinishedSessionDraftStore(File(directory, FinishedSessionDraftStore.FILE_NAME)))
    }

    private val started: Instant = Instant.now().minusSeconds(3_600)

    private fun draft(templateID: UUID? = UUID.randomUUID()) = FinishedSessionDraft(
        id = UUID.randomUUID(),
        templateID = templateID,
        templateName = "Evening burn",
        sessionsPerDayTarget = 2,
        plan = SessionPlan(name = "Evening burn", sets = listOf(SetPlan(grip = GripSpec(), repsPerSide = 1))),
        reps = listOf(RepSummary(grip = GripSpec(), heldSeconds = 10.0, peakKg = 14.0, avgKg = 12.0)),
        startedAt = started,
        finishedAt = started.plusSeconds(600),
    )

    /// Save goes through the ordinary recording path, under the draft's own id, with the
    /// routine it ran as — deleted or not — and no effort reading nobody gave.
    @Test
    fun savingADraftLogsItOnceUnderItsOwnIdAndDeletesIt() = runTest {
        val (db, store, drafts) = world()
        val written = draft()
        drafts.save(written)
        val recovery = UnsavedSessionRecovery(drafts, store)

        val offered = assertNotNull(recovery.pending())
        assertTrue(recovery.save(offered))

        val row = db.logs().all().single()
        assertEquals(written.id, row.id)
        assertEquals(written.templateID, row.templateID)
        assertEquals("Evening burn", row.templateName)
        assertEquals(2, row.sessionsPerDayTarget)
        assertNull(row.rpe)
        assertEquals(1, row.completedReps)
        assertEquals(DayStamp.trainingDayOf(started, ZoneId.systemDefault()), row.day)
        assertNull(drafts.load(), "the draft goes once the row has landed")
        assertNull(recovery.pending())
    }

    @Test
    fun discardingADraftWritesNothing() = runTest {
        val (db, store, drafts) = world()
        drafts.save(draft())
        val recovery = UnsavedSessionRecovery(drafts, store)
        assertNotNull(recovery.pending())

        recovery.discard()

        assertNull(recovery.pending())
        assertTrue(db.logs().all().isEmpty())
    }

    /// The summary's Save landed, then the process died before the draft was deleted. The
    /// next launch finishes that quietly — never offering a session History already shows,
    /// and never overwriting the RPE the climber gave it.
    @Test
    fun aDraftWhoseSaveAlreadyLandedIsClearedWithoutAsking() = runTest {
        val (db, store, drafts) = world()
        val written = draft()
        drafts.save(written)
        assertNotNull(store.recordSession(
            plan = written.plan, identity = written.identity, reps = written.reps,
            startedAt = written.startedAt, finishedAt = written.finishedAt, rpe = RPE.solid,
        ))

        assertNull(UnsavedSessionRecovery(drafts, store).pending())

        assertNull(drafts.load())
        assertEquals(RPE.solid.rawValue, db.logs().all().single().rpe)
    }
}
