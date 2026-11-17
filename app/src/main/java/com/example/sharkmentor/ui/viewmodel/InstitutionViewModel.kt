package com.example.sharkmentor.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.core.util.GoogleServicesHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    init {
        loadData()
    }

    private fun loadData() {
        val user = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser }.getOrNull()
            ?: GoogleServicesHelper.getCurrentUser()
        val uid = user?.uid
        if (user != null && uid != null) {
            val db = GoogleServicesHelper.getFirebaseFirestore()
            runCatching {
                db?.collection("students")?.document(uid)?.addSnapshotListener { studentDoc, studentErr ->
                    if (studentErr != null) {
                        _uiState.value = _uiState.value.copy(isLoading = false, hasCoach = false)
                        return@addSnapshotListener
                    }
                    if (studentDoc != null && studentDoc.exists()) {
                        val sName = studentDoc.getString("name") ?: user.displayName ?: ""
                        val coachId = studentDoc.getString("coachId")
                        if (!coachId.isNullOrBlank()) {
                            db.collection("coaches").document(coachId).addSnapshotListener { coachDoc, coachErr ->
                                if (coachErr != null) {
                                    _uiState.value = _uiState.value.copy(isLoading = false, hasCoach = false, studentName = sName)
                                    return@addSnapshotListener
                                }
                                if (coachDoc != null && coachDoc.exists()) {
                                    val cName = coachDoc.getString("coachName") ?: "Bilinmeyen Koç"
                                    val cOffDay = coachDoc.getString("offDay")
                                    val cBranch = coachDoc.getString("coachBranch") ?: ""
                                    val cPhone = coachDoc.getString("coachPhone") ?: ""
                                    var iName = coachDoc.getString("institutionName") ?: "SharkMentor Campus"
                                    val instId = coachDoc.getString("institutionId")

                                    if (!instId.isNullOrBlank()) {
                                        db.collection("institutions").document(instId).addSnapshotListener { instDoc, instErr ->
                                            if (instErr != null) {
                                                _uiState.value = _uiState.value.copy(
                                                    isLoading = false,
                                                    hasCoach = true,
                                                    coachName = cName,
                                                    coachOffDay = cOffDay,
                                                    coachBranch = cBranch,
                                                    coachPhone = cPhone,
                                                    institutionName = iName,
                                                    activeCoachId = coachId,
                                                    studentName = sName
                                                )
                                                return@addSnapshotListener
                                            }
                                            if (instDoc != null && instDoc.exists()) {
                                                val iStart = instDoc.getLong("workStartHour")?.toInt() ?: 9
                                                val iEnd = instDoc.getLong("workEndHour")?.toInt() ?: 18
                                                val overrideName = instDoc.getString("name")
                                                if (!overrideName.isNullOrBlank()) iName = overrideName

                                                _uiState.value = _uiState.value.copy(
                                                    isLoading = false,
                                                    hasCoach = true,
                                                    coachName = cName,
                                                    coachOffDay = cOffDay,
                                                    coachBranch = cBranch,
                                                    coachPhone = cPhone,
                                                    institutionName = iName,
                                                    instStartHour = iStart,
                                                    instEndHour = iEnd,
                                                    activeCoachId = coachId,
                                                    studentName = sName
                                                )
                                            } else {
                                                _uiState.value = _uiState.value.copy(
                                                    isLoading = false,
                                                    hasCoach = true,
                                                    coachName = cName,
                                                    coachOffDay = cOffDay,
                                                    coachBranch = cBranch,
                                                    coachPhone = cPhone,
                                                    institutionName = iName,
                                                    activeCoachId = coachId,
                                                    studentName = sName
                                                )
                                            }
                                        }
                                    } else {
                                        _uiState.value = _uiState.value.copy(
                                            isLoading = false,
                                            hasCoach = true,
                                            coachName = cName,
                                            coachOffDay = cOffDay,
                                            coachBranch = cBranch,
                                            coachPhone = cPhone,
                                            institutionName = iName,
                                            activeCoachId = coachId,
                                            studentName = sName
                                        )
                                    }
                                }
                            }
                        } else {
                            _uiState.value = _uiState.value.copy(isLoading = false, hasCoach = false, studentName = sName)
                        }
                    } else {
                        _uiState.value = _uiState.value.copy(isLoading = false, hasCoach = false)
                    }
                }
            }.onFailure {
                _uiState.value = _uiState.value.copy(isLoading = false, hasCoach = false)
            }
        } else {
            _uiState.value = _uiState.value.copy(isLoading = false, hasCoach = false)
        }
    }
}
