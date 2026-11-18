package com.example.sharkmentor.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.InstitutionScheduleEntity
import com.example.sharkmentor.data.local.preferences.UserPreferences
import com.example.sharkmentor.data.remote.ScheduleScannerEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

sealed class ScheduleUiState {
    object Loading : ScheduleUiState()
    data class Success(
        val isReadOnlyMode: Boolean,
        val schedules: List<InstitutionScheduleEntity>,
        val webScheduleText: String? = null,
        /** True when schedules were synced from a coach-assigned customScheduleData block */
        val isCoachSchedule: Boolean = false
    ) : ScheduleUiState()
    data class Error(val message: String) : ScheduleUiState()
}

class ScheduleViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ScheduleUiState>(ScheduleUiState.Loading)
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    private val scheduleDao = SharkMentorDatabase.getDatabase(application).institutionScheduleDao()
    private val prefs = UserPreferences.getInstance(application)
    private val scannerEngine = ScheduleScannerEngine()

    private val _weeklySchedule = MutableStateFlow<WeeklyScheduleData?>(null)
    val weeklySchedule: StateFlow<WeeklyScheduleData?> = _weeklySchedule.asStateFlow()

    private val syncRepository = com.example.sharkmentor.data.repository.StudentSyncRepository(
        com.google.firebase.firestore.FirebaseFirestore.getInstance(),
        com.google.firebase.auth.FirebaseAuth.getInstance()
    )

    init {
        loadSchedules()
        listenToStudentSchedule()
    }

    private fun listenToStudentSchedule() {
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) return
        viewModelScope.launch {
            syncRepository.observeStudentProfile()
                .catch { _weeklySchedule.value = null }
                .collect { profile ->
                    if (profile != null) {
                        @Suppress("UNCHECKED_CAST")
                        val scheduleMap = profile["customScheduleData"] as? Map<String, Any>
                        if (scheduleMap != null) {
                            val parsed = parseScheduleFromMap(scheduleMap)
                            _weeklySchedule.value = parsed
                        } else {
                            _weeklySchedule.value = null
                        }
                    } else {
                        _weeklySchedule.value = null
                    }
                }
        }
    }

    private fun loadSchedules() {
        viewModelScope.launch {
            try {
                var isReadOnly = !prefs.isIndividual
                var instId: String? = null
                var webText: String? = null
                var hasCoachSchedule = false

                val currentUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    ?: com.example.sharkmentor.core.util.GoogleServicesHelper.getCurrentUser()
                val uid = currentUser?.uid
                val db = com.example.sharkmentor.core.util.GoogleServicesHelper.getFirebaseFirestore()
                if (currentUser != null && uid != null && db != null) {
                    try {
                        val doc = db.collection("students").document(uid).get().await()
                        val coachId = doc.getString("coachId")
                        val isCorporate = doc.getBoolean("isCorporate") ?: false
                        // If the coach assigned a customScheduleData, prefer the visual grid
                        val hasCustomSchedule = doc.get("customScheduleData") != null
                        if (!coachId.isNullOrBlank() || isCorporate) {
                            if (hasCustomSchedule) {
                                // Coach assigned a timetable via AiScheduleStudioModal:
                                // show the visual grid, not the read-only institution text
                                isReadOnly = false
                                hasCoachSchedule = true
                            } else {
                                isReadOnly = true
                                if (!coachId.isNullOrBlank()) {
                                    val coachDoc = db.collection("coaches").document(coachId).get().await()
                                    instId = coachDoc.getString("institutionId")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Use default fallback
                    }
                }

                if (currentUser != null && instId != null && db != null) {
                    runCatching {
                        db.collection("institutions").document(instId).addSnapshotListener { snap, err ->
                            if (err != null) return@addSnapshotListener
                            if (snap != null && snap.exists()) {
                                val text = snap.getString("weeklySchedule")
                                val currentState = _uiState.value
                                if (currentState is ScheduleUiState.Success) {
                                    _uiState.value = currentState.copy(webScheduleText = text)
                                } else {
                                    webText = text
                                }
                            }
                        }
                    }
                }

                scheduleDao.getAllSchedulesFlow().collect { schedules ->
                    _uiState.value = ScheduleUiState.Success(
                        isReadOnlyMode = isReadOnly,
                        schedules = schedules,
                        isCoachSchedule = hasCoachSchedule,
                        webScheduleText = (if (_uiState.value is ScheduleUiState.Success) {
                            (_uiState.value as ScheduleUiState.Success).webScheduleText
                        } else webText)
                    )
                }
            } catch (e: Exception) {
                _uiState.value = ScheduleUiState.Error(e.localizedMessage ?: "Bilinmeyen hata")
            }
        }
    }

    fun scanAndSaveSchedule(uri: Uri) {
        val previousState = _uiState.value
        viewModelScope.launch {
            _uiState.value = ScheduleUiState.Loading
            val result = scannerEngine.scanSchedule(getApplication(), uri)
            result.onSuccess { scannedTasks ->
                val entities = scannedTasks.map { task ->
                    val times = task.scheduledTime.split("-").map { it.trim() }
                    val startTime = times.getOrNull(0) ?: "08:00"
                    val endTime = times.getOrNull(1) ?: "09:00"

                    InstitutionScheduleEntity(
                        id = UUID.randomUUID().toString(),
                        dayOfWeek = task.dayOfWeek,
                        startTime = startTime,
                        endTime = endTime,
                        subject = task.subject,
                        type = if (prefs.isIndividual) "INDIVIDUAL" else "DERSHANE",
                        teacherName = task.teacherName,
                        period = task.period
                    )
                }
                // Clear old individual schedules without touching coach-assigned custom schedules
                scheduleDao.replaceIndividualSchedules(entities)
                com.example.sharkmentor.service.sync.SyncWorker.enqueueImmediateSync(getApplication())

            }.onFailure {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        getApplication(),
                        it.localizedMessage ?: "Tarama başarısız oldu.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                if (previousState is ScheduleUiState.Success) {
                    _uiState.value = previousState
                } else {
                    _uiState.value = ScheduleUiState.Error(it.localizedMessage ?: "Tarama başarısız oldu.")
                }
            }
        }
    }

    fun deleteSchedule(id: String) {
        viewModelScope.launch {
            try {
                scheduleDao.deleteById(id)
            } catch (e: Exception) {
                // Ignore or handle
            }
        }
    }

    fun deleteSchedulesByDay(day: Int) {
        viewModelScope.launch {
            try {
                scheduleDao.deleteByDay(day)
            } catch (e: Exception) {
                // Ignore or handle
            }
        }
    }

    fun clearAllIndividualSchedules() {
        viewModelScope.launch {
            try {
                scheduleDao.deleteNonCustomSchedules()
            } catch (e: Exception) {
                // Ignore or handle
            }
        }
    }
}

data class WeeklyScheduleData(
    val weeklyGoal: String?,
    val totalTargetQuestions: Int?,
    val days: List<ScheduleDayData>
)

data class ScheduleDayData(
    val day: String,
    val blocks: List<ScheduleBlockData>
)

data class ScheduleBlockData(
    val time: String,
    val subject: String,
    val topic: String?,
    val targetQuestions: Int?
)

fun parseScheduleFromMap(map: Map<String, Any>): WeeklyScheduleData {
    val goal = map["weeklyGoal"] as? String
    val totalQ = (map["totalTargetQuestions"] as? Number)?.toInt()
    
    val daysList = map["days"] as? List<Map<String, Any>> ?: emptyList()
    val parsedDays = daysList.map { dayMap ->
        val dayName = dayMap["day"] as? String ?: ""
        val blocksList = dayMap["blocks"] as? List<Map<String, Any>> ?: emptyList()
        val parsedBlocks = blocksList.map { blockMap ->
            ScheduleBlockData(
                time = blockMap["time"] as? String ?: "",
                subject = blockMap["subject"] as? String ?: "",
                topic = blockMap["topic"] as? String,
                targetQuestions = (blockMap["targetQuestions"] as? Number)?.toInt()
            )
        }
        ScheduleDayData(day = dayName, blocks = parsedBlocks)
    }
    
    return WeeklyScheduleData(weeklyGoal = goal, totalTargetQuestions = totalQ, days = parsedDays)
}
