// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip

import run.nuri.getagrip.data.LogDayStamp
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import run.nuri.getagrip.store.StoreGateway
import run.nuri.getagrip.store.StoreWriter
import java.time.Instant
import java.util.UUID

/// A gateway that is three lists and nothing else — no Room, no threads.
///
/// That last part is the point for ordering tests: every call runs on the caller's
/// dispatcher, so under a test dispatcher "nothing else can happen" is something
/// `runCurrent()` proves, rather than something a real-time timeout hopes for.
internal open class MemoryStoreGateway : StoreGateway, StoreWriter {
    val routines = mutableListOf<SessionTemplateEntity>()
    val logs = mutableListOf<WorkoutLogEntity>()
    val maxes = mutableListOf<MaxRecordEntity>()
    var failLogReads = false

    override suspend fun allRoutines(): List<SessionTemplateEntity>? = routines.toList()
    override suspend fun routine(id: UUID) = routines.firstOrNull { it.id == id }
    override suspend fun logsFrom(dayKey: Int): List<WorkoutLogEntity>? =
        if (failLogReads) null else logs.filter { it.dayKey >= dayKey }
    override suspend fun allLogs(): List<WorkoutLogEntity>? = logs.toList()
    override suspend fun allMaxes(): List<MaxRecordEntity>? = maxes.toList()
    override suspend fun log(id: UUID) = logs.firstOrNull { it.id == id }
    override suspend fun logsFor(templateID: UUID): List<WorkoutLogEntity>? =
        logs.filter { it.templateID == templateID }
    override suspend fun dayStamps(before: Instant): List<LogDayStamp>? =
        logs.filter { it.startedAt.isBefore(before) }.map {
            LogDayStamp(it.id, it.startedAt, it.finishedAt, it.dayKey, it.kindRaw)
        }
    override suspend fun write(work: suspend (StoreWriter) -> Unit) = work(this)

    override suspend fun putRoutine(row: SessionTemplateEntity) {
        routines.removeAll { it.id == row.id }; routines.add(row)
    }
    override suspend fun removeRoutine(id: UUID) { routines.removeAll { it.id == id } }
    override suspend fun putLog(row: WorkoutLogEntity) {
        logs.removeAll { it.id == row.id }; logs.add(row)
    }
    override suspend fun removeLog(id: UUID) { logs.removeAll { it.id == id } }
    override suspend fun refileLog(id: UUID, dayKey: Int) {
        val index = logs.indexOfFirst { it.id == id }
        if (index >= 0) logs[index] = logs[index].copy(dayKey = dayKey)
    }
    override suspend fun putMax(row: MaxRecordEntity) {
        maxes.removeAll { it.id == row.id }; maxes.add(row)
    }
    override suspend fun removeMax(id: UUID) { maxes.removeAll { it.id == id } }
}
