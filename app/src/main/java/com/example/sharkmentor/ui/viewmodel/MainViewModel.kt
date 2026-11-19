package com.example.sharkmentor.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.SharkMentorApplication
import com.example.sharkmentor.data.local.entity.TargetEntity
import com.example.sharkmentor.data.local.entity.TargetType
import com.example.sharkmentor.data.repository.PlanRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.TimeUnit

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.example.sharkmentor.core.util.GoogleServicesHelper

@Immutable
data class MainUiState(
    val targetCountdownText: String = "Hesaplanıyor...",
    val isCampus: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var studentBrandingListener: com.google.firebase.firestore.ListenerRegistration? = null

    private val prefs = application.getSharedPreferences("sharkmentor_prefs", Context.MODE_PRIVATE)
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "target_date_millis" || key == "target_exam_title") {
            refreshCountdownText()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        refreshCountdownText()

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                delay(60_000L) // Update every minute
                refreshCountdownText()
            }
        }

        // Listen for institution/coach assignment for dynamic branding
        com.example.sharkmentor.core.notification.SharkNotificationEngine.init(application)
        val currentUser = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser }.getOrNull()
            ?: GoogleServicesHelper.getCurrentUser(GoogleServicesHelper.getFirebaseAuth(application), application)
        val uid = currentUser?.uid
        if (currentUser != null && uid != null) {
            com.example.sharkmentor.core.notification.SocialNotificationMonitor.start(application)
            runCatching {
                studentBrandingListener = GoogleServicesHelper.getFirebaseFirestore(application)
                    ?.collection("students")?.document(uid)
                    ?.addSnapshotListener { snapshot, error ->
                        if (error != null) {
                            // Task 6.4: Silently ignore PERMISSION_DENIED or offline errors
                            return@addSnapshotListener
                        }
                        if (snapshot == null || !snapshot.exists()) return@addSnapshotListener
                        val coachId = snapshot.getString("coachId")
                        val instId = snapshot.getString("institutionId")
                        val userMode = snapshot.getString("userMode")
                        val hasInstitution = !coachId.isNullOrBlank() || !instId.isNullOrBlank() || userMode == "campus"
                        _uiState.value = _uiState.value.copy(isCampus = hasInstitution)
                    }
            }
        }
    }

    private fun refreshCountdownText() {
        val rawTitle = prefs.getString("target_exam_title", "YKS")?.trim().orEmpty()
        val examTitle = if (rawTitle.isBlank()) "YKS" else rawTitle
        var targetMillis = prefs.getLong("target_date_millis", 0L)
        if (targetMillis <= 0L) {
            // Default to upcoming June 20 YKS exam date if user hasn't customized yet
            val cal = java.util.Calendar.getInstance()
            val currentYear = cal.get(java.util.Calendar.YEAR)
            cal.set(currentYear, java.util.Calendar.JUNE, 20, 10, 15, 0)
            if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.set(currentYear + 1, java.util.Calendar.JUNE, 20, 10, 15, 0)
            }
            targetMillis = cal.timeInMillis
        }

        val diffMillis = targetMillis - System.currentTimeMillis()
        val formatted = if (diffMillis <= 0) {
            "$examTitle: Süre Doldu"
        } else {
            val days = TimeUnit.MILLISECONDS.toDays(diffMillis)
            if (days > 0) {
                "$examTitle • ${days} Gün"
            } else {
                val hours = TimeUnit.MILLISECONDS.toHours(diffMillis).coerceAtLeast(1)
                "$examTitle • ${hours} Saat"
            }
        }
        _uiState.value = _uiState.value.copy(targetCountdownText = formatted)
    }

    override fun onCleared() {
        studentBrandingListener?.remove()
        studentBrandingListener = null
        super.onCleared()
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefsListener) }
    }

    fun saveDailyReflection(score: Int, note: String, monthlyNet: String? = null) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val db = com.example.sharkmentor.data.local.SharkMentorDatabase.getDatabase(getApplication())
            val dao = db.dailyReflectionDao()
            val cal = java.util.Calendar.getInstance()
            val dateStr = String.format("%04d-%02d-%02d", cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
            
            dao.insertReflection(
                com.example.sharkmentor.data.local.entity.DailyReflectionEntity(
                    dateString = dateStr,
                    productivityScore = score,
                    reflectionNote = note
                )
            )

            if (!monthlyNet.isNullOrBlank()) {
                val monthStr = String.format("%04d-%02d", cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1)
                db.monthlyExamNetDao().insertNet(
                    com.example.sharkmentor.data.local.entity.MonthlyExamNetEntity(
                        yearMonth = monthStr,
                        examType = "Genel",
                        netDetailsJson = monthlyNet,
                        notes = "Daily Reflection Auto-Save"
                    )
                )
            }
        }
    }
}
