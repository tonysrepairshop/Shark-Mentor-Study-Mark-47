package com.example.sharkmentor.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.BuildConfig
import com.example.sharkmentor.SharkMentorApplication
import com.example.sharkmentor.data.local.entity.TargetEntity
import com.example.sharkmentor.data.local.entity.TargetPeriod
import com.example.sharkmentor.data.local.entity.TargetType
import com.example.sharkmentor.data.local.entity.TargetWithTasks
import com.example.sharkmentor.data.local.entity.TaskEntity
import com.example.sharkmentor.data.repository.PlanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.work.ExistingWorkPolicy
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.domain.util.PlanOrganizer
import com.example.sharkmentor.service.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine

@Immutable
sealed class PlanImportState {
    object Idle : PlanImportState()
    object Loading : PlanImportState()
    data class Error(val message: String, val lastUri: Uri? = null, val isLongTerm: Boolean = false) : PlanImportState()
    data class ImportSuccess(val parsedPlan: com.example.sharkmentor.core.ai.ParsedPlanResult, val isLongTerm: Boolean) : PlanImportState()
}

@Immutable
data class PlansUiState(
    val longTermTargets: List<TargetWithTasks> = emptyList(),
    val shortTermTargets: List<TargetWithTasks> = emptyList(),
    val importState: PlanImportState = PlanImportState.Idle
)

class PlansViewModel(application: Application) : AndroidViewModel(application) {

    private var repository: PlanRepository? = null

    private val _longTermTargets = MutableStateFlow<List<TargetWithTasks>>(emptyList())
    private val _shortTermTargets = MutableStateFlow<List<TargetWithTasks>>(emptyList())
    private val _importState = MutableStateFlow<PlanImportState>(PlanImportState.Idle)

    val uiState: StateFlow<PlansUiState> = combine(
        _longTermTargets, _shortTermTargets, _importState
    ) { longTerm, shortTerm, importSt ->
        PlansUiState(longTerm, shortTerm, importSt)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PlansUiState()
    )

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val database = com.example.sharkmentor.data.local.SharkMentorDatabase.getDatabase(application)
            val repo = PlanRepository(database.targetDao(), database.sessionDao())
            repository = repo
            
            launch {
                repo.getTargetsWithTasks(TargetType.LONG_TERM).collect { _longTermTargets.value = it }
            }
            launch {
                repo.getTargetsWithTasks(TargetType.SHORT_TERM).collect { _shortTermTargets.value = it }
            }
        }
    }

    fun addTarget(
        title: String,
        description: String,
        type: TargetType,
        period: TargetPeriod,
        deadlineMillis: Long,
        taskTitles: List<String>,
        priority: String = "NORMAL",
        targetValue: String? = null
    ) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val context = getApplication<Application>()
            val institutionId = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(context).institutionId ?: "INDIVIDUAL"
            val target = TargetEntity(
                title = title,
                description = description,
                type = type,
                period = period,
                deadlineMillis = deadlineMillis,
                institutionId = institutionId,
                priority = priority,
                targetValue = targetValue,
                isAssignedByCoach = false,
                isSynced = false
            )
            repository?.addTarget(target, taskTitles)
            android.util.Log.d("SharkMentorSync", "Triggering sync for addTarget")
            com.example.sharkmentor.service.sync.SyncWorker.enqueueImmediateSync(context)
        }
    }

    fun toggleTask(task: TaskEntity) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val context = getApplication<Application>()
            val database = com.example.sharkmentor.data.local.SharkMentorDatabase.getDatabase(context)
            val targetDao = database.targetDao()

            // 1. Toggle task status
            val updatedTask = task.copy(isCompleted = !task.isCompleted, isSynced = false)
            repository?.updateTask(updatedTask)

            // 2. Fetch all tasks for this plan to check completion
            val allTasks = targetDao.getTasksByTargetId(task.targetId)
            val isAllCompleted = allTasks.isNotEmpty() && allTasks.all { it.isCompleted }

            // 3. Update parent plan if needed
            val parentPlan = targetDao.getTargetById(task.targetId)
            if (parentPlan != null && parentPlan.isCompleted != isAllCompleted) {
                val updatedPlan = parentPlan.copy(
                    isCompleted = isAllCompleted,
                    isSynced = false,
                    lastModifiedMillis = System.currentTimeMillis()
                )
                targetDao.updateTarget(updatedPlan)
                android.util.Log.d("SharkMentorSync", "Plan auto-completed: ${updatedPlan.id} -> ${updatedPlan.isCompleted}")
            } else {
                // If plan completion didn't change, still mark it dirty to sync the new task state
                database.syncDao().markTargetDirty(task.targetId, System.currentTimeMillis())
            }

            android.util.Log.d("SharkMentorSync", "Triggering sync for toggleTask")
            com.example.sharkmentor.service.sync.SyncWorker.enqueueImmediateSync(context)
        }
    }

    fun toggleTarget(target: TargetEntity) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val context = getApplication<Application>()
            val database = com.example.sharkmentor.data.local.SharkMentorDatabase.getDatabase(context)
            val targetDao = database.targetDao()

            val isChecked = !target.isCompleted
            val updatedTarget = target.copy(
                isCompleted = isChecked,
                isSynced = false,
                lastModifiedMillis = System.currentTimeMillis()
            )
            targetDao.updateTarget(updatedTarget)

            // 2. Immediately Update Remote Firestore
            if (target.cloudId != null) {
                val db = GoogleServicesHelper.getFirebaseFirestore(getApplication())
                db?.collection("targets")?.document(target.cloudId)
                    ?.update(
                        mapOf(
                            "isCompleted" to isChecked,
                            "completed" to isChecked,
                            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                        )
                    )
                    ?.addOnFailureListener {
                        android.util.Log.e("TargetSync", "Bulut hedef güncelleme hatası: ${it.message}")
                    }
            }

            android.util.Log.d("SharkMentorSync", "Triggering sync for toggleTarget")
            com.example.sharkmentor.service.sync.SyncWorker.enqueueImmediateSync(context)
        }
    }

    fun deleteTarget(targetId: Long) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val context = getApplication<Application>()
            val database = SharkMentorDatabase.getDatabase(context)
            val targetDao = database.targetDao()
            
            // Step 2.3: Use soft delete for SyncWorker to pick it up
            targetDao.markTargetDeleted(targetId)
            
            Log.d("SharkMentorSync", "Target marked deleted: $targetId")
            triggerManualSync()
        }
    }

    fun importScheduleFromUri(uri: Uri, isLongTerm: Boolean = true) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            _importState.value = PlanImportState.Loading
            
            val aiService = com.example.sharkmentor.core.ai.GeminiCoreService(getApplication())
            val result = aiService.parseStudyPlanWithTasks(uri.toString(), isLongTerm)
            
            result.onSuccess { parsedPlan ->
                _importState.value = PlanImportState.ImportSuccess(parsedPlan, isLongTerm)
            }.onFailure { err ->
                _importState.value = PlanImportState.Error(err.message ?: "Bilinmeyen hata", uri, isLongTerm)
            }
        }
    }

    fun confirmAndSavePlan(planResult: com.example.sharkmentor.core.ai.ParsedPlanResult, isLongTerm: Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val institutionId = com.example.sharkmentor.data.local.preferences.UserPreferences.getInstance(context).institutionId ?: "INDIVIDUAL"
                val targetEntity = TargetEntity(
                    title = if (isLongTerm) "Uzun Vadeli AI Planı" else "Kısa Vadeli AI Planı",
                    description = planResult.summary,
                    type = if (isLongTerm) TargetType.LONG_TERM else TargetType.SHORT_TERM,
                    period = if (isLongTerm) TargetPeriod.MONTHLY else TargetPeriod.WEEKLY,
                    deadlineMillis = System.currentTimeMillis() + (if (isLongTerm) 30L else 7L) * 24 * 60 * 60 * 1000,
                    institutionId = institutionId,
                    priority = "NORMAL",
                    isAssignedByCoach = false,
                    isSynced = false
                )
                
                val taskTitles = planResult.tasks.map { task -> 
                    "${task.title} (${task.targetDayOrWeek}) - ${task.estimatedMinutes} dk: ${task.description}"
                }
                
                repository?.addTarget(targetEntity, taskTitles)
                android.util.Log.d("SharkMentorSync", "Triggering sync for confirmAndSavePlan")
                com.example.sharkmentor.service.sync.SyncWorker.enqueueImmediateSync(context)
                _importState.value = PlanImportState.Idle
            } catch (e: Exception) {
                _importState.value = PlanImportState.Error("Plan kaydedilirken hata oluştu: ${e.message}", null, isLongTerm)
            }
        }
    }

    fun clearUiState() {
        _importState.value = PlanImportState.Idle
    }

    fun triggerManualSync() {
        val syncRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.sharkmentor.service.sync.SyncWorker>()
            .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        androidx.work.WorkManager.getInstance(getApplication())
            .enqueueUniqueWork(
                "sharkmentor_plan_sync",
                ExistingWorkPolicy.KEEP,
                syncRequest
            )
    }

    fun organizeWeeklyPlan() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val context = getApplication<Application>()
            val database = SharkMentorDatabase.getDatabase(context)
            val targetDao = database.targetDao()

            val currentTargetsWithTasks = targetDao.getTargetsWithTasksSync(TargetType.SHORT_TERM)
            if (currentTargetsWithTasks.isEmpty()) return@launch

            val organizedTargets = PlanOrganizer.organizeTargetWithTasks(currentTargetsWithTasks)

            organizedTargets.forEach { item ->
                targetDao.updateTarget(item.target)
                item.tasks.forEach { task ->
                    targetDao.updateTask(task)
                }
            }
            
            triggerManualSync()
        }
    }
}
