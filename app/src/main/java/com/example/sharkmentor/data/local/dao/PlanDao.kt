package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.sharkmentor.data.local.entity.PlanEntity
import com.example.sharkmentor.data.local.entity.PlanFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlanDao {
    @Query("SELECT * FROM plan_folders WHERE isArchived = 0 ORDER BY createdAt DESC")
    fun getAllActiveFolders(): Flow<List<PlanFolderEntity>>

    @Query("SELECT * FROM plan_folders WHERE isArchived = 0 ORDER BY createdAt DESC")
    suspend fun getAllFoldersSync(): List<PlanFolderEntity>

    @Query("SELECT * FROM plans WHERE folderId = :folderId ORDER BY dayOfWeek ASC, scheduledTime ASC")
    fun getTasksForFolder(folderId: String): Flow<List<PlanEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: PlanFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<PlanEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(plans: List<PlanEntity>)

    @Query("DELETE FROM plan_folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: String)

    @Query("DELETE FROM plans WHERE folderId = :folderId")
    suspend fun deleteTasksByFolder(folderId: String)

    /** Purges timetable items that were mistakenly stored in plans. */
    @Query("DELETE FROM plans WHERE id LIKE 'schedule_%' OR folderId = 'weekly_schedule_folder'")
    suspend fun purgeMisroutedScheduleTasks()

    @Query("DELETE FROM plan_folders WHERE id = 'weekly_schedule_folder'")
    suspend fun purgeWeeklyScheduleFolder()

    @Query("UPDATE plans SET folderId = :targetFolderId WHERE folderId IS NULL OR folderId = '' OR folderId = 'default_folder'")
    suspend fun bindOrphanTasksToFolder(targetFolderId: String)

    @Delete
    suspend fun deleteTask(task: PlanEntity)

    @Query("UPDATE plans SET isCompleted = :isCompleted WHERE id = :taskId")
    suspend fun updateTaskCompletion(taskId: String, isCompleted: Boolean)

    @Transaction
    suspend fun insertPlanWithTasks(folder: PlanFolderEntity, tasks: List<PlanEntity>) {
        insertFolder(folder)
        insertTasks(tasks)
    }

    @Transaction
    suspend fun cascadeDeleteFolder(folderId: String) {
        deleteTasksByFolder(folderId)
        deleteFolder(folderId)
    }
}
