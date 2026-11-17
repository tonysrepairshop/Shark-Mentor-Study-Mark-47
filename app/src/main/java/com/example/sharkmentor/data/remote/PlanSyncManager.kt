package com.example.sharkmentor.data.remote

import android.util.Log
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.dao.PlanDao
import com.example.sharkmentor.data.local.entity.PlanEntity
import com.example.sharkmentor.data.local.entity.PlanFolderEntity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlanSyncManager @Inject constructor(
    private val firestore: FirebaseFirestore?,
    private val auth: FirebaseAuth?,
    private val planDao: PlanDao
) {
    private val currentUid: String?
        get() = GoogleServicesHelper.getCurrentUser(auth)?.uid

    suspend fun syncFolderToCloud(folder: PlanFolderEntity) {
        val uid = currentUid ?: return
        val db = firestore ?: return
        try {
            db.collection("users").document(uid)
                .collection("plan_folders").document(folder.id)
                .set(folder).await()
        } catch (e: Throwable) {
            Log.w("PlanSyncManager", "syncFolderToCloud failed: ${e.message}")
        }
    }

    suspend fun syncTasksToCloud(tasks: List<PlanEntity>) {
        val uid = currentUid ?: return
        val db = firestore ?: return
        try {
            val batch = db.batch()
            tasks.forEach { task ->
                val ref = db.collection("users").document(uid)
                    .collection("plans").document(task.id)
                batch.set(ref, task)
            }
            batch.commit().await()
        } catch (e: Throwable) {
            Log.w("PlanSyncManager", "syncTasksToCloud failed: ${e.message}")
        }
    }

    suspend fun deleteFolderFromCloud(folderId: String) {
        val uid = currentUid ?: return
        val db = firestore ?: return
        try {
            try {
                db.collection("users").document(uid)
                    .collection("plan_folders").document(folderId).delete().await()
            } catch (_: Throwable) {}
            try {
                db.collection("plan_folders").document(folderId).delete().await()
            } catch (_: Throwable) {}

            // Delete associated tasks
            val tasksSnapshot = try {
                db.collection("users").document(uid)
                    .collection("plans").whereEqualTo("folderId", folderId).get().await()
            } catch (e: Throwable) { null }
            val batch = db.batch()
            tasksSnapshot?.documents?.forEach { doc -> batch.delete(doc.reference) }

            val rootTasksSnapshot = try {
                db.collection("plans").whereEqualTo("folderId", folderId).get().await()
            } catch (e: Throwable) { null }
            rootTasksSnapshot?.documents?.forEach { doc -> batch.delete(doc.reference) }

            try {
                batch.commit().await()
            } catch (_: Throwable) {}
        } catch (e: Throwable) {
            Log.w("PlanSyncManager", "deleteFolderFromCloud failed: ${e.message}")
        }
    }

    suspend fun deleteTaskFromCloud(taskId: String) {
        val uid = currentUid ?: return
        val db = firestore ?: return
        try {
            try {
                db.collection("users").document(uid)
                    .collection("plans").document(taskId).delete().await()
            } catch (_: Throwable) {}
            try {
                db.collection("plans").document(taskId).delete().await()
            } catch (_: Throwable) {}
        } catch (e: Throwable) {
            Log.w("PlanSyncManager", "deleteTaskFromCloud failed: ${e.message}")
        }
    }
}
