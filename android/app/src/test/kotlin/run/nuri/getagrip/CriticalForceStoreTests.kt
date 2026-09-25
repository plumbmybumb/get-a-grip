// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.data.latestHands
import run.nuri.getagrip.data.latestVisit
import run.nuri.getagrip.engine.CriticalForceHands
import run.nuri.getagrip.engine.CriticalForcePoint
import run.nuri.getagrip.engine.CriticalForceProtocol
import run.nuri.getagrip.engine.CriticalForceRep
import run.nuri.getagrip.engine.CriticalForceResult
import run.nuri.getagrip.engine.CriticalForceTrace
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.SessionKind
import run.nuri.getagrip.engine.Side
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

/// Critical force through the real store and an in-memory Room database: the benchmark
/// day, the frozen max, the optional max in the same save, and the exact Undo.
///
/// Translated from the `// MARK: - Critical force` extension of Tests/TemplateStoreTests.swift.
/// Its own class because the rolled-back-save seam here is a wrapper gateway (see
/// `TemplateStoreTests`' note), which needs no shared fixture beyond `makeWorld`.
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class CriticalForceStoreTests {

    private class World(val db: GetAGripDatabase, val store: TemplateStore)

    private val opened = mutableListOf<GetAGripDatabase>()

    @After
    fun tearDown() {
        opened.forEach { it.close() }
        opened.clear()
    }

    private suspend fun TestScope.makeWorld(allowsSave: Boolean = true): World {
        val db = GetAGripDatabase.inMemory(RuntimeEnvironment.getApplication())
        opened.add(db)
        val room = RoomStoreGateway(db, UnconfinedTestDispatcher(testScheduler))
        val gateway: StoreGateway = if (allowsSave) room else object : StoreGateway by room {
            override suspend fun write(work: suspend (StoreWriter) -> Unit) {
                throw IllegalStateException("this store is read-only")
            }
        }
        val store = TemplateStore(
            gateway = gateway,
            clock = DayClock(),
            settings = InMemoryRoutineSettings(),
            scheduler = RecordingAlarmScheduler(),
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )
        store.syncDerived()
        return World(db, store)
    }

    private fun cfResult(cf: Double = 18.5, peak: Double = 38.0) = CriticalForceResult(
        protocolUsed = CriticalForceProtocol.standard,
        criticalForceKg = cf,
        wPrimeKgS = 1000.0,
        peakKg = peak,
        endForceKg = 16.0,
        reps = (0 until 24).map {
            CriticalForceRep(index = it, meanKg = cf, peakKg = peak, endKg = 16.0, impulseKgS = cf * 7,
                coverage = 1.0, restLoadSeconds = if (it == 23) null else 0.2)
        },
        criticalForceReps = 19..24,
    )

    private suspend fun World.benchmarks() = db.logs().all().count { it.kind == SessionKind.benchmark }

    /// A test is maximal testing: it settles the day exactly as a measured max does, once,
    /// and freezes the max it was taken against.
    @Test
    fun aCriticalForceTestIsABenchmarkDayAndFreezesTheMax() = runTest {
        val w = makeWorld()
        val grip = GripSpec()
        assertTrue(w.store.recordMaxes(listOf(TemplateStore.MaxSave(grip, Side.left, 36.0, MaxSource.measured))))

        val record = assertNotNull(w.store.recordCriticalForce(cfResult(), byteArrayOf(1, 20, 0, 0, 0, 0),
            grip, Side.left, bodyMassKg = 70.0))
        assertEquals(36.0, record.maxAtTestKg)
        assertEquals(70.0, record.bodyMassKg)
        assertEquals(18.5 / 36 * 100, record.percentOfMax!!, 1e-9)
        assertEquals(23, record.restsKept)
        assertEquals(24, record.reps.size)
        assertEquals(1, w.benchmarks(), "the max already stamped today; the test must not stamp a second")
        assertTrue(w.store.benchmarkedToday)
        assertEquals(listOf(record), w.store.criticalForceRecords, "the store publishes the saved test")

        // A later max never rewrites what the test was taken against.
        assertTrue(w.store.recordMaxes(listOf(TemplateStore.MaxSave(grip, Side.left, 40.0, MaxSource.manual))))
        assertEquals(36.0, w.db.criticalForce().all().single().maxAtTestKg)
    }

    @Test
    fun aCriticalForceTestAloneStampsTheDay() = runTest {
        val w = makeWorld()
        assertNotNull(w.store.recordCriticalForce(cfResult(), ByteArray(0), GripSpec(), Side.both, bodyMassKg = null))
        assertEquals(1, w.benchmarks())
        assertTrue(w.store.benchmarkedToday)
    }

    /// The hardest pull becomes a max only when the climber ticked the offer, and then in
    /// the same save as the test.
    @Test
    fun theTestPeakIsSavedAsAMaxOnlyWhenAsked() = runTest {
        val w = makeWorld()
        val grip = GripSpec()
        assertNotNull(w.store.recordCriticalForce(cfResult(), ByteArray(0), grip, Side.right, bodyMassKg = null))
        assertNull(w.store.maxTable.exact(grip.key, Side.right))

        assertNotNull(w.store.recordCriticalForce(cfResult(peak = 41.0), ByteArray(0), grip, Side.right,
            bodyMassKg = null, alsoMax = TemplateStore.MaxSave(grip, Side.right, 41.0, MaxSource.measured)))
        assertEquals(41.0, w.store.maxTable.exact(grip.key, Side.right))
    }

    @Test
    fun aCriticalForceSaveThatFailsLeavesNothingBehind() = runTest {
        val w = makeWorld(allowsSave = false)
        assertNull(w.store.recordCriticalForce(cfResult(), ByteArray(0), GripSpec(), Side.left, bodyMassKg = 70.0))
        assertTrue(w.db.criticalForce().all().isEmpty())
        assertTrue(w.db.logs().all().isEmpty())
        assertTrue(w.store.criticalForceRecords.isEmpty())
    }

    @Test
    fun aNonsenseResultIsRefused() = runTest {
        val w = makeWorld()
        assertNull(w.store.recordCriticalForce(cfResult(cf = Double.NaN), ByteArray(0), GripSpec(), Side.left,
            bodyMassKg = null))
        assertNull(w.store.recordCriticalForce(cfResult(), ByteArray(0), GripSpec(), Side.left, bodyMassKg = null,
            alsoMax = TemplateStore.MaxSave(GripSpec(), Side.left, 0.0, MaxSource.measured)))
        assertTrue(w.db.criticalForce().all().isEmpty())
    }

    /// One hand at a time: both hands land in one save, each frozen against its OWN max,
    /// with one benchmark day.
    @Test
    fun bothHandsInTurnSaveTogetherAgainstTheirOwnMaxes() = runTest {
        val w = makeWorld()
        val grip = GripSpec()
        assertTrue(w.store.recordMaxes(listOf(
            TemplateStore.MaxSave(grip, Side.left, 36.0, MaxSource.measured),
            TemplateStore.MaxSave(grip, Side.right, 32.0, MaxSource.measured),
        )))
        val saved = assertNotNull(w.store.recordCriticalForces(
            listOf(TemplateStore.CriticalForceSave(Side.left, cfResult(cf = 18.0), ByteArray(0)),
                TemplateStore.CriticalForceSave(Side.right, cfResult(cf = 16.0), ByteArray(0))),
            grip, bodyMassKg = 70.0,
            alsoMaxes = listOf(TemplateStore.MaxSave(grip, Side.right, 38.0, MaxSource.measured)),
        ))
        assertEquals(listOf(Side.left, Side.right), saved.map { it.side })
        assertEquals(listOf<Double?>(36.0, 32.0), saved.map { it.maxAtTestKg },
            "each hand against its own max, before the new one")
        assertEquals(saved[0].recordedAt, saved[1].recordedAt, "one visit, one instant")
        assertEquals(38.0, w.store.maxTable.exact(grip.key, Side.right))
        assertEquals(1, w.benchmarks())

        assertNull(w.store.recordCriticalForces(
            listOf(TemplateStore.CriticalForceSave(Side.left, cfResult(), ByteArray(0)),
                TemplateStore.CriticalForceSave(Side.left, cfResult(), ByteArray(0))),
            grip, bodyMassKg = null),
            "two results for one hand in one visit is a bug, not a save")
    }

    /// Android-only: the visit that Today reports, and how the next test opens.
    @Test
    fun theLatestVisitIsBothHandsOfOneSave() = runTest {
        val w = makeWorld()
        val grip = GripSpec()
        assertNotNull(w.store.recordCriticalForce(cfResult(cf = 15.0), ByteArray(0), GripSpec(edgeMM = 15),
            Side.both, bodyMassKg = null))
        assertEquals(CriticalForceHands.BothHands, w.store.criticalForceRecords.latestHands)
        assertNotNull(w.store.recordCriticalForces(
            listOf(TemplateStore.CriticalForceSave(Side.right, cfResult(cf = 16.0), ByteArray(0)),
                TemplateStore.CriticalForceSave(Side.left, cfResult(cf = 18.0), ByteArray(0))),
            grip, bodyMassKg = null))
        val visit = w.store.criticalForceRecords.latestVisit
        assertEquals(listOf(Side.left, Side.right), visit.map { it.side }, "left before right")
        assertEquals(CriticalForceHands.OneAtATime(Side.left), w.store.criticalForceRecords.latestHands)
    }

    /// Undo puts the test back EXACTLY: same id, date, blobs and frozen values.
    @Test
    fun deletingATestUndoesExactly() = runTest {
        val w = makeWorld()
        val trace = CriticalForceTrace.encode(listOf(CriticalForcePoint(0.1, 30.0), CriticalForcePoint(0.2, 31.0)))
        val record = assertNotNull(w.store.recordCriticalForce(cfResult(), trace, GripSpec(edgeMM = 15), Side.left,
            bodyMassKg = 68.5))
        val before = w.db.criticalForce().all().single()

        assertTrue(w.store.deleteCriticalForce(record))
        assertTrue(w.db.criticalForce().all().isEmpty())
        assertTrue(w.store.criticalForceRecords.isEmpty())
        assertNotNull(w.store.lastDeletedCriticalForce)

        w.store.undoDeleteCriticalForce()
        val restored = w.db.criticalForce().all().single()
        assertEquals(before, restored)
        assertTrue(before.traceData.contentEquals(restored.traceData))
        assertNull(w.store.lastDeletedCriticalForce)
        assertEquals(2, restored.trace.size)
        assertEquals(listOf(restored), w.store.criticalForceRecords)
    }

    /// Android-only: a delete that never landed offers no Undo, the rule every other
    /// delete in the store keeps.
    @Test
    fun aDeleteThatFailsOffersNoUndo() = runTest {
        val w = makeWorld()
        val record = assertNotNull(w.store.recordCriticalForce(cfResult(), ByteArray(0), GripSpec(), Side.left,
            bodyMassKg = null))
        w.db.criticalForce().delete(record.id)
        w.store.syncDerived()
        assertTrue(!w.store.deleteCriticalForce(record))
        assertNull(w.store.lastDeletedCriticalForce)
    }
}
