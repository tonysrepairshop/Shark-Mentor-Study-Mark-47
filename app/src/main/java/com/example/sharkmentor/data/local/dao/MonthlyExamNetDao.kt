package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sharkmentor.data.local.entity.MonthlyExamNetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MonthlyExamNetDao {
    @Query("SELECT * FROM monthly_exam_nets ORDER BY yearMonth DESC")
    fun getAllNetsFlow(): Flow<List<MonthlyExamNetEntity>>

    @Query("DELETE FROM monthly_exam_nets WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM monthly_exam_nets WHERE isSynced = 0 AND isDeleted = 0")
    suspend fun getUnsyncedExams(): List<MonthlyExamNetEntity>

    @Query("UPDATE monthly_exam_nets SET isSynced = 1, cloudId = :cloudId WHERE id = :localId")
    suspend fun markSynced(localId: Long, cloudId: String)

    @Query("SELECT * FROM monthly_exam_nets ORDER BY yearMonth DESC LIMIT 1")
    suspend fun getLatestNetRaw(): MonthlyExamNetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNet(net: MonthlyExamNetEntity)
}
