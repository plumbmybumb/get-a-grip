// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.store

import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import run.nuri.getagrip.data.GetAGripDatabase
import run.nuri.getagrip.data.MaxRecordEntity
import run.nuri.getagrip.data.SessionTemplateEntity
import run.nuri.getagrip.data.WorkoutLogEntity
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

    /// One atomic unit of work. It THROWS when it could not be committed — the caller
    /// (`TemplateStore.persistAndSync`) is the one place that turns that into a
    /// `saveError`, so no write path can forget to report one.
    suspend fun write(work: suspend (StoreWriter) -> Unit)
}

/// The mutations, named one by one rather than exposed as a live DAO: it is what makes
/// `persistAndSync` able to see whether a write touched a `MaxRecord`, and therefore
/// whether the unbounded max refold may be skipped.
interface StoreWriter {
    suspend fun putRoutine(row: SessionTemplateEntity)
    suspend fun removeRoutine(id: UUID)
    suspend fun putLog(row: WorkoutLogEntity)
    suspend fun removeLog(id: UUID)
    suspend fun putMax(row: MaxRecordEntity)
    suspend fun removeMax(id: UUID)
}

/// Room, on ONE thread.
///
/// `Dispatchers.IO.limitedParallelism(1)` rather than plain IO: SQLite serializes writes
/// anyway, and a single lane means the store's own read-modify-write sequences (renumber,
/// import-then-deconflict) cannot interleave with each other. Results hop back to the
/// caller's context, which for the UI is Main.
class RoomStoreGateway(
    private val db: GetAGripDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : StoreGateway {

    override suspend fun allRoutines(): List<SessionTemplateEntity>? =
        read { db.routines().all() }

    override suspend fun routine(id: UUID): SessionTemplateEntity? =
        read { db.routines().byId(id) }

    override suspend fun logsFrom(dayKey: Int): List<WorkoutLogEntity>? =
        read { db.logs().from(dayKey) }

    override suspend fun allLogs(): List<WorkoutLogEntity>? = read { db.logs().all() }

    override suspend fun allMaxes(): List<MaxRecordEntity>? = read { db.maxes().all() }

    override suspend fun write(work: suspend (StoreWriter) -> Unit) {
        withContext(dispatcher) {
            db.withTransaction { work(RoomWriter(db)) }
        }
    }

    /// A thrown read is a FAILED read, and the null says so. It must never be caught into
    /// an empty list — see `StoreGateway`.
    private suspend fun <T> read(block: suspend () -> T): T? = withContext(dispatcher) {
        runCatching { block() }.getOrNull()
    }
}

private class RoomWriter(private val db: GetAGripDatabase) : StoreWriter {
    override suspend fun putRoutine(row: SessionTemplateEntity) = db.routines().upsert(row)
    override suspend fun removeRoutine(id: UUID) = db.routines().delete(id)
    override suspend fun putLog(row: WorkoutLogEntity) = db.logs().upsert(row)
    override suspend fun removeLog(id: UUID) = db.logs().delete(id)
    override suspend fun putMax(row: MaxRecordEntity) = db.maxes().upsert(row)
    override suspend fun removeMax(id: UUID) = db.maxes().delete(id)
}
