package com.example.sharkmentor.core.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.sharkmentor.BuildConfig
import com.example.sharkmentor.data.local.entity.TargetPeriod
import com.example.sharkmentor.data.local.entity.TargetType
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object GeminiPlanAnalyzer {

    private fun createModel(modelName: String): GenerativeModel {
        val apiKey = ApiKeyRotator.getActiveKey().ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
        val cleanKey = apiKey.trim().replace("\"", "").replace("'", "")
        if (cleanKey.isBlank() || cleanKey == "null") {
            Log.e("GeminiError", "AI Call Failed: API Key is missing or invalid.")
            throw IllegalStateException("Gemini API Key is missing or invalid. Check local.properties or BuildConfig.")
        }
        val cleanModelName = modelName.trim().removePrefix("models/").removePrefix("/")
        return GenerativeModel(
            modelName = cleanModelName,
            apiKey = cleanKey,
            requestOptions = RequestOptions(timeout = 60_000L)
        )
    }

    suspend fun analyzeStudySchedule(bitmaps: List<Bitmap>): Result<ParsedScheduleResult> = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                Analyze the provided images of a study schedule or plan. 
                Extract the main targets (goals) and their associated tasks.
                
                For each target, determine:
                1. Title (e.g., "Mathematics Preparation", "Exam Week")
                2. Type: "LONG_TERM" or "SHORT_TERM"
                3. Period: "SIX_MONTH", "MONTHLY", "WEEKLY", or "DAILY"
                4. A list of specific tasks under this target.
                5. Estimated deadline timestamp in milliseconds (if not found, use a reasonable future date or 0).

                Respond ONLY with a JSON object in this format:
                {
                  "targets": [
                    {
                      "title": "String",
                      "type": "LONG_TERM|SHORT_TERM",
                      "period": "SIX_MONTH|MONTHLY|WEEKLY|DAILY",
                      "tasks": ["Task 1", "Task 2"],
                      "estimatedDeadlineMillis": 1724619600000
                    }
                  ]
                }
            """.trimIndent()

            var rawJson: String? = null
            var lastErr: Exception? = null
            var currentDelay = 1500L
            val modelName = "gemini-3.6-flash"
            val maxRetries = 3

            for (attempt in 1..maxRetries) {
                try {
                    val model = createModel(modelName)
                    val resp = model.generateContent(content {
                        bitmaps.forEach { image(it) }
                        text(prompt)
                    })
                    rawJson = resp.text
                    if (!rawJson.isNullOrBlank()) break
                } catch (e: Exception) {
                    val rootCause = e.cause?.message ?: e.message
                    Log.e("GeminiPlanAnalyzer", "Call failed on $modelName: ${e.javaClass.simpleName} | Root:$rootCause", e)
                    lastErr = e
                    if (ApiKeyRotator.shouldRotateKey(e)) {
                        Log.w("GeminiPlanAnalyzer", "Quota limit reached on current key. Rotating key...")
                        ApiKeyRotator.rotateToNextKey()
                    }

                    val isUnavailable = e.message?.contains("503") == true || 
                                        e.message?.contains("high demand", ignoreCase = true) == true ||
                                        e.message?.contains("UNAVAILABLE", ignoreCase = true) == true
                    
                    if (isUnavailable && attempt < maxRetries) {
                        Log.i("GeminiPlanAnalyzer", "Server busy (503). Retrying in ${currentDelay}ms...")
                        kotlinx.coroutines.delay(currentDelay)
                        currentDelay *= 2
                    } else if (attempt == maxRetries && isUnavailable) {
                        lastErr = java.lang.Exception("Yapay zeka sunucularında anlık yoğunluk yaşanıyor (503). Lütfen birkaç saniye sonra tekrar deneyin.")
                    }
                }
            }

            if (rawJson.isNullOrBlank()) {
                throw lastErr ?: java.lang.Exception("Empty AI response")
            }
            
            // Basic cleaning in case AI includes markdown code blocks
            val cleanedJson = rawJson.removePrefix("```json").removeSuffix("```").trim()
            
            val jsonObject = JSONObject(cleanedJson)
            val targetsArray = jsonObject.getJSONArray("targets")
            
            val parsedTargets = mutableListOf<ParsedTarget>()
            for (i in 0 until targetsArray.length()) {
                val obj = targetsArray.getJSONObject(i)
                val tasksArray = obj.getJSONArray("tasks")
                val tasks = mutableListOf<String>()
                for (j in 0 until tasksArray.length()) {
                    tasks.add(tasksArray.getString(j))
                }
                
                parsedTargets.add(
                    ParsedTarget(
                        title = obj.getString("title"),
                        type = TargetType.valueOf(obj.getString("type")),
                        period = TargetPeriod.valueOf(obj.getString("period")),
                        tasks = tasks,
                        estimatedDeadlineMillis = obj.optLong("estimatedDeadlineMillis", 0)
                    )
                )
            }
            
            Result.success(ParsedScheduleResult(parsedTargets))
        } catch (e: Exception) {
            Log.e("GeminiPlanAnalyzer", "Error analyzing schedule: ${e.message}", e)
            Result.failure(e)
        }
    }
}
