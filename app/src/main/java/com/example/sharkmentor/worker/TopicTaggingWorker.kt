package com.example.sharkmentor.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.sharkmentor.core.ai.ApiKeyRotator
import com.example.sharkmentor.core.util.ImageStorageManager
import com.example.sharkmentor.data.local.SharkMentorDatabase
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TopicTaggingWorker
 *
 * A background [CoroutineWorker] that batch-processes error-bank questions lacking
 * an [aiTopic] tag. For each untagged question it loads the question image, sends it
 * to Gemini Vision with a concise subject-aware prompt, and persists the detected
 * sub-topic name back to the local Room database.
 *
 * Scheduling:
 *  - Enqueued as a unique [OneTimeWorkRequest] named [WORK_NAME] with
 *    [ExistingWorkPolicy.KEEP] so multiple addQuestion() calls won't stack workers.
 *  - Call [TopicTaggingWorker.enqueue] from the ViewModel after adding any question.
 */
class TopicTaggingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "TopicTaggingWorker"
        private const val WORK_NAME = "topic_tagging_unique"
        private const val BATCH_SIZE = 5

        /** Schedules the worker if it is not already queued or running. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<TopicTaggingWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = SharkMentorDatabase.getDatabase(applicationContext)
        val dao = db.errorQuestionDao()

        val apiKey = ApiKeyRotator.getActiveKey()
            .takeIf { it.isNotBlank() && it != "null" }
            ?: run {
                Log.w(TAG, "No valid Gemini API key — skipping tagging run")
                return@withContext Result.failure()
            }

        val model = GenerativeModel(
            modelName = "gemini-3.6-flash",
            apiKey = apiKey
        )

        val untagged = dao.getUntaggedQuestions(limit = BATCH_SIZE)
        if (untagged.isEmpty()) {
            Log.d(TAG, "No untagged questions found.")
            return@withContext Result.success()
        }

        var successCount = 0
        for (question in untagged) {
            try {
                val bitmap = ImageStorageManager.getOptimizedBitmap(
                    applicationContext,
                    question.questionImageUri
                )

                val topic: String? = if (bitmap != null) {
                    // Multimodal (Vision) path — image + prompt
                    val prompt = buildTaggingPrompt(question.subject)
                    val inputContent = content {
                        image(bitmap)
                        text(prompt)
                    }
                    val response = model.generateContent(inputContent)
                    response.text?.trim()
                        ?.lines()
                        ?.firstOrNull { it.isNotBlank() }
                        ?.take(60) // guard against runaway text
                } else {
                    // Text-only fallback when image can't be decoded
                    val prompt = buildTaggingPromptTextOnly(question.subject, question.topic)
                    val response = model.generateContent(prompt)
                    response.text?.trim()
                        ?.lines()
                        ?.firstOrNull { it.isNotBlank() }
                        ?.take(60)
                }

                if (!topic.isNullOrBlank()) {
                    dao.updateAiTopic(question.id, topic)
                    Log.d(TAG, "Tagged question ${question.id} (${question.subject}) → $topic")
                    successCount++
                } else {
                    // Fallback: keep current topic field value so the question isn't retried endlessly
                    val fallback = question.topic.ifBlank { "Genel" }
                    dao.updateAiTopic(question.id, fallback)
                    Log.w(TAG, "Empty AI response for question ${question.id} — using fallback: $fallback")
                }
            } catch (e: Exception) {
                // Soft failure: log and continue to next question
                Log.e(TAG, "Failed to tag question ${question.id}: ${e.localizedMessage}", e)
            }
        }

        Log.d(TAG, "Tagging run complete. $successCount/${untagged.size} questions tagged.")

        // If there were more untagged questions, re-enqueue for the next batch
        val remaining = dao.getUntaggedQuestions(limit = 1)
        if (remaining.isNotEmpty()) {
            enqueue(applicationContext)
        }

        Result.success()
    }

    /** Vision prompt for image-attached requests. */
    private fun buildTaggingPrompt(subject: String): String = """
        You are an expert Turkish high-school exam tutor analyzing a $subject question image.
        Identify the specific sub-topic tested by this question.
        Return ONLY the sub-topic name as clean Turkish text — no explanation, no punctuation, no numbering.
        Examples: Sinir Sistemi | Hücre Bölünmesi | Integral | Osmanlı'nın Kuruluşu | Elektrostatik
    """.trimIndent()

    /** Text-only fallback prompt when the image cannot be loaded. */
    private fun buildTaggingPromptTextOnly(subject: String, existingTopic: String): String = """
        Ders: $subject. Mevcut konu etiketi: "$existingTopic".
        Bu bilgilere göre en uygun alt konu adını Türkçe olarak yalnızca tek satır halinde yaz.
        Sadece konu adını ver, başka hiçbir şey yazma.
    """.trimIndent()
}
