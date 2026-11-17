package com.example.sharkmentor.data.local.dao

import androidx.room.*
import com.example.sharkmentor.data.local.entity.ActivePlanEntity
import com.example.sharkmentor.data.local.entity.StudyTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StudyPlanDao {

    @Query("SELECT * FROM active_study_plans WHERE id = 'CURRENT_ACTIVE_PLAN' LIMIT 1")
    fun observeActivePlan(): Flow<ActivePlanEntity?>

    @Query("SELECT * FROM study_plan_tasks WHERE planId = 'CURRENT_ACTIVE_PLAN' ORDER BY dayIndex ASC, localId ASC")
    fun observeAllTasks(): Flow<List<StudyTaskEntity>>

    @Query("SELECT * FROM study_plan_tasks WHERE dayName = :dayName ORDER BY localId ASC")
    fun observeTasksForDay(dayName: String): Flow<List<StudyTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlan(plan: ActivePlanEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTasks(tasks: List<StudyTaskEntity>)

    @Query("DELETE FROM study_plan_tasks WHERE planId = 'CURRENT_ACTIVE_PLAN'")
    suspend fun clearTasks()

    @Query("DELETE FROM active_study_plans WHERE id = 'CURRENT_ACTIVE_PLAN'")
    suspend fun clearPlan()

    @Transaction
    suspend fun replaceActivePlanWithTasks(plan: ActivePlanEntity, tasks: List<StudyTaskEntity>) {
        clearTasks()
        insertPlan(plan)
        insertTasks(tasks)
    }

    @Query("UPDATE study_plan_tasks SET isCompleted = :completed WHERE localId = :taskId")
    suspend fun updateTaskStatus(taskId: Long, completed: Boolean)
}
