package com.example.sharkmentor.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.sharkmentor.core.util.ImageStorageManager
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity
import com.example.sharkmentor.service.sync.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Controls how much of a Note or PDF document is sent to AI on each request.
 * Defaulting to [CURRENT_PAGE] keeps latency low and prevents OOM crashes on large PDFs.
 */
enum class DocumentAiScope(val label: String) {
    CURRENT_PAGE("Bu Sayfa"),
    NEARBY_PAGES("Yakın Sayfalar"),
    WHOLE_DOC_TEXT("Tüm Belge (Metin)")
}

/**
 * Structured metadata parsed from the top line of a Lasso AI response.
 */
data class LassoAiMeta(
    val isTestQuestion: Boolean,
    val subject: String,
    val topic: String,
    val answer: String? = null
)

/**
 * Incremental parse result used during SSE streaming so metadata markers never flicker in UI.
 */
data class ParsedLassoAiResponse(
    val meta: LassoAiMeta?,
    val displayText: String,
    val isMetaPending: Boolean
)

/**
 * Tracks an Error Bank record saved from a Lasso AI question solve so the user can Undo cleanly.
 */
data class SavedErrorBankRecord(
    val entity: ErrorQuestionEntity,
    val imagePath: String,
    val sizeBytes: Long
) {
    val id: Long get() = entity.id
    val subject: String get() = entity.subject
    val topic: String get() = entity.topic
}

/**
 * Smart, OOM-safe document context selection, image downsampling, Lasso AI structured parsing,
 * and low-storage Error Bank auto-registration utilities for Notes and PDF Reader.
 */
object NoteDocumentAiHelper {

    private const val TAG = "NoteDocumentAiHelper"
    private const val META_PREFIX = "[[SHARK_META"
    private const val META_SUFFIX = "]]"
    private const val MAX_META_HEADER_SCAN_CHARS = 280

    val STANDARD_ERROR_BANK_SUBJECTS = listOf(
        "Matematik",
        "Fizik",
        "Kimya",
        "Biyoloji",
        "Türkçe / Edebiyat",
        "Tarih",
        "Coğrafya",
        "Felsefe / Din",
        "İngilizce",
        "Genel Yetenek / Diğer"
    )

    val LASSO_AI_SYSTEM_INSTRUCTION = """
Sen öğrencinin bir PDF veya ders notundan kementle seçtiği bölgeyi inceleyen uzman bir çalışma ve soru çözüm asistanısın.

İLK SATIR KURALI (ZORUNLU):
Yanıtının EN İLK SATIRINA, başka hiçbir metin eklemeden tam olarak şu formatta tek bir etiket yaz:
[[SHARK_META|IS_TEST_QUESTION=true|SUBJECT=Matematik|TOPIC=Türev|ANSWER=C]]

Alan kuralları:
- IS_TEST_QUESTION: Seçilen alan çözülmeyi bekleyen bir test sorusu, çoktan seçmeli soru (A/B/C/D/E), deneme sorusu veya açık uçlu problem/alıştırma ise `true`; sadece konu anlatımı, ders notu, tanım, tablo veya özet metin ise `false` yaz.
- SUBJECT: Yalnızca şu standart ders adlarından en uygun olanı seç: Matematik, Fizik, Kimya, Biyoloji, Türkçe / Edebiyat, Tarih, Coğrafya, Felsefe / Din, İngilizce, Genel Yetenek / Diğer.
- TOPIC: 1-4 kelimelik net alt konu adı yaz (örnek: Türev, Newton Yasaları, Paragraf, Hücre Bölünmesi). Emin değilsen `Genel` yaz.
- ANSWER: Çoktan seçmeli test sorusunun kesin şıkkı varsa tek harf yaz (A, B, C, D veya E); şık yoksa veya test sorusu değilse `-` yaz.

YANIT KURALLARI (İLK SATIRDAN HEMEN SONRA):
1. Yapay selamlama ve gereksiz giriş cümlesi kullanma, emoji kullanma; doğrudan çözüme veya açıklamaya gir.
2. Yalnızca görselde gördüğün bilgilere dayan; uydurma veri ekleme. Görsel okunaksız veya eksikse bunu açıkça belirt.
3. Test sorusuysa adım adım, sade ve anlaşılır biçimde çöz; sonunda `Doğru Cevap: ...` şeklinde net sonucu belirt.
4. Konu anlatımı veya not parçasıysa ana fikri, önemli formül/kavramları düzenli maddeler halinde açıkla.
    """.trimIndent()

    /**
     * Selects 0-based page indices for the given [scope] around [currentPage].
     * - [DocumentAiScope.CURRENT_PAGE]: strictly `[clampedCurrent]` (1 page).
     * - [DocumentAiScope.NEARBY_PAGES]: up to 3 pages centered on `clampedCurrent` (`current-1..current+1`),
     *   shifting window cleanly at document boundaries so start/end pages still get up to 3 pages when available.
     * - [DocumentAiScope.WHOLE_DOC_TEXT]: returns `[clampedCurrent]` as the visual anchor fallback.
     */
    fun selectPageIndicesForScope(
        currentPage: Int,
        totalPages: Int,
        scope: DocumentAiScope
    ): List<Int> {
        if (totalPages <= 0) return emptyList()
        val safeCurrent = currentPage.coerceIn(0, totalPages - 1)
        return when (scope) {
            DocumentAiScope.CURRENT_PAGE,
            DocumentAiScope.WHOLE_DOC_TEXT -> listOf(safeCurrent)

            DocumentAiScope.NEARBY_PAGES -> {
                if (totalPages <= 3) {
                    (0 until totalPages).toList()
                } else {
                    val start = when {
                        safeCurrent <= 0 -> 0
                        safeCurrent >= totalPages - 1 -> totalPages - 3
                        else -> safeCurrent - 1
                    }
                    (start until (start + 3).coerceAtMost(totalPages)).toList()
                }
            }
        }
    }

    /**
     * Normalizes an AI-returned subject string to the canonical Error Bank folder name.
     * Pure JVM safe (does not depend on Android framework classes).
     */
    fun normalizeErrorBankSubject(
        rawSubject: String,
        fallbackSubject: String = "Genel Yetenek / Diğer"
    ): String {
        val cleaned = rawSubject
            .trim()
            .removePrefix("Ders:")
            .trim()
        if (cleaned.isEmpty() || cleaned == "-") return fallbackSubject
        val lower = cleaned.lowercase(Locale.forLanguageTag("tr"))
        return when {
            lower.contains("biyoloji") -> "Biyoloji"
            lower.contains("kimya") -> "Kimya"
            lower.contains("fizik") -> "Fizik"
            lower.contains("matematik") || lower.contains("geometri") -> "Matematik"
            lower.contains("türkçe") || lower.contains("turkce") || lower.contains("edebiyat") || lower.contains("paragraf") ->
                "Türkçe / Edebiyat"
            lower.contains("tarih") -> "Tarih"
            lower.contains("coğrafya") || lower.contains("cografya") -> "Coğrafya"
            lower.contains("felsefe") || lower.contains("din") -> "Felsefe / Din"
            lower.contains("ingilizce") || lower.contains("english") -> "İngilizce"
            else -> STANDARD_ERROR_BANK_SUBJECTS.firstOrNull {
                it.equals(cleaned, ignoreCase = true)
            } ?: fallbackSubject
        }
    }

    /**
     * Parses a streaming or final Lasso AI response containing an optional leading
     * `[[SHARK_META|IS_TEST_QUESTION=...|SUBJECT=...|TOPIC=...|ANSWER=...]]` header.
     *
     * Guarantees that raw metadata brackets are never flashed to the user mid-stream,
     * and gracefully returns the full response if the model omits the metadata header.
     */
    fun parseLassoAiStream(rawText: String): ParsedLassoAiResponse {
        if (rawText.isEmpty()) {
            return ParsedLassoAiResponse(meta = null, displayText = "", isMetaPending = false)
        }

        val trimmedStart = rawText.trimStart()
        if (trimmedStart.isEmpty()) {
            return ParsedLassoAiResponse(meta = null, displayText = "", isMetaPending = false)
        }

        // Handle model occasionally starting with ``` or whitespace before [[SHARK_META
        val working = if (trimmedStart.startsWith("```")) {
            val afterFence = trimmedStart.substringAfter('\n', missingDelimiterValue = "")
            if (afterFence.trimStart().startsWith("[")) afterFence.trimStart() else trimmedStart
        } else {
            trimmedStart
        }

        // Case 1: Still streaming the opening prefix "[[" .. "[[SHARK_META"
        if (working.length < META_PREFIX.length && META_PREFIX.startsWith(working, ignoreCase = true)) {
            return ParsedLassoAiResponse(meta = null, displayText = "", isMetaPending = true)
        }

        // Case 2: Starts with [[SHARK_META
        if (working.startsWith(META_PREFIX, ignoreCase = true)) {
            val closeIdx = working.indexOf(META_SUFFIX)
            if (closeIdx < 0) {
                // Still receiving the metadata line — keep hidden unless abnormally long
                return if (working.length <= MAX_META_HEADER_SCAN_CHARS && !working.contains("\n\n")) {
                    ParsedLassoAiResponse(meta = null, displayText = "", isMetaPending = true)
                } else {
                    ParsedLassoAiResponse(meta = null, displayText = working, isMetaPending = false)
                }
            }

            val headerInner = working.substring(META_PREFIX.length, closeIdx)
            val body = working.substring(closeIdx + META_SUFFIX.length)
                .trimStart('\r', '\n', ' ')
            val meta = parseMetaFields(headerInner)
            return ParsedLassoAiResponse(
                meta = meta,
                displayText = body,
                isMetaPending = false
            )
        }

        // Case 3: Model omitted metadata tag completely — render cleanly without failing
        return ParsedLassoAiResponse(
            meta = null,
            displayText = rawText.trim(),
            isMetaPending = false
        )
    }

    private fun parseMetaFields(headerInner: String): LassoAiMeta {
        var isTest = false
        var rawSubject = ""
        var rawTopic = ""
        var rawAnswer = ""

        headerInner.split('|').forEach { segment ->
            val eqIdx = segment.indexOf('=')
            if (eqIdx <= 0) return@forEach
            val key = segment.substring(0, eqIdx).trim().uppercase(Locale.ROOT)
            val value = segment.substring(eqIdx + 1).trim()
            when (key) {
                "IS_TEST_QUESTION", "TEST_QUESTION", "IS_QUESTION" -> {
                    isTest = value.equals("true", ignoreCase = true) ||
                        value.equals("evet", ignoreCase = true) ||
                        value == "1"
                }
                "SUBJECT", "DERS" -> rawSubject = value
                "TOPIC", "KONU" -> rawTopic = value
                "ANSWER", "CEVAP" -> rawAnswer = value
            }
        }

        val normalizedSubject = normalizeErrorBankSubject(rawSubject)
        val cleanTopic = rawTopic
            .takeIf { it.isNotBlank() && it != "-" && !it.equals("null", ignoreCase = true) }
            ?.take(48)
            ?: "Genel"
        val cleanAnswer = rawAnswer
            .takeIf {
                it.isNotBlank() &&
                    it != "-" &&
                    !it.equals("yok", ignoreCase = true) &&
                    !it.equals("none", ignoreCase = true) &&
                    !it.equals("null", ignoreCase = true)
            }
            ?.take(24)

        return LassoAiMeta(
            isTestQuestion = isTest,
            subject = normalizedSubject,
            topic = cleanTopic,
            answer = cleanAnswer
        )
    }

    /**
     * Formats byte size compactly for storage-transparency UI badges (e.g., "84 KB").
     */
    fun formatCompactSizeKb(sizeBytes: Long): String {
        val safeBytes = sizeBytes.coerceAtLeast(0L)
        if (safeBytes < 1024L) return "1 KB"
        val kb = ((safeBytes + 512L) / 1024L).coerceAtLeast(1L)
        return "$kb KB"
    }

    /**
     * OOM-safe 2-pass Bitmap decoder for rendered PDF/note image files on disk.
     * Replaces unsafe raw `BitmapFactory.decodeFile(path)` calls that could blow heap memory.
     */
    fun decodeDownsampledBitmap(
        filePath: String,
        maxDimension: Int = 1024
    ): Bitmap? {
        if (filePath.isBlank()) return null
        val file = File(filePath)
        if (!file.exists() || file.length() == 0L) return null
        val targetMax = maxDimension.coerceIn(320, 1440)

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(filePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (
                (bounds.outWidth / sampleSize) > targetMax * 2 ||
                (bounds.outHeight / sampleSize) > targetMax * 2
            ) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = try {
                BitmapFactory.decodeFile(filePath, opts)
            } catch (_: OutOfMemoryError) {
                System.gc()
                opts.inSampleSize = sampleSize * 2
                BitmapFactory.decodeFile(filePath, opts)
            } ?: return null

            val maxSide = maxOf(decoded.width, decoded.height)
            if (maxSide > targetMax) {
                val scale = targetMax.toFloat() / maxSide.toFloat()
                val w = (decoded.width * scale).toInt().coerceAtLeast(1)
                val h = (decoded.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(decoded, w, h, true)
                if (scaled !== decoded) decoded.recycle()
                scaled
            } else {
                decoded
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) System.gc()
            Log.w(TAG, "decodeDownsampledBitmap failed for $filePath: ${t.message}")
            null
        }
    }

    /**
     * Saves a Lasso-selected test question directly into Error Bank using an ultra-compact
     * app-private JPEG (<= 140 KB, NEVER written to Android Gallery).
     */
    suspend fun saveLassoQuestionToErrorBank(
        context: Context,
        cropBitmap: Bitmap,
        subject: String,
        topic: String
    ): Result<SavedErrorBankRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val normalizedSubject = normalizeErrorBankSubject(subject)
            val resolvedTopic = topic.trim().ifBlank { "Genel" }.take(48)

            val (savedPath, sizeBytes) = ImageStorageManager.saveCompressedBitmapForErrorBank(
                context = appContext,
                source = cropBitmap,
                maxDimension = 960,
                maxBytes = 140 * 1024
            )

            val now = System.currentTimeMillis()
            val entity = ErrorQuestionEntity(
                subject = normalizedSubject,
                questionImageUri = savedPath,
                solutionImageUri = null,
                topic = resolvedTopic,
                aiTopic = resolvedTopic,
                createdAtMillis = now,
                dueDateMillis = now,
                lastModifiedMillis = now,
                isSynced = false
            )

            val db = SharkMentorDatabase.getDatabase(appContext)
            val insertedId = db.errorQuestionDao().insertQuestion(entity)
            runCatching { SyncWorker.enqueueImmediateSync(appContext) }

            SavedErrorBankRecord(
                entity = entity.copy(id = insertedId),
                imagePath = savedPath,
                sizeBytes = sizeBytes
            )
        }
    }

    /**
     * Cleanly reverts an auto-saved Lasso Error Bank entry — removes both the Room DB row
     * and the compressed JPEG file so 0 bytes remain on disk.
     */
    suspend fun undoSaveLassoQuestionToErrorBank(
        context: Context,
        record: SavedErrorBankRecord
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val appContext = context.applicationContext
            val db = SharkMentorDatabase.getDatabase(appContext)
            db.errorQuestionDao().deleteQuestion(record.entity)
            ImageStorageManager.deleteImageFile(record.imagePath)
            runCatching { SyncWorker.enqueueImmediateSync(appContext) }
            Unit
        }
    }
}
