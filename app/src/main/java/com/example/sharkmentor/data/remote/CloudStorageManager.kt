package com.example.sharkmentor.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.File
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
 *  - league_questions/{channelId}/{uid}_{questionId}.jpg
 *  - league_solutions/{channelId}/{questionMessageId}/{uid}_{solutionId}.jpg
 */
@Singleton
class CloudStorageManager @Inject constructor() {

    companion object {
        private const val TAG = "CloudStorageManager"
        private const val BUCKET = "gs://sharkmentor-v5.firebasestorage.app"
        const val LEAGUE_IMAGE_MAX_DIM_PX = 1280
        const val LEAGUE_IMAGE_JPEG_QUALITY = 75
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

    // ── Shark League Peer Learning Question & Solution Images ────────────────

    /**
     * Scales [bitmap] so its longest side is at most [maxDimensionPx] (default 1280px)
     * and compresses it as JPEG at [jpegQuality] (default 75%).
     */
    fun compressAndScaleToJpegBytes(
        bitmap: Bitmap,
        maxDimensionPx: Int = LEAGUE_IMAGE_MAX_DIM_PX,
        jpegQuality: Int = LEAGUE_IMAGE_JPEG_QUALITY
    ): ByteArray {
        val maxDim = maxOf(bitmap.width, bitmap.height)
        var scaled: Bitmap = bitmap
        var createdCopy = false

        if (maxDim > maxDimensionPx && maxDimensionPx > 0) {
            val ratio = maxDimensionPx.toFloat() / maxDim.toFloat()
            val targetW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
            val targetH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
            val candidate = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            if (candidate !== bitmap) {
                scaled = candidate
                createdCopy = true
            }
        }

        return try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(40, 95), out)
            out.toByteArray()
        } finally {
            if (createdCopy && !scaled.isRecycled) {
                runCatching { scaled.recycle() }
            }
        }
    }

    /**
     * Reads an image from a local `content://` or `file://` [uri] (or raw file path),
     * decodes with downsampling if needed, scales to max [maxDimensionPx] (1280px),
     * and compresses to [jpegQuality] (75%) JPEG bytes.
     */
    fun decodeAndCompressUriToJpegBytes(
        context: Context,
        uri: Uri,
        maxDimensionPx: Int = LEAGUE_IMAGE_MAX_DIM_PX,
        jpegQuality: Int = LEAGUE_IMAGE_JPEG_QUALITY
    ): ByteArray? {
        val rawBytes = try {
            when (uri.scheme?.lowercase()) {
                "content", "android.resource" ->
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                "file" ->
                    uri.path?.let { File(it).takeIf(File::exists)?.readBytes() }
                else -> {
                    val directFile = File(uri.toString())
                    if (directFile.exists()) {
                        directFile.readBytes()
                    } else {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read local image URI $uri: ${e.message}", e)
            null
        } ?: return null

        if (rawBytes.isEmpty()) return null

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
        var sampleSize = 1
        val longestSide = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
        if (longestSide > maxDimensionPx * 2) {
            while ((longestSide / sampleSize) > maxDimensionPx * 2) {
                sampleSize *= 2
            }
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
            ?: return null

        return try {
            compressAndScaleToJpegBytes(
                bitmap = decoded,
                maxDimensionPx = maxDimensionPx,
                jpegQuality = jpegQuality
            )
        } finally {
            if (!decoded.isRecycled) {
                runCatching { decoded.recycle() }
            }
        }
    }

    /**
     * Uploads a question image to `league_questions/{channelId}/{uid}_{questionId}.jpg`
     * after scaling to max 1280px and compressing at 75% JPEG quality.
     */
    suspend fun uploadLeagueQuestionImage(
        context: Context,
        localUri: Uri,
        channelId: String = "general",
        questionId: String = System.currentTimeMillis().toString()
    ): String? {
        val jpegBytes = decodeAndCompressUriToJpegBytes(
            context = context,
            uri = localUri,
            maxDimensionPx = LEAGUE_IMAGE_MAX_DIM_PX,
            jpegQuality = LEAGUE_IMAGE_JPEG_QUALITY
        ) ?: return null

        return uploadLeagueQuestionBytes(
            jpegBytes = jpegBytes,
            channelId = channelId,
            questionId = questionId
        )
    }

    /**
     * Uploads a question [Bitmap] to `league_questions/{channelId}/{uid}_{questionId}.jpg`
     * after scaling to max 1280px and compressing at 75% JPEG quality.
     */
    suspend fun uploadLeagueQuestionBitmap(
        bitmap: Bitmap,
        channelId: String = "general",
        questionId: String = System.currentTimeMillis().toString()
    ): String? {
        if (bitmap.isRecycled) return null
        val jpegBytes = compressAndScaleToJpegBytes(
            bitmap = bitmap,
            maxDimensionPx = LEAGUE_IMAGE_MAX_DIM_PX,
            jpegQuality = LEAGUE_IMAGE_JPEG_QUALITY
        )
        return uploadLeagueQuestionBytes(
            jpegBytes = jpegBytes,
            channelId = channelId,
            questionId = questionId
        )
    }

    suspend fun uploadLeagueQuestionBytes(
        jpegBytes: ByteArray,
        channelId: String = "general",
        questionId: String = System.currentTimeMillis().toString()
    ): String? {
        val uid = currentUid ?: "anonymous"
        val st = storage ?: return null
        val safeChannel = channelId.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "general" }
        val safeQuestionId = questionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return try {
            val ref = st.reference.child("league_questions/$safeChannel/${uid}_$safeQuestionId.jpg")
            val metadata = StorageMetadata.Builder()
                .setContentType("image/jpeg")
                .build()
            ref.putBytes(jpegBytes, metadata).await()
            val url = ref.downloadUrl.await().toString()
            Log.i(TAG, "League question image uploaded (${jpegBytes.size} bytes): $url")
            url
        } catch (e: Throwable) {
            Log.e(TAG, "League question image upload failed: ${e.message}", e)
            null
        }
    }

    /**
     * Uploads an annotated peer-solution image to
     * `league_solutions/{channelId}/{questionMessageId}/{uid}_{solutionId}.jpg`
     * after scaling to max 1280px and compressing at 75% JPEG quality.
     */
    suspend fun uploadLeagueSolutionImage(
        context: Context,
        localUri: Uri,
        channelId: String = "general",
        questionMessageId: String = "question",
        solutionId: String = System.currentTimeMillis().toString()
    ): String? {
        val jpegBytes = decodeAndCompressUriToJpegBytes(
            context = context,
            uri = localUri,
            maxDimensionPx = LEAGUE_IMAGE_MAX_DIM_PX,
            jpegQuality = LEAGUE_IMAGE_JPEG_QUALITY
        ) ?: return null

        return uploadLeagueSolutionBytes(
            jpegBytes = jpegBytes,
            channelId = channelId,
            questionMessageId = questionMessageId,
            solutionId = solutionId
        )
    }

    /**
     * Uploads an annotated peer-solution [Bitmap] to
     * `league_solutions/{channelId}/{questionMessageId}/{uid}_{solutionId}.jpg`
     * after scaling to max 1280px and compressing at 75% JPEG quality.
     */
    suspend fun uploadLeagueSolutionBitmap(
        bitmap: Bitmap,
        channelId: String = "general",
        questionMessageId: String = "question",
        solutionId: String = System.currentTimeMillis().toString()
    ): String? {
        if (bitmap.isRecycled) return null
        val jpegBytes = compressAndScaleToJpegBytes(
            bitmap = bitmap,
            maxDimensionPx = LEAGUE_IMAGE_MAX_DIM_PX,
            jpegQuality = LEAGUE_IMAGE_JPEG_QUALITY
        )
        return uploadLeagueSolutionBytes(
            jpegBytes = jpegBytes,
            channelId = channelId,
            questionMessageId = questionMessageId,
            solutionId = solutionId
        )
    }

    suspend fun uploadLeagueSolutionBytes(
        jpegBytes: ByteArray,
        channelId: String = "general",
        questionMessageId: String = "question",
        solutionId: String = System.currentTimeMillis().toString()
    ): String? {
        val uid = currentUid ?: "anonymous"
        val st = storage ?: return null
        val safeChannel = channelId.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "general" }
        val safeQuestionMsgId = questionMessageId.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "question" }
        val safeSolutionId = solutionId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return try {
            val ref = st.reference.child(
                "league_solutions/$safeChannel/$safeQuestionMsgId/${uid}_$safeSolutionId.jpg"
            )
            val metadata = StorageMetadata.Builder()
                .setContentType("image/jpeg")
                .build()
            ref.putBytes(jpegBytes, metadata).await()
            val url = ref.downloadUrl.await().toString()
            Log.i(TAG, "League solution image uploaded (${jpegBytes.size} bytes): $url")
            url
        } catch (e: Throwable) {
            Log.e(TAG, "League solution image upload failed: ${e.message}", e)
            null
        }
    }
}

