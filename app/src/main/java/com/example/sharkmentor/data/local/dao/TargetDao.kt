package com.example.sharkmentor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.example.sharkmentor.data.local.entity.TargetEntity
import com.example.sharkmentor.data.local.entity.TargetType
import com.example.sharkmentor.data.local.entity.TargetWithTasks
import com.example.sharkmentor.data.local.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TargetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTarget(target: TargetEntity): Long

    @Update
    suspend fun updateTarget(target: TargetEntity)

    @Query("UPDATE targets SET isDeleted = 1, isSynced = 0, lastModifiedMillis = :timestamp WHERE id = :targetId")
    suspend fun markTargetDeleted(targetId: Long, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM targets WHERE id = :targetId")
    suspend fun deleteTargetPermanently(targetId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<TaskEntity>)

    @Upsert
    suspend fun upsertTarget(target: TargetEntity)

    @Upsert
    suspend fun upsertTasks(tasks: List<TaskEntity>)

    /**
     * Conflict-safe inbound sync: only overwrite local data if the local copy
     * is already synced (no pending local edits). This prevents the echo-loop:
     * remote coach plan arrives → inserted as isSynced=true → SyncWorker skips it.
     */
    @Transaction
    suspend fun syncRemotePlan(plan: TargetEntity, tasks: List<TaskEntity>) {
        val existing = getTargetById(plan.id)
        if (existing == null || existing.isSynced) {
            upsertTarget(plan.copy(isSynced = true))
            deleteTasksByTargetId(plan.id)
            if (tasks.isNotEmpty()) {
                upsertTasks(tasks.map { it.copy(isSynced = true) })
            }
        }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: TaskEntity): Long

    @Update
    suspend fun updateTask(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: Long)

    @Query("DELETE FROM tasks WHERE targetId = :targetId")
    suspend fun deleteTasksByTargetId(targetId: Long)

    @Query("DELETE FROM targets WHERE id NOT IN (:remoteIds) AND isSynced = 1")
    suspend fun deleteOrphanedTargets(remoteIds: List<Long>)

    @Query("DELETE FROM targets WHERE isSynced = 1")
    suspend fun clearAllTargets()

    @Transaction
    suspend fun purgeTarget(targetId: Long) {
        deleteTasksByTargetId(targetId)
        deleteTargetPermanently(targetId)
    }

    @Transaction
    suspend fun purgeTargetAndTasks(targetId: Long) {
        purgeTarget(targetId)
    }

    @Query("SELECT * FROM targets WHERE isDeleted = 1 AND isSynced = 0")
    suspend fun getPendingDeletedTargets(): List<TargetEntity>

    @Query("""
        SELECT * FROM targets 
        WHERE isDeleted = 0 
        ORDER BY 
            CASE priority 
                WHEN 'HIGH' THEN 1 
                WHEN 'NORMAL' THEN 2 
                WHEN 'LOW' THEN 3 
                ELSE 2 
            END ASC, 
            deadlineMillis ASC
    """)
    fun getAllActiveTargets(): Flow<List<TargetEntity>>

    @Transaction
    @Query("""
        SELECT * FROM targets 
        WHERE type = :type AND isDeleted = 0 
        ORDER BY 
            CASE priority 
                WHEN 'HIGH' THEN 1 
                WHEN 'NORMAL' THEN 2 
                WHEN 'LOW' THEN 3 
                ELSE 2 
            END ASC, 
            deadlineMillis ASC
    """)
    fun getTargetsWithTasks(type: TargetType): Flow<List<TargetWithTasks>>

    @Query("SELECT * FROM tasks WHERE targetId = :targetId")
    suspend fun getTasksByTargetId(targetId: Long): List<TaskEntity>

    @Query("SELECT * FROM targets WHERE id = :id")
    suspend fun getTargetById(id: Long): TargetEntity?

    @Query("""
        SELECT 
            COALESCE(SUM(CASE WHEN tasks.isCompleted = 1 THEN 1 ELSE 0 END), 0) as completed,
            COUNT(tasks.id) as total
        FROM tasks
        INNER JOIN targets ON tasks.targetId = targets.id
        WHERE targets.type = :type AND targets.isDeleted = 0 AND targets.createdAt >= :startOfDay AND targets.createdAt <= :endOfDay
    """)
    fun getTaskStatsByTypeAndDate(type: TargetType, startOfDay: Long, endOfDay: Long): Flow<TaskStats?>

    @Transaction
    @Query("""
        SELECT * FROM targets 
        WHERE type = :type AND isDeleted = 0 
        ORDER BY 
            CASE priority 
                WHEN 'HIGH' THEN 1 
                WHEN 'NORMAL' THEN 2 
                WHEN 'LOW' THEN 3 
                ELSE 2 
            END ASC, 
            deadlineMillis ASC
    """)
    suspend fun getTargetsWithTasksSync(type: TargetType): List<TargetWithTasks>

    @Query("""
        SELECT * FROM targets 
        WHERE type = :type AND isDeleted = 0 AND isCompleted = 0 
        ORDER BY deadlineMillis ASC 
        LIMIT 1
    """)
    fun getClosestActiveTarget(type: TargetType): Flow<TargetEntity?>
}

data class TaskStats(
    val completed: Int,
    val total: Int
)
