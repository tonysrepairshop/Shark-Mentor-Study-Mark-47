package com.example.sharkmentor.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.example.sharkmentor.core.ai.GeminiAiFactory
import com.example.sharkmentor.core.ai.UserAiPreferences
import com.example.sharkmentor.core.util.ImageStorageManager
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.example.sharkmentor.data.local.entity.ErrorQuestionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TopicTaggingWorker
 *
 * A background [CoroutineWorker] that batch-processes error-bank questions lacking
 * an [aiTopic] tag. For each untagged question it loads the question image, sends it
 * to Gemini Vision (`gemini-3.6-flash`) with a strict Physics / Chemistry / Biology
 * disambiguation prompt, and persists the detected subject & sub-topic name back to
 * the local Room database without overwriting user-edited questions.
 */
class TopicTaggingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TopicTaggingWorker"
        private const val WORK_NAME = "topic_tagging_unique"
        private const val BATCH_SIZE = 5
        private const val MODEL_NAME = "gemini-3.6-flash"

        val STANDARD_SUBJECTS = listOf(
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

        /** Schedules the worker if it is not already queued or running. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<TopicTaggingWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        /**
         * Returns true if the user has already manually assigned a specific topic
         * (so background AI tagging must NOT overwrite it).
         */
        fun isManuallyClassified(question: ErrorQuestionEntity): Boolean {
            val cleanTopic = question.topic.trim()
            return cleanTopic.isNotEmpty() &&
                !cleanTopic.equals("Genel", ignoreCase = true) &&
                !cleanTopic.equals("Belirsiz", ignoreCase = true) &&
                !question.aiTopic.isNullOrBlank()
        }

        /**
         * Normalizes an AI-returned subject string to the canonical folder name used in ErrorBank.
         */
        fun normalizeSubject(rawSubject: String, fallbackSubject: String): String {
            val cleaned = rawSubject.trim().removePrefix("Ders:").trim()
            if (cleaned.isEmpty()) return fallbackSubject
            val lower = cleaned.lowercase(java.util.Locale("tr"))
            return when {
                lower.contains("biyoloji") -> "Biyoloji"
                lower.contains("kimya") -> "Kimya"
                lower.contains("fizik") -> "Fizik"
                lower.contains("matematik") || lower.contains("geometri") -> "Matematik"
                lower.contains("türkçe") || lower.contains("edebiyat") || lower.contains("paragraf") -> "Türkçe / Edebiyat"
                lower.contains("tarih") -> "Tarih"
                lower.contains("coğrafya") -> "Coğrafya"
                lower.contains("felsefe") || lower.contains("din") -> "Felsefe / Din"
                lower.contains("ingilizce") -> "İngilizce"
                else -> STANDARD_SUBJECTS.firstOrNull { it.equals(cleaned, ignoreCase = true) } ?: fallbackSubject
            }
        }

        /**
         * Classifies a single question using ONLY `gemini-3.6-flash` and returns
         * `Pair(resolvedSubject, resolvedSubTopic)` or null if classification failed.
         */
        suspend fun classifyQuestionWithGemini(
            context: Context,
            question: ErrorQuestionEntity
        ): Pair<String, String>? = withContext(Dispatchers.IO) {
            UserAiPreferences.initialize(context)
            if (!ApiKeyRotator.hasAnyGeminiKey(context)) {
                return@withContext null
            }

            val bitmap = ImageStorageManager.getOptimizedBitmap(
                context,
                question.questionImageUri
            )

            try {
                val prompt = if (bitmap != null && !bitmap.isRecycled) {
                    buildTaggingPrompt(question.subject)
                } else {
                    buildTaggingPromptTextOnly(question.subject, question.topic)
                }

                val responseResult = GeminiAiFactory.generateContentWithFailover(
                    context = context,
                    userPrompt = prompt,
                    bitmaps = if (bitmap != null && !bitmap.isRecycled) listOf(bitmap) else emptyList(),
                    responseJson = false,
                    temperature = 0.2f,
                    maxOutputTokens = 512,
                    timeoutMs = 45_000L
                )

                val text = responseResult.getOrThrow().text
                val rawLine = text.trim()
                    .lines()
                    .firstOrNull { it.isNotBlank() }
                    ?.take(100)

                if (rawLine.isNullOrBlank()) return@withContext null

                parseClassificationResponse(
                    rawResponse = rawLine,
                    currentSubject = question.subject,
                    fallbackTopic = question.topic.ifBlank { "Genel" }
                )
            } finally {
                if (bitmap != null && !bitmap.isRecycled) {
                    runCatching { bitmap.recycle() }
                }
            }
        }

        private fun parseClassificationResponse(
            rawResponse: String,
            currentSubject: String,
            fallbackTopic: String
        ): Pair<String, String> {
            val cleaned = rawResponse
                .replace("*", "")
                .replace("\"", "")
                .replace("`", "")
                .trim()

            if (cleaned.contains("|")) {
                val parts = cleaned.split("|", limit = 2)
                val aiSubjectRaw = parts.getOrNull(0)?.trim().orEmpty()
                val aiTopicRaw = parts.getOrNull(1)?.trim()?.take(60).orEmpty()
                val resolvedSubject = normalizeSubject(aiSubjectRaw, currentSubject)
                val resolvedTopic = aiTopicRaw.ifBlank { fallbackTopic }
                return resolvedSubject to resolvedTopic
            }

            val resolvedTopic = cleaned.take(60).ifBlank { fallbackTopic }
            return currentSubject to resolvedTopic
        }

        /** Vision prompt with strict Kimya / Biyoloji / Fizik disambiguation using gemini-3.6-flash. */
        fun buildTaggingPrompt(subjectHint: String): String = """
            Sen uzman bir YKS/LGS soru sınıflandırma öğretmenisin. Görseldeki soruyu incele.
            Kullanıcının seçtiği ön ders etiketi: "$subjectHint" (ancak kullanıcı özellikle Fen derslerinde Kimya, Biyoloji ve Fizik arasında yanlış ders seçmiş olabilir; görsel içeriğine göre gerçek dersi doğrula).

            KESİN DERS AYRIM KURALLARI (KİMYA / BİYOLOJİ / FİZİK KARIŞIKLIĞINI ÖNLE):
            1. BİYOLOJİ:
               - Hücre, organeller (ribozom, mitokondri, kloroplast, golgi, lizozom), hücre zarından madde geçişi (osmoz, difüzyon, turgor, plazmoliz).
               - Canlıların temel bileşenleri (karbonhidrat, lipit, protein, enzim, hormon, vitamin, ATP, DNA, RNA, nükleotit).
               - Fotosentez, kemosentez, hücresel solunum (glikoliz, krebs, ETS, fermantasyon) — kimyasal denklem içerse bile canlı/metabolizma sorusuysa BİYOLOJİDİR, Kimya değildir!
               - Mitoz, mayoz, kalıtım (genetik çaprazlama, soy ağacı), ekoloji, besin zinciri, azot döngüsü.
               - İnsan fizyolojisi (sinir, endokrin, duyu, destek-hareket, sindirim, dolaşım, bağışıklık, solunum, boşaltım/üriner, üreme) ve bitki biyolojisi.
            2. KİMYA:
               - Atom modelleri (orbital, kuantum sayıları, elektron dizilimi), periyodik sistem ve periyodik özellikler.
               - Kimyasal türler arası etkileşimler (iyonik, kovalent, metalik bağ, hidrojen bağı, van der Waals).
               - Mol kavramı, kimyasal hesaplamalar, tepkime türleri, asitler-bazlar-tuzlar, pH/pOH, titrasyon.
               - Karışımlar, çözeltiler, koligatif özellikler (kaynama/donma noktası değişimi, molarite), çözünürlük dengesi (Kçç).
               - Gazlar (ideal gaz denklemi, kısmi basınç), kimyasal tepkimelerde enerji (entalpi), hız ve kimyasal denge (Kc, Kp, Le Chatelier).
               - Elektrokimya (redoks, aktiflik, galvanik pil, elektroliz) ve Organik Kimya / Karbon Kimyası (hidrokarbonlar, fonksiyonel gruplar, izomeri).
            3. FİZİK:
               - Madde ve özkütle, dayanıklılık, adezyon-kohezyon-kılcallık, basınç ve sıvı/gaz kaldırma kuvveti.
               - Kuvvet ve hareket (Newton yasaları, sürtünme, ivme, atışlar, itme-momentum, tork-denge, basit makineler).
               - İş, güç, mekanik enerji, ısı-sıcaklık-genleşme (Q=m.c.ΔT, hal değişimi).
               - Elektrostatik, elektrik devreleri (Ohm yasası, direnç, lamba parlaklığı, kondansatör), manyetizma, alternatif akım.
               - Dalgalar, optik (aydınlanma, ayna, kırılma, mercek, prizma), çembersel hareket, basit harmonik hareket, modern fizik (fotoelektrik, Compton, de Broglie, radyoaktivite).

            ÇIKTI FORMATI:
            Yalnızca tek satır halinde şu formatta yanıt ver (açıklama, noktalama veya numara ekleme):
            DERS | ALT_KONU

            Geçerli DERS adları: Matematik, Fizik, Kimya, Biyoloji, Türkçe / Edebiyat, Tarih, Coğrafya, Felsefe / Din, İngilizce
            Örnek çıktılar:
            Biyoloji | Hücresel Solunum
            Biyoloji | Enzimler ve ATP
            Kimya | Sıvı Çözeltiler ve Çözünürlük
            Kimya | Kimyasal Türler Arası Etkileşimler
            Fizik | Basınç ve Kaldırma Kuvveti
            Fizik | Elektrik Devreleri
            Matematik | Türev Uygulamaları
        """.trimIndent()

        /** Text-only fallback prompt when the image cannot be loaded. */
        fun buildTaggingPromptTextOnly(subject: String, existingTopic: String): String = """
            Ders: $subject. Mevcut konu etiketi: "$existingTopic".
            Kimya, Biyoloji ve Fizik konularını birbirine karıştırmadan en uygun ders ve alt konu adını yalnızca tek satır halinde "DERS | ALT_KONU" formatında Türkçe olarak yaz.
            Başka hiçbir açıklama yazma.
        """.trimIndent()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = SharkMentorDatabase.getDatabase(applicationContext)
        val dao = db.errorQuestionDao()

        UserAiPreferences.initialize(applicationContext)
        if (!ApiKeyRotator.hasAnyGeminiKey(applicationContext)) {
            Log.w(TAG, "No valid Gemini API key — skipping tagging run")
            return@withContext Result.failure()
        }

        val untagged = dao.getUntaggedQuestions(limit = BATCH_SIZE)
        if (untagged.isEmpty()) {
            Log.d(TAG, "No untagged questions found.")
            return@withContext Result.success()
        }

        var successCount = 0
        for (question in untagged) {
            // Kullanıcının elle düzelttiği soruları kesinlikle ezme
            if (isManuallyClassified(question)) {
                Log.d(TAG, "Skipping manually classified question ${question.id}")
                continue
            }

            try {
                val classified = classifyQuestionWithGemini(applicationContext, question)
                if (classified != null) {
                    val (resolvedSubject, resolvedSubTopic) = classified
                    // If user hadn't manually specified a non-Genel topic, update both subject and topic/aiTopic
                    val finalTopic = if (question.topic.isBlank() || question.topic.equals("Genel", ignoreCase = true)) {
                        resolvedSubTopic
                    } else {
                        question.topic
                    }
                    dao.updateQuestionClassification(
                        id = question.id,
                        subject = resolvedSubject,
                        topic = finalTopic,
                        aiTopic = resolvedSubTopic
                    )
                    Log.d(TAG, "Tagged question ${question.id}: ${question.subject} -> $resolvedSubject | $resolvedSubTopic")
                    successCount++
                } else {
                    val fallback = question.topic.ifBlank { "Genel" }
                    dao.updateAiTopic(question.id, fallback)
                    Log.w(TAG, "Empty AI response for question ${question.id} — using fallback: $fallback")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to tag question ${question.id}: ${e.localizedMessage}", e)
            }
        }

        if (successCount > 0) {
            com.example.sharkmentor.service.sync.SyncWorker.enqueueImmediateSync(applicationContext)
        }

        Log.d(TAG, "Tagging run complete. $successCount/${untagged.size} questions tagged.")

        val remaining = dao.getUntaggedQuestions(limit = 1)
        if (remaining.isNotEmpty()) {
            enqueue(applicationContext)
        }

        Result.success()
    }
}
