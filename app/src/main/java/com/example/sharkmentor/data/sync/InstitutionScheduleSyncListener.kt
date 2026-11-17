package com.example.sharkmentor.data.sync

import android.util.Log
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.dao.InstitutionScheduleDao
import com.example.sharkmentor.data.local.entity.InstitutionScheduleEntity
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Attaches a Firestore real-time listener to
 *   users/{uid}/institution_schedule
 * and upserts all documents into the local Room table whenever Firestore
 * reports a change (including on first attach).
 *
 * Usage:
 *   val listener = InstitutionScheduleSyncListener(dao)
 *   listener.start()   // attach – call once, e.g. in ViewModel.init{}
 *   listener.stop()    // detach – call in ViewModel.onCleared()
 */
class InstitutionScheduleSyncListener(
    private val dao: InstitutionScheduleDao
) {
    private val TAG = "InstitutionScheduleSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registration: ListenerRegistration? = null

    fun start() {
        if (com.google.firebase.auth.FirebaseAuth.getInstance().currentUser == null) {
            Log.w(TAG, "FirebaseAuth currentUser is null – skipping institution schedule sync (offline mode).")
            return
        }
        val uid = GoogleServicesHelper.getCurrentUser()?.uid
        if (uid.isNullOrBlank()) {
            Log.w(TAG, "User not logged in – skipping institution schedule sync.")
            return
        }

        val firestore = GoogleServicesHelper.getFirebaseFirestore()
        if (firestore == null) {
            Log.w(TAG, "Firestore unavailable – skipping institution schedule sync.")
            return
        }

        registration = runCatching {
            firestore
                .collection("users")
                .document(uid)
                .collection("institution_schedule")
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            registration?.remove()
                            registration = null
                            return@addSnapshotListener
                        }
                        Log.w(TAG, "Firestore listener error: ${error.message}")
                        return@addSnapshotListener
                    }
                    if (snapshot == null || snapshot.isEmpty) return@addSnapshotListener

                    val entities = snapshot.documents.mapNotNull { doc ->
                        try {
                            InstitutionScheduleEntity(
                                id         = doc.id,
                                dayOfWeek  = (doc.getLong("dayOfWeek") ?: 1L).toInt().coerceIn(1, 7),
                                startTime  = doc.getString("startTime") ?: "09:00",
                                endTime    = doc.getString("endTime")   ?: "10:00",
                                subject    = doc.getString("subject")   ?: "Ders",
                                type       = doc.getString("type")      ?: "DERSHANE",
                                teacherName = doc.getString("teacherName") ?: "",
                                period      = (doc.getLong("period") ?: 0L).toInt()
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse document ${doc.id}: ${e.message}")
                            null
                        }
                    }

                    scope.launch {
                        try {
                            dao.deleteAll()
                            dao.insertAll(entities)
                            Log.d(TAG, "Synced ${entities.size} institution schedule rows into Room.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Room upsert failed: ${e.message}", e)
                        }
                    }
                }
        }.onFailure { e ->
            Log.w(TAG, "Failed to attach institution schedule listener: ${e.message}")
        }.getOrNull()
    }

    fun stop() {
        registration?.remove()
        registration = null
    }
}
