// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.
package run.nuri.getagrip

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.store.HistoryFeed
import run.nuri.getagrip.store.HistorySource
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryFeedTests {
    @Test fun supersededReadCannotBringDeletedWorkoutBack() = runTest {
        val old = WorkoutLogEntity(templateName = "Deleted")
        val latest = WorkoutLogEntity(templateName = "Kept")
        val releaseFirst = CompletableDeferred<Unit>()
        var calls = 0
        val source = object : HistorySource {
            override suspend fun allLogs(): List<WorkoutLogEntity> {
                if (++calls == 1) return withContext(NonCancellable) {
                    releaseFirst.await()
                    listOf(old, latest)
                }
                return listOf(latest)
            }
            override suspend fun allMaxes() = emptyList<MaxRecordEntity>()
        }
        val feed = HistoryFeed(source, this, StandardTestDispatcher(testScheduler))
        feed.refresh()
        runCurrent()
        feed.refresh()
        runCurrent()
        assertEquals(listOf(latest.id), feed.logs.map { it.id })
        releaseFirst.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(latest.id), feed.logs.map { it.id })
        assertTrue(feed.hasLoaded)
    }

    @Test fun failedRefreshKeepsLastCompleteSnapshot() = runTest {
        val log = WorkoutLogEntity()
        val max = MaxRecordEntity(kg = 25.0)
        var fail = false
        val source = object : HistorySource {
            override suspend fun allLogs() = if (fail) emptyList() else listOf(log)
            override suspend fun allMaxes() = if (fail) null else listOf(max)
        }
        val feed = HistoryFeed(source, this, StandardTestDispatcher(testScheduler))
        assertFalse(feed.hasLoaded)
        feed.refresh(); advanceUntilIdle()
        fail = true
        feed.refresh(); advanceUntilIdle()
        assertEquals(listOf(log), feed.logs)
        assertEquals(listOf(max), feed.maxRecords)
    }

    @Test fun historyAndBenchmarksKeepOppositeChronologicalOrders() = runTest {
        val logs = (0..1_999).map { WorkoutLogEntity(startedAt = Instant.ofEpochSecond(it.toLong())) }
        val maxes = (0..99).reversed().map { MaxRecordEntity(recordedAt = Instant.ofEpochSecond(it.toLong())) }
        val source = object : HistorySource {
            override suspend fun allLogs() = logs
            override suspend fun allMaxes() = maxes
        }
        val feed = HistoryFeed(source, this, StandardTestDispatcher(testScheduler))
        feed.refresh(); advanceUntilIdle()
        assertEquals(logs.reversed(), feed.logs)
        assertEquals(maxes.reversed(), feed.maxRecords)
    }
}
