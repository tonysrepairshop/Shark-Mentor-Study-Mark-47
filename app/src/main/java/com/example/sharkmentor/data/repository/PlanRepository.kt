package com.example.sharkmentor.data.repository

import android.graphics.Bitmap
import com.example.sharkmentor.core.ai.GeminiPlanAnalyzer
import com.example.sharkmentor.data.local.dao.PlanFolderDao
import com.example.sharkmentor.data.local.entity.PlanFolderEntity
import com.example.sharkmentor.data.local.dao.SessionDao
import com.example.sharkmentor.data.local.dao.TargetDao
import com.example.sharkmentor.data.local.entity.TargetEntity
import com.example.sharkmentor.data.local.entity.TargetType
import com.example.sharkmentor.data.local.entity.TargetWithTasks
import com.example.sharkmentor.data.local.entity.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

class PlanRepository(
    private val targetDao: TargetDao,
    private val sessionDao: SessionDao,
    private val planFolderDao: PlanFolderDao? = null
) {
    fun getTargetsWithTasks(type: TargetType): Flow<List<TargetWithTasks>> {
        return targetDao.getTargetsWithTasks(type)
    }

    suspend fun saveAiGeneratedPlan(tasksWithSubtasks: List<Pair<TargetEntity, List<String>>>, forceNewFolder: Boolean = false) = withContext(Dispatchers.IO) {
        val todayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val existingTodayFolder = planFolderDao?.getFoldersCreatedAfter(todayStart)?.firstOrNull()

        val targetFolderId = if (existingTodayFolder != null && !forceNewFolder) {
            existingTodayFolder.id
        } else {
            val sdf = SimpleDateFormat("dd MMM", Locale.getDefault())
            val startStr = sdf.format(System.currentTimeMillis())
            val endStr = sdf.format(System.currentTimeMillis() + 7L * 24 * 3600 * 1000)
            val newFolder = PlanFolderEntity(title = "$startStr - $endStr Planı")
            planFolderDao?.insertFolder(newFolder)
            newFolder.id
        }

        // We assign all generated tasks and save them
        for ((target, subtasks) in tasksWithSubtasks) {
            addTarget(target, subtasks) 
        }
    }

    suspend fun addTarget(target: TargetEntity, tasks: List<String>) = withContext(Dispatchers.IO) {
        val targetId = targetDao.insertTarget(target)
        val taskEntities = tasks.mapIndexed { index, title ->
            TaskEntity(
                targetId = targetId,
                title = title,
                orderIndex = index,
                isSynced = false,
                institutionId = target.institutionId
            )
        }
        targetDao.insertTasks(taskEntities)
    }

    suspend fun updateTask(task: TaskEntity) = withContext(Dispatchers.IO) {
        targetDao.updateTask(task)
    }

    suspend fun deleteTarget(targetId: Long) = withContext(Dispatchers.IO) {
        targetDao.markTargetDeleted(targetId)
    }

    suspend fun importFromImages(bitmaps: List<Bitmap>): Result<Int> = withContext(Dispatchers.IO) {
        GeminiPlanAnalyzer.analyzeStudySchedule(bitmaps).mapCatching { result ->
            var count = 0
            for (parsed in result.targets) {
                val targetEntity = TargetEntity(
                    title = parsed.title,
                    description = "Imported via AI",
                    type = parsed.type,
                    period = parsed.period,
                    deadlineMillis = parsed.estimatedDeadlineMillis
                )
                addTarget(targetEntity, parsed.tasks)
                count++
            }
            count
        }
    }

    // --- Analytics & History ---
    fun getSessionsForDateRange(start: Long, end: Long) = sessionDao.getSessionsForDateRange(start, end)
    fun getAllSessionsSorted() = sessionDao.getAllSessionsSorted()
    fun getTodayTotalFocusMinutes(startOfDay: Long, endOfDay: Long) = sessionDao.getTodayTotalFocusMinutes(startOfDay, endOfDay)
    fun getTodayTotalPhoneLifts(startOfDay: Long, endOfDay: Long) = sessionDao.getTodayTotalPhoneLifts(startOfDay, endOfDay)
    fun getTodayTotalScreenOnMinutes(startOfDay: Long, endOfDay: Long) = sessionDao.getTodayTotalScreenOnMinutes(startOfDay, endOfDay)
    suspend fun deleteSession(sessionId: Long) = withContext(Dispatchers.IO) { sessionDao.deleteSession(sessionId) }

    fun getTaskStatsByTypeAndDate(type: TargetType, startOfDay: Long, endOfDay: Long) = targetDao.getTaskStatsByTypeAndDate(type, startOfDay, endOfDay)
    fun getClosestActiveTarget(type: TargetType) = targetDao.getClosestActiveTarget(type)
}
