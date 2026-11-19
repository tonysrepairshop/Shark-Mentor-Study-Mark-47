package com.example.sharkmentor.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class InstitutionUiState(
    val isLoading: Boolean = true,
    val hasCoach: Boolean = false,
    val coachName: String = "",
    val coachBranch: String = "",
    val coachPhone: String = "",
    val coachOffDay: String? = null,
    val institutionName: String = "",
    val instStartHour: Int = 9,
    val instEndHour: Int = 18,
    val activeCoachId: String = "",
    val studentName: String = ""
)

class InstitutionViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(InstitutionUiState())
    val uiState: StateFlow<InstitutionUiState> = _uiState.asStateFlow()

    private var studentListener: ListenerRegistration? = null
    private var coachListener: ListenerRegistration? = null
    private var instListener: ListenerRegistration? = null
    private var observedCoachId: String? = null
    private var observedInstId: String? = null
    @Volatile
    private var latestStudentName: String = ""

    init {
        loadData()
    }

    private fun clearCoachAndInstListeners() {
        coachListener?.remove()
        coachListener = null
        observedCoachId = null
        clearInstListener()
    }

    private fun clearInstListener() {
        instListener?.remove()
        instListener = null
        observedInstId = null
    }

    private fun attachInstitutionListener(
        db: FirebaseFirestore,
        instId: String,
        coachId: String,
        coachName: String,
        coachOffDay: String?,
        coachBranch: String,
        coachPhone: String,
        fallbackInstName: String
    ) {
        if (observedInstId == instId && instListener != null) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                hasCoach = true,
                coachName = coachName,
                coachOffDay = coachOffDay,
                coachBranch = coachBranch,
                coachPhone = coachPhone,
                institutionName = _uiState.value.institutionName.ifBlank { fallbackInstName },
                activeCoachId = coachId,
                studentName = latestStudentName
            )
            return
        }
        clearInstListener()
        observedInstId = instId
        instListener = db.collection("institutions").document(instId)
            .addSnapshotListener { instDoc, instErr ->
                val cur = _uiState.value
                if (instErr != null) {
                    _uiState.value = cur.copy(
                        isLoading = false,
                        hasCoach = true,
                        coachName = cur.coachName.ifBlank { coachName },
                        coachOffDay = cur.coachOffDay ?: coachOffDay,
                        coachBranch = cur.coachBranch.ifBlank { coachBranch },
                        coachPhone = cur.coachPhone.ifBlank { coachPhone },
                        institutionName = cur.institutionName.ifBlank { fallbackInstName },
                        activeCoachId = coachId,
                        studentName = latestStudentName
                    )
                    return@addSnapshotListener
                }
                if (instDoc != null && instDoc.exists()) {
                    val iStart = instDoc.getLong("workStartHour")?.toInt() ?: 9
                    val iEnd = instDoc.getLong("workEndHour")?.toInt() ?: 18
                    val overrideName = instDoc.getString("name")?.takeIf { it.isNotBlank() }
                    _uiState.value = cur.copy(
                        isLoading = false,
                        hasCoach = true,
                        coachName = cur.coachName.ifBlank { coachName },
                        coachOffDay = cur.coachOffDay ?: coachOffDay,
                        coachBranch = cur.coachBranch.ifBlank { coachBranch },
                        coachPhone = cur.coachPhone.ifBlank { coachPhone },
                        institutionName = overrideName ?: fallbackInstName,
                        instStartHour = iStart,
                        instEndHour = iEnd,
                        activeCoachId = coachId,
                        studentName = latestStudentName
                    )
                } else {
                    _uiState.value = cur.copy(
                        isLoading = false,
                        hasCoach = true,
                        coachName = cur.coachName.ifBlank { coachName },
                        coachOffDay = cur.coachOffDay ?: coachOffDay,
                        coachBranch = cur.coachBranch.ifBlank { coachBranch },
                        coachPhone = cur.coachPhone.ifBlank { coachPhone },
                        institutionName = fallbackInstName,
                        activeCoachId = coachId,
                        studentName = latestStudentName
                    )
                }
            }
    }

    private fun attachCoachListener(db: FirebaseFirestore, coachId: String) {
        if (observedCoachId == coachId && coachListener != null) {
            _uiState.value = _uiState.value.copy(studentName = latestStudentName)
            return
        }
        clearCoachAndInstListeners()
        observedCoachId = coachId
        coachListener = db.collection("coaches").document(coachId)
            .addSnapshotListener { coachDoc, coachErr ->
                if (coachErr != null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        hasCoach = false,
                        studentName = latestStudentName
                    )
                    return@addSnapshotListener
                }
                if (coachDoc != null && coachDoc.exists()) {
                    val cName = coachDoc.getString("coachName") ?: "Bilinmeyen Koç"
                    val cOffDay = coachDoc.getString("offDay")
                    val cBranch = coachDoc.getString("coachBranch") ?: ""
                    val cPhone = coachDoc.getString("coachPhone") ?: ""
                    val iName = coachDoc.getString("institutionName") ?: "SharkMentor Campus"
                    val instId = coachDoc.getString("institutionId")

                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        hasCoach = true,
                        coachName = cName,
                        coachOffDay = cOffDay,
                        coachBranch = cBranch,
                        coachPhone = cPhone,
                        institutionName = iName,
                        activeCoachId = coachId,
                        studentName = latestStudentName
                    )

                    if (!instId.isNullOrBlank()) {
                        attachInstitutionListener(
                            db = db,
                            instId = instId,
                            coachId = coachId,
                            coachName = cName,
                            coachOffDay = cOffDay,
                            coachBranch = cBranch,
                            coachPhone = cPhone,
                            fallbackInstName = iName
                        )
                    } else {
                        clearInstListener()
                    }
                } else {
                    clearInstListener()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        hasCoach = false,
                        activeCoachId = "",
                        studentName = latestStudentName
                    )
                }
            }
    }

    private fun loadData() {
        val user = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser }.getOrNull()
            ?: GoogleServicesHelper.getCurrentUser()
        val uid = user?.uid
        if (user != null && uid != null) {
            val db = GoogleServicesHelper.getFirebaseFirestore()
            if (db == null) {
                _uiState.value = _uiState.value.copy(isLoading = false, hasCoach = false)
                return
            }
            studentListener?.remove()
            clearCoachAndInstListeners()
            runCatching {
                studentListener = db.collection("students").document(uid)
                    .addSnapshotListener { studentDoc, studentErr ->
                        if (studentErr != null) {
                            _uiState.value = _uiState.value.copy(isLoading = false, hasCoach = false)
                            return@addSnapshotListener
                        }
                        if (studentDoc != null && studentDoc.exists()) {
                            val sName = studentDoc.getString("name") ?: user.displayName ?: ""
                            latestStudentName = sName
                            val coachId = studentDoc.getString("coachId")
                            if (!coachId.isNullOrBlank()) {
                                attachCoachListener(db, coachId)
                            } else {
                                clearCoachAndInstListeners()
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    hasCoach = false,
                                    activeCoachId = "",
                                    studentName = sName
                                )
                            }
                        } else {
                            clearCoachAndInstListeners()
                            _uiState.value = _uiState.value.copy(isLoading = false, hasCoach = false, activeCoachId = "")
                        }
                    }
            }.onFailure {
                _uiState.value = _uiState.value.copy(isLoading = false, hasCoach = false)
            }
        } else {
            _uiState.value = _uiState.value.copy(isLoading = false, hasCoach = false)
        }
    }

    override fun onCleared() {
        studentListener?.remove()
        studentListener = null
        clearCoachAndInstListeners()
        super.onCleared()
    }
}
