// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import run.nuri.getagrip.data.CriticalForceRecordEntity
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.data.LogDayStamp
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import java.time.Instant
import java.util.UUID

/// Everything `TemplateStore` may do to the database, and the seam the rolled-back-save
/// tests drive.
///
/// TRANSLATION NOTE: iOS rolls back a `ModelContext` by hand after a failed save. Room rows
/// are values, so **a unit of work is a TRANSACTION**: if it throws nothing was written,
/// and the following recompute reads the same disk.
///
/// **A read returns null when it FAILED, which is not "there is nothing".** Every caller
/// that would publish on the answer bails on null; collapsing the two blanks the Today card
/// and hands `ReminderPlanner` an empty plan that cancels every reminder.
interface StoreGateway {
    suspend fun allRoutines(): List<SessionTemplateEntity>?
    suspend fun routine(id: UUID): SessionTemplateEntity?
    suspend fun logsFrom(dayKey: Int): List<WorkoutLogEntity>?
    suspend fun allLogs(): List<WorkoutLogEntity>?
    suspend fun allMaxes(): List<MaxRecordEntity>?

    /// Every critical force test, oldest first. Null when the read FAILED.
    suspend fun allCriticalForce(): List<CriticalForceRecordEntity>?

    /// One session by id; null when absent OR failed (either way nothing to delete). A
    /// point read: no single-session path may read the whole history.
    suspend fun log(id: UUID): WorkoutLogEntity?

    /// A routine's sessions. Null when the read FAILED, as everywhere here.
    suspend fun logsFor(templateID: UUID): List<WorkoutLogEntity>?

    /// The day-filing columns of every session started before `before` — the repair's only
    /// read, a projection that decodes no blob. Null on failure.
    suspend fun dayStamps(before: Instant): List<LogDayStamp>?

    /// One atomic unit of work. THROWS when it could not commit;
    /// `TemplateStore.persistAndSync` alone turns that into `saveError`.
    suspend fun write(work: suspend (StoreWriter) -> Unit)

    /// Counts calls to `write`, landed or not, so a raw-table reader (`HistoryFeed`) can
    /// tell whether the database may have changed. Observable, so a keyed screen rereads.
    ///
    /// Kept HERE, at the one door every write uses: the training-day repair writes straight
    /// through the gateway, and a caller-kept counter would miss it.
    val writeRevision: Long
}

/// The mutations, named one by one rather than a live DAO, so `persistAndSync` can see
/// whether a write touched a `MaxRecord` and may skip the unbounded max refold.
interface StoreWriter {
    /// Reads inside the writes' transaction: a max save or target rescale must not race
    /// another screen between calls.
    suspend fun allRoutines(): List<SessionTemplateEntity>?
    suspend fun allMaxes(): List<MaxRecordEntity>?
    suspend fun logsFrom(dayKey: Int): List<WorkoutLogEntity>?
    suspend fun putRoutine(row: SessionTemplateEntity)
    suspend fun removeRoutine(id: UUID)
    suspend fun putLog(row: WorkoutLogEntity)
    suspend fun removeLog(id: UUID)
    /// Move one session to another training day, touching nothing else about the row.
    suspend fun refileLog(id: UUID, dayKey: Int)
    suspend fun putMax(row: MaxRecordEntity)
    suspend fun removeMax(id: UUID)
    suspend fun putCriticalForce(row: CriticalForceRecordEntity)
    suspend fun removeCriticalForce(id: UUID)
}

/// Room, one call at a time.
///
/// **The lane is a `Mutex`, not the dispatcher.** `limitedParallelism(1)` is one THREAD,
/// which still interleaves at every suspension, and `withTransaction` suspends — a queued
/// read could run inside an open write transaction and see the old disk. The mutex holds
/// the lane for the whole call, which "a read never overtakes a write" (the history feed)
/// requires. It is NOT atomic across two calls: a read-then-write (renumber,
/// import-then-deconflict) is two turns, and anything that must be atomic reads through the
/// `StoreWriter` inside ONE transaction, as the max save and rescale do.
///
/// Never call the gateway from inside a `write` block — use its writer. The mutex is not
/// re-entrant and would deadlock.
class RoomStoreGateway(
    private val db: GetAGripDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StoreGateway {

    private val lane = Mutex()

    override var writeRevision: Long by mutableLongStateOf(0L)
        private set

    override suspend fun allRoutines(): List<SessionTemplateEntity>? =
        read { db.routines().all() }

    override suspend fun routine(id: UUID): SessionTemplateEntity? =
        read { db.routines().byId(id) }

    override suspend fun logsFrom(dayKey: Int): List<WorkoutLogEntity>? =
        read { db.logs().from(dayKey) }

    override suspend fun allLogs(): List<WorkoutLogEntity>? = read { db.logs().all() }

    override suspend fun allMaxes(): List<MaxRecordEntity>? = read { db.maxes().all() }

    override suspend fun allCriticalForce(): List<CriticalForceRecordEntity>? =
        read { db.criticalForce().all() }

    override suspend fun log(id: UUID): WorkoutLogEntity? = read { db.logs().byId(id) }

    override suspend fun logsFor(templateID: UUID): List<WorkoutLogEntity>? =
        read { db.logs().byTemplate(templateID) }

    override suspend fun dayStamps(before: Instant): List<LogDayStamp>? =
        read { db.logs().dayStamps(before) }

    override suspend fun write(work: suspend (StoreWriter) -> Unit) {
        lane.withLock {
            try {
                withContext(dispatcher) {
                    db.withTransaction { work(RoomWriter(db)) }
                }
            } finally {
                // AFTER the transaction, landed or not, still inside the lane: a read that
                // started earlier is then one revision behind and rereads; bumping before
                // could mark pre-write rows current.
                writeRevision++
            }
        }
    }

    /// A thrown read is a FAILED read, and the null says so — never an empty list. See
    /// `StoreGateway`.
    private suspend fun <T> read(block: suspend () -> T): T? = lane.withLock {
        withContext(dispatcher) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
        }
    }
}

private class RoomWriter(private val db: GetAGripDatabase) : StoreWriter {
    override suspend fun allRoutines() = db.routines().all()
    override suspend fun allMaxes() = db.maxes().all()
    override suspend fun logsFrom(dayKey: Int) = db.logs().from(dayKey)
    override suspend fun putRoutine(row: SessionTemplateEntity) = db.routines().upsert(row)
    override suspend fun removeRoutine(id: UUID) = db.routines().delete(id)
    override suspend fun putLog(row: WorkoutLogEntity) = db.logs().upsert(row)
    override suspend fun removeLog(id: UUID) = db.logs().delete(id)
    override suspend fun refileLog(id: UUID, dayKey: Int) = db.logs().refile(id, dayKey)
    override suspend fun putMax(row: MaxRecordEntity) = db.maxes().upsert(row)
    override suspend fun removeMax(id: UUID) = db.maxes().delete(id)
    override suspend fun putCriticalForce(row: CriticalForceRecordEntity) = db.criticalForce().upsert(row)
    override suspend fun removeCriticalForce(id: UUID) = db.criticalForce().delete(id)
}
