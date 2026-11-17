package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.sharkmentor.data.local.entity.PendingDeletionEntity

@Dao
interface PendingDeletionDao {

    @Insert
    suspend fun insert(deletion: PendingDeletionEntity)

    @Query("SELECT * FROM pending_deletions ORDER BY createdAt ASC")
    suspend fun getAllPendingDeletions(): List<PendingDeletionEntity>

    @Query("DELETE FROM pending_deletions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_deletions")
    suspend fun clearAll()
}
