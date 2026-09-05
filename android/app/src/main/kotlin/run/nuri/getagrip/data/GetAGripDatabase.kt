// SPDX-License-Identifier: MPL-2.0
// Original contributions Copyright 2026 Nuri Bruner.

package run.nuri.getagrip.data

import android.content.Context
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
/// **UUIDs are TEXT, uppercase**, and dates are epoch MILLIS — the same shapes the iOS
/// blobs use, so a routine id in a `SessionTemplate` row and the same id inside an
/// exported `RoutineDraft` blob are the same characters. Decoding accepts either case,
/// because a row written by hand or by a future import may not be uppercase and losing a
/// routine over a letter case is not a trade anybody would make.
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

/// **Now, at the resolution the column can hold.**
///
/// `Instant.now()` is microsecond-resolution on a modern JDK and these columns are epoch
/// MILLIS, so a row minted with `Instant.now()` and the same row read back are not equal —
/// they differ by a few hundred microseconds. That breaks exactly the promise the undo
/// path exists to make ("the row came back precisely as it was"), and it breaks it
/// invisibly, because both values print as the same second. Every timestamp this app mints
/// therefore comes from here.
fun storedNow(): Instant = Instant.ofEpochMilli(System.currentTimeMillis())

/// Routines. Ordering is deliberately NOT expressed here — `TemplateStore.routineOrder`
/// owns the total order, because the tiebreak runs off `id.toString()` and a SQL
/// `ORDER BY` would have to duplicate a rule that exists in exactly one place.
@Dao
interface SessionTemplateDao {
    @Query("SELECT * FROM SessionTemplate")
    suspend fun all(): List<SessionTemplateEntity>

    @Query("SELECT * FROM SessionTemplate WHERE id = :id")
    suspend fun byId(id: UUID): SessionTemplateEntity?

    /// One method for insert AND update. A routine is a value here, so "apply a draft"
    /// produces a whole new row and there is nothing to patch column by column.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SessionTemplateEntity)

    @Query("DELETE FROM SessionTemplate WHERE id = :id")
    suspend fun delete(id: UUID)

    @Query("DELETE FROM SessionTemplate")
    suspend fun deleteAll()
}

/// Sessions. `dayKey` is an Int column precisely so the window is a cheap predicate
/// rather than a calendar pass over every log ever written.
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
}

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

/// The three tables, in the CloudKit-safe shape the iOS models froze.
///
/// **The database file name is FROZEN as `getagrip`** for the same reason the application
/// id is: it is where somebody's training history lives on their phone, and renaming it
/// after the first install is an empty History tab with the old file still on disk. It is
/// also named in `res/xml/data_extraction_rules.xml`, so the two must move together or
/// Auto Backup silently starts backing up nothing.
///
/// `exportSchema = true` writes `app/schemas/…/1.json`, which is what a future migration
/// gets diffed against. Version 1 is the first shipped schema; every change after it is
/// ADDITIVE — a new column with a default, never a rename, never a drop.
@Database(
    entities = [
        SessionTemplateEntity::class,
        WorkoutLogEntity::class,
        MaxRecordEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class GetAGripDatabase : RoomDatabase() {
    abstract fun routines(): SessionTemplateDao
    abstract fun logs(): WorkoutLogDao
    abstract fun maxes(): MaxRecordDao

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
