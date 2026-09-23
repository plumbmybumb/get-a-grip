// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.data.LogDayStamp
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.data.WorkoutLogEntity
import java.time.Instant
import java.util.UUID

/// Everything `TemplateStore` is allowed to do to the database, and the seam the
/// rolled-back-save tests drive.
///
/// TRANSLATION NOTE: iOS hands the store a `ModelContext` and rolls back by hand
/// (`context.rollback()` after a failed `context.save()`). Room has no in-memory object
/// graph to roll back — rows are values — so **a unit of work is a TRANSACTION**, and
/// "memory matches disk" comes for free: if the transaction throws, nothing was written,
/// and the derived recompute that follows reads the same disk it read before.
///
/// **A read returns null when it FAILED, which is not the same fact as "there is
/// nothing".** The Optional exists only to keep those two apart; every caller that would
/// publish state on the strength of the answer bails on null. Collapsing them is what
/// blanks the Today card and hands `ReminderPlanner` an empty plan that cancels every
/// reminder the user has.
interface StoreGateway {
    suspend fun allRoutines(): List<SessionTemplateEntity>?
    suspend fun routine(id: UUID): SessionTemplateEntity?
    suspend fun logsFrom(dayKey: Int): List<WorkoutLogEntity>?
    suspend fun allLogs(): List<WorkoutLogEntity>?
    suspend fun allMaxes(): List<MaxRecordEntity>?

    /// One session by id. Null when it is absent OR the read failed — every caller treats
    /// the two alike (there is nothing to delete either way). Defaulted through `allLogs`
    /// so a test double need not care; Room answers it with an indexed point read.
    suspend fun log(id: UUID): WorkoutLogEntity? = allLogs()?.firstOrNull { it.id == id }

    /// A routine's sessions. Null when the read FAILED, as everywhere here.
    suspend fun logsFor(templateID: UUID): List<WorkoutLogEntity>? =
        allLogs()?.filter { it.templateID == templateID }

    /// The day-filing columns of every session started before `before` — the repair's only
    /// read, and a projection so it never decodes a blob. Null when the read failed.
    suspend fun dayStamps(before: Instant): List<LogDayStamp>? =
        allLogs()?.filter { it.startedAt.isBefore(before) }?.map {
            LogDayStamp(it.id, it.startedAt, it.finishedAt, it.dayKey, it.kindRaw)
        }

    /// One atomic unit of work. It THROWS when it could not be committed — the caller
    /// (`TemplateStore.persistAndSync`) is the one place that turns that into a
    /// `saveError`, so no write path can forget to report one.
    suspend fun write(work: suspend (StoreWriter) -> Unit)
}

/// The mutations, named one by one rather than exposed as a live DAO: it is what makes
/// `persistAndSync` able to see whether a write touched a `MaxRecord`, and therefore
/// whether the unbounded max refold may be skipped.
interface StoreWriter {
    /// Reads inside the same transaction as the writes they validate. A max save or
    /// reviewed target rescale must not race another screen's changes between calls.
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
}

/// Room, one call at a time.
///
/// **The lane is a `Mutex`, not the dispatcher.** `Dispatchers.IO.limitedParallelism(1)`
/// is not one serial lane: a lane of one THREAD still interleaves at every suspension
/// point, and `withTransaction` suspends — so a read queued behind a write could run while
/// the write's transaction was still open and read the disk as it stood before it. The mutex holds the lane across
/// the whole call, which is what "a read can never overtake a write" (the history feed
/// relies on it) actually requires. What it does NOT give is atomicity across two calls:
/// a read-then-write in the store (renumber, import-then-deconflict) is two turns of the
/// lane, and anything that must be atomic reads through the `StoreWriter` inside ONE
/// transaction instead, as the max save and the rescale do.
///
/// Never call the gateway from inside a `write` block — use the writer it hands you. The
/// mutex is not re-entrant, so that would wait on itself forever.
class RoomStoreGateway(
    private val db: GetAGripDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : StoreGateway {

    private val lane = Mutex()

    override suspend fun allRoutines(): List<SessionTemplateEntity>? =
        read { db.routines().all() }

    override suspend fun routine(id: UUID): SessionTemplateEntity? =
        read { db.routines().byId(id) }

    override suspend fun logsFrom(dayKey: Int): List<WorkoutLogEntity>? =
        read { db.logs().from(dayKey) }

    override suspend fun allLogs(): List<WorkoutLogEntity>? = read { db.logs().all() }

    override suspend fun allMaxes(): List<MaxRecordEntity>? = read { db.maxes().all() }

    override suspend fun log(id: UUID): WorkoutLogEntity? = read { db.logs().byId(id) }

    override suspend fun logsFor(templateID: UUID): List<WorkoutLogEntity>? =
        read { db.logs().byTemplate(templateID) }

    override suspend fun dayStamps(before: Instant): List<LogDayStamp>? =
        read { db.logs().dayStamps(before) }

    override suspend fun write(work: suspend (StoreWriter) -> Unit) {
        lane.withLock {
            withContext(dispatcher) {
                db.withTransaction { work(RoomWriter(db)) }
            }
        }
    }

    /// A thrown read is a FAILED read, and the null says so. It must never be caught into
    /// an empty list — see `StoreGateway`.
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
}
