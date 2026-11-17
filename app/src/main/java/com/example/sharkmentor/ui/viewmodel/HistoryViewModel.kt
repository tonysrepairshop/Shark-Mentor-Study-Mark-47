package com.example.sharkmentor.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.SharkMentorApplication
import com.example.sharkmentor.data.local.entity.FocusSessionEntity
import com.example.sharkmentor.data.repository.PlanRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private var repository: PlanRepository? = null

    private val _recentSessions = kotlinx.coroutines.flow.MutableStateFlow<List<FocusSessionEntity>>(emptyList())
    val recentSessions: StateFlow<List<FocusSessionEntity>> = _recentSessions

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val database = com.example.sharkmentor.data.local.SharkMentorDatabase.getDatabase(application)
            val repo = PlanRepository(database.targetDao(), database.sessionDao())
            repository = repo
            
            launch {
                repo.getAllSessionsSorted().collect { _recentSessions.value = it }
            }
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val context = getApplication<Application>()
            val database = com.example.sharkmentor.data.local.SharkMentorDatabase.getDatabase(context)
            database.pendingDeletionDao().insert(
                com.example.sharkmentor.data.local.entity.PendingDeletionEntity(
                    entityId = sessionId.toString(),
                    collectionName = "focus_sessions"
                )
            )
            repository?.deleteSession(sessionId)
            android.util.Log.d("SharkMentorSync", "Triggering sync for deleteSession")
            com.example.sharkmentor.service.sync.SyncWorker.enqueueImmediateSync(context)
        }
    }
}
