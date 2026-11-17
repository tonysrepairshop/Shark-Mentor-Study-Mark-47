package com.example.sharkmentor.data.remote

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CloudStorageManager handles all Firebase Storage operations for SharkMentor.
 * Bucket: gs://sharkmentor-v5.firebasestorage.app
 *
 * Paths managed:
 *  - profile_photos/{uid}/avatar.jpg
 *  - schedules/{uid}/{timestamp}.jpg
 *  - resources/{uid}/{resourceId}/{filename}
 */
@Singleton
class CloudStorageManager @Inject constructor() {

    companion object {
        private const val TAG = "CloudStorageManager"
        private const val BUCKET = "gs://sharkmentor-v5.firebasestorage.app"
    }

    private val storage: FirebaseStorage?
        get() = GoogleServicesHelper.getFirebaseStorage(bucket = BUCKET)

    private val currentUid: String?
        get() = GoogleServicesHelper.getCurrentUser()?.uid

    // ── Profile Photo ────────────────────────────────────────────────────────

    suspend fun uploadProfilePhoto(context: Context, localUri: Uri): String? {
        val uid = currentUid ?: return null
        val st = storage ?: return null
        return try {
            val ref = st.reference.child("profile_photos/$uid/avatar.jpg")
            val stream = context.contentResolver.openInputStream(localUri) ?: return null
            stream.use { ref.putStream(it).await() }
            val url = ref.downloadUrl.await().toString()
            Log.i(TAG, "Profile photo uploaded: $url")
            url
        } catch (e: Throwable) {
            Log.e(TAG, "Profile photo upload failed: ${e.message}", e)
            null
        }
    }

    suspend fun deleteProfilePhoto(): Boolean {
        val uid = currentUid ?: return false
        val st = storage ?: return false
        return try {
            st.reference.child("profile_photos/$uid/avatar.jpg").delete().await()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Delete profile photo failed: ${e.message}", e)
            false
        }
    }

    // ── Schedule Scan Images ─────────────────────────────────────────────────

    suspend fun uploadScheduleImage(context: Context, localUri: Uri): String? {
        val uid = currentUid ?: return null
        val st = storage ?: return null
        return try {
            val timestamp = System.currentTimeMillis()
            val ref = st.reference.child("schedules/$uid/$timestamp.jpg")
            val stream = context.contentResolver.openInputStream(localUri) ?: return null
            stream.use { ref.putStream(it).await() }
            val url = ref.downloadUrl.await().toString()
            Log.i(TAG, "Schedule image uploaded: $url")
            url
        } catch (e: Throwable) {
            Log.e(TAG, "Schedule image upload failed: ${e.message}", e)
            null
        }
    }

    // ── Resource Attachments ─────────────────────────────────────────────────

    suspend fun uploadResourceAttachment(
        context: Context,
        localUri: Uri,
        resourceId: String,
        filename: String
    ): String? {
        val uid = currentUid ?: return null
        val st = storage ?: return null
        return try {
            val ref = st.reference.child("resources/$uid/$resourceId/$filename")
            val stream = context.contentResolver.openInputStream(localUri) ?: return null
            stream.use { ref.putStream(it).await() }
            val url = ref.downloadUrl.await().toString()
            Log.i(TAG, "Resource attachment uploaded: $url")
            url
        } catch (e: Throwable) {
            Log.e(TAG, "Resource attachment upload failed: ${e.message}", e)
            null
        }
    }

    suspend fun deleteResourceAttachments(resourceId: String) {
        val uid = currentUid ?: return
        val st = storage ?: return
        try {
            val folderRef = st.reference.child("resources/$uid/$resourceId")
            val items = folderRef.listAll().await()
            items.items.forEach { fileRef ->
                try { fileRef.delete().await() } catch (_: Throwable) {}
            }
            Log.i(TAG, "Attachments deleted for resource=$resourceId")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to delete attachments for resource=$resourceId: ${e.message}", e)
        }
    }
}
