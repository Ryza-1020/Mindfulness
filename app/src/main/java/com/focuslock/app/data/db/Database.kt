package com.focuslock.app.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.focuslock.app.core.RepeatType
import com.focuslock.app.core.ScheduleRule
import com.focuslock.app.core.SessionRecordData
import kotlinx.coroutines.flow.Flow

/** One finished (completed or ended-early) work session. */
@Entity(tableName = "session_records")
data class SessionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val plannedMs: Long,
    val actualMs: Long,
    val completed: Boolean,
    val taskName: String,
    /** Local date of the start, as epoch day – the day the session is counted on. */
    val dayEpoch: Long,
) {
    fun toData() = SessionRecordData(dayEpoch, plannedMs, actualMs, completed)
}

/** A future session the user scheduled. Times are local (see [ScheduleRule]). */
@Entity(tableName = "scheduled_sessions")
data class ScheduledSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val durationMin: Int,
    val hour: Int,
    val minute: Int,
    val repeat: RepeatType,
    val daysMask: Int,
    val dateEpochDay: Long?,
    val enabled: Boolean,
    /** Wall-clock millis of the armed alarm, null when not armed. Informational + stale-alarm guard. */
    val nextTriggerAt: Long?,
) {
    fun toRule() = ScheduleRule(repeat, hour, minute, daysMask, dateEpochDay)
}

@Dao
interface SessionRecordDao {
    @Insert
    suspend fun insert(record: SessionRecordEntity): Long

    @Query("SELECT * FROM session_records WHERE dayEpoch >= :fromDay ORDER BY startedAt")
    fun observeSince(fromDay: Long): Flow<List<SessionRecordEntity>>

    @Query("SELECT COALESCE(SUM(actualMs), 0) FROM session_records")
    fun observeTotalMs(): Flow<Long>
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM scheduled_sessions ORDER BY hour, minute")
    fun observeAll(): Flow<List<ScheduledSessionEntity>>

    @Query("SELECT * FROM scheduled_sessions WHERE id = :id")
    suspend fun get(id: Long): ScheduledSessionEntity?

    @Query("SELECT * FROM scheduled_sessions WHERE enabled = 1")
    suspend fun getEnabled(): List<ScheduledSessionEntity>

    /** Insert or replace; returns the row id (== id for existing rows). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScheduledSessionEntity): Long

    @Query("UPDATE scheduled_sessions SET enabled = :enabled, nextTriggerAt = :next WHERE id = :id")
    suspend fun setState(id: Long, enabled: Boolean, next: Long?)

    @Query("DELETE FROM scheduled_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [SessionRecordEntity::class, ScheduledSessionEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionRecordDao(): SessionRecordDao
    abstract fun scheduleDao(): ScheduleDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "focuslock.db").build()
    }
}
