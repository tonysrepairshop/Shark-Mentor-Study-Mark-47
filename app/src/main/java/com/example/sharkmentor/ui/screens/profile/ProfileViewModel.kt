package com.example.sharkmentor.ui.screens.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.sharkmentor.core.util.SessionManager
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.model.Meeting
import com.example.sharkmentor.data.repository.MeetingRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.sharkmentor.worker.MeetingReminderWorker

class ProfileViewModel @JvmOverloads constructor(
    application: Application,
    private val meetingRepository: MeetingRepository = MeetingRepository()
) : AndroidViewModel(application) {

    private val currentUid get() = runCatching {
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
    }.getOrNull() ?: GoogleServicesHelper.getCurrentUser(context = getApplication())?.uid ?: ""

    // Live list of student's meetings
    val studentMeetings: StateFlow<List<Meeting>> = callbackFlow {
        val currentUser = runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser }.getOrNull()
        val uid = currentUid
        if (currentUser == null || uid.isEmpty()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val db = GoogleServicesHelper.getFirebaseFirestore(getApplication())
        if (db == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val registration = runCatching {
            db.collection("meetings")
                .whereEqualTo("studentId", uid)
                .addSnapshotListener { snap, error ->
                    if (error != null) {
                        trySend(emptyList())
                        return@addSnapshotListener
                    }
                    val list = snap?.documents?.mapNotNull { doc ->
                        doc.toObject(Meeting::class.java)?.copy(id = doc.id)
                    }?.sortedByDescending { it.appointmentTimestamp.seconds } ?: emptyList()
                    trySend(list)
                }
        }.getOrNull()
        awaitClose { registration?.remove() }
    }.catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _bookingMessage = MutableSharedFlow<String>()
    val bookingMessage = _bookingMessage.asSharedFlow()

    fun updateUserMode(mode: String) {
        val ctx = getApplication<Application>()
        SessionManager.setUserMode(ctx, mode)
        val uid = currentUid
        if (uid.isNotEmpty()) {
            val db = GoogleServicesHelper.getFirebaseFirestore(ctx)
            db?.collection("students")?.document(uid)?.update("userMode", mode)
        }
    }

    fun unlinkCoach(onComplete: (Boolean, String?) -> Unit) {
        val ctx = getApplication<Application>()
        val uid = currentUid
        if (uid.isEmpty()) {
            onComplete(false, "Kullanıcı bulunamadı")
            return
        }
        SessionManager.setLinkedToInstitution(ctx, false)
        SessionManager.setUserMode(ctx, "study")
        
        val db = GoogleServicesHelper.getFirebaseFirestore(ctx)
        if (db != null) {
            db.collection("students").document(uid).update(
                mapOf(
                    "coachCode" to null,
                    "institutionCode" to null,
                    "coachId" to null,
                    "assignedCoachId" to null,
                    "assignedCoachName" to null,
                    "branchCode" to null,
                    "institutionName" to null,
                    "isInstitutional" to false,
                    "userMode" to "study"
                )
            ).addOnSuccessListener {
                onComplete(true, null)
            }.addOnFailureListener { e ->
                onComplete(false, e.localizedMessage)
            }
        } else {
            onComplete(false, "Veritabanına ulaşılamadı")
        }
    }

    fun bookMeeting(
        coachId: String,
        institutionId: String?,
        studentName: String,
        appointmentDate: Date,
        requestNote: String?
    ) {
        viewModelScope.launch {
            if (coachId.isBlank()) {
                _bookingMessage.emit("Randevu oluşturulamadı: Geçerli bir koç ID bulunamadı.")
                return@launch
            }
            val result = meetingRepository.createMeetingRequest(
                coachId = coachId,
                institutionId = institutionId,
                studentName = studentName,
                appointmentDate = appointmentDate,
                requestNote = requestNote
            )
            result.onSuccess {
                _bookingMessage.emit("Görüşme randevunuz otomatik olarak onaylandı ve koçunuza iletildi!")
                val dateStr = SimpleDateFormat("d MMMM yyyy, EEEE - HH:mm", Locale("tr")).format(appointmentDate)
                MeetingReminderWorker.scheduleReminder(
                    context = getApplication(),
                    appointmentTimestampMillis = appointmentDate.time,
                    studentName = studentName,
                    dateStr = dateStr
                )
            }.onFailure { err ->
                _bookingMessage.emit("Randevu oluşturulamadı: ${err.localizedMessage}")
            }
        }
    }
}
