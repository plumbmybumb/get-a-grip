// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import run.nuri.getagrip.data.*
import run.nuri.getagrip.engine.*
import run.nuri.getagrip.store.*
import run.nuri.getagrip.ui.components.spokenSession
import run.nuri.getagrip.ui.maxes.MaxGripGroup
import run.nuri.getagrip.ui.maxes.presentSides
import run.nuri.getagrip.ui.maxes.progressLine
import java.time.Instant
import java.util.UUID
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
class DataAuditStoreTests {
    @Test fun failedHistoryReadDoesNotInventAnotherBenchmarkDay() = runTest {
        val gateway = MemoryGateway()
        val clock = DayClock()
        gateway.logs += WorkoutLogEntity.logged(SessionKind.benchmark, clock.today, Instant.now(), 1)
        gateway.failLogReads = true
        val store = TemplateStore(gateway, clock, InMemoryRoutineSettings(), RecordingAlarmScheduler(), backgroundScope)
        assertTrue(store.recordMax(30.0, GripSpec(), MaxSource.measured))
        assertEquals(1, gateway.logs.size)
        assertEquals(1, gateway.maxes.size, "the measured max still saves when day metadata cannot be checked")
    }

    @Test fun sameNamedRoutinesHaveDistinctPercentReceiptIdentity() = runTest {
        val gateway = MemoryGateway()
        val store = TemplateStore(gateway, DayClock(), InMemoryRoutineSettings(), RecordingAlarmScheduler(), backgroundScope)
        val blank = RoutineDraft.blank("Same name")
        val draft = blank.copy(plan = blank.plan.copy(handMode = HandMode.bothHands,
            sets = listOf(SetPlan(targetLoPercent = 0.25, targetHiPercent = 0.30))))
        val first = assertNotNull(store.create(draft))
        val second = assertNotNull(store.create(draft))
        val impact = store.maxImpact(GripSpec(), MaxTable(), 40.0)
        assertEquals(2, impact.percentMoves.size)
        assertEquals(setOf(first.id, second.id), impact.percentMoves.map { it.routineID }.toSet())
    }

    @Test fun historyTracksFromRoutineCreationEvenBeforeFirstLog() {
        val today = DayStamp.of(2026, 9, 6)
        val log = WorkoutLogEntity.logged(SessionKind.hangManual, today, Instant.now(), 1)
        val ledger = DayLedger(listOf(log), today, today - 7)
        assertEquals(today - 7, ledger.trackingSince)
        assertEquals(0.0, ledger.fraction(today - 1))
        assertFalse(spokenSession(log.copy(kindRaw = SessionKind.hang.rawValue,
            completedReps = 1, plannedReps = 1, peakKg = 0.0), "Timed").contains("kilograms"))
    }

    @Test fun mixedHandMaxesKeepTheirSeparateReadoutsAndBestValues() {
        val grip = GripSpec()
        val group = MaxGripGroup(grip.key, grip, listOf(
            MaxRecordEntity(kg = 60.0, sideRaw = Side.both.rawValue),
            MaxRecordEntity(kg = 25.0, sideRaw = Side.left.rawValue)))
        assertEquals(listOf(Side.both, Side.left), presentSides(group))
        val line = progressLine(group)
        assertTrue(line.contains("60.0"))
        assertTrue(line.contains("25.0"))
    }

    private class MemoryGateway : StoreGateway, StoreWriter {
        val routines = mutableListOf<SessionTemplateEntity>()
        val logs = mutableListOf<WorkoutLogEntity>()
        val maxes = mutableListOf<MaxRecordEntity>()
        var failLogReads = false
        override suspend fun allRoutines() = routines.toList()
        override suspend fun routine(id: UUID) = routines.firstOrNull { it.id == id }
        override suspend fun logsFrom(dayKey: Int) = if (failLogReads) null else logs.filter { it.dayKey >= dayKey }
        override suspend fun allLogs() = logs.toList()
        override suspend fun allMaxes() = maxes.toList()
        override suspend fun write(work: suspend (StoreWriter) -> Unit) = work(this)
        override suspend fun putRoutine(row: SessionTemplateEntity) { routines.removeAll { it.id == row.id }; routines.add(row) }
        override suspend fun removeRoutine(id: UUID) { routines.removeAll { it.id == id } }
        override suspend fun putLog(row: WorkoutLogEntity) { logs.removeAll { it.id == row.id }; logs.add(row) }
        override suspend fun removeLog(id: UUID) { logs.removeAll { it.id == id } }
        override suspend fun putMax(row: MaxRecordEntity) { maxes.removeAll { it.id == row.id }; maxes.add(row) }
        override suspend fun removeMax(id: UUID) { maxes.removeAll { it.id == id } }
    }
}
