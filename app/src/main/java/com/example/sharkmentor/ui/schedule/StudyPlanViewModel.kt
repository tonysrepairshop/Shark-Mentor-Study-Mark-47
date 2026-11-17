package com.example.sharkmentor.ui.schedule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.dao.StudyPlanDao
import com.example.sharkmentor.data.local.entity.ActivePlanEntity
import com.example.sharkmentor.data.local.entity.StudyTaskEntity
import com.example.sharkmentor.data.repository.FirebaseToRoomSyncManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class StudyPlanUiState(
    val plan: ActivePlanEntity? = null,
    val tasksByDay: Map<String, List<StudyTaskEntity>> = emptyMap(),
    val isLoading: Boolean = false
)

class StudyPlanViewModel(application: Application) : AndroidViewModel(application) {

    private val studyPlanDao: StudyPlanDao = SharkMentorDatabase.getDatabase(application).studyPlanDao()
    private val syncManager: FirebaseToRoomSyncManager = FirebaseToRoomSyncManager(application)

    private val _isRefreshing = MutableStateFlow(false)

    val uiState: StateFlow<StudyPlanUiState> = combine(
        studyPlanDao.observeActivePlan(),
        studyPlanDao.observeAllTasks(),
        _isRefreshing
    ) { plan, tasks, refreshing ->
        val groupedTasks = tasks.groupBy { it.dayName }
        StudyPlanUiState(
            plan = plan,
            tasksByDay = groupedTasks,
            isLoading = refreshing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StudyPlanUiState(isLoading = true)
    )

    init {
        checkRemoteUpdates()
    }

    fun checkRemoteUpdates() {
        viewModelScope.launch {
            _isRefreshing.value = true
            syncManager.syncRemotePlanToRoom(force = false)
            _isRefreshing.value = false
        }
    }

    fun toggleTaskCompletion(task: StudyTaskEntity) {
        viewModelScope.launch {
            studyPlanDao.updateTaskStatus(task.localId, !task.isCompleted)
        }
    }
}
