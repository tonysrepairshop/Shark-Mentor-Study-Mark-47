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
 *   users/{uid}/institution_schedules
 * and reconciles all documents into the local Room table whenever Firestore
 * reports a change without wiping unsynced or custom schedules on cache/empty snapshots.
 */
class InstitutionScheduleSyncListener(
    private val dao: InstitutionScheduleDao
) {
    private val TAG = "InstitutionScheduleSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var registration: ListenerRegistration? = null
    @Volatile
    private var hasSeenServerSchedules = false

    fun start() {
        stop()
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
                .collection("institution_schedules")
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
                    if (snapshot == null) return@addSnapshotListener
                    if (snapshot.metadata.isFromCache && snapshot.documents.isEmpty()) {
                        return@addSnapshotListener
                    }

                    val deletedIds = mutableSetOf<String>()
                    val activeEntities = mutableListOf<InstitutionScheduleEntity>()
                    snapshot.documents.forEach { doc ->
                        try {
                            if (doc.getBoolean("isDeleted") == true) {
                                deletedIds.add(doc.id)
                                return@forEach
                            }
                            val subject = doc.getString("subject")?.trim().orEmpty()
                            if (subject.isBlank()) return@forEach

                            val startTime = doc.getString("startTime")?.trim().takeIf { !it.isNullOrBlank() } ?: "09:00"
                            val endTime = doc.getString("endTime")?.trim().takeIf { !it.isNullOrBlank() } ?: "10:00"
                            val type = doc.getString("type")?.trim().takeIf { !it.isNullOrBlank() } ?: "DERSHANE"

                            activeEntities.add(
                                InstitutionScheduleEntity(
                                    id = doc.id,
                                    dayOfWeek = (doc.getLong("dayOfWeek") ?: 1L).toInt().coerceIn(1, 7),
                                    startTime = startTime,
                                    endTime = endTime,
                                    subject = subject,
                                    type = type,
                                    teacherName = doc.getString("teacherName") ?: "",
                                    period = (doc.getLong("period") ?: 0L).toInt(),
                                    isSynced = true,
                                    lastModifiedMillis = doc.getLong("lastModifiedMillis") ?: System.currentTimeMillis(),
                                    isDeleted = false,
                                    cloudId = doc.id
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse document ${doc.id}: ${e.message}")
                        }
                    }

                    val isServerSnapshot = !snapshot.metadata.isFromCache
                    if (isServerSnapshot && activeEntities.isNotEmpty()) {
                        hasSeenServerSchedules = true
                    }

                    scope.launch {
                        try {
                            deletedIds.forEach { dao.deleteById(it) }

                            val localAll = dao.getAllSchedules()
                            val unsyncedIds = localAll.filter { !it.isSynced }.map { it.id }.toSet()
                            val unsyncedCloudIds = localAll.filter { !it.isSynced && !it.cloudId.isNullOrBlank() }
                                .mapNotNull { it.cloudId }
                                .toSet()

                            val activeIds = activeEntities.map { it.id }.toSet()
                            val toUpsert = activeEntities.filter { entity ->
                                entity.id !in unsyncedIds && entity.id !in unsyncedCloudIds
                            }

                            if (toUpsert.isNotEmpty()) {
                                // Remove stale synced duplicates mapped via different local id -> same cloudId
                                localAll.filter { local ->
                                    local.isSynced &&
                                        !local.id.startsWith("CUSTOM_SCHED_") &&
                                        local.cloudId != null &&
                                        local.cloudId in activeIds &&
                                        local.id != local.cloudId
                                }.forEach { dao.deleteById(it.id) }

                                dao.insertAll(toUpsert)
                            }

                            if (isServerSnapshot && (activeEntities.isNotEmpty() || hasSeenServerSchedules)) {
                                localAll.filter { local ->
                                    local.isSynced &&
                                        !local.id.startsWith("CUSTOM_SCHED_") &&
                                        local.id !in activeIds &&
                                        (local.cloudId == null || local.cloudId !in activeIds)
                                }.forEach { dao.deleteById(it.id) }
                            }
                            Log.d(TAG, "Reconciled ${toUpsert.size} institution schedule rows into Room.")
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
