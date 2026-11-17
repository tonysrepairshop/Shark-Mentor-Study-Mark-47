package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sharkmentor.data.local.entity.PlanFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanFolderDao {
    @Query("SELECT * FROM plan_folders WHERE isArchived = 0 ORDER BY createdAt DESC")
    fun getAllActiveFolders(): Flow<List<PlanFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: PlanFolderEntity)

    @Query("SELECT * FROM plan_folders WHERE createdAt >= :startOfDayTimestamp ORDER BY createdAt ASC")
    suspend fun getFoldersCreatedAfter(startOfDayTimestamp: Long): List<PlanFolderEntity>

    @Query("DELETE FROM plan_folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: String)
}
