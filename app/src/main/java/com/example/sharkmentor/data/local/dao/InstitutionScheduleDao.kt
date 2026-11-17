package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sharkmentor.data.local.entity.InstitutionScheduleEntity

@Dao
interface InstitutionScheduleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(schedules: List<InstitutionScheduleEntity>)

    /**
     * Returns all institution slots for a specific day, ordered by start time.
     * Used by [SchedulePlannerEngine] to build per-day constraint blocks.
     */
    @Query("SELECT * FROM institution_schedule WHERE dayOfWeek = :day ORDER BY startTime ASC")
    suspend fun getSchedulesForDay(day: Int): List<InstitutionScheduleEntity>

    /**
     * Returns all institution slots across all days, ordered chronologically.
     * Used to build the full constraint preamble for the Gemini planner prompt.
     */
    @Query("SELECT * FROM institution_schedule ORDER BY dayOfWeek ASC, startTime ASC")
    suspend fun getAllSchedules(): List<InstitutionScheduleEntity>

    @Query("SELECT * FROM institution_schedule ORDER BY dayOfWeek ASC, startTime ASC")
    fun getAllSchedulesFlow(): kotlinx.coroutines.flow.Flow<List<InstitutionScheduleEntity>>

    /** Wipe-and-replace on every full Firestore sync. */
    @Query("DELETE FROM institution_schedule")
    suspend fun deleteAll()

    @Query("DELETE FROM institution_schedule WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM institution_schedule WHERE dayOfWeek = :day")
    suspend fun deleteByDay(day: Int)

    @Query("SELECT * FROM institution_schedule WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsyncedSchedules(): List<InstitutionScheduleEntity>

    @Query("UPDATE institution_schedule SET isSynced = 1, cloudId = :cloudId WHERE id = :localId")
    suspend fun markSynced(localId: String, cloudId: String)
}
