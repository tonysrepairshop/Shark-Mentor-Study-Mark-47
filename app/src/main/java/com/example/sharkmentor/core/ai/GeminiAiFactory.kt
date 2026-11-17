package com.example.sharkmentor.core.ai

import android.util.Log
import com.example.sharkmentor.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.RequestOptions
import com.google.ai.client.generativeai.type.Tool
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig

enum class AiTaskType(
    val modelName: String,
    val defaultTemperature: Float,
    val defaultMaxOutputTokens: Int,
    val defaultTopP: Float
) {
    LIGHT_INTERACTION(
        modelName = "gemini-3.6-flash",
        defaultTemperature = 0.25f,
        defaultMaxOutputTokens = 4096,
        defaultTopP = 0.8f
    ),
    HEAVY_ANALYSIS(
        modelName = "gemini-3.6-flash",
        defaultTemperature = 0.45f,
        defaultMaxOutputTokens = 8192,
        defaultTopP = 0.9f
    )
}

object GeminiAiFactory {
    const val VISION_MODEL = "gemini-3.6-flash"
    const val FAST_MODEL_3_6 = "gemini-3.6-flash"
    const val DEEP_MODEL_3_8 = "gemini-3.6-flash"
    const val FALLBACK_MODEL_3_6 = "gemini-3.6-flash"

    val VISION_PRESET_CONFIG: GenerationConfig = generationConfig {
        temperature = 0.1f
        maxOutputTokens = 8192
        topP = 0.8f
    }

    val FAST_PRESET_CONFIG: GenerationConfig = generationConfig {
        temperature = 0.25f
        maxOutputTokens = 4096
        topP = 0.8f
    }

    val DEEP_PRESET_CONFIG: GenerationConfig = generationConfig {
        temperature = 0.45f
        maxOutputTokens = 8192
        topP = 0.9f
    }

    fun sanitizeModelName(name: String): String {
        val cleaned = name.trim().removePrefix("models/").removePrefix("/")
        return when {
            cleaned.isBlank() -> FAST_MODEL_3_6
            cleaned.contains("preview", ignoreCase = true) || cleaned.contains("exp", ignoreCase = true) -> FAST_MODEL_3_6
            else -> cleaned
        }
    }

    fun resolveCleanApiKey(overrideKey: String? = null): String {
        val rawKey = if (!overrideKey.isNullOrBlank()) {
            overrideKey
        } else {
            try {
                ApiKeyRotator.getActiveKey().ifBlank { BuildConfig.GEMINI_API_KEY.trim() }
            } catch (_: Exception) {
                BuildConfig.GEMINI_API_KEY.trim()
            }
        }
        return rawKey.trim().replace("\"", "").replace("'", "")
    }

    fun createModel(
        taskType: AiTaskType,
        systemPrompt: String = "",
        generationConfig: GenerationConfig? = null,
        tools: List<Tool> = emptyList()
    ): GenerativeModel {
        val cleanKey = resolveCleanApiKey()
        if (cleanKey.isBlank() || cleanKey == "null") {
            Log.e("GeminiError", "AI Call Failed: API Key is missing or invalid.")
            throw IllegalStateException("Gemini API Key is missing or invalid. Check local.properties or BuildConfig.")
        }

        val resolvedConfig = generationConfig ?: generationConfig {
            temperature = taskType.defaultTemperature
            maxOutputTokens = taskType.defaultMaxOutputTokens
            topP = taskType.defaultTopP
        }

        val cleanModelName = sanitizeModelName(taskType.modelName)
        return GenerativeModel(
            modelName = cleanModelName,
            apiKey = cleanKey,
            generationConfig = resolvedConfig,
            tools = tools,
            systemInstruction = if (systemPrompt.isNotBlank()) {
                content { text(systemPrompt) }
            } else null,
            requestOptions = RequestOptions(timeout = 30_000L)
        )
    }

    /**
     * Dedicated factory method for Google Gemini Vision requests.
     * Enforces `gemini-3.6-flash`, `temperature = 0.1f`, and `tools = emptyList()`.
     */
    fun createVisionModel(
        systemPrompt: String = "",
        overrideKey: String? = null
    ): GenerativeModel {
        val cleanKey = resolveCleanApiKey(overrideKey)
        if (cleanKey.isBlank() || cleanKey == "null") {
            throw IllegalStateException("Gemini API Key is missing for Vision task.")
        }
        return GenerativeModel(
            modelName = VISION_MODEL,
            apiKey = cleanKey,
            generationConfig = VISION_PRESET_CONFIG,
            tools = emptyList(),
            systemInstruction = if (systemPrompt.isNotBlank()) {
                content { text(systemPrompt) }
            } else null,
            requestOptions = RequestOptions(timeout = 60_000L)
        )
    }
}
