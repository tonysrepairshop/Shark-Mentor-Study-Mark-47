package com.example.sharkmentor.data.repository

import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.model.Meeting
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Date
import javax.inject.Inject

class MeetingRepository @Inject constructor(
    private val firestoreSupplier: () -> FirebaseFirestore? = { GoogleServicesHelper.getFirebaseFirestore() },
    private val authSupplier: () -> FirebaseAuth? = { GoogleServicesHelper.getFirebaseAuth() }
) {
    suspend fun createMeetingRequest(
        coachId: String,
        institutionId: String?,
        studentName: String,
        appointmentDate: Date,
        requestNote: String?
    ): Result<Unit> {
        return try {
            val auth = authSupplier() ?: throw Exception("Firebase Auth unavailable")
            val firestore = firestoreSupplier() ?: throw Exception("Firestore unavailable")
            val uid = auth.currentUser?.uid ?: throw Exception("User not logged in")
            val docRef = firestore.collection("meetings").document()
            val meeting = Meeting(
                id = docRef.id,
                studentId = uid,
                studentName = studentName,
                coachId = coachId,
                institutionId = institutionId,
                appointmentTimestamp = Timestamp(appointmentDate),
                requestNote = requestNote,
                status = "AUTO_CONFIRMED"
            )
            docRef.set(meeting).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
