package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.sharkmentor.data.local.entity.TrainingFolderEntity

@Dao
interface TrainingFolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: TrainingFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(folders: List<TrainingFolderEntity>)

    @Query("SELECT * FROM training_folders WHERE isDeleted = 0 ORDER BY createdAt DESC")
    suspend fun getAllFolders(): List<TrainingFolderEntity>

    @Query("SELECT * FROM training_folders")
    suspend fun getAll(): List<TrainingFolderEntity>

    @Query("SELECT * FROM training_folders WHERE isSynced = 0")
    suspend fun getUnsyncedFolders(): List<TrainingFolderEntity>

    @Query("UPDATE training_folders SET isSynced = 1, cloudId = :cloudId WHERE id = :localId")
    suspend fun markSynced(localId: String, cloudId: String)
}
