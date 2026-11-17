package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sharkmentor.data.local.entity.FocusSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: FocusSessionEntity)

    @Query("SELECT COALESCE(SUM(durationSeconds) / 60, 0) FROM focus_sessions WHERE completedAt >= :sinceMillis")
    fun getFocusTotalSince(sinceMillis: Long): Flow<Int>

    @Query("SELECT * FROM focus_sessions WHERE completedAt >= :startTimestamp AND completedAt <= :endTimestamp ORDER BY completedAt ASC")
    fun getSessionsForDateRange(startTimestamp: Long, endTimestamp: Long): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions ORDER BY completedAt DESC")
    fun getAllSessionsSorted(): Flow<List<FocusSessionEntity>>

    @Query("SELECT * FROM focus_sessions WHERE completedAt >= :sinceMillis")
    fun getSessionsSince(sinceMillis: Long): List<FocusSessionEntity>

    @Query("SELECT * FROM focus_sessions")
    fun getAllSessionsRaw(): List<FocusSessionEntity>

    @Query("SELECT SUM(durationSeconds) / 60 FROM focus_sessions WHERE completedAt >= :startOfDay AND completedAt <= :endOfDay")
    fun getTodayTotalFocusMinutes(startOfDay: Long, endOfDay: Long): Flow<Int?>

    @Query("SELECT SUM(phoneLiftCount) FROM focus_sessions WHERE completedAt >= :startOfDay AND completedAt <= :endOfDay")
    fun getTodayTotalPhoneLifts(startOfDay: Long, endOfDay: Long): Flow<Int?>

    @Query("SELECT SUM(screenOnMinutes) FROM focus_sessions WHERE completedAt >= :startOfDay AND completedAt <= :endOfDay")
    fun getTodayTotalScreenOnMinutes(startOfDay: Long, endOfDay: Long): Flow<Int?>

    @Query("DELETE FROM focus_sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: Long)
}
