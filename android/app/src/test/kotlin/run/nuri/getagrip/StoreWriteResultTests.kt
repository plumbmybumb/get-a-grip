// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.engine.RoutineDraft
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.store.DayClock
import run.nuri.getagrip.store.InMemoryRoutineSettings
import run.nuri.getagrip.store.RecordingAlarmScheduler
import run.nuri.getagrip.store.RoomStoreGateway
import run.nuri.getagrip.store.StoreGateway
import run.nuri.getagrip.store.StoreWriter
import run.nuri.getagrip.store.TemplateStore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/// **Every write answers for itself, and the derived world is published in order.**
///
/// Two failures of the shared `saveError` field and the unserialized recompute, each driven
/// by holding one write or one recompute at a suspension point while another runs past it.
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class StoreWriteResultTests {

    /// Reads can be held at a gate; the next write can be told to fail.
    private class GatedGateway(private val inner: StoreGateway) : StoreGateway by inner {
        var logsGate: CompletableDeferred<Unit>? = null
        /// Completed when a read reaches the gate — Room answers on its own threads, so a
        /// test has to WAIT for the held coroutine to get there rather than assume it has.
        val gateReached = CompletableDeferred<Unit>()
        val wrote = CompletableDeferred<Unit>()
        var failNextWrite = false

        override suspend fun logsFrom(dayKey: Int): List<WorkoutLogEntity>? {
            logsGate?.let { logsGate = null; gateReached.complete(Unit); it.await() }
            return inner.logsFrom(dayKey)
        }

        override suspend fun write(work: suspend (StoreWriter) -> Unit) {
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("disk full")
            }
            inner.write(work)
            wrote.complete(Unit)
        }
    }

    private val opened = mutableListOf<GetAGripDatabase>()

    @After
    fun tearDown() = opened.forEach { it.close() }

    private fun world(): Triple<GetAGripDatabase, GatedGateway, Pair<TemplateStore, RecordingAlarmScheduler>> {
        val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
        opened.add(db)
        val gateway = GatedGateway(RoomStoreGateway(db, UnconfinedTestDispatcher()))
        val scheduler = RecordingAlarmScheduler()
        val store = TemplateStore(
            gateway = gateway,
            clock = DayClock(),
            settings = InMemoryRoutineSettings(),
            scheduler = scheduler,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        return Triple(db, gateway, store to scheduler)
    }

    /// **A failed write is a failure even if another write clears the banner meanwhile.**
    /// The failing save set `saveError`, then suspended in its recompute; a second save
    /// reset the field on its way in, and the first one — reading the shared field back —
    /// reported success. For a delete that is an Undo offered for a row still there.
    @Test
    fun aFailedWriteIsNotReadAsSuccessAfterAnotherWriteResetsTheField() = runTest {
        val (db, gateway, pair) = world()
        val store = pair.first

        gateway.failNextWrite = true
        // Hold the failing write's recompute after its failure has been recorded.
        gateway.logsGate = CompletableDeferred()
        val held = gateway.logsGate!!
        val failing = async(UnconfinedTestDispatcher(testScheduler)) {
            store.recordLoggedSession(SessionKind.climbVolume)
        }
        gateway.gateReached.await()
        assertNotNull(store.saveError, "the failure is on screen")

        // A second write starts while the first is still in its recompute.
        val succeeding = async(UnconfinedTestDispatcher(testScheduler)) {
            store.recordLoggedSession(SessionKind.climbLimit)
        }
        // The second write has committed and is on its way into its own recompute, which
        // resets the banner — exactly what used to flip the first write's answer.
        gateway.wrote.await()
        held.complete(Unit)

        assertNull(failing.await(), "the write that failed must say so")
        assertNotNull(succeeding.await())
        assertEquals(listOf(SessionKind.climbLimit), db.logs().all().map { it.kind })
    }

    /// **An older recompute cannot publish over a newer one.** The first `syncDerived`
    /// read the routines, then stalled; a routine was created and its own recompute ran.
    /// Unserialized, the stalled one finished LAST and put the world without the new
    /// routine back on screen — and its plan back in the alarm scheduler.
    @Test
    fun anOlderRecomputeCannotPublishAfterANewerOne() = runTest {
        val (_, gateway, pair) = world()
        val (store, scheduler) = pair

        gateway.logsGate = CompletableDeferred()
        val held = gateway.logsGate!!
        val older = async(UnconfinedTestDispatcher(testScheduler)) { store.syncDerived() }
        gateway.gateReached.await()

        val draft = RoutineDraft(remindersEnabled = true)
        val created = async(UnconfinedTestDispatcher(testScheduler)) { store.create(draft) }
        gateway.wrote.await()
        // Give the newer recompute real time to run past the stalled one, as it did before
        // recomputes were serialized; serialized, it waits here instead and times out.
        withContext(Dispatchers.Default) { withTimeoutOrNull(500) { created.await() } }
        held.complete(Unit)
        older.await()

        val routine = assertNotNull(created.await())
        assertEquals(listOf(routine.id), store.routines.map { it.id },
            "the newest world is the one left on screen")
        // The replan is launched off the recompute (as on iOS), so it lands a beat later.
        // Wait for it rather than racing it: under a loaded full-suite run it had not yet
        // applied anything, which read as "no plan" rather than "the wrong plan".
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(5_000) { while (scheduler.applied.isEmpty()) delay(10) }
        }
        assertTrue(scheduler.applied.isNotEmpty() &&
            scheduler.applied.all { it.identifier.contains(routine.id.toString().uppercase()) },
            "and the newest plan is the one left in the scheduler")
    }
}
