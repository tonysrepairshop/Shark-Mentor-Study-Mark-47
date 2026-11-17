package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sharkmentor.data.local.entity.DailyReflectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyReflectionDao {
    @Query("SELECT * FROM daily_reflections ORDER BY createdAtMillis DESC LIMIT 7")
    fun getRecentReflectionsFlow(): Flow<List<DailyReflectionEntity>>

    @Query("SELECT * FROM daily_reflections ORDER BY createdAtMillis DESC LIMIT 7")
    fun getRecentReflectionsRaw(): List<DailyReflectionEntity>

    @Query("SELECT * FROM daily_reflections WHERE dateString = :dateString LIMIT 1")
    suspend fun getReflectionForDate(dateString: String): DailyReflectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReflection(reflection: DailyReflectionEntity)
}
