package com.example.sharkmentor.core.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.sharkmentor.data.local.entity.TargetPeriod
import com.example.sharkmentor.data.local.entity.TargetType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object GeminiPlanAnalyzer {

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

            val response = GeminiRestClient.generateWithFailover(
                userPrompt = prompt,
                bitmaps = bitmaps,
                responseJson = true,
                maxOutputTokens = 8192,
                thinkingLevel = GeminiThinkingLevel.MEDIUM,
                timeoutMs = 75_000L
            ).getOrThrow()

            val cleanedJson = GeminiRestClient.extractCleanJsonString(response.text)
            if (cleanedJson.isBlank()) {
                throw IllegalStateException("Empty AI response")
            }

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
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e("GeminiPlanAnalyzer", "Error analyzing schedule: ${e.message}", e)
            Result.failure(e)
        }
    }
}
