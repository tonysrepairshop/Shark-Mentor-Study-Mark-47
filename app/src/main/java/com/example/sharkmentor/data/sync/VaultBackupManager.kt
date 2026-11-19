package com.example.sharkmentor.data.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.sharkmentor.core.util.GoogleServicesHelper
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity
import com.example.sharkmentor.data.local.entity.NoteEntity
import com.example.sharkmentor.ui.screens.notes.drawing.DrawingSerializer
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class VaultBackupSummary(
    val notesCount: Int,
    val drawingsCount: Int,
    val questionsCount: Int,
    val pdfAnnotationsCount: Int = 0,
    val uploadedImagesCount: Int = 0,
    val reusedImagesCount: Int = 0,
    val compressedBytesCount: Int = 0,
    val contentHash: String = "",
    val skippedUnchanged: Boolean = false,
    val message: String = ""
)

/**
 * Quota-friendly compressed backup & restore manager (`SHK-VAULT-1:<base64>`)
 * for Notes, Note Drawings (all pages), Error Bank Questions + verified inline/Storage WebP images,
 * and quota-safe PDF vector annotations (metadata + strokes only — NEVER raw 50 MB PDF binaries!).
 */
object VaultBackupManager {

    private const val TAG = "VaultBackupManager"
    const val VAULT_PREFIX = "SHK-VAULT-1:"
    private const val FORMAT_VERSION = 2

    private const val PREFS_NAME = "shark_vault_backup_prefs"
    private const val KEY_LAST_HASH_PREFIX = "last_vault_sha256_"
    private const val KEY_LAST_BACKUP_TIME = "last_vault_backup_millis"
    private const val KEY_IMG_URL_PREFIX = "vault_img_url_"

    // 700 KB Firestore single-document threshold (Firestore max is 1 MB; 700 KB leaves generous safety margin)
    private const val MAX_SINGLE_DOC_BYTES = 700 * 1024

    // Image compression settings: max 960px dimension, adaptive WebP (~25-48 KB)
    private const val MAX_IMAGE_DIMENSION = 960
    private const val WEBP_QUALITY = 62
    private const val TARGET_INLINE_WEBP_BYTES = 48 * 1024
    private const val MAX_INLINE_WEBP_EMBED_BYTES = 72 * 1024
    private const val MAX_PDF_ANNOTATION_JSON_BYTES = 250 * 1024
    private const val ERROR_BANK_IMAGES_DIR = "error_bank_images"
    private const val NOTE_DRAWINGS_DIR = "note_drawings"
    private const val PDF_ANNOTATIONS_DIR = "pdf_annotations"

    private fun getPrefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastBackupTimestamp(context: Context): Long =
        getPrefs(context).getLong(KEY_LAST_BACKUP_TIME, 0L)

    // ── 1. Local Code Export (SHK-VAULT-1:<base64>) ──────────────────────────

    /**
     * Collects all Notes, Note Drawings, Error Bank records (with inline WebP images), and
     * PDF vector annotations into a single GZIP + Base64 package (`SHK-VAULT-1:<base64>`).
     */
    suspend fun exportLocalVaultCode(
        context: Context
    ): Result<Pair<String, VaultBackupSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val (rootJson, summary) = buildVaultJsonPackage(
                context = appContext,
                uidForImageUpload = null
            )
            val jsonString = rootJson.toString()
            val contentHash = sha256Hex(jsonString.toByteArray(Charsets.UTF_8))
            val vaultCode = encodeToVaultCode(jsonString)
            val finalSummary = summary.copy(
                compressedBytesCount = vaultCode.length,
                contentHash = contentHash,
                message = "Sıkıştırılmış yedek kodu hazır (${vaultCode.length / 1024} KB)."
            )
            vaultCode to finalSummary
        }
    }

    // ── 2. Local Code Import (SHK-VAULT-1:<base64>) ──────────────────────────

    /**
     * Restores Notes, Note Drawings, Error Bank Questions (+ images), and PDF annotations
     * from a `SHK-VAULT-1:<base64>` string.
     */
    suspend fun importFromVaultCode(
        context: Context,
        rawCode: String
    ): Result<VaultBackupSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val jsonString = decodeFromVaultCode(rawCode)
            val rootJson = JSONObject(jsonString)
            val auth = GoogleServicesHelper.getFirebaseAuth(appContext)
            val uid = GoogleServicesHelper.getCurrentUser(auth, appContext)?.uid
            restoreFromVaultJson(appContext, rootJson, uid)
        }
    }

    // ── 3. Quota-Safe Cloud Backup (Firestore + Verified Inline/Storage WebP) ─

    /**
     * Backs up all Notes, Note Drawings (all pages), Error Bank Questions (with white-composited
     * WebP images guaranteed recoverable even without Storage), and quota-safe PDF vector annotations
     * to `students/{uid}/vault_backups/latest`.
     */
    suspend fun backupToCloud(
        context: Context,
        force: Boolean = false
    ): Result<VaultBackupSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val auth = GoogleServicesHelper.getFirebaseAuth(appContext)
            val currentUser = GoogleServicesHelper.getCurrentUser(auth, appContext)
            val uid = currentUser?.uid?.takeIf { it.isNotBlank() && it != "student_default" }
                ?: throw IllegalStateException("Bulut yedekleme için oturum açmış bir hesap gereklidir. Çevrimdışı yedekleme için 'Yedek Kodunu Kopyala' seçeneğini kullanabilirsiniz.")

            val firestore = GoogleServicesHelper.getFirebaseFirestore(appContext)
                ?: throw IllegalStateException("Firestore bağlantısı kurulamadı.")

            val (rootJson, buildSummary) = buildVaultJsonPackage(
                context = appContext,
                uidForImageUpload = uid
            )

            // Compute deterministic hash over all canonical data sections
            val canonicalContent = JSONObject().apply {
                put("version", FORMAT_VERSION)
                put("notes", rootJson.optJSONArray("notes") ?: JSONArray())
                put("drawings", rootJson.optJSONObject("drawings") ?: JSONObject())
                put("notePageDrawings", rootJson.optJSONObject("notePageDrawings") ?: JSONObject())
                put("questions", rootJson.optJSONArray("questions") ?: JSONArray())
                put("pdfAnnotations", rootJson.optJSONObject("pdfAnnotations") ?: JSONObject())
            }.toString()
            val contentHash = sha256Hex(canonicalContent.toByteArray(Charsets.UTF_8))

            val prefs = getPrefs(appContext)
            val lastUploadedHash = prefs.getString("$KEY_LAST_HASH_PREFIX$uid", null)

            if (!force && lastUploadedHash != null && lastUploadedHash == contentHash) {
                return@runCatching buildSummary.copy(
                    contentHash = contentHash,
                    skippedUnchanged = true,
                    message = "Değişiklik tespit edilmedi (SHA-256 aynı). Firestore yazma kotası korundu."
                )
            }

            val vaultCode = encodeToVaultCode(rootJson.toString())
            val codeBytes = vaultCode.toByteArray(Charsets.UTF_8)
            val now = System.currentTimeMillis()
            val backupsCollection = firestore
                .collection("students")
                .document(uid)
                .collection("vault_backups")

            if (codeBytes.size <= MAX_SINGLE_DOC_BYTES) {
                val docData = mapOf(
                    "version" to FORMAT_VERSION,
                    "contentHash" to contentHash,
                    "updatedAtMillis" to now,
                    "notesCount" to buildSummary.notesCount,
                    "drawingsCount" to buildSummary.drawingsCount,
                    "questionsCount" to buildSummary.questionsCount,
                    "pdfAnnotationsCount" to buildSummary.pdfAnnotationsCount,
                    "compressedBytes" to codeBytes.size,
                    "chunkCount" to 0,
                    "payload" to vaultCode
                )
                backupsCollection.document("latest").set(docData, SetOptions.merge()).await()
            } else {
                // Split into <= 700 KB chunks (chunk_0, chunk_1, ...)
                val chunkChars = MAX_SINGLE_DOC_BYTES
                val chunks = vaultCode.chunked(chunkChars)
                for ((index, chunkStr) in chunks.withIndex()) {
                    val chunkDoc = mapOf(
                        "chunkIndex" to index,
                        "contentHash" to contentHash,
                        "updatedAtMillis" to now,
                        "data" to chunkStr
                    )
                    backupsCollection.document("chunk_$index").set(chunkDoc).await()
                }
                val latestMeta = mapOf(
                    "version" to FORMAT_VERSION,
                    "contentHash" to contentHash,
                    "updatedAtMillis" to now,
                    "notesCount" to buildSummary.notesCount,
                    "drawingsCount" to buildSummary.drawingsCount,
                    "questionsCount" to buildSummary.questionsCount,
                    "pdfAnnotationsCount" to buildSummary.pdfAnnotationsCount,
                    "compressedBytes" to codeBytes.size,
                    "chunkCount" to chunks.size,
                    "payload" to ""
                )
                backupsCollection.document("latest").set(latestMeta, SetOptions.merge()).await()
            }

            prefs.edit()
                .putString("$KEY_LAST_HASH_PREFIX$uid", contentHash)
                .putLong(KEY_LAST_BACKUP_TIME, now)
                .apply()

            val pdfPart = if (buildSummary.pdfAnnotationsCount > 0) {
                ", ${buildSummary.pdfAnnotationsCount} PDF çizimi"
            } else ""

            buildSummary.copy(
                compressedBytesCount = codeBytes.size,
                contentHash = contentHash,
                skippedUnchanged = false,
                message = "Bulut yedeği başarıyla kaydedildi (${codeBytes.size / 1024} KB • ${buildSummary.notesCount} not, ${buildSummary.questionsCount} soru$pdfPart)."
            )
        }
    }

    // ── 4. Cloud Restore (from students/{uid}/vault_backups/latest) ──────────

    suspend fun restoreFromCloud(
        context: Context
    ): Result<VaultBackupSummary> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val auth = GoogleServicesHelper.getFirebaseAuth(appContext)
            val currentUser = GoogleServicesHelper.getCurrentUser(auth, appContext)
            val uid = currentUser?.uid?.takeIf { it.isNotBlank() && it != "student_default" }
                ?: throw IllegalStateException("Buluttan geri yükleme için oturum açmış bir hesap gereklidir.")

            val firestore = GoogleServicesHelper.getFirebaseFirestore(appContext)
                ?: throw IllegalStateException("Firestore bağlantısı kurulamadı.")

            val backupsCollection = firestore
                .collection("students")
                .document(uid)
                .collection("vault_backups")

            val latestSnap = backupsCollection.document("latest").get().await()
            if (!latestSnap.exists()) {
                throw IllegalStateException("Bu hesap için kayıtlı bir bulut yedeği bulunamadı.")
            }

            val chunkCount = (latestSnap.getLong("chunkCount") ?: 0L).toInt()
            val fullVaultCode = if (chunkCount <= 0) {
                latestSnap.getString("payload").orEmpty()
            } else {
                val sb = StringBuilder()
                for (i in 0 until chunkCount) {
                    val chunkSnap = backupsCollection.document("chunk_$i").get().await()
                    val part = chunkSnap.getString("data").orEmpty()
                    sb.append(part)
                }
                sb.toString()
            }

            if (fullVaultCode.isBlank()) {
                throw IllegalStateException("Bulut yedek paketi boş veya okunamadı.")
            }

            val jsonString = decodeFromVaultCode(fullVaultCode)
            val rootJson = JSONObject(jsonString)
            val summary = restoreFromVaultJson(appContext, rootJson, uid)

            val remoteHash = latestSnap.getString("contentHash").orEmpty()
            if (remoteHash.isNotBlank()) {
                getPrefs(appContext).edit()
                    .putString("$KEY_LAST_HASH_PREFIX$uid", remoteHash)
                    .putLong(KEY_LAST_BACKUP_TIME, System.currentTimeMillis())
                    .apply()
            }

            summary.copy(
                compressedBytesCount = fullVaultCode.length,
                contentHash = remoteHash
            )
        }
    }

    // ── Internal Package Builder & Restorer ──────────────────────────────────

    private suspend fun buildVaultJsonPackage(
        context: Context,
        uidForImageUpload: String?
    ): Pair<JSONObject, VaultBackupSummary> {
        val db = SharkMentorDatabase.getDatabase(context)
        val noteDao = db.noteDao()
        val errorQuestionDao = db.errorQuestionDao()

        val allNotes = runCatching { noteDao.getAllNotesFlow().first() }.getOrDefault(emptyList())
        val allQuestions = runCatching { errorQuestionDao.getAllQuestionsDirect() }.getOrDefault(emptyList())

        val notesArray = JSONArray()
        val drawingsObject = JSONObject()
        val notePageDrawingsObject = JSONObject()
        var drawingsCount = 0

        val noteDrawingsDir = File(context.filesDir, NOTE_DRAWINGS_DIR)

        for (note in allNotes) {
            val noteObj = JSONObject().apply {
                put("id", note.id)
                put("title", note.title)
                put("content", note.content)
                put("colorHex", note.colorHex)
                put("isPinned", note.isPinned)
                put("createdAt", note.createdAt)
                put("updatedAt", note.updatedAt)
            }
            notesArray.put(noteObj)

            val strokes = DrawingSerializer.loadNoteDrawing(context, note.id)
            if (strokes.isNotEmpty()) {
                drawingsObject.put(note.id, DrawingSerializer.strokesToJson(strokes))
                drawingsCount++
            }

            // Also capture additional pages (note_<safeId>_p<N>.json) for multi-page notes
            val safeId = note.id.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "default" }
            val pagePrefix = "note_${safeId}_p"
            noteDrawingsDir.listFiles()?.forEach { pageFile ->
                if (pageFile.name.startsWith(pagePrefix) && pageFile.name.endsWith(".json")) {
                    val pageNum = pageFile.name
                        .removePrefix(pagePrefix)
                        .removeSuffix(".json")
                        .toIntOrNull()
                    if (pageNum != null && pageNum > 0) {
                        val pageStrokes = DrawingSerializer.loadNotePageDrawing(context, note.id, pageNum)
                        if (pageStrokes.isNotEmpty()) {
                            notePageDrawingsObject.put("${note.id}::p$pageNum", DrawingSerializer.strokesToJson(pageStrokes))
                            drawingsCount++
                        }
                    }
                }
            }
        }

        // Quota-safe PDF vector annotations backup (strokes + lastReadPage JSON only — NEVER raw 50MB PDF files!)
        val pdfAnnotationsObject = JSONObject()
        var pdfAnnotationsCount = 0
        val pdfAnnDir = File(context.filesDir, PDF_ANNOTATIONS_DIR)
        if (pdfAnnDir.exists() && pdfAnnDir.isDirectory) {
            pdfAnnDir.listFiles()?.forEach { pdfJsonFile ->
                if (pdfJsonFile.isFile &&
                    pdfJsonFile.name.startsWith("pdf_") &&
                    pdfJsonFile.name.endsWith(".json") &&
                    pdfJsonFile.length() in 10L..MAX_PDF_ANNOTATION_JSON_BYTES.toLong()
                ) {
                    runCatching {
                        val raw = pdfJsonFile.readText(Charsets.UTF_8)
                        val parsed = JSONObject(raw)
                        val pagesObj = parsed.optJSONObject("pages")
                        val lastReadPage = parsed.optInt("lastReadPage", 0)
                        if ((pagesObj != null && pagesObj.length() > 0) || lastReadPage > 0) {
                            pdfAnnotationsObject.put(pdfJsonFile.name, raw)
                            pdfAnnotationsCount++
                        }
                    }
                }
            }
        }

        var uploadedImages = 0
        var reusedImages = 0
        val questionsArray = JSONArray()

        for (q in allQuestions) {
            var qVaultUrl: String? = null
            var qHash: String? = null
            var qInlineWebp: String? = null
            var sVaultUrl: String? = null
            var sHash: String? = null
            var sInlineWebp: String? = null

            if (q.questionImageUri.isNotBlank()) {
                val imgRes = processQuestionImageForVault(context, q.questionImageUri, uidForImageUpload)
                if (imgRes != null) {
                    qHash = imgRes.sha256Hash
                    qVaultUrl = imgRes.downloadUrl
                    qInlineWebp = imgRes.inlineWebpBase64
                    if (imgRes.wasNewlyUploaded) uploadedImages++ else if (imgRes.downloadUrl != null || imgRes.inlineWebpBase64 != null) reusedImages++
                }
            }

            if (!q.solutionImageUri.isNullOrBlank()) {
                val solRes = processQuestionImageForVault(context, q.solutionImageUri, uidForImageUpload)
                if (solRes != null) {
                    sHash = solRes.sha256Hash
                    sVaultUrl = solRes.downloadUrl
                    sInlineWebp = solRes.inlineWebpBase64
                    if (solRes.wasNewlyUploaded) uploadedImages++ else if (solRes.downloadUrl != null || solRes.inlineWebpBase64 != null) reusedImages++
                }
            }

            val qObj = JSONObject().apply {
                put("id", q.id)
                put("subject", q.subject)
                put("topic", q.topic)
                put("aiTopic", q.aiTopic ?: JSONObject.NULL)
                put("questionImageUri", q.questionImageUri)
                put("solutionImageUri", q.solutionImageUri ?: JSONObject.NULL)
                put("questionImageHash", qHash ?: JSONObject.NULL)
                put("questionImageVaultUrl", qVaultUrl ?: JSONObject.NULL)
                put("questionImageInlineWebp", qInlineWebp ?: JSONObject.NULL)
                put("solutionImageHash", sHash ?: JSONObject.NULL)
                put("solutionImageVaultUrl", sVaultUrl ?: JSONObject.NULL)
                put("solutionImageInlineWebp", sInlineWebp ?: JSONObject.NULL)
                put("stability", q.stability)
                put("difficulty", q.difficulty)
                put("reps", q.reps)
                put("lapses", q.lapses)
                put("state", q.state)
                put("lastReviewMillis", q.lastReviewMillis ?: JSONObject.NULL)
                put("dueDateMillis", q.dueDateMillis)
                put("isTwinGenerated", q.isTwinGenerated)
                put("twinQuestionJson", q.twinQuestionJson ?: JSONObject.NULL)
                put("createdAtMillis", q.createdAtMillis)
                put("lastModifiedMillis", q.lastModifiedMillis)
                put("cloudId", q.cloudId ?: JSONObject.NULL)
                put("institutionId", q.institutionId ?: JSONObject.NULL)
                put("assignedCoachId", q.assignedCoachId ?: JSONObject.NULL)
            }
            questionsArray.put(qObj)
        }

        val root = JSONObject().apply {
            put("format", "SHK-VAULT")
            put("version", FORMAT_VERSION)
            put("exportedAtMillis", System.currentTimeMillis())
            put("notes", notesArray)
            put("drawings", drawingsObject)
            put("notePageDrawings", notePageDrawingsObject)
            put("questions", questionsArray)
            put("pdfAnnotations", pdfAnnotationsObject)
        }

        val summary = VaultBackupSummary(
            notesCount = allNotes.size,
            drawingsCount = drawingsCount,
            questionsCount = allQuestions.size,
            pdfAnnotationsCount = pdfAnnotationsCount,
            uploadedImagesCount = uploadedImages,
            reusedImagesCount = reusedImages
        )
        return root to summary
    }

    private suspend fun restoreFromVaultJson(
        context: Context,
        rootJson: JSONObject,
        currentUid: String?
    ): VaultBackupSummary {
        val db = SharkMentorDatabase.getDatabase(context)
        val noteDao = db.noteDao()
        val errorQuestionDao = db.errorQuestionDao()

        // 1. Restore Notes
        val notesArray = rootJson.optJSONArray("notes") ?: JSONArray()
        val notesToUpsert = ArrayList<NoteEntity>(notesArray.length())
        for (i in 0 until notesArray.length()) {
            val obj = notesArray.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            if (id.isEmpty()) continue
            val existing = noteDao.getNoteById(id)
            val incomingUpdatedAt = obj.optLong("updatedAt", System.currentTimeMillis())
            if (existing == null || incomingUpdatedAt >= existing.updatedAt) {
                notesToUpsert.add(
                    NoteEntity(
                        id = id,
                        title = obj.optString("title", ""),
                        content = obj.optString("content", ""),
                        colorHex = obj.optString("colorHex", "#F8FAFC"),
                        isPinned = obj.optBoolean("isPinned", false),
                        createdAt = obj.optLong("createdAt", incomingUpdatedAt),
                        updatedAt = incomingUpdatedAt,
                        isSynced = true
                    )
                )
            }
        }
        if (notesToUpsert.isNotEmpty()) {
            noteDao.upsertAllNotes(notesToUpsert)
        }

        // 2. Restore Note Drawings (page 0 + additional pages)
        val drawingsObj = rootJson.optJSONObject("drawings")
        var restoredDrawingsCount = 0
        if (drawingsObj != null) {
            val keys = drawingsObj.keys()
            while (keys.hasNext()) {
                val noteId = keys.next()
                val rawStrokeJson = drawingsObj.optString(noteId, "")
                if (rawStrokeJson.isNotBlank()) {
                    val strokes = DrawingSerializer.strokesFromJson(rawStrokeJson)
                    if (strokes.isNotEmpty()) {
                        DrawingSerializer.saveNoteDrawing(context, noteId, strokes)
                        restoredDrawingsCount++
                    }
                }
            }
        }

        val notePageDrawingsObj = rootJson.optJSONObject("notePageDrawings")
        if (notePageDrawingsObj != null) {
            val keys = notePageDrawingsObj.keys()
            while (keys.hasNext()) {
                val compositeKey = keys.next()
                val parts = compositeKey.split("::p")
                if (parts.size == 2) {
                    val noteId = parts[0]
                    val pageIdx = parts[1].toIntOrNull() ?: continue
                    val rawStrokeJson = notePageDrawingsObj.optString(compositeKey, "")
                    if (rawStrokeJson.isNotBlank()) {
                        val strokes = DrawingSerializer.strokesFromJson(rawStrokeJson)
                        if (strokes.isNotEmpty()) {
                            DrawingSerializer.saveNotePageDrawing(context, noteId, pageIdx, strokes)
                            restoredDrawingsCount++
                        }
                    }
                }
            }
        }

        // 2b. Restore Quota-Safe PDF Vector Annotations
        val pdfAnnotationsObj = rootJson.optJSONObject("pdfAnnotations")
        var restoredPdfAnnotationsCount = 0
        if (pdfAnnotationsObj != null && pdfAnnotationsObj.length() > 0) {
            val pdfAnnDir = File(context.filesDir, PDF_ANNOTATIONS_DIR).apply {
                if (!exists()) mkdirs()
            }
            val keys = pdfAnnotationsObj.keys()
            while (keys.hasNext()) {
                val fileName = keys.next()
                if (!fileName.startsWith("pdf_") || !fileName.endsWith(".json") || fileName.contains("/") || fileName.contains("..")) {
                    continue
                }
                val rawJson = pdfAnnotationsObj.optString(fileName, "")
                if (rawJson.isBlank()) continue
                runCatching {
                    val incomingObj = JSONObject(rawJson)
                    val incomingUpdatedAt = incomingObj.optLong("updatedAt", 0L)
                    val targetFile = File(pdfAnnDir, fileName)
                    val existingUpdatedAt = if (targetFile.exists()) {
                        runCatching { JSONObject(targetFile.readText(Charsets.UTF_8)).optLong("updatedAt", 0L) }
                            .getOrDefault(0L)
                    } else -1L
                    if (!targetFile.exists() || incomingUpdatedAt >= existingUpdatedAt) {
                        targetFile.writeText(rawJson, Charsets.UTF_8)
                        restoredPdfAnnotationsCount++
                    }
                }
            }
            if (restoredPdfAnnotationsCount > 0) {
                runCatching { com.example.sharkmentor.core.util.PdfLibraryManager.refresh(context) }
            }
        }

        // 3. Restore Error Bank Questions & Materialize Verified Local Image Files
        val questionsArray = rootJson.optJSONArray("questions") ?: JSONArray()
        val existingQuestions = errorQuestionDao.getAllQuestionsDirect()
        var restoredQuestionsCount = 0
        var restoredImagesCount = 0

        for (i in 0 until questionsArray.length()) {
            val qObj = questionsArray.optJSONObject(i) ?: continue
            val subject = qObj.optString("subject", "Matematik")
            val topic = qObj.optString("topic", "Genel")
            val aiTopic = qObj.optString("aiTopic", "").takeIf { it.isNotBlank() && it != "null" }
            val createdAt = qObj.optLong("createdAtMillis", System.currentTimeMillis())
            val cloudId = qObj.optString("cloudId", "").takeIf { it.isNotBlank() && it != "null" }

            val localQPath = qObj.optString("questionImageUri", "")
            val qHash = qObj.optString("questionImageHash", "").takeIf { it.isNotBlank() && it != "null" }
            val qVaultUrl = qObj.optString("questionImageVaultUrl", "").takeIf { it.isNotBlank() && it != "null" }
            val qInlineWebp = qObj.optString("questionImageInlineWebp", "").takeIf { it.isNotBlank() && it != "null" }

            val resolvedQuestionUri = resolveOrDownloadQuestionImage(
                context = context,
                existingPath = localQPath,
                imageHash = qHash,
                vaultDownloadUrl = qVaultUrl,
                inlineWebpBase64 = qInlineWebp,
                uid = currentUid,
                prefix = "question"
            ).also {
                if (it.isNotBlank()) restoredImagesCount++
            }

            val localSolPath = qObj.optString("solutionImageUri", "").takeIf { it.isNotBlank() && it != "null" }
            val sHash = qObj.optString("solutionImageHash", "").takeIf { it.isNotBlank() && it != "null" }
            val sVaultUrl = qObj.optString("solutionImageVaultUrl", "").takeIf { it.isNotBlank() && it != "null" }
            val sInlineWebp = qObj.optString("solutionImageInlineWebp", "").takeIf { it.isNotBlank() && it != "null" }

            val resolvedSolutionUri = if (localSolPath != null || sHash != null || sVaultUrl != null || sInlineWebp != null) {
                resolveOrDownloadQuestionImage(
                    context = context,
                    existingPath = localSolPath.orEmpty(),
                    imageHash = sHash,
                    vaultDownloadUrl = sVaultUrl,
                    inlineWebpBase64 = sInlineWebp,
                    uid = currentUid,
                    prefix = "solution"
                ).takeIf { it.isNotBlank() }
            } else null

            // Check if question already exists locally
            val matchedExisting = existingQuestions.firstOrNull { eq ->
                (!cloudId.isNullOrBlank() && eq.cloudId == cloudId) ||
                    (eq.createdAtMillis == createdAt && eq.subject == subject)
            }

            val existingValidQPath = matchedExisting?.questionImageUri
                ?.takeIf { isValidLocalImagePath(it) }
                .orEmpty()

            val entity = ErrorQuestionEntity(
                id = matchedExisting?.id ?: 0L,
                subject = subject,
                questionImageUri = resolvedQuestionUri.ifBlank { existingValidQPath },
                solutionImageUri = resolvedSolutionUri ?: matchedExisting?.solutionImageUri?.takeIf { isValidLocalImagePath(it) },
                topic = topic,
                stability = qObj.optDouble("stability", 0.4),
                difficulty = qObj.optDouble("difficulty", 5.0),
                reps = qObj.optInt("reps", 0),
                lapses = qObj.optInt("lapses", 0),
                state = qObj.optInt("state", 0),
                lastReviewMillis = if (qObj.isNull("lastReviewMillis")) null else qObj.optLong("lastReviewMillis"),
                dueDateMillis = qObj.optLong("dueDateMillis", System.currentTimeMillis()),
                isTwinGenerated = qObj.optBoolean("isTwinGenerated", false),
                twinQuestionJson = qObj.optString("twinQuestionJson", "").takeIf { it.isNotBlank() && it != "null" },
                createdAtMillis = createdAt,
                isSynced = true,
                lastModifiedMillis = qObj.optLong("lastModifiedMillis", System.currentTimeMillis()),
                cloudId = cloudId ?: matchedExisting?.cloudId,
                institutionId = qObj.optString("institutionId", "").takeIf { it.isNotBlank() && it != "null" }
                    ?: matchedExisting?.institutionId,
                assignedCoachId = qObj.optString("assignedCoachId", "").takeIf { it.isNotBlank() && it != "null" }
                    ?: matchedExisting?.assignedCoachId,
                aiTopic = aiTopic
            )

            errorQuestionDao.insertQuestion(entity)
            restoredQuestionsCount++
        }

        val pdfMsg = if (restoredPdfAnnotationsCount > 0) ", $restoredPdfAnnotationsCount PDF çizimi" else ""
        return VaultBackupSummary(
            notesCount = notesToUpsert.size,
            drawingsCount = restoredDrawingsCount,
            questionsCount = restoredQuestionsCount,
            pdfAnnotationsCount = restoredPdfAnnotationsCount,
            uploadedImagesCount = restoredImagesCount,
            message = "Geri yükleme tamamlandı: ${notesToUpsert.size} not, $restoredDrawingsCount çizim, $restoredQuestionsCount hata sorusu$pdfMsg."
        )
    }

    // ── Image WebP Compression (White-Composited, Adaptive <= 48 KB) & Recovery ──

    private data class ProcessedVaultImage(
        val sha256Hash: String,
        val downloadUrl: String?,
        val inlineWebpBase64: String?,
        val wasNewlyUploaded: Boolean
    )

    private suspend fun processQuestionImageForVault(
        context: Context,
        uriOrPath: String,
        uidForUpload: String?
    ): ProcessedVaultImage? {
        if (uriOrPath.isBlank()) return null
        if (uriOrPath.startsWith("https://")) {
            val urlHash = sha256Hex(uriOrPath.toByteArray(Charsets.UTF_8)).take(32)
            return ProcessedVaultImage(
                sha256Hash = urlHash,
                downloadUrl = uriOrPath,
                inlineWebpBase64 = null,
                wasNewlyUploaded = false
            )
        }

        val webpBytes = compressImageToVaultWebp(context, uriOrPath) ?: return null
        val hash = sha256Hex(webpBytes).take(32)
        val inlineB64 = Base64.encodeToString(webpBytes, Base64.NO_WRAP)

        // Local code export: always embed inline WebP so code is 100% self-contained
        if (uidForUpload.isNullOrBlank()) {
            return ProcessedVaultImage(
                sha256Hash = hash,
                downloadUrl = null,
                inlineWebpBase64 = inlineB64,
                wasNewlyUploaded = false
            )
        }

        val prefs = getPrefs(context)
        val cacheKey = "${KEY_IMG_URL_PREFIX}${uidForUpload}_$hash"
        val cachedUrl = prefs.getString(cacheKey, null)
        if (!cachedUrl.isNullOrBlank()) {
            return ProcessedVaultImage(
                sha256Hash = hash,
                downloadUrl = cachedUrl,
                // Keep inline fallback when compact so restore never fails even if Storage is unreachable
                inlineWebpBase64 = inlineB64.takeIf { webpBytes.size <= MAX_INLINE_WEBP_EMBED_BYTES },
                wasNewlyUploaded = false
            )
        }

        val storage = GoogleServicesHelper.getFirebaseStorage(context)
        if (storage == null) {
            return ProcessedVaultImage(
                sha256Hash = hash,
                downloadUrl = null,
                inlineWebpBase64 = inlineB64,
                wasNewlyUploaded = false
            )
        }

        val storageRef = storage.reference.child("images/$uidForUpload/vault_$hash.webp")

        val existingUrl = runCatching {
            storageRef.downloadUrl.await().toString()
        }.getOrNull()

        if (!existingUrl.isNullOrBlank()) {
            prefs.edit().putString(cacheKey, existingUrl).apply()
            return ProcessedVaultImage(
                sha256Hash = hash,
                downloadUrl = existingUrl,
                inlineWebpBase64 = inlineB64.takeIf { webpBytes.size <= MAX_INLINE_WEBP_EMBED_BYTES },
                wasNewlyUploaded = false
            )
        }

        // Try Firebase Storage upload; if Spark plan / rules / network blocks Storage, inline WebP still guarantees 100% restore!
        return runCatching {
            storageRef.putBytes(webpBytes).await()
            val uploadedUrl = storageRef.downloadUrl.await().toString()
            prefs.edit().putString(cacheKey, uploadedUrl).apply()
            ProcessedVaultImage(
                sha256Hash = hash,
                downloadUrl = uploadedUrl,
                inlineWebpBase64 = inlineB64.takeIf { webpBytes.size <= MAX_INLINE_WEBP_EMBED_BYTES },
                wasNewlyUploaded = true
            )
        }.onFailure { e ->
            Log.w(TAG, "Storage upload unavailable for hash=$hash (using guaranteed inline WebP fallback): ${e.message}")
        }.getOrDefault(
            ProcessedVaultImage(
                sha256Hash = hash,
                downloadUrl = null,
                inlineWebpBase64 = inlineB64,
                wasNewlyUploaded = false
            )
        )
    }

    /**
     * Composites onto opaque WHITE background (so transparent Lasso PNG crops NEVER turn black!),
     * downscales to max 960px, and encodes to compact adaptive WebP (~25-48 KB).
     */
    private fun compressImageToVaultWebp(context: Context, uriOrPath: String): ByteArray? {
        return runCatching {
            val rawBytes = readImageBytes(context, uriOrPath) ?: return@runCatching null
            if (rawBytes.isEmpty()) return@runCatching null

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOpts)
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return@runCatching null

            var sampleSize = 1
            while (
                boundsOpts.outWidth / sampleSize > MAX_IMAGE_DIMENSION * 2 ||
                boundsOpts.outHeight / sampleSize > MAX_IMAGE_DIMENSION * 2
            ) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: return@runCatching null

            val maxSide = maxOf(decoded.width, decoded.height)
            val scaled = if (maxSide > MAX_IMAGE_DIMENSION) {
                val ratio = MAX_IMAGE_DIMENSION.toFloat() / maxSide.toFloat()
                val w = (decoded.width * ratio).toInt().coerceAtLeast(1)
                val h = (decoded.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, w, h, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }

            // Composite onto solid opaque white so Lasso transparent regions NEVER turn pitch black
            val flattened = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(flattened)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawBitmap(scaled, 0f, 0f, null)
            scaled.recycle()

            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            val baos = ByteArrayOutputStream()
            var quality = WEBP_QUALITY
            flattened.compress(format, quality, baos)
            while (baos.size() > TARGET_INLINE_WEBP_BYTES && quality > 34) {
                baos.reset()
                quality -= 8
                flattened.compress(format, quality, baos)
            }
            flattened.recycle()
            baos.toByteArray()
        }.onFailure { e ->
            Log.w(TAG, "Failed compressing image for vault: $uriOrPath", e)
        }.getOrNull()
    }

    private fun readImageBytes(context: Context, uriOrPath: String): ByteArray? {
        return runCatching {
            val directFile = File(uriOrPath)
            if (directFile.exists() && directFile.isFile) {
                return@runCatching directFile.readBytes()
            }
            val uri = Uri.parse(uriOrPath)
            when (uri.scheme) {
                "file" -> uri.path?.let { File(it) }?.takeIf { it.exists() }?.readBytes()
                "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                else -> null
            }
        }.getOrNull()
    }

    private fun isValidImageBytes(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.isEmpty()) return false
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        }.getOrDefault(false)
    }

    private fun isValidLocalImagePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("https://") || path.startsWith("content://")) return true
        val file = if (path.startsWith("file://")) File(Uri.parse(path).path ?: return false) else File(path)
        if (!file.exists() || !file.isFile || file.length() <= 0L) return false
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        }.getOrDefault(false)
    }

    private suspend fun resolveOrDownloadQuestionImage(
        context: Context,
        existingPath: String,
        imageHash: String?,
        vaultDownloadUrl: String?,
        inlineWebpBase64: String?,
        uid: String?,
        prefix: String
    ): String {
        if (existingPath.isNotBlank() && isValidLocalImagePath(existingPath)) {
            return existingPath
        }

        val dir = File(context.filesDir, ERROR_BANK_IMAGES_DIR).apply {
            if (!exists()) mkdirs()
        }
        val safeHash = imageHash?.replace(Regex("[^a-zA-Z0-9_-]"), "")?.takeIf { it.isNotBlank() }
        if (safeHash != null) {
            val deterministicFile = File(dir, "${prefix}_vault_${safeHash}.webp")
            if (isValidLocalImagePath(deterministicFile.absolutePath)) {
                return deterministicFile.absolutePath
            }
        }

        // Priority 1: Guaranteed inline WebP payload inside vault (zero network dependency, instant!)
        var restoredBytes: ByteArray? = null
        if (!inlineWebpBase64.isNullOrBlank()) {
            val decoded = runCatching { Base64.decode(inlineWebpBase64, Base64.DEFAULT) }.getOrNull()
            if (isValidImageBytes(decoded)) {
                restoredBytes = decoded
            }
        }

        // Priority 2: Download via Firebase Storage reference if uid & imageHash are known
        if (restoredBytes == null && !uid.isNullOrBlank() && !safeHash.isNullOrBlank()) {
            val storage = GoogleServicesHelper.getFirebaseStorage(context)
            if (storage != null) {
                val downloaded = runCatching {
                    val ref = storage.reference.child("images/$uid/vault_$safeHash.webp")
                    ref.getBytes(5L * 1024L * 1024L).await()
                }.getOrNull()
                if (isValidImageBytes(downloaded)) {
                    restoredBytes = downloaded
                }
            }
        }

        // Priority 3: Fallback HTTP download from vaultDownloadUrl
        if (restoredBytes == null && !vaultDownloadUrl.isNullOrBlank() && vaultDownloadUrl.startsWith("https://")) {
            val downloaded = runCatching {
                val conn = (URL(vaultDownloadUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    doInput = true
                }
                conn.inputStream.use { it.readBytes() }
            }.getOrNull()
            if (isValidImageBytes(downloaded)) {
                restoredBytes = downloaded
            }
        }

        if (restoredBytes != null && restoredBytes.isNotEmpty()) {
            val fileHash = safeHash ?: sha256Hex(restoredBytes).take(16)
            val targetFile = File(dir, "${prefix}_vault_${fileHash}.webp")
            val tmpFile = File(dir, "${prefix}_vault_${fileHash}.webp.tmp")
            val written = runCatching {
                FileOutputStream(tmpFile).use { out ->
                    out.write(restoredBytes)
                    out.flush()
                }
                if (!tmpFile.renameTo(targetFile)) {
                    tmpFile.copyTo(targetFile, overwrite = true)
                    runCatching { tmpFile.delete() }
                }
                isValidLocalImagePath(targetFile.absolutePath)
            }.getOrDefault(false)
            if (written) {
                return targetFile.absolutePath
            }
        }

        // Never return a dead deleted local file path that causes black screens
        return ""
    }

    // ── GZIP + Base64 & SHA-256 Utilities ────────────────────────────────────

    fun encodeToVaultCode(jsonStr: String): String {
        val rawBytes = jsonStr.toByteArray(Charsets.UTF_8)
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { gzip ->
            gzip.write(rawBytes)
        }
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return "$VAULT_PREFIX$base64"
    }

    fun decodeFromVaultCode(vaultCode: String): String {
        val trimmed = vaultCode.trim()
        val base64Part = if (trimmed.startsWith(VAULT_PREFIX, ignoreCase = true)) {
            trimmed.substring(VAULT_PREFIX.length).trim()
        } else {
            throw IllegalArgumentException("Geçersiz yedek kodu formatı. Kod '$VAULT_PREFIX' ile başlamalıdır.")
        }

        val compressedBytes = Base64.decode(base64Part, Base64.DEFAULT)
        val bais = ByteArrayInputStream(compressedBytes)
        return GZIPInputStream(bais).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Compact, theme & Zen-mode compatible dialog for:
 * 1) Buluta Sıkıştırılmış Yedek Al / Buluttan Geri Yükle
 * 2) Sıkıştırılmış Yedek Kodunu (`SHK-VAULT-1:...`) Kopyala / Koddan İçe Aktar
 */
@Composable
fun VaultBackupDialog(
    onDismiss: () -> Unit,
    onDataRestored: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isStatusError by remember { mutableStateOf(false) }
    var generatedCodePreview by remember { mutableStateOf<String?>(null) }
    var importCodeInput by remember { mutableStateOf("") }
    var showImportField by remember { mutableStateOf(false) }
    var lastBackupTime by remember { mutableStateOf(VaultBackupManager.getLastBackupTimestamp(context)) }

    val formattedLastBackup = remember(lastBackupTime) {
        if (lastBackupTime <= 0L) {
            "Henüz bulut yedeği alınmadı"
        } else {
            val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("tr"))
            "Son Yedek: ${sdf.format(Date(lastBackupTime))}"
        }
    }

    Dialog(onDismissRequest = { if (!isBusy) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(1.dp, colorScheme.onSurface.copy(alpha = 0.12f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colorScheme.primary.copy(alpha = 0.14f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = null,
                                    tint = colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Yedekle / Geri Yükle",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = formattedLastBackup,
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }
                    }

                    IconButton(
                        onClick = { if (!isBusy) onDismiss() },
                        enabled = !isBusy
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Kapat",
                            tint = colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Notlarınız, el yazısı/PDF çizimleriniz ve Hata Bankası soru görselleriniz tek pakette GZIP + SHA-256 kota korumasıyla sıkıştırılır.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface.copy(alpha = 0.75f)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // ── Section 1: Cloud Backup / Restore ────────────────────────
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, colorScheme.onSurface.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "1. Bulut Yedekleme (Kota Dostu)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    isBusy = true
                                    statusMessage = null
                                    coroutineScope.launch {
                                        val res = VaultBackupManager.backupToCloud(context, force = false)
                                        isBusy = false
                                        res.onSuccess { summary ->
                                            isStatusError = false
                                            statusMessage = summary.message
                                            lastBackupTime = VaultBackupManager.getLastBackupTimestamp(context)
                                        }.onFailure { err ->
                                            isStatusError = true
                                            statusMessage = err.localizedMessage ?: "Bulut yedekleme başarısız."
                                        }
                                    }
                                },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Buluta Yedekle", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    isBusy = true
                                    statusMessage = null
                                    coroutineScope.launch {
                                        val res = VaultBackupManager.restoreFromCloud(context)
                                        isBusy = false
                                        res.onSuccess { summary ->
                                            isStatusError = false
                                            statusMessage = summary.message
                                            lastBackupTime = VaultBackupManager.getLastBackupTimestamp(context)
                                            onDataRestored()
                                        }.onFailure { err ->
                                            isStatusError = true
                                            statusMessage = err.localizedMessage ?: "Buluttan geri yükleme başarısız."
                                        }
                                    }
                                },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Geri Yükle", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Section 2: Compressed Vault Code (SHK-VAULT-1:...) ────────
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, colorScheme.onSurface.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "2. Sıkıştırılmış Yedek Kodu (SHK-VAULT-1)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    isBusy = true
                                    statusMessage = null
                                    coroutineScope.launch {
                                        val res = VaultBackupManager.exportLocalVaultCode(context)
                                        isBusy = false
                                        res.onSuccess { (code, summary) ->
                                            clipboardManager.setText(AnnotatedString(code))
                                            generatedCodePreview = code.take(64) + "..."
                                            isStatusError = false
                                            statusMessage = "Yedek kodu panoya kopyalandı (${summary.notesCount} not, ${summary.drawingsCount} çizim, ${summary.questionsCount} soru)."
                                            Toast.makeText(context, "SHK-VAULT-1 kodu kopyalandı", Toast.LENGTH_SHORT).show()
                                        }.onFailure { err ->
                                            isStatusError = true
                                            statusMessage = "Kod üretilemedi: ${err.localizedMessage}"
                                        }
                                    }
                                },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Kodu Kopyala", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    showImportField = !showImportField
                                    if (showImportField && importCodeInput.isBlank()) {
                                        val clip = clipboardManager.getText()?.text.orEmpty().trim()
                                        if (clip.startsWith(VaultBackupManager.VAULT_PREFIX, ignoreCase = true)) {
                                            importCodeInput = clip
                                        }
                                    }
                                },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Koddan Yükle", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (!generatedCodePreview.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = generatedCodePreview!!,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = colorScheme.onSurface.copy(alpha = 0.65f)
                            )
                        }

                        if (showImportField) {
                            Spacer(modifier = Modifier.height(10.dp))
                            OutlinedTextField(
                                value = importCodeInput,
                                onValueChange = { importCodeInput = it },
                                placeholder = { Text("SHK-VAULT-1:... kodunu buraya yapıştırın") },
                                maxLines = 3,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 64.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    isBusy = true
                                    statusMessage = null
                                    coroutineScope.launch {
                                        val res = VaultBackupManager.importFromVaultCode(context, importCodeInput)
                                        isBusy = false
                                        res.onSuccess { summary ->
                                            isStatusError = false
                                            statusMessage = summary.message
                                            showImportField = false
                                            importCodeInput = ""
                                            onDataRestored()
                                        }.onFailure { err ->
                                            isStatusError = true
                                            statusMessage = err.localizedMessage ?: "Geçersiz yedek kodu."
                                        }
                                    }
                                },
                                enabled = !isBusy && importCodeInput.isNotBlank(),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Kodu İçe Aktar ve Birleştir", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                if (isBusy) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "İşlem yapılıyor, lütfen bekleyin...",
                            style = MaterialTheme.typography.labelMedium,
                            color = colorScheme.onSurface
                        )
                    }
                }

                if (!statusMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isStatusError) {
                            colorScheme.errorContainer.copy(alpha = 0.85f)
                        } else {
                            colorScheme.primaryContainer.copy(alpha = 0.85f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = statusMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (isStatusError) colorScheme.onErrorContainer else colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isBusy
                    ) {
                        Text("Kapat")
                    }
                }
            }
        }
    }
}
