// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.Instant
import java.util.Locale
import java.util.UUID

/// How the three value types cross the SQLite boundary.
///
/// **UUIDs are uppercase TEXT and dates epoch MILLIS**, the iOS blob shapes, so an id in a
/// row and inside an exported `RoutineDraft` blob are the same characters. Decoding accepts
/// either case: losing a routine over letter case is no trade.
object Converters {
    @TypeConverter
    fun uuidToText(value: UUID?): String? = value?.toString()?.uppercase(Locale.ROOT)

    @TypeConverter
    fun uuidFromText(value: String?): UUID? =
        value?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun instantFromMillis(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }
}

/// **Now, at the resolution the column can hold.** `Instant.now()` is
/// microsecond-resolution and the columns are epoch MILLIS, so a minted row and its
/// read-back differ invisibly (both print the same second) — breaking the undo promise that
/// a row comes back exactly as it was. Every timestamp the app mints comes from here.
fun storedNow(): Instant = Instant.ofEpochMilli(System.currentTimeMillis())

/// Routines. No ordering here: `TemplateStore.routineOrder` owns the total order (its
/// `id.toString()` tiebreak), and an `ORDER BY` would duplicate it.
@Dao
interface SessionTemplateDao {
    @Query("SELECT * FROM SessionTemplate")
    suspend fun all(): List<SessionTemplateEntity>

    @Query("SELECT * FROM SessionTemplate WHERE id = :id")
    suspend fun byId(id: UUID): SessionTemplateEntity?

    /// Insert AND update: a routine is a value, so applying a draft produces a whole new
    /// row.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SessionTemplateEntity)

    @Query("DELETE FROM SessionTemplate WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("DELETE FROM SessionTemplate")
    suspend fun deleteAll()
}

/// Sessions. `dayKey` is an Int so the window is a cheap predicate, not a calendar pass
/// over every log.
@Dao
interface WorkoutLogDao {
    @Query("SELECT * FROM WorkoutLog WHERE dayKey >= :earliest ORDER BY startedAt ASC")
    suspend fun from(earliest: Int): List<WorkoutLogEntity>

    @Query("SELECT * FROM WorkoutLog ORDER BY startedAt ASC")
    suspend fun all(): List<WorkoutLogEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: WorkoutLogEntity)

    @Query("DELETE FROM WorkoutLog WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("DELETE FROM WorkoutLog")
    suspend fun deleteAll()

    // MARK: - Point reads
    //
    // Deleting a session or a routine's sessions reads only those rows; loading all history
    // to filter in memory cost more every week trained.

    @Query("SELECT * FROM WorkoutLog WHERE id = :id")
    suspend fun byId(id: UUID): WorkoutLogEntity?

    @Query("SELECT * FROM WorkoutLog WHERE templateID = :templateID ORDER BY startedAt ASC")
    suspend fun byTemplate(templateID: UUID): List<WorkoutLogEntity>

    /// The four columns the training-day repair reads — no blobs, whatever the history's size.
    @Query("SELECT id, startedAt, finishedAt, dayKey, kindRaw FROM WorkoutLog WHERE startedAt < :before")
    suspend fun dayStamps(before: Instant): List<LogDayStamp>

    @Query("UPDATE WorkoutLog SET dayKey = :dayKey WHERE id = :id")
    suspend fun refile(id: UUID, dayKey: Int)
}

/// The slice of a `WorkoutLog` row that says which day it is filed under, and why — see
/// `TemplateStore.repairTrainingDays`.
data class LogDayStamp(
    val id: UUID,
    val startedAt: Instant,
    val finishedAt: Instant,
    val dayKey: Int,
    val kindRaw: String,
)

/// Maxes, oldest first — `newestPerGrip` and `lastMeasuredMaxAt` both read that order.
@Dao
interface MaxRecordDao {
    @Query("SELECT * FROM MaxRecord ORDER BY recordedAt ASC")
    suspend fun all(): List<MaxRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MaxRecordEntity)

    @Query("DELETE FROM MaxRecord WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("DELETE FROM MaxRecord")
    suspend fun deleteAll()
}

/// Critical force tests, oldest first — the Maxes cards and Today's line read that order.
@Dao
interface CriticalForceRecordDao {
    @Query("SELECT * FROM CriticalForceRecord ORDER BY recordedAt ASC")
    suspend fun all(): List<CriticalForceRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CriticalForceRecordEntity)

    @Query("DELETE FROM CriticalForceRecord WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("DELETE FROM CriticalForceRecord")
    suspend fun deleteAll()
}

/// The four tables, in the CloudKit-safe shape the iOS models froze.
///
/// **The file name is FROZEN as `getagrip`**, like the application id: renaming it after
/// install is an empty History with the old file still on disk. It is also named in
/// `res/xml/data_extraction_rules.xml`; move them together or Auto Backup silently backs up
/// nothing.
///
/// `exportSchema = true` writes `app/schemas/…/<version>.json` for migration diffs. Version
/// 1 is the first shipped schema; every later change is ADDITIVE (a defaulted column, never
/// a rename or drop), which Room auto-migrations write on their own.
///
/// Version 2 (2026-09-18): `SessionTemplate.startingHandRaw`, defaulted `left`.
/// Version 3 (2026-09-25): the `CriticalForceRecord` table. A new table is additive, so the
/// auto-migration creates it and touches nothing else.
@Database(
    entities = [
        SessionTemplateEntity::class,
        WorkoutLogEntity::class,
        MaxRecordEntity::class,
        CriticalForceRecordEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class GetAGripDatabase : RoomDatabase() {
    abstract fun routines(): SessionTemplateDao
    abstract fun logs(): WorkoutLogDao
    abstract fun maxes(): MaxRecordDao
    abstract fun criticalForce(): CriticalForceRecordDao

    companion object {
        /// FROZEN — see the type's note.
        const val NAME = "getagrip"

        fun open(context: Context): GetAGripDatabase =
            Room.databaseBuilder(context.applicationContext, GetAGripDatabase::class.java, NAME)
                .build()

        /// Tests and the seeders' scratch worlds.
        fun inMemory(context: Context): GetAGripDatabase =
            Room.inMemoryDatabaseBuilder(context, GetAGripDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
