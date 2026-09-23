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
import run.nuri.getagrip.engine.BlobCodec
import run.nuri.getagrip.engine.DayStamp
import run.nuri.getagrip.engine.FingerSet
import run.nuri.getagrip.engine.GripPosition
import run.nuri.getagrip.engine.GripSpec
import run.nuri.getagrip.engine.MaxSource
import run.nuri.getagrip.engine.RepSummary
import run.nuri.getagrip.store.HistoryFeed
import run.nuri.getagrip.store.HistorySource
import java.time.Instant
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    /// A source that counts its reads, over lists a test can change.
    private class CountingSource(var logs: List<WorkoutLogEntity> = emptyList()) : HistorySource {
        var reads = 0
        var maxes: List<MaxRecordEntity> = emptyList()
        override suspend fun allLogs(): List<WorkoutLogEntity> { reads++; return logs }
        override suspend fun allMaxes() = maxes
    }

    @Test fun aTabSwitchWithNothingWrittenDoesNotReadAgain() = runTest {
        val source = CountingSource(listOf(WorkoutLogEntity()))
        var revision = 0L
        val feed = HistoryFeed(source, this, StandardTestDispatcher(testScheduler), revision = { revision })
        feed.refreshIfStale(); advanceUntilIdle()
        assertEquals(1, source.reads)
        repeat(5) { feed.refreshIfStale(); advanceUntilIdle() }
        assertEquals(1, source.reads, "no write, no read")
        revision++
        feed.refreshIfStale(); advanceUntilIdle()
        assertEquals(2, source.reads)
    }

    @Test fun aWriteLandingDuringTheReadIsReadAgain() = runTest {
        val release = CompletableDeferred<Unit>()
        var revision = 0L
        var reads = 0
        val stale = WorkoutLogEntity(templateName = "before")
        val fresh = WorkoutLogEntity(templateName = "after")
        val source = object : HistorySource {
            override suspend fun allLogs(): List<WorkoutLogEntity> {
                if (++reads == 1) { release.await(); return listOf(stale) }
                return listOf(fresh)
            }
            override suspend fun allMaxes() = emptyList<MaxRecordEntity>()
        }
        val feed = HistoryFeed(source, this, StandardTestDispatcher(testScheduler), revision = { revision })
        feed.refreshIfStale(); runCurrent()
        revision++ // the write lands while the first read is still out
        release.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(stale.id), feed.logs.map { it.id })
        feed.refreshIfStale(); advanceUntilIdle()
        assertEquals(listOf(fresh.id), feed.logs.map { it.id }, "a read that began before the write is not current")
    }

    @Test fun theFoldsArriveWithTheListsTheyDescribe() = runTest {
        val crimp = GripSpec(20, FingerSet.four, GripPosition.halfCrimp)
        val pinch = GripSpec(30, FingerSet.four, GripPosition.pinch)
        val withReps = WorkoutLogEntity(dayKey = 100, completedReps = 3,
            resultsData = BlobCodec.encodeAll(listOf(RepSummary(grip = pinch), RepSummary(grip = crimp)))!!)
        val climb = WorkoutLogEntity(dayKey = 101, kindRaw = "climbLimit")
        val source = CountingSource(listOf(withReps, climb))
        source.maxes = listOf(
            MaxRecordEntity.from(crimp, 30.0, MaxSource.manual, recordedAt = Instant.ofEpochSecond(1)),
            MaxRecordEntity.from(pinch, 20.0, MaxSource.manual, recordedAt = Instant.ofEpochSecond(2)),
            MaxRecordEntity.from(crimp, 31.0, MaxSource.manual, recordedAt = Instant.ofEpochSecond(3)),
        )
        val feed = HistoryFeed(source, this, StandardTestDispatcher(testScheduler))
        feed.refresh(); advanceUntilIdle()
        assertEquals(pinch, feed.leadingGrip(withReps))
        assertNull(feed.leadingGrip(climb))
        assertEquals(3, feed.lifetime.pulls)
        assertEquals(1, feed.lifetime.climbDays)
        assertEquals(listOf(crimp.key, pinch.key), feed.maxGroups.map { it.key })
        assertEquals(2, feed.maxGroups.first().records.size)
    }

    @Test fun theLedgerIsKeptUntilTheLogsOrTheDayMove() = runTest {
        val source = CountingSource(listOf(WorkoutLogEntity(dayKey = 100)))
        var revision = 0L
        val feed = HistoryFeed(source, this, StandardTestDispatcher(testScheduler), revision = { revision })
        feed.refresh(); advanceUntilIdle()
        val today = DayStamp(105)
        val first = feed.ledger(today, null)
        assertSame(first, feed.ledger(today, null), "a tab switch folds nothing")
        assertEquals(1.0, first.fraction(DayStamp(100)))
        source.logs = listOf(WorkoutLogEntity(dayKey = 100), WorkoutLogEntity(dayKey = 104, sessionsPerDayTarget = 2))
        revision++
        feed.refreshIfStale(); advanceUntilIdle()
        val refolded = feed.ledger(today, null)
        assertTrue(refolded !== first)
        assertEquals(0.5, refolded.fraction(DayStamp(104)))
        assertTrue(feed.ledger(DayStamp(106), null) !== refolded, "a new day is a new fold")
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
